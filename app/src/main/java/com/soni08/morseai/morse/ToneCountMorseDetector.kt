package com.soni08.morseai.morse

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.sqrt

class ToneCountMorseDetector(
    private val sampleRate: Int = 16000,
    private val burstGapMs: Long = 350L,
    private val finalizeTimeoutMs: Long = 800L,
    private val shortcutMap: Map<String, String> = defaultShortcuts(),
    private val onShortcutTriggered: ((pattern: String, actionOrPackage: String) -> Unit)? = null,
    private val onToneBurstCompleted: (toneCount: Int, morseChar: String) -> Unit,
    private val onPhraseCompleted: (morseSequence: String, decodedText: String) -> Unit
) {
    companion object {
        fun defaultShortcuts(): Map<String, String> = mapOf(
            "." to "おんりょうあっぷ",
            "---" to "もどる",
            ".---" to "ほーむ",
            "---." to "つうち"
        )
    }

    private var isRunning = false
    private var audioRecord: AudioRecord? = null
    private var workerThread: Thread? = null

    private var noiseFloorRms = 300.0
    private var isToneActive = false
    private var toneStartMs = 0L
    private var lastToneEndMs = 0L
    private var currentBurstCount = 0
    private val phraseSymbols = StringBuilder()

    private val morseMap = mapOf(
        ".-" to "A", "-..." to "B", "-.-." to "C", "-.." to "D", "." to "E",
        "..-." to "F", "--." to "G", "...." to "H", ".." to "I", ".---" to "J",
        "-.-" to "K", ".-.." to "L", "--" to "M", "-." to "N", "---" to "O",
        ".--." to "P", "--.-" to "Q", ".-." to "R", "..." to "S", "-" to "T",
        "..-" to "U", "...-" to "V", ".--" to "W", "-..-" to "X", "-.--" to "Y",
        "--.." to "Z", "-----" to "0", ".----" to "1", "..---" to "2",
        "...--" to "3", "....-" to "4", "....." to "5", "-...." to "6",
        "--..." to "7", "---.." to "8", "----." to "9"
    )

    @Volatile
    var paused: Boolean = false

    fun start() {
        if (isRunning) return
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = maxOf(minBuf, sampleRate / 5)

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord?.release()
            audioRecord = null
            return
        }

        isRunning = true
        audioRecord?.startRecording()

        workerThread = Thread {
            val frameSamples = sampleRate / 50
            val pcm = ShortArray(frameSamples)

            while (isRunning) {
                if (paused) {
                    resetState()
                    Thread.sleep(50)
                    continue
                }

                val read = audioRecord?.read(pcm, 0, frameSamples) ?: 0
                if (read <= 0) continue

                val rms = calculateRms(pcm, read)
                val now = System.currentTimeMillis()
                val threshold = maxOf(noiseFloorRms * 2.8, 800.0)

                val detectedNow = rms >= threshold
                if (detectedNow) {
                    if (!isToneActive) {
                        if (lastToneEndMs > 0L && (now - lastToneEndMs) > burstGapMs && currentBurstCount > 0) {
                            flushBurst()
                        }
                        isToneActive = true
                        toneStartMs = now
                    }
                } else {
                    if (isToneActive) {
                        val duration = now - toneStartMs
                        if (duration in 50L..450L) {
                            currentBurstCount++
                            lastToneEndMs = now
                        }
                        isToneActive = false
                    } else {
                        noiseFloorRms = noiseFloorRms * 0.97 + rms * 0.03
                    }

                    if (currentBurstCount > 0 && lastToneEndMs > 0L && (now - lastToneEndMs) >= burstGapMs) {
                        flushBurst()
                    }

                    if (phraseSymbols.isNotEmpty() && lastToneEndMs > 0L && (now - lastToneEndMs) >= finalizeTimeoutMs) {
                        val seq = phraseSymbols.toString()
                        phraseSymbols.clear()
                        lastToneEndMs = 0L

                        val matchedShortcut = shortcutMap[seq]
                        if (matchedShortcut != null && onShortcutTriggered != null) {
                            onShortcutTriggered.invoke(seq, matchedShortcut)
                        } else {
                            val decoded = decodeSequence(seq)
                            onPhraseCompleted(seq, decoded)
                        }
                    }
                }
            }
        }.apply {
            name = "ToneCountMorseDetector"
            isDaemon = true
            start()
        }
    }

    private fun flushBurst() {
        val count = currentBurstCount
        currentBurstCount = 0
        val symbol = when {
            count == 1 -> "."
            count >= 3 -> "-"
            count == 2 -> ".."
            else -> ""
        }
        if (symbol.isNotEmpty()) {
            phraseSymbols.append(symbol)
            onToneBurstCompleted(count, symbol)
        }
    }

    private fun decodeSequence(seq: String): String {
        return seq.split(" ").filter { it.isNotEmpty() }.joinToString("") { token ->
            morseMap[token] ?: "[$token]"
        }
    }

    private fun resetState() {
        isToneActive = false
        currentBurstCount = 0
        lastToneEndMs = 0L
        phraseSymbols.clear()
    }

    private fun calculateRms(buffer: ShortArray, length: Int): Double {
        var sumSq = 0.0
        for (i in 0 until length) {
            val v = buffer[i].toDouble()
            sumSq += v * v
        }
        return sqrt(sumSq / length)
    }

    fun stop() {
        isRunning = false
        try {
            workerThread?.join(500)
        } catch (_: InterruptedException) {
        }
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }
}
