package ai.osnova.app

import android.os.SystemClock
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class TextRecognitionAnalyzer(
    private val onText: (String) -> Unit,
    private val onState: (String) -> Unit
) : ImageAnalysis.Analyzer {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var busy = false
    private var lastRun = 0L
    private var lastText = ""
    private var stableCount = 0

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        if (busy || now - lastRun < 850) {
            imageProxy.close()
            return
        }
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        busy = true
        lastRun = now
        onState("смотрю")
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        recognizer.process(image)
            .addOnSuccessListener { result ->
                val text = result.text.trim()
                if (text.length < 8) {
                    stableCount = 0
                    onState("текст не найден")
                    return@addOnSuccessListener
                }
                val comparable = text.normalizeForCompare()
                if (comparable == lastText) {
                    stableCount += 1
                } else {
                    stableCount = 1
                    lastText = comparable
                }
                onState("вставляю")
                if (stableCount >= 1) {
                    stableCount = 0
                    onText(text)
                }
            }
            .addOnFailureListener {
                onState("ошибка OCR")
            }
            .addOnCompleteListener {
                busy = false
                imageProxy.close()
            }
    }

    fun close() {
        recognizer.close()
    }

    private fun String.normalizeForCompare(): String {
        return lowercase()
            .replace(Regex("[^a-zа-я0-9]+"), " ")
            .trim()
            .take(300)
    }
}
