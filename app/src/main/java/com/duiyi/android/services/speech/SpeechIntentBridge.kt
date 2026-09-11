package com.duiyi.android.services.speech

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import androidx.activity.result.ActivityResult
import com.duiyi.android.app.MainActivity
import com.duiyi.android.services.DuiyiLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SpeechIntentBridge @Inject constructor() {
    @Volatile
    var launching: Boolean = false
        private set

    @Volatile
    var sessionActive: Boolean = false

    private var owner: Any? = null
    private var launch: ((Intent) -> Unit)? = null
    private var waiter: CompletableDeferred<ActivityResult>? = null

    @Volatile
    private var broadcastText: String? = null

    @Volatile
    private var leftForSpeech: Boolean = false

    private val main = Handler(Looper.getMainLooper())
    private val finishAfterReturn = Runnable { finishIfStillWaiting() }

    fun attach(owner: Any, launch: (Intent) -> Unit) {
        this.owner = owner
        this.launch = launch
    }

    fun detach(owner: Any) {
        if (this.owner !== owner) return
        this.owner = null
        this.launch = null
        cancel()
    }

    suspend fun showAndWait(intent: Intent): Pair<Boolean, ActivityResult?> {
        val deferred = CompletableDeferred<ActivityResult>()
        waiter?.cancel()
        waiter = deferred
        launching = true
        leftForSpeech = false
        main.removeCallbacks(finishAfterReturn)
        val started = withContext(Dispatchers.Main.immediate) {
            val fn = launch ?: return@withContext false
            runCatching { fn(intent); true }.getOrDefault(false)
        }
        if (!started) {
            clearWaiter(deferred)
            return false to null
        }
        delay(900)
        if (!leftForSpeech && !deferred.isCompleted) {
            clearWaiter(deferred)
            return false to null
        }
        return try {
            true to deferred.await()
        } finally {
            clearWaiter(deferred)
        }
    }

    fun complete(result: ActivityResult) {
        waiter?.complete(result)
    }

    fun onHostStopped() {
        if (launching) leftForSpeech = true
    }

    fun onHostResumed() {
        if (!launching || !leftForSpeech) return
        main.removeCallbacks(finishAfterReturn)
        main.postDelayed(finishAfterReturn, 500)
    }

    fun onSpeechText(text: String, context: Context) {
        DuiyiLog.i("speech honor-intent result len=${text.length}")
        broadcastText = text
        val data = Intent().putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, arrayListOf(text))
        waiter?.complete(ActivityResult(Activity.RESULT_OK, data))
        bringAppToFront(context)
    }

    fun consumeBroadcastText(): String? {
        val text = broadcastText
        broadcastText = null
        return text
    }

    fun bringAppToFront(context: Context) {
        runCatching {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP,
                    ),
            )
        }
    }

    fun cancel() {
        main.removeCallbacks(finishAfterReturn)
        waiter?.cancel()
        waiter = null
        launching = false
        leftForSpeech = false
    }

    private fun clearWaiter(deferred: CompletableDeferred<ActivityResult>) {
        if (waiter === deferred) {
            waiter = null
            launching = false
            leftForSpeech = false
            main.removeCallbacks(finishAfterReturn)
        }
    }

    private fun finishIfStillWaiting() {
        val current = waiter ?: return
        if (current.isCompleted) return
        val text = broadcastText
        if (!text.isNullOrBlank()) {
            val data = Intent().putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, arrayListOf(text))
            current.complete(ActivityResult(Activity.RESULT_OK, data))
        } else {
            current.complete(ActivityResult(Activity.RESULT_CANCELED, null))
        }
    }
}
