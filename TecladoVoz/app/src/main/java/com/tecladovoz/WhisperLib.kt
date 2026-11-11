package com.tecladovoz

/** Puente JNI hacia whisper.cpp (libwhisperjni.so). */
object WhisperLib {
    val ok: Boolean = try {
        System.loadLibrary("whisperjni")
        true
    } catch (e: Throwable) {
        false
    }

    external fun initContext(path: String): Long
    external fun transcribe(ctx: Long, audio: FloatArray, threads: Int, lang: String): ByteArray?
    external fun freeContext(ctx: Long)
}
