#include <jni.h>
#include <android/log.h>
#include <string>
#include "whisper.h"

#define TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_tecladovoz_WhisperLib_initContext(JNIEnv *env, jobject /*thiz*/, jstring path) {
    const char *p = env->GetStringUTFChars(path, nullptr);
    if (p == nullptr) return 0;
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(p, cparams);
    if (ctx == nullptr) {
        LOGE("No se pudo cargar el modelo: %s", p);
    } else {
        LOGI("Modelo cargado: %s", p);
    }
    env->ReleaseStringUTFChars(path, p);
    return reinterpret_cast<jlong>(ctx);
}

// Devuelve el texto en bytes UTF-8 (se decodifica en Kotlin; evita fallos de JNI
// cuando un carácter multibyte queda partido entre tokens).
JNIEXPORT jbyteArray JNICALL
Java_com_tecladovoz_WhisperLib_transcribe(JNIEnv *env, jobject /*thiz*/, jlong ctxPtr,
                                          jfloatArray audio, jint threads, jstring lang) {
    auto *ctx = reinterpret_cast<whisper_context *>(ctxPtr);
    if (ctx == nullptr) return nullptr;

    const jsize n = env->GetArrayLength(audio);
    jfloat *data = env->GetFloatArrayElements(audio, nullptr);
    if (data == nullptr) return nullptr;
    const char *l = env->GetStringUTFChars(lang, nullptr);
    if (l == nullptr) {
        env->ReleaseFloatArrayElements(audio, data, JNI_ABORT);
        return nullptr;
    }

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = threads;
    params.language = l;
    params.translate = false;
    params.no_context = true;
    params.single_segment = false;
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.temperature_inc = 0.0f;  // sin reintentos lentos

    const int rc = whisper_full(ctx, params, data, n);

    std::string out;
    if (rc == 0) {
        const int segments = whisper_full_n_segments(ctx);
        for (int i = 0; i < segments; i++) {
            const char *t = whisper_full_get_segment_text(ctx, i);
            if (t != nullptr) out += t;
        }
    } else {
        LOGE("whisper_full devolvió %d", rc);
    }

    env->ReleaseStringUTFChars(lang, l);
    env->ReleaseFloatArrayElements(audio, data, JNI_ABORT);

    if (rc != 0) return nullptr;

    jbyteArray arr = env->NewByteArray(static_cast<jsize>(out.size()));
    if (arr == nullptr) return nullptr;
    env->SetByteArrayRegion(arr, 0, static_cast<jsize>(out.size()),
                            reinterpret_cast<const jbyte *>(out.data()));
    return arr;
}

JNIEXPORT void JNICALL
Java_com_tecladovoz_WhisperLib_freeContext(JNIEnv * /*env*/, jobject /*thiz*/, jlong ctxPtr) {
    auto *ctx = reinterpret_cast<whisper_context *>(ctxPtr);
    if (ctx != nullptr) whisper_free(ctx);
}

}  // extern "C"
