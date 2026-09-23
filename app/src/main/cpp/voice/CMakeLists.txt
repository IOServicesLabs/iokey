cmake_minimum_required(VERSION 3.22.1)
project(localboard_voice CXX C)

# Builds liblocalboard_voice.so — the whisper.cpp offline speech engine behind
# VoiceBridge. This is a SEPARATE cmake project from ../CMakeLists.txt on
# purpose: whisper.cpp and llama.cpp each vendor a full copy of ggml, and
# configuring both in one project defines ggml/ggml-base/ggml-cpu twice, which
# cmake rejects. Two projects, two build dirs, no collision.
#
# whisper.cpp is not checked in. Run tools/fetch_whisper.sh first.

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# --- 16 KB page size (REQUIRED by Google Play for targetSdk >= 35) ---
# See the long note in ../CMakeLists.txt. Same requirement, same flags: a
# 4 KB-aligned .so cannot be mapped on an Android 15 16 KB-page device, and
# Play rejects the upload rather than shipping a guaranteed crash.
add_link_options("-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384")

set(WHISPER_SRC "${CMAKE_CURRENT_SOURCE_DIR}/../whisper.cpp")
if(NOT EXISTS "${WHISPER_SRC}/CMakeLists.txt")
    message(FATAL_ERROR "whisper.cpp not found at ${WHISPER_SRC} - run tools/fetch_whisper.sh")
endif()

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
# Same reasoning as the LLM build: without an explicit arch, ggml-cpu falls
# back to plain armv8-a and loses the dot-product instructions that carry most
# of the matmul throughput on ARM.
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)

add_subdirectory("${WHISPER_SRC}" "${CMAKE_CURRENT_BINARY_DIR}/whisper-build")

add_library(localboard_voice SHARED ../voice_jni.cpp)
target_include_directories(localboard_voice PRIVATE
    "${WHISPER_SRC}/include" "${WHISPER_SRC}/ggml/include")
target_link_libraries(localboard_voice whisper android log)

# Strip symbols to keep the .so small
set_target_properties(localboard_voice PROPERTIES LINK_FLAGS "-s")
