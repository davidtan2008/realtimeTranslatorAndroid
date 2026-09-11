package com.duiyi.android.services.translation

object GemmaModelSpec {
    const val DIR = "translategemma-4b-it-q4"
    const val FILE_NAME = "translategemma-4b-it.Q4_K_M.gguf"
    const val MIN_BYTES = 2_000_000_000L
    const val EXPECTED_BYTES = 2_489_909_760L

    private const val REPO = "mradermacher/translategemma-4b-it-GGUF"

    val mirrors: List<String> = listOf(
        "https://hf-mirror.com/$REPO/resolve/main/$FILE_NAME",
        "https://huggingface.co/$REPO/resolve/main/$FILE_NAME",
    )

    fun isComplete(size: Long): Boolean = size >= MIN_BYTES
}
