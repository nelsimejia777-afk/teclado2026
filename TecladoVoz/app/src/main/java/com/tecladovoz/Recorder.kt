package com.tecladovoz

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Graba SIN INTERRUPCIONES desde que se llama a start() hasta stop().
 * Nunca se corta por silencio: solo divide el audio en trozos (en las pausas largas)
 * para ir transcribiendo mientras sigues hablando. No se descarta ningún audio con voz.
 */
class SegmentRecorder(
    private val onSegment: (FloatArray) -> Unit,
    private val onEnded: () -> Unit
) {
    companion object {
        const val RATE = 16000
        const val FRAME = 1600                 // 100 ms
        const val MIN_SEG = 2 * RATE           // un trozo dura al menos 2 s
        const val MAX_SEG = 25 * RATE          // y como máximo 25 s
        const val SILENCE_FRAMES = 10          // 1 s de silencio = punto de corte
        const val PREROLL = RATE / 2           // 0,5 s previos para no comerse la primera palabra
        const val MIN_SPEECH_FRAMES = 2        // mínimo 200 ms de voz para transcribir
    }

    @Volatile
    private var running = false

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        val minBuf = AudioRecord.getMinBufferSize(
            RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return false
        val size = max(minBuf, RATE * 2 * 2) // 2 s de búfer (en bytes)
        val r = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size
            )
        } catch (e: Exception) {
            return false
        }
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            return false
        }
        try {
            r.startRecording()
        } catch (e: Exception) {
            r.release()
            return false
        }
        if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            r.release()
            return false
        }
        running = true
        Thread { loop(r) }.start()
        return true
    }

    fun stop() {
        running = false
    }

    private fun loop(r: AudioRecord) {
        val frame = ShortArray(FRAME)
        val seg = ShortArray(MAX_SEG + FRAME)
        var len = 0
        var speech = 0
        var silent = 0
        var noise = 100.0
        try {
            while (running) {
                val n = r.read(frame, 0, FRAME)
                if (n < 0) break
                if (n == 0) continue

                var sum = 0.0
                for (i in 0 until n) {
                    val v = frame[i].toDouble()
                    sum += v * v
                }
                val rms = sqrt(sum / n)
                val isSpeech = rms > max(250.0, noise * 3.0)
                if (isSpeech) {
                    speech++
                    silent = 0
                } else {
                    silent++
                    noise = (noise * 0.95 + rms * 0.05).coerceIn(80.0, 1200.0)
                }

                System.arraycopy(frame, 0, seg, len, n)
                len += n

                if (speech == 0 && len > RATE) {
                    // Solo silencio: conservar únicamente los últimos 0,5 s
                    System.arraycopy(seg, len - PREROLL, seg, 0, PREROLL)
                    len = PREROLL
                } else if ((len >= MIN_SEG && silent >= SILENCE_FRAMES && speech > 0) || len >= MAX_SEG) {
                    emit(seg, len, speech)
                    len = 0
                    speech = 0
                    silent = 0
                }
            }
        } catch (e: Throwable) {
            // se termina la grabación; lo grabado hasta aquí se envía abajo
        } finally {
            try {
                r.stop()
            } catch (e: Exception) {
            }
            r.release()
            emit(seg, len, speech)
            try {
                onEnded()
            } catch (e: Throwable) {
            }
        }
    }

    private fun emit(seg: ShortArray, len: Int, speechFrames: Int) {
        if (speechFrames < MIN_SPEECH_FRAMES || len < RATE / 4) return
        val out = FloatArray(len)
        for (i in 0 until len) out[i] = seg[i] / 32768f
        try {
            onSegment(out)
        } catch (e: Throwable) {
        }
    }
}
