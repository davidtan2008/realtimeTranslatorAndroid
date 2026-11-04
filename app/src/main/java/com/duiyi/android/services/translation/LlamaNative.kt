package com.duiyi.android.services.translation

internal object LlamaNative {
    init {
        System.loadLibrary("duiyi_llama")
    }

    external fun create(path: String, nCtx: Int, nThreads: Int): Long
    external fun warmPrefix(handle: Long, prefix: String)
    external fun generate(handle: Long, prefix: String, suffix: String, maxTokens: Int): String
    external fun progress(handle: Long): String
    external fun prefillSource(handle: Long, prefix: String, suffix: String)
    external fun setVerboseLogging(enabled: Boolean)
    external fun abort(handle: Long)
    external fun destroy(handle: Long)
}
