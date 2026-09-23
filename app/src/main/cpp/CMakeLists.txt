cmake_minimum_required(VERSION 3.22.1)
project(localboard_llm CXX C)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# --- llama.cpp configuration: static, CPU-only, minimal footprint ---
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)     # safe cross-compile
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE) # statically linked backends
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
# With NATIVE off and no arch given, ggml-cpu falls back to PLAIN armv8-a:
# no SDOT (dot-product) instructions — which is most of the Q4_K matmul speed
# on ARM (2-3x). Every 2019+ flagship/midrange SoC (SD855+, Dimensity 800+,
# Exynos 990+) has armv8.2-a + dotprod; the S22 Ultra this targets has more.
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)

# --- 16 KB page size (REQUIRED by Google Play for targetSdk >= 35) ---
# Android 15 introduced devices with a 16 KB kernel page size. A .so whose
# LOAD segments are aligned to the old 4 KB boundary cannot be mapped on
# them, so Play rejects the upload outright rather than shipping something
# that crashes on load. NDK r27+ does this by default, but the flag is set
# explicitly because a build done with an older NDK would otherwise silently
# produce a 4 KB binary that only fails at store-submission time.
#
# Verify after building — this is the check, and it is not optional:
#   llvm-readelf --program-headers liblocalboard_llm.so | grep LOAD
# every LOAD line must end in 0x4000 (16384), not 0x1000.
add_link_options("-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384")

add_subdirectory(llama.cpp)

add_library(localboard_llm SHARED llm_jni.cpp)
target_include_directories(localboard_llm PRIVATE llama.cpp/include llama.cpp/ggml/include)
target_link_libraries(localboard_llm llama android log)

# Strip symbols to keep the .so small
set_target_properties(localboard_llm PROPERTIES LINK_FLAGS "-s")

# The whisper.cpp voice engine is built as a SEPARATE cmake project, in
# voice/CMakeLists.txt. It cannot be an add_subdirectory() here: whisper.cpp
# vendors its own copy of ggml, and configuring both trees in one project
# defines ggml/ggml-base/ggml-cpu twice, which cmake rejects outright.
# tools/build_native.sh runs the two configure passes back to back.
