package com.duiyi.android.services.speech

import com.duiyi.android.domain.DuiyiError

class SpeechEngineException(val kind: DuiyiError) : IllegalStateException(kind.name) {
    companion object {
        fun of(error: Throwable): DuiyiError =
            (error as? SpeechEngineException)?.kind ?: DuiyiError.RecognitionInterrupted
    }
}
