// JNI shim between VoiceBridge (Kotlin) and whisper.cpp.
// 16 kHz mono PCM16 in -> text out. Fully offline.
#include <jni.h>
#include <string>
#include <vector>
#include <mutex>
#include "whisper.h"

namespace {
whisper_context* g_ctx = nullptr;
std::mutex g_mu;  // whisper_context is not thread-safe; serialize access
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_localboard_keyboard_llm_VoiceBridge_nativeLoadModel(
    JNIEnv* env, jobject, jstring path, jint threads) {
    std::lock_guard<std::mutex> lock(g_mu);
    const char* p = env->GetStringUTFChars(path, nullptr);
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    whisper_context* ctx = whisper_init_from_file_with_params(p, cparams);
    env->ReleaseStringUTFChars(path, p);
    if (ctx == nullptr) return JNI_FALSE;
    if (g_ctx != nullptr) whisper_free(g_ctx);
    g_ctx = ctx;
    (void)threads;
    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_localboard_keyboard_llm_VoiceBridge_nativeTranscribe(
    JNIEnv* env, jobject, jshortArray pcm, jint samples, jint threads) {
    std::lock_guard<std::mutex> lock(g_mu);
    if (g_ctx == nullptr || pcm == nullptr || samples <= 0) return nullptr;

    jshort* in = env->GetShortArrayElements(pcm, nullptr);
    std::vector<float> f(samples);
    for (int i = 0; i < samples; i++) f[i] = in[i] / 32768.0f;
    env->ReleaseShortArrayElements(pcm, in, JNI_ABORT);

    whisper_full_params wparams =
        whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wparams.n_threads = threads > 0 ? threads : 4;
    wparams.print_progress = false;
    wparams.print_timestamps = false;
    wparams.print_special = false;
    wparams.print_realtime = false;
    wparams.no_timestamps = true;
    wparams.single_segment = true;
    wparams.language = "en";
    wparams.translate = false;

    if (whisper_full(g_ctx, wparams, f.data(), samples) != 0) return nullptr;

    std::string out;
    const int nseg = whisper_full_n_segments(g_ctx);
    for (int i = 0; i < nseg; i++) {
        const char* t = whisper_full_get_segment_text(g_ctx, i);
        if (t != nullptr) out += t;
    }
    return env->NewStringUTF(out.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_localboard_keyboard_llm_VoiceBridge_nativeFreeModel(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mu);
    if (g_ctx != nullptr) {
        whisper_free(g_ctx);
        g_ctx = nullptr;
    }
}
