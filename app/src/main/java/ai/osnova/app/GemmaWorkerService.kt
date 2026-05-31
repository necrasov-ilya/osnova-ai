package ai.osnova.app

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import android.util.Log
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class GemmaWorkerService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_SUMMARIZE) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val requestId = intent.getLongExtra(EXTRA_REQUEST_ID, -1L)
        val rawText = intent.getStringExtra(EXTRA_RAW_TEXT).orEmpty()

        val completed = AtomicBoolean(false)
        val worker = thread(name = "osnova-gemma-worker-$requestId") {
            try {
                sendEvent(requestId, EVENT_START, result = "")
                sendEvent(requestId, EVENT_RAW_DRAFT, result = rawText)
                val manager = ModelManager(applicationContext)
                val runtime = GemmaRuntime(applicationContext, manager.gemmaFile())
                val finalMarkdown = StringBuilder()
                runBlocking {
                    runtime.initialize()
                    runtime.streamMarkdown(rawText).collect { delta ->
                        finalMarkdown.append(delta)
                        sendEvent(requestId, EVENT_DELTA, result = delta)
                    }
                }
                runtime.close()
                val result = finalMarkdown.toString().ifBlank { rawText }
                Log.i(TAG, "Gemma completed: ${result.length} chars")
                sendResult(completed, requestId, EVENT_DONE, result, null)
            } catch (error: Throwable) {
                Log.e(TAG, "Gemma failed", error)
                sendResult(completed, requestId, EVENT_ERROR, rawText, error.message ?: error.javaClass.simpleName)
            } finally {
                stopSelf(startId)
            }
        }

        thread(name = "osnova-gemma-watchdog-$requestId") {
            worker.join(GEMMA_TIMEOUT_MS)
            if (completed.compareAndSet(false, true)) {
                Log.e(TAG, "Gemma timeout")
                val resultIntent = Intent(ACTION_STREAM).setPackage(packageName)
                    .putExtra(EXTRA_REQUEST_ID, requestId)
                    .putExtra(EXTRA_EVENT, EVENT_ERROR)
                    .putExtra(EXTRA_RESULT_TEXT, rawText)
                    .putExtra(EXTRA_ERROR, "Gemma timeout")
                sendBroadcast(resultIntent)
                stopSelf(startId)
                Thread.sleep(250)
                Process.killProcess(Process.myPid())
            }
        }

        return START_NOT_STICKY
    }

    private fun sendResult(completed: AtomicBoolean, requestId: Long, event: String, result: String, error: String?) {
        if (!completed.compareAndSet(false, true)) return
        sendEvent(requestId, event, result, error)
    }

    private fun sendEvent(requestId: Long, event: String, result: String, error: String? = null) {
        val resultIntent = Intent(ACTION_STREAM).setPackage(packageName)
            .putExtra(EXTRA_REQUEST_ID, requestId)
            .putExtra(EXTRA_EVENT, event)
            .putExtra(EXTRA_RESULT_TEXT, result)
        if (error != null) resultIntent.putExtra(EXTRA_ERROR, error)
        sendBroadcast(resultIntent)
    }

    companion object {
        const val ACTION_SUMMARIZE = "ai.osnova.app.action.SUMMARIZE_WITH_GEMMA"
        const val ACTION_STREAM = "ai.osnova.app.action.GEMMA_STREAM"
        const val EXTRA_REQUEST_ID = "request_id"
        const val EXTRA_EVENT = "event"
        const val EXTRA_RAW_TEXT = "raw_text"
        const val EXTRA_RESULT_TEXT = "result_text"
        const val EXTRA_ERROR = "error"
        const val EVENT_START = "start"
        const val EVENT_RAW_DRAFT = "raw_draft"
        const val EVENT_DELTA = "delta"
        const val EVENT_DONE = "done"
        const val EVENT_ERROR = "error"
        private const val TAG = "OsnovaGemma"
        private const val GEMMA_TIMEOUT_MS = 25_000L
    }
}
