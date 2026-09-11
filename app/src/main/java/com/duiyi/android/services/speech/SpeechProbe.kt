package com.duiyi.android.services.speech

import android.content.Context
import com.duiyi.android.services.DuiyiLog
import java.io.File

object SpeechProbe {
    fun i(context: Context, message: String) {
        DuiyiLog.i(message)
        runCatching {
            File(context.filesDir, "speech-probe.txt").appendText("${System.currentTimeMillis()} $message\n")
        }
    }
}
