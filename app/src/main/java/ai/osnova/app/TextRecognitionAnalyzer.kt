package ai.osnova.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.os.SystemClock
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

class TextRecognitionAnalyzer(
    private val onText: (String) -> Unit,
    private val onState: (String) -> Unit,
    private val cyrillicOcr: CyrillicOcrEngine
) : ImageAnalysis.Analyzer {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val callbackExecutor = Executors.newSingleThreadExecutor()
    private var busy = false
    private var lastRun = 0L
    private var lastText = ""
    private var lastEmittedText = ""
    private var stableCount = 0

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        if (busy || now - lastRun < 1200) {
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
        val bitmap = if (cyrillicOcr.isReady()) {
            runCatching { imageProxy.toBitmap(maxSide = 1280) }.getOrNull()
        } else {
            null
        }
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        recognizer.process(image)
            .addOnSuccessListener(callbackExecutor) { result ->
                val mlText = result.text.trim()
                val tessText = if (bitmap != null && shouldAskCyrillicOcr(mlText)) {
                    onState("OCR ru")
                    cyrillicOcr.recognize(bitmap)
                } else {
                    null
                }
                val candidate = chooseBest(mlText, tessText)
                val text = candidate?.text.orEmpty()
                if (candidate == null || text.length < 8) {
                    stableCount = 0
                    onState("держу кадр")
                    return@addOnSuccessListener
                }
                val comparable = text.normalizeForCompare()
                if (similarity(comparable, lastText) > 0.74f) {
                    stableCount += 1
                } else {
                    stableCount = 1
                }
                lastText = comparable
                onState(if (candidate.source == "tesseract") "OCR ru" else "OCR")
                if (stableCount >= 2 && similarity(comparable, lastEmittedText) < 0.82f) {
                    lastEmittedText = comparable
                    stableCount = 0
                    onText(text)
                }
            }
            .addOnFailureListener(callbackExecutor) {
                val fallback = bitmap?.let { cyrillicOcr.recognize(it) }
                    ?.let { DraftBuilder.build(it.text, source = it.source) }
                if (fallback != null) {
                    onText(fallback.text)
                    return@addOnFailureListener
                }
                onState("ошибка OCR")
            }
            .addOnCompleteListener(callbackExecutor) {
                busy = false
                imageProxy.close()
            }
    }

    fun close() {
        recognizer.close()
        callbackExecutor.shutdown()
        cyrillicOcr.close()
    }

    private fun shouldAskCyrillicOcr(mlText: String): Boolean {
        if (mlText.isBlank()) return true
        if (mlText.hasCyrillic()) return true
        val letters = mlText.count { it.isLetter() }.coerceAtLeast(1)
        val strange = mlText.count { it == '|' || it == '~' || it == '`' || it == '\\' }
        return strange.toFloat() / letters > 0.08f || mlText.length < 60
    }

    private fun chooseBest(mlText: String, tessText: OcrCandidate?): DraftCandidate? {
        val mlDraft = DraftBuilder.build(mlText, source = "mlkit")
        val tessDraft = tessText?.let { candidate ->
            DraftBuilder.build(candidate.text, source = candidate.source)
                ?.let { draft -> draft.copy(quality = draft.quality + candidate.confidence.coerceIn(0, 100) / 3) }
        }
        if (mlDraft == null) return tessDraft
        if (tessDraft == null) return mlDraft
        if (tessDraft.cyrillicRatio > 0.35f && tessDraft.quality >= mlDraft.quality * 0.72f) return tessDraft
        return if (tessDraft.quality > mlDraft.quality) tessDraft else mlDraft
    }

    private fun String.hasCyrillic(): Boolean = any { it in 'А'..'я' || it == 'Ё' || it == 'ё' }

    private fun String.normalizeForCompare(): String {
        return lowercase()
            .replace(Regex("[^a-zа-я0-9]+"), " ")
            .trim()
            .take(300)
    }

    private fun similarity(a: String, b: String): Float {
        if (a.isBlank() || b.isBlank()) return 0f
        val left = a.split(" ").filter { it.length > 2 }.toSet()
        val right = b.split(" ").filter { it.length > 2 }.toSet()
        if (left.isEmpty() || right.isEmpty()) return 0f
        val intersection = left.count { it in right }
        return intersection.toFloat() / max(1, min(left.size, right.size))
    }

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun ImageProxy.toBitmap(maxSide: Int): Bitmap? {
        val source = image ?: return null
        val bytes = source.toNv21()
        val yuvImage = YuvImage(bytes, ImageFormat.NV21, width, height, null)
        val output = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, width, height), 84, output)
        val bitmap = BitmapFactory.decodeByteArray(output.toByteArray(), 0, output.size()) ?: return null
        val rotated = bitmap.rotate(imageInfo.rotationDegrees)
        return rotated.scaleDown(maxSide)
    }

    private fun Image.toNv21(): ByteArray {
        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]
        val ySize = width * height
        val chromaWidth = width / 2
        val chromaHeight = height / 2
        val output = ByteArray(ySize + chromaWidth * chromaHeight * 2)

        var offset = 0
        val yBuffer = yPlane.buffer
        for (row in 0 until height) {
            val rowOffset = row * yPlane.rowStride
            for (col in 0 until width) {
                output[offset++] = yBuffer.get(rowOffset + col * yPlane.pixelStride)
            }
        }

        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        for (row in 0 until chromaHeight) {
            val uRowOffset = row * uPlane.rowStride
            val vRowOffset = row * vPlane.rowStride
            for (col in 0 until chromaWidth) {
                output[offset++] = vBuffer.get(vRowOffset + col * vPlane.pixelStride)
                output[offset++] = uBuffer.get(uRowOffset + col * uPlane.pixelStride)
            }
        }
        return output
    }

    private fun Bitmap.rotate(degrees: Int): Bitmap {
        if (degrees == 0) return this
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
    }

    private fun Bitmap.scaleDown(maxSide: Int): Bitmap {
        val largest = width.coerceAtLeast(height)
        if (largest <= maxSide) return this
        val scale = maxSide.toFloat() / largest
        val targetWidth = (width * scale).toInt().coerceAtLeast(1)
        val targetHeight = (height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(this, targetWidth, targetHeight, true)
    }
}
