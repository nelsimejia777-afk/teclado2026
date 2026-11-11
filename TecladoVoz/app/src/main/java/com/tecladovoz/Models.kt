package com.tecladovoz

import android.content.Context
import java.io.File

data class ModelInfo(val id: String, val file: String, val size: String)

object Models {
    val all = listOf(
        ModelInfo("tiny", "ggml-tiny-q5_1.bin", "≈31 MB"),
        ModelInfo("base", "ggml-base-q5_1.bin", "≈57 MB"),
        ModelInfo("small", "ggml-small-q5_1.bin", "≈181 MB")
    )

    fun info(id: String): ModelInfo = all.firstOrNull { it.id == id } ?: all[1]

    fun file(c: Context, id: String): File =
        File(File(c.filesDir, "models"), info(id).file)

    fun url(id: String): String =
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/" + info(id).file
}
