# On-device LLM engine (llama.cpp / GGUF)

> **Status: IMPLEMENTED in v1.1.0.** IOKey ships with a working llama.cpp
> provider (`LlamaCppProvider` + JNI bridge + in-app model download).
> **Settings → Language model → Download** fetches Qwen3-0.6B (Q4_K_M, ~400 MB)
> with thinking disabled (`/no_think`, i.e. `enable_thinking=false`) and makes
> it the default provider; the classic offline composer remains the automatic
> fallback until the model is downloaded. The INTERNET permission is used
> **only** for this user-initiated download — typing never leaves the device.
>
> This document remains the reference for swapping in a different GGUF model,
> rebuilding the native library (`tools/fetch_llama.sh`, `tools/build_native.sh`),
> or bundling a tiny model inside the APK.

IOKey's `LLMProvider` interface is the single seam for neural backends.
The bundled provider runs a small GGUF model fully on-device via llama.cpp;
everything below applies to any model you plug into the same seam.

## 1. Pick a model — recommended shortlist (verified July 2026)

All of these are **Apache-2.0** (no usage restrictions, commercial OK) with
**GGUF builds on Hugging Face** ready for llama.cpp:

| Model | Q4_K_M size* | Speed (flagship phone)* | Why for this keyboard |
|---|---|---|---|
| **SmolLM2-135M-Instruct** | ~100 MB | very fast (~100+ tok/s) | Ultra-light. Only model you can reasonably *bundle inside an APK*. Good enough for short casual drafts. |
| **SmolLM2-360M-Instruct** | ~300 MB | ~80–100 tok/s | **Sweet spot.** Coherent short replies, instant load, tiny RAM. English-focused. → `HuggingFaceTB/SmolLM2-360M-Instruct-GGUF` (official) |
| **Qwen3-0.6B** | ~450 MB | ~40–60 tok/s | Best brains-per-MB + 119 languages. **Disable thinking** (`enable_thinking=false` / `/no_think`) for keyboard latency. GGUF: `bartowski/Qwen_Qwen3-0.6B-GGUF`, `unsloth/Qwen3-0.6B-GGUF` |
| **Qwen3-1.7B** | ~1.1 GB | ~24–32 tok/s | Flagship tier, strongest multilingual drafting under 2 GB. |
| **SmolLM3-3B** | ~2 GB | ~10–20 tok/s | Quality ceiling for 8 GB+ phones. SOTA at 3B, use `no_think` mode. Official GGUF collection on `HuggingFaceTB`. |

\* sizes/speed are practical estimates from llama.cpp Q4_K_M measurements on
recent flagship SoCs (Snapdragon 8 Elite / A19 Pro class); mid-range phones
run roughly 40–60% slower.

Not Apache but worth knowing: **Gemma 3 1B** (Gemma license, ~720 MB,
~35–45 tok/s) and **Gemma 3n E2B** (mobile-first MatFormer, ~2 GB RAM) —
both fine options if the custom Gemma license works for you. **Phi-4-mini**
(3.8B, MIT) is the smartest small model but heavy for a keyboard.

**Bundling strategy:** SmolLM2-135M can ship *inside* the APK (assets).
Anything larger: sideload the `.gguf` to `Documents/localboard/`, or add a
Play Asset Delivery pack. Keep the app's no-INTERNET posture either way.

Put the file on the device, e.g. `Documents/localboard/smollm2-360m-q4.gguf`,
or bundle it via assets + copy-on-first-run (big APK).

## 2. Add the NDK build

```bash
cd TinyLLMKeyboard
git submodule add https://github.com/ggerganov/llama.cpp app/src/main/cpp/llama.cpp
```

`app/build.gradle.kts`:

```kotlin
android {
    ndkVersion = "26.1.10909125"
    defaultConfig {
        externalNativeBuild {
            cmake {
                cppFlags += "-O3"
                abiFilters += listOf("arm64-v8a", "x86_64")
            }
        }
    }
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt") }
    }
}
```

`app/src/main/cpp/CMakeLists.txt`:

```cmake
cmake_minimum_required(VERSION 3.22.1)
project(localboard_llm)
set(LLAMA_BUILD_COMMON ON)
add_subdirectory(llama.cpp)
add_library(localboard_llm SHARED llm_jni.cpp)
target_link_libraries(localboard_llm llama common android log)
```

## 3. JNI shim (`llm_jni.cpp`)

Three functions are enough: load / generate-stream / free.

```cpp
#include <jni.h>
#include "llama.h"
#include <string>
#include <vector>

static llama_model* g_model = nullptr;
static llama_context* g_ctx = nullptr;

extern "C" JNIEXPORT jboolean JNICALL
Java_com_localboard_keyboard_llm_LlamaCppBridge_nativeLoad(JNIEnv* env, jclass, jstring path) {
    const char* p = env->GetStringUTFChars(path, nullptr);
    llama_backend_init();
    llama_model_params mp = llama_model_default_params();
    g_model = llama_load_model_from_file(p, mp);
    env->ReleaseStringUTFChars(path, p);
    if (!g_model) return JNI_FALSE;
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = 1024; cp.n_threads = 4;
    g_ctx = llama_new_context_with_model(g_model, cp);
    return g_ctx ? JNI_TRUE : JNI_FALSE;
}

// Streaming: call repeatedly with the current prompt; returns the next piece.
extern "C" JNIEXPORT jstring JNICALL
Java_com_localboard_keyboard_llm_LlamaCppBridge_nativeGenerate(
        JNIEnv* env, jclass, jstring prompt, jint maxTokens, jfloat temp) {
    // tokenize prompt -> eval -> sample loop with llama_sample_temp
    // collect detokenized text into std::string, return as jstring
    // (see llama.cpp/examples/simple for the canonical loop)
    const char* c = env->GetStringUTFChars(prompt, nullptr);
    std::string out = "TODO: wire llama sampling for: ";
    out += c;
    env->ReleaseStringUTFChars(prompt, c);
    return env->NewStringUTF(out.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_localboard_keyboard_llm_LlamaCppBridge_nativeFree(JNIEnv*, jclass) {
    if (g_ctx) llama_free(g_ctx);
    if (g_model) llama_free_model(g_model);
    llama_backend_free();
    g_ctx = nullptr; g_model = nullptr;
}
```

## 4. Kotlin provider — plug into the existing registry

```kotlin
package com.localboard.keyboard.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object LlamaCppBridge {
    init { System.loadLibrary("localboard_llm") }
    external fun nativeLoad(path: String): Boolean
    external fun nativeGenerate(prompt: String, maxTokens: Int, temp: Float): String
    external fun nativeFree()
}

class LlamaCppProvider(private val modelPath: String) : LLMProvider {
    override val providerId = "llamacpp"
    override val displayName = "On-device GGUF (llama.cpp)"
    override val description = "Neural drafting from a local GGUF model file."
    override val isAvailable: Boolean get() = java.io.File(modelPath).exists()

    private var loaded = false

    override suspend fun generate(req: GenerateRequest, onToken: (String) -> Unit): String =
        withContext(Dispatchers.Default) {
            if (!loaded) {
                check(LlamaCppBridge.nativeLoad(modelPath)) { "model load failed" }
                loaded = true
            }
            val style = req.styleProfile
            val prompt = buildString {
                append("Continue this message in the writer's own voice")
                style?.let { append(" (formality ${"%.1f".format(it.formality)}, ~${it.avgSentenceLength.toInt()} words)") }
                append(":\n${req.contextBefore.takeLast(300)}")
            }
            // For true streaming, expose a token callback via JNI; chunked
            // generation works fine as a first pass:
            val text = LlamaCppBridge.nativeGenerate(prompt, req.maxWords * 2, req.temperature)
            text.split(" ").forEach { w -> onToken("$w ") }
            text
        }
}
```

Register it once in `LLMKeyboardService.onCreate`:

```kotlin
registry.register(LlamaCppProvider(getModelPath(this)))
// user picks it in Settings -> Prediction engine, or set default:
// prefs.activeProviderId = "llamacpp"
```

## 5. Realistic performance budget (Snapdragon 8-class)

| Model (Q4) | Load | Draft 30 words |
|---|---|---|
| SmolLM2-135M | <0.5 s | ~1 s |
| SmolLM2-360M | ~0.5–1 s | ~1–2 s |
| Qwen3-0.6B (no-think) | ~1 s | ~2–4 s |
| Qwen3-1.7B (no-think) | ~2 s | ~4–8 s |
| SmolLM3-3B (no_think) | ~3–4 s | ~8–15 s |

Prompt-format notes: SmolLM2 uses ChatML (`<|im_start|>...`), Qwen3 uses
ChatML too — always pass `enable_thinking=false` (or `/no_think` in the
system prompt) so the model answers immediately instead of emitting a
reasoning trace; SmolLM3 honors `/no_think` the same way.

Keep the model resident after first load; the per-keystroke path never
touches the LLM — it only serves Write-It-For-Me and (optionally) paused
typing, exactly as the hybrid architecture intends.
