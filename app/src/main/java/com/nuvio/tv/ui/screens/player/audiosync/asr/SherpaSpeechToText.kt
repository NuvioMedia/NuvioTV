package com.nuvio.tv.ui.screens.player.audiosync.asr

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import java.io.File

/**
 * English speech recognition with sherpa-onnx (Apache-2.0) and an int8 Zipformer transducer
 * trained on GigaSpeech (podcasts and YouTube, close to film dialogue). Runs fully on-device.
 */
internal class SherpaSpeechToText(modelDir: File, threads: Int) : SpeechToText, AutoCloseable {
    private val recognizer = OfflineRecognizer(
        config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = File(modelDir, AsrModel.ENCODER).absolutePath,
                    decoder = File(modelDir, AsrModel.DECODER).absolutePath,
                    joiner = File(modelDir, AsrModel.JOINER).absolutePath,
                ),
                tokens = File(modelDir, AsrModel.TOKENS).absolutePath,
                numThreads = threads,
                modelType = "transducer",
            ),
            decodingMethod = "greedy_search",
        ),
    )

    override fun transcribe(samples: FloatArray): List<Pair<Double, String>> {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            recognizer.decode(stream)
            val result = recognizer.getResult(stream)
            return mergeTokens(result.tokens, result.timestamps)
        } finally {
            stream.release()
        }
    }

    override fun close() = recognizer.release()

    companion object {
        private const val SAMPLE_RATE = 16_000

        /** BPE pieces starting with "▁" (or a space) begin a new word. */
        fun mergeTokens(tokens: Array<String>, timestamps: FloatArray): List<Pair<Double, String>> {
            val words = ArrayList<Pair<Double, String>>()
            val current = StringBuilder()
            var start = 0.0
            for (i in tokens.indices) {
                val token = tokens[i]
                val startsWord = token.startsWith('▁') || token.startsWith(' ') || current.isEmpty()
                if (startsWord) {
                    if (current.isNotEmpty()) words += start to current.toString()
                    current.setLength(0)
                    start = timestamps.getOrElse(i) { 0f }.toDouble()
                }
                current.append(token.trimStart('▁', ' '))
            }
            if (current.isNotEmpty()) words += start to current.toString()
            return words
        }
    }
}
