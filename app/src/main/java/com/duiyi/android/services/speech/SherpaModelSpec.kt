package com.duiyi.android.services.speech

object SherpaModelSpec {
    const val DIR = "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17"
    const val LEGACY_DIR = "sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20"

    data class Part(
        val fileName: String,
        val minBytes: Long,
        val expectedBytes: Long,
        val mirrors: List<String>,
    )

    private const val SENSE_VOICE = "csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17"
    private const val SENSE_VOICE_ALT = "csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17"

    val parts: List<Part> = listOf(
        Part(
            fileName = "model.int8.onnx",
            minBytes = 80_000_000,
            expectedBytes = 239_000_000,
            mirrors = huggingface(SENSE_VOICE, "model.int8.onnx") +
                huggingface(SENSE_VOICE_ALT, "model.int8.onnx"),
        ),
        Part(
            fileName = "tokens.txt",
            minBytes = 10_000,
            expectedBytes = 316_000,
            mirrors = huggingface(SENSE_VOICE, "tokens.txt") +
                huggingface(SENSE_VOICE_ALT, "tokens.txt"),
        ),
        Part(
            fileName = "silero_vad.onnx",
            minBytes = 200_000,
            expectedBytes = 643_854,
            mirrors = listOf(
                "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx",
                "https://ghproxy.net/https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx",
                "https://hf-mirror.com/csukuangfj/sherpa-onnx-asr-models/resolve/main/silero_vad.onnx",
                "https://huggingface.co/csukuangfj/sherpa-onnx-asr-models/resolve/main/silero_vad.onnx",
            ),
        ),
    )

    val expectedTotalBytes: Long = parts.sumOf { it.expectedBytes }

    fun isComplete(fileName: String, size: Long): Boolean {
        val part = parts.firstOrNull { it.fileName == fileName } ?: return false
        return size >= part.minBytes
    }

    fun mirrors(fileName: String): List<String> =
        parts.firstOrNull { it.fileName == fileName }?.mirrors.orEmpty()

    private fun huggingface(repo: String, fileName: String): List<String> = listOf(
        "https://hf-mirror.com/$repo/resolve/main/$fileName",
        "https://huggingface.co/$repo/resolve/main/$fileName",
        "https://www.modelscope.cn/models/$repo/resolve/master/$fileName",
    )
}
