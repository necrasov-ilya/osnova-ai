package ai.osnova.app

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import android.util.Log
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
                val manager = ModelManager(applicationContext)
                val runtime = GemmaRuntime(applicationContext, manager.gemmaFile())
                val result = runBlocking {
                    runtime.initialize()
                    runtime.summarize(rawText)
                }
                runtime.close()
                Log.i(TAG, "Gemma completed: ${result.length} chars")
                sendResult(completed, requestId, result, null)
            } catch (error: Throwable) {
                Log.e(TAG, "Gemma failed", error)
                sendResult(completed, requestId, rawText, error.message ?: error.javaClass.simpleName)
            } finally {
                stopSelf(startId)
            }
        }

        thread(name = "osnova-gemma-watchdog-$requestId") {
            worker.join(GEMMA_TIMEOUT_MS)
            if (completed.compareAndSet(false, true)) {
                Log.e(TAG, "Gemma timeout")
                val resultIntent = Intent(ACTION_RESULT).setPackage(packageName)
                    .putExtra(EXTRA_REQUEST_ID, requestId)
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

    private fun sendResult(completed: AtomicBoolean, requestId: Long, result: String, error: String?) {
        if (!completed.compareAndSet(false, true)) return
        val resultIntent = Intent(ACTION_RESULT).setPackage(packageName)
            .putExtra(EXTRA_REQUEST_ID, requestId)
            .putExtra(EXTRA_RESULT_TEXT, result)
        if (error != null) resultIntent.putExtra(EXTRA_ERROR, error)
        sendBroadcast(resultIntent)
    }

    companion object {
        const val ACTION_SUMMARIZE = "ai.osnova.app.action.SUMMARIZE_WITH_GEMMA"
        const val ACTION_RESULT = "ai.osnova.app.action.GEMMA_RESULT"
        const val EXTRA_REQUEST_ID = "request_id"
        const val EXTRA_RAW_TEXT = "raw_text"
        const val EXTRA_RESULT_TEXT = "result_text"
        const val EXTRA_ERROR = "error"
        private const val TAG = "OsnovaGemma"
        private const val GEMMA_TIMEOUT_MS = 25_000L
    }
}
