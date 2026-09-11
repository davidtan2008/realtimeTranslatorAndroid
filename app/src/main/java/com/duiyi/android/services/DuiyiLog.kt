package com.duiyi.android.services

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DuiyiLog {
    private const val TAG = "Duiyi"
    @Volatile
    private var file: File? = null
    private val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun install(context: Context) {
        file = File(context.filesDir, "duiyi.log")
        i("log file ${file?.absolutePath}")
    }

    fun i(message: String) {
        write("I", message, null)
    }

    fun d(message: String) {
        write("D", message, null)
    }

    fun w(message: String, error: Throwable? = null) {
        write("W", message, error)
    }

    private fun write(level: String, message: String, error: Throwable?) {
        val line = "${time.format(Date())} $level $message"
        if (error != null) {
            Log.e(TAG, message, error)
        } else {
            Log.e(TAG, message)
        }
        runCatching {
            val target = file ?: return@runCatching
            val extra = if (error == null) "" else " ${error.javaClass.simpleName}: ${error.message}"
            target.appendText("$line$extra\n")
        }
    }
}
