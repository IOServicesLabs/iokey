#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <functional>
#include <string>
#include <vector>

#include "llama.h"

#define LOG_TAG "LocalBoardLLM"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ---------------------------------------------------------------------------
// lbg_slots_v3: two independent model slots.
//   slot 0 — the DRAFT model (the big one; ✨ button, test lab, profiles)
//   slot 1 — the QUICK model (tiny; per-word autocorrect and other fast
//            tasks). Two small resident models beat one big model doing
//            everything: autocorrect on a 2B model was a multi-second CPU
//            blast per word — the battery drain.
//
// Prefix SNAPSHOTS replace the old rollback-reuse. The prompt is passed as
// (staticPrefix, dynamicTail): the static part (system block + few-shot —
// byte-identical across runs) is decoded ONCE and its full model state is
// captured with llama_state_get_data; every later run restores that state
// (~memcpy) and only decodes the dynamic tail. Rollback reuse could not do
// this for hybrid/recurrent arches (Qwen3.5, LFM2): their conv state can't
// be rewound — but a saved snapshot restores it exactly. One mechanism,
// every architecture, verified offline byte-identical to a full decode.
// ---------------------------------------------------------------------------

struct Slot {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
    std::string path;
    int nCtx = 0;
    // prefix snapshot
    std::vector<uint8_t> snap;
    std::string snapPrefix;
    int snapTokens = 0;

    void clearSnap() {
        snap.clear();
        snap.shrink_to_fit();
        snapPrefix.clear();
        snapTokens = 0;
    }
};

static Slot g_slots[2];
static std::atomic<bool> g_cancel{false};
static int g_loaded_count = 0;

// Last-generation stats, read back from Kotlin via nativeLastStats().
// finish: 0=eog/normal 1=max-tokens 2=cancelled 3=tokenize-failed
//         4=prompt-decode-failed 5=no-model 6=gen-decode-failed
static long g_stat_prompt_tokens = 0;
static long g_stat_reused_tokens = 0;
static long g_stat_prompt_ms = 0;
static long g_stat_gen_tokens = 0;
static long g_stat_gen_ms = 0;
static long g_stat_finish = 0;

static void reset_stats() {
    g_stat_prompt_tokens = 0;
    g_stat_reused_tokens = 0;
    g_stat_prompt_ms = 0;
    g_stat_gen_tokens = 0;
    g_stat_gen_ms = 0;
    g_stat_finish = 0;
}

static long now_ms() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
}

// ---------------------------------------------------------------------------
// UTF-8 safe streaming: hold back an incomplete trailing multi-byte sequence
// so Java always receives well-formed strings.
// ---------------------------------------------------------------------------
static size_t utf8_complete_prefix_len(const std::string &s) {
    size_t i = s.size();
    int continuation = 0;
    while (i > 0 && (static_cast<unsigned char>(s[i - 1]) & 0xC0) == 0x80) {
        continuation++;
        i--;
    }
    if (continuation == 0) return s.size();
    unsigned char lead = static_cast<unsigned char>(s[i - 1 >= 0 ? i - 1 : 0]);
    if (i == 0) return 0;
    int expected = (lead >= 0xF0) ? 3 : (lead >= 0xE0) ? 2 : 1;
    return (continuation == expected) ? s.size() : (i - 1);
}

// Decode ALL of tokens at absolute positions [pos..pos+n) on the given slot.
// (The old decode_range(tokens, start) decoded tokens[start..end): when the
// dynamic tail is SHORTER than the static prefix — the common case — the
// count went negative and the decode silently never happened, so generation
// sampled stale static-end logits. Caught by the offline harness.)
// Chunked at 512 so any prompt size fits the default n_batch.
// Logits only on the very last token — that is the only one we sample from.
static bool decode_at(Slot &s, const std::vector<llama_token> &tokens, int pos) {
    const int n = (int) tokens.size();
    if (n <= 0) return true;
    for (int off = 0; off < n; off += 512) {
        const int bn = std::min(512, n - off);
        llama_batch batch = llama_batch_init(bn, 0, 1);
        for (int i = 0; i < bn; i++) {
            batch.token[i] = tokens[off + i];
            batch.pos[i] = pos + off + i;
            batch.n_seq_id[i] = 1;
            batch.seq_id[i][0] = 0;
            batch.logits[i] = (off + i == n - 1) ? 1 : 0;
        }
        batch.n_tokens = bn;
        const int rc = llama_decode(s.ctx, batch);
        llama_batch_free(batch);
        if (rc != 0) { LOGE("decode failed rc=%d", rc); return false; }
    }
    return true;
}

static void free_slot(Slot &s) {
    if (s.ctx) { llama_free(s.ctx); s.ctx = nullptr; }
    if (s.model) { llama_model_free(s.model); s.model = nullptr; }
    s.vocab = nullptr;
    s.path.clear();
    s.nCtx = 0;
    s.clearSnap();
}

static bool tokenize(const Slot &s, const std::string &text, bool addSpecial,
                     std::vector<llama_token> &out) {
    out.resize(text.size() / 2 + 16);
    int n = llama_tokenize(s.vocab, text.c_str(), (int) text.size(),
                           out.data(), (int) out.size(), addSpecial, true);
    if (n < 0) {
        out.resize(-n);
        n = llama_tokenize(s.vocab, text.c_str(), (int) text.size(),
                           out.data(), (int) out.size(), addSpecial, true);
    }
    if (n <= 0) return false;
    out.resize(n);
    return true;
}

// ---------------------------------------------------------------------------
// lbg_predict_v1: next-word prediction with an INCREMENTAL context.
//
// The keyboard calls this after every committed word with the text of the
// message so far. The tokens of that text stay resident in the slot's KV
// cache between calls, so a call normally decodes only the one to three
// tokens the user just added (tens of milliseconds on the 0.6B quick model)
// rather than the whole message. Backspacing or editing earlier in the
// message diverges from the cached prefix; the divergent tail is dropped with
// llama_memory_seq_rm and re-decoded from the divergence point.
//
// Output: the most probable next WORDS. Top first tokens are read straight
// off the logits (no sampler: this is a ranking, not a draw). A first token
// that is a whole word by itself (" you") is enough; a fragment (" tom") is
// extended greedily for a couple of steps to see whether it completes into
// a word (" tom"+"orrow") and the extension is rolled back from the cache
// afterwards, so every candidate is measured from the same base state.
//
// nativeGenerate, nativeLoad and nativeFree all invalidate this cache: a
// draft or an autocorrect run rewrites the slot's KV wholesale.
// ---------------------------------------------------------------------------

struct PredState {
    std::vector<llama_token> tokens;   // what sequence 0 currently holds
    bool valid = false;
};
static PredState g_pred[2];

static void invalidate_pred(int slot) {
    if (slot >= 0 && slot < 2) { g_pred[slot].tokens.clear(); g_pred[slot].valid = false; }
}

static std::string piece_of(const Slot &s, llama_token t) {
    char buf[128];
    const int n = llama_token_to_piece(s.vocab, t, buf, sizeof(buf), 0, true);
    return n > 0 ? std::string(buf, n) : std::string();
}

// A piece that begins a word: leading space then letters (apostrophes
// allowed inside). Returns the word body or "" when it is not one.
static std::string word_start_of(const std::string &piece) {
    if (piece.size() < 2 || piece[0] != ' ') return "";
    for (size_t i = 1; i < piece.size(); i++) {
        const unsigned char c = (unsigned char) piece[i];
        const bool letter = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
        if (!letter && !(c == '\'' && i > 1)) return "";
    }
    return piece.substr(1);
}

// A piece that CONTINUES a word: letters only, no leading space.
static bool word_body_piece(const std::string &piece) {
    if (piece.empty()) return false;
    for (unsigned char c : piece) {
        const bool letter = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
        if (!letter && c != '\'') return false;
    }
    return true;
}

static bool decode_one(Slot &s, llama_token t, int pos) {
    llama_batch b = llama_batch_init(1, 0, 1);
    b.token[0] = t;
    b.pos[0] = pos;
    b.n_seq_id[0] = 1;
    b.seq_id[0][0] = 0;
    b.logits[0] = 1;
    b.n_tokens = 1;
    const int rc = llama_decode(s.ctx, b);
    llama_batch_free(b);
    return rc == 0;
}

static llama_token argmax_token(const Slot &s) {
    const float *logits = llama_get_logits_ith(s.ctx, -1);
    const int nv = llama_vocab_n_tokens(s.vocab);
    int best = 0;
    for (int i = 1; i < nv; i++) if (logits[i] > logits[best]) best = i;
    return (llama_token) best;
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_localboard_keyboard_llm_LlamaCppBridge_nativeLoad(
        JNIEnv *env, jclass /*clazz*/, jstring jpath, jint nThreads, jint nCtx,
        jint slotIdx) {
    if (slotIdx < 0 || slotIdx > 1) return JNI_FALSE;
    Slot &s = g_slots[slotIdx];
    const bool wasLoaded = (s.model != nullptr);
    free_slot(s);
    invalidate_pred(slotIdx);
    if (wasLoaded) g_loaded_count--; // slot is empty now even if load fails
    reset_stats();

    llama_backend_init();

    const char *path = env->GetStringUTFChars(jpath, nullptr);
    const std::string pathStr = path ? path : ""; // copy BEFORE release
    LOGI("loading model: %s (threads=%d ctx=%d slot=%d)", pathStr.c_str(),
         (int) nThreads, (int) nCtx, (int) slotIdx);

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0; // CPU-only: predictable, private
    s.model = llama_model_load_from_file(pathStr.c_str(), mp);
    env->ReleaseStringUTFChars(jpath, path);

    if (!s.model) {
        LOGE("model load failed (slot %d)", (int) slotIdx);
        return JNI_FALSE;
    }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = (uint32_t) nCtx;
    cp.n_threads = (int32_t) nThreads;
    cp.n_threads_batch = (int32_t) nThreads;
    s.ctx = llama_init_from_model(s.model, cp);
    if (!s.ctx) {
        LOGE("context init failed (slot %d)", (int) slotIdx);
        llama_model_free(s.model);
        s.model = nullptr;
        return JNI_FALSE;
    }
    s.vocab = llama_model_get_vocab(s.model);
    s.path = pathStr;
    s.nCtx = (int) nCtx;
    g_loaded_count++;
    LOGI("model ready (slot %d, snapshot reuse armed)", (int) slotIdx);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_localboard_keyboard_llm_LlamaCppBridge_nativeIsLoaded(
        JNIEnv *, jclass, jint slotIdx) {
    if (slotIdx < 0 || slotIdx > 1) return JNI_FALSE;
    return (g_slots[slotIdx].model && g_slots[slotIdx].ctx) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_localboard_keyboard_llm_LlamaCppBridge_nativeCancel(JNIEnv *, jclass) {
    g_cancel.store(true);
}

JNIEXPORT void JNICALL
Java_com_localboard_keyboard_llm_LlamaCppBridge_nativeFree(
        JNIEnv *, jclass, jint slotIdx) {
    if (slotIdx < 0 || slotIdx > 1) return;
    g_cancel.store(false);
    Slot &s = g_slots[slotIdx];
    const bool wasLoaded = (s.model != nullptr);
    free_slot(s);
    invalidate_pred(slotIdx);
    if (wasLoaded) g_loaded_count--;
    if (g_loaded_count <= 0) {
        g_loaded_count = 0;
        llama_backend_free();
    }
}

/** [promptTokens, reusedTokens, promptMs, genTokens, genMs, finishCode] of
 *  the most recent nativeGenerate call. */
JNIEXPORT jlongArray JNICALL
Java_com_localboard_keyboard_llm_LlamaCppBridge_nativeLastStats(JNIEnv *env, jclass) {
    jlongArray arr = env->NewLongArray(6);
    jlong vals[6] = {
        g_stat_prompt_tokens, g_stat_reused_tokens, g_stat_prompt_ms,
        g_stat_gen_tokens, g_stat_gen_ms, g_stat_finish
    };
    env->SetLongArrayRegion(arr, 0, 6, vals);
    return arr;
}

JNIEXPORT jstring JNICALL
Java_com_localboard_keyboard_llm_LlamaCppBridge_nativeGenerate(
        JNIEnv *env, jclass /*clazz*/,
        jstring jstatic, jstring jdynamic, jint maxTokens, jfloat temperature,
        jint seed, jint topK, jfloat topP, jfloat repeatPen, jfloat presencePen,
        jfloat freqPen, jint slotIdx, jobject callback) {
    reset_stats();
    if (slotIdx < 0 || slotIdx > 1) {
        g_stat_finish = 5;
        return env->NewStringUTF("");
    }
    Slot &s = g_slots[slotIdx];
    if (!s.ctx || !s.vocab) {
        g_stat_finish = 5; // no model
        return env->NewStringUTF("");
    }
    g_cancel.store(false);
    // A draft/autocorrect run rewrites this slot's KV wholesale (snapshot
    // restore or clear): the prediction prefix is gone either way.
    invalidate_pred(slotIdx);

    const char *staticC = env->GetStringUTFChars(jstatic, nullptr);
    std::string staticPrompt = staticC ? staticC : "";
    env->ReleaseStringUTFChars(jstatic, staticC);
    const char *dynC = env->GetStringUTFChars(jdynamic, nullptr);
    std::string dynPrompt = dynC ? dynC : "";
    env->ReleaseStringUTFChars(jdynamic, dynC);

    std::vector<llama_token> staticTok, dynTok;
    if (!tokenize(s, staticPrompt, true, staticTok) ||
        !tokenize(s, dynPrompt, false, dynTok)) {
        g_stat_finish = 3;
        return env->NewStringUTF("");
    }

    // Keep the whole thing inside the window: trim the DYNAMIC tail's head
    // (the oldest context), never the static prefix. The old whole-prompt
    // left-truncation used to saw the system block off oversized prompts.
    const int budget = s.nCtx - (int) staticTok.size() - (int) maxTokens - 8;
    if (budget > 64 && (int) dynTok.size() > budget) {
        dynTok.erase(dynTok.begin(), dynTok.end() - budget);
        LOGI("dynamic tail left-truncated to %d tokens", (int) dynTok.size());
    }

    g_stat_prompt_tokens = (long) staticTok.size() + (long) dynTok.size();
    const long t0 = now_ms();
    bool ok = false;

    if (s.snapTokens > 0 && s.snapPrefix == staticPrompt &&
        s.snapTokens == (int) staticTok.size()) {
        // Snapshot hit: restore the static prefix's full state, decode only
        // the dynamic tail. ~memcpy instead of a few hundred token decodes.
        size_t rd = llama_state_set_data(s.ctx, s.snap.data(), s.snap.size());
        if (rd == s.snap.size() && decode_at(s, dynTok, s.snapTokens)) {
            ok = true;
            g_stat_reused_tokens = s.snapTokens;
        } else {
            LOGI("snapshot restore failed — falling back to full decode");
            s.clearSnap();
        }
    }

    if (!ok) {
        // Snapshot miss (first run, prefix changed, or restore failed):
        // decode the static prefix, capture its state for next time, then
        // decode the dynamic tail.
        llama_memory_clear(llama_get_memory(s.ctx), true);
        if (!decode_at(s, staticTok, 0)) {
            g_stat_finish = 4;
            return env->NewStringUTF("");
        }
        s.clearSnap();
        const size_t snapSize = llama_state_get_size(s.ctx);
        if (snapSize > 0 && snapSize < (size_t) 512 * 1024 * 1024) {
            s.snap.resize(snapSize);
            const size_t written = llama_state_get_data(s.ctx, s.snap.data(), snapSize);
            if (written == snapSize) {
                s.snapPrefix = staticPrompt;
                s.snapTokens = (int) staticTok.size();
            } else {
                s.clearSnap();
                LOGI("snapshot capture incomplete — reuse disabled this run");
            }
        } else {
            LOGI("snapshot too large (%zu bytes) — reuse disabled", snapSize);
        }
        if (!decode_at(s, dynTok, (int) staticTok.size())) {
            g_stat_finish = 4;
            s.clearSnap();
            return env->NewStringUTF("");
        }
        ok = true;
    }
    g_stat_prompt_ms = now_ms() - t0;

    // lbg_sampler_v2: sampling is caller-tuned per model family (Qwen3 gets
    // its official non-thinking recipe — the presence penalty breaks the
    // verbatim reply-loops small models fall into).
    LOGI("lbg_sampler_v2 topk=%d topp=%.2f rep=%.2f pres=%.2f freq=%.2f temp=%.2f slot=%d",
         (int) topK, topP, repeatPen, presencePen, freqPen, temperature, (int) slotIdx);
    llama_sampler *sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(sampler, llama_sampler_init_penalties(64, repeatPen, freqPen, presencePen));
    if (topK > 0) llama_sampler_chain_add(sampler, llama_sampler_init_top_k((int) topK));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(topP, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist((uint32_t) seed));

    jclass cbClass = env->GetObjectClass(callback);
    jmethodID onToken = env->GetMethodID(cbClass, "onToken", "(Ljava/lang/String;)V");

    std::string result;
    result.reserve(maxTokens * 4);
    const long g0 = now_ms();
    int finish = 0;
    for (int i = 0; i < maxTokens; i++) {
        if (g_cancel.load()) { finish = 2; break; }
        llama_token t = llama_sampler_sample(sampler, s.ctx, -1);
        if (llama_vocab_is_eog(s.vocab, t)) { finish = 0; break; }
        char buf[256];
        const int n = llama_token_to_piece(s.vocab, t, buf, sizeof(buf), 0, true);
        if (n > 0) {
            result.append(buf, n);
            if (onToken) {
                const size_t clen = utf8_complete_prefix_len(result);
                if (clen > 0) {
                    jstring chunk = env->NewStringUTF(result.substr(0, clen).c_str());
                    env->CallVoidMethod(callback, onToken, chunk);
                    env->DeleteLocalRef(chunk);
                }
            }
        }
        llama_sampler_accept(sampler, t);
        g_stat_gen_tokens++;
        llama_batch one = llama_batch_get_one(&t, 1);
        if (llama_decode(s.ctx, one) != 0) {
            finish = 6;
            break;
        }
    }
    g_stat_gen_ms = now_ms() - g0;
    if (finish == 0 && g_stat_gen_tokens >= maxTokens) finish = 1;
    g_stat_finish = finish;

    llama_sampler_free(sampler);
    return env->NewStringUTF(result.c_str());
}

} // extern "C"

extern "C" JNIEXPORT jstring JNICALL
Java_com_localboard_keyboard_llm_LlamaCppBridge_nativePredictWords(
        JNIEnv *env, jclass, jstring jtext, jint maxWords, jint slotIdx) {
    if (slotIdx < 0 || slotIdx > 1) return env->NewStringUTF("");
    Slot &s = g_slots[slotIdx];
    if (!s.ctx || !s.vocab) return env->NewStringUTF("");
    PredState &ps = g_pred[slotIdx];

    const char *tc = env->GetStringUTFChars(jtext, nullptr);
    std::string text = tc ? tc : "";
    env->ReleaseStringUTFChars(jtext, tc);
    if (text.empty()) return env->NewStringUTF("");

    std::vector<llama_token> toks;
    if (!tokenize(s, text, true, toks) || toks.empty()) return env->NewStringUTF("");
    // Keep the newest context that fits, always leaving room for the
    // candidate probes. Dropping the head changes every position, so a
    // trimmed prompt simply never matches the cache and decodes in full.
    const int budget = s.nCtx - 24;
    if ((int) toks.size() > budget && budget > 8) {
        toks.erase(toks.begin() + 1, toks.end() - (budget - 1));
    }

    llama_memory_t mem = llama_get_memory(s.ctx);
    const long t0 = now_ms();

    // Longest common prefix with what the cache holds. The last token must
    // always be (re)decoded because logits are not retained between calls.
    size_t common = 0;
    if (ps.valid) {
        const size_t lim = std::min(ps.tokens.size(), toks.size() - 1);
        while (common < lim && ps.tokens[common] == toks[common]) common++;
    }
    bool fresh = false;
    if (!ps.valid || common == 0) {
        llama_memory_clear(mem, true);
        common = 0;
        fresh = true;
    } else if (!llama_memory_seq_rm(mem, 0, (llama_pos) common, -1)) {
        // Recurrent/hybrid architectures cannot drop a partial sequence:
        // start over from an empty cache.
        llama_memory_clear(mem, true);
        common = 0;
        fresh = true;
    }
    std::vector<llama_token> tail(toks.begin() + common, toks.end());
    if (!decode_at(s, tail, (int) common)) {
        invalidate_pred(slotIdx);
        llama_memory_clear(mem, true);
        return env->NewStringUTF("");
    }
    ps.tokens = toks;
    ps.valid = true;
    const int basePos = (int) toks.size();
    g_stat_prompt_tokens = (long) tail.size();
    g_stat_reused_tokens = (long) common;
    g_stat_prompt_ms = now_ms() - t0;
    (void) fresh;

    // Top first tokens by probability.
    const float *logits = llama_get_logits_ith(s.ctx, -1);
    const int nv = llama_vocab_n_tokens(s.vocab);
    float mx = logits[0];
    for (int i = 1; i < nv; i++) if (logits[i] > mx) mx = logits[i];
    double z = 0.0;
    for (int i = 0; i < nv; i++) z += std::exp((double) (logits[i] - mx));
    const int TOPN = 24;
    std::vector<std::pair<float, llama_token>> top;
    top.reserve(TOPN + 1);
    for (int i = 0; i < nv; i++) {
        const float l = logits[i];
        if ((int) top.size() < TOPN) {
            top.emplace_back(l, (llama_token) i);
            if ((int) top.size() == TOPN)
                std::make_heap(top.begin(), top.end(), std::greater<>());
        } else if (l > top.front().first) {
            std::pop_heap(top.begin(), top.end(), std::greater<>());
            top.back() = {l, (llama_token) i};
            std::push_heap(top.begin(), top.end(), std::greater<>());
        }
    }
    std::sort(top.begin(), top.end(), [](auto &a, auto &b) { return a.first > b.first; });

    const long g0 = now_ms();
    std::string out;
    int words = 0, tried = 0, probes = 0;
    for (auto &cand : top) {
        if (words >= maxWords || tried >= 8 || g_cancel.load()) break;
        if (llama_vocab_is_eog(s.vocab, cand.second) || llama_vocab_is_control(s.vocab, cand.second)) continue;
        const std::string piece = piece_of(s, cand.second);
        std::string word = word_start_of(piece);
        if (word.empty()) continue;
        tried++;
        const double p = std::exp((double) (cand.first - mx)) / z;

        // Is the first token a whole word? Decode it and look at what the
        // model wants next: a new word/punctuation means yes; more letters
        // mean it was a fragment — extend a little and see.
        bool complete = false;
        bool ok = decode_one(s, cand.second, basePos);
        probes++;
        int pos = basePos + 1;
        for (int step = 0; ok && step < 3; step++) {
            const llama_token nx = argmax_token(s);
            const std::string np = piece_of(s, nx);
            if (llama_vocab_is_eog(s.vocab, nx) || np.empty() || np[0] == ' ' ||
                np[0] == '\n' || !word_body_piece(np)) {
                complete = true;
                break;
            }
            // Fragment: append and keep going.
            word += np;
            ok = decode_one(s, nx, pos++);
            probes++;
        }
        // Roll the probe back so the next candidate starts from the same
        // state. Failure here means the cache is unusable for anything.
        if (!llama_memory_seq_rm(mem, 0, (llama_pos) basePos, -1)) {
            invalidate_pred(slotIdx);
            llama_memory_clear(mem, true);
            break;
        }
        if (!ok || !complete || word.size() > 24) continue;
        char pb[32];
        snprintf(pb, sizeof(pb), "%.5f", p);
        out += word;
        out += '\t';
        out += pb;
        out += '\n';
        words++;
    }
    g_stat_gen_tokens = probes;
    g_stat_gen_ms = now_ms() - g0;
    g_stat_finish = 0;
    return env->NewStringUTF(out.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_localboard_keyboard_llm_LlamaCppBridge_nativePredictReset(JNIEnv *, jclass, jint slotIdx) {
    invalidate_pred(slotIdx);
}
