#include <jni.h>
#include "whisper.h"
#include <android/log.h>
#include <string>

#define TAG "WhisperJNI"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_audiotranscriber_WhisperEngine_allocContext(
        JNIEnv* env, jobject, jstring modelPath) {
    const char* path = env->GetStringUTFChars(modelPath, nullptr);
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    whisper_context* ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(modelPath, path);
    if (!ctx) LOGE("Failed to load whisper model from path");
    return (jlong)(intptr_t)ctx;
}

JNIEXPORT void JNICALL
Java_com_audiotranscriber_WhisperEngine_freeContext(
        JNIEnv*, jobject, jlong ctxPtr) {
    if (ctxPtr) whisper_free((whisper_context*)(intptr_t)ctxPtr);
}

JNIEXPORT jstring JNICALL
Java_com_audiotranscriber_WhisperEngine_transcribeJni(
        JNIEnv* env, jobject,
        jlong ctxPtr, jfloatArray samples, jstring languageCode) {
    if (!ctxPtr) return env->NewStringUTF("");

    auto* ctx = (whisper_context*)(intptr_t)ctxPtr;

    jsize   n   = env->GetArrayLength(samples);
    jfloat* buf = env->GetFloatArrayElements(samples, nullptr);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_progress   = false;
    params.print_special    = false;
    params.print_realtime   = false;
    params.print_timestamps = false;
    params.single_segment   = false;
    params.no_context       = true;
    params.n_threads        = 4;

    const char* lang = env->GetStringUTFChars(languageCode, nullptr);
    params.language = lang;

    int rc = whisper_full(ctx, params, buf, (int)n);
    env->ReleaseStringUTFChars(languageCode, lang);
    env->ReleaseFloatArrayElements(samples, buf, JNI_ABORT);

    if (rc != 0) {
        LOGE("whisper_full failed: %d", rc);
        return env->NewStringUTF("");
    }

    std::string result;
    int segs = whisper_full_n_segments(ctx);
    for (int i = 0; i < segs; i++) {
        const char* text = whisper_full_get_segment_text(ctx, i);
        if (text) result += text;
    }

    size_t s = result.find_first_not_of(" \t\n\r");
    if (s == std::string::npos) return env->NewStringUTF("");
    size_t e = result.find_last_not_of(" \t\n\r");
    return env->NewStringUTF(result.substr(s, e - s + 1).c_str());
}

} // extern "C"
