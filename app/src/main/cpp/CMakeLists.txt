cmake_minimum_required(VERSION 3.22.1)
project(whisper_jni)

include(FetchContent)

FetchContent_Declare(
    whispercpp
    GIT_REPOSITORY https://github.com/ggerganov/whisper.cpp.git
    GIT_TAG        v1.7.4
    GIT_SHALLOW    TRUE
)

set(BUILD_SHARED_LIBS      OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(GGML_METAL             OFF CACHE BOOL "" FORCE)
set(GGML_ACCELERATE        OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP            OFF CACHE BOOL "" FORCE)
set(GGML_AVX               OFF CACHE BOOL "" FORCE)
set(GGML_AVX2              OFF CACHE BOOL "" FORCE)
set(GGML_FMA               OFF CACHE BOOL "" FORCE)
set(GGML_F16C              OFF CACHE BOOL "" FORCE)

FetchContent_MakeAvailable(whispercpp)

add_library(whisper_jni SHARED whisper_jni.cpp)

target_include_directories(whisper_jni PRIVATE
    ${whispercpp_SOURCE_DIR}/include
    ${whispercpp_SOURCE_DIR}
)

target_compile_options(whisper_jni PRIVATE -std=c++17 -fexceptions -frtti)

target_link_libraries(whisper_jni
    whisper
    android
    log
)
