package ai.osnova.app

import android.content.Context
import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File

data class OcrCandidate(
    val text: String,
    val confidence: Int,
    val source: String
)

class CyrillicOcrEngine(context: Context) : AutoCloseable {
    private val dataRoot = ModelManager(context).tesseractDataRoot()
    private val lock = Any()
    private var tessBaseApi: TessBaseAPI? = null

    fun isReady(): Boolean {
        return File(dataRoot, "tessdata/rus.traineddata").exists() &&
            File(dataRoot, "tessdata/eng.traineddata").exists()
    }

    fun recognize(bitmap: Bitmap): OcrCandidate? {
        if (!isReady()) return null
        return synchronized(lock) {
            runCatching {
                val api = tessBaseApi ?: createApi().also { tessBaseApi = it }
                api.setImage(bitmap)
                val text = api.utF8Text.orEmpty().trim()
                val confidence = api.meanConfidence()
                api.clear()
                OcrCandidate(text = text, confidence = confidence, source = "tesseract")
            }.getOrNull()
        }
    }

    override fun close() {
        synchronized(lock) {
            runCatching { tessBaseApi?.recycle() }
            tessBaseApi = null
        }
    }

    private fun createApi(): TessBaseAPI {
        return TessBaseAPI().apply {
            init(dataRoot.absolutePath, "rus+eng")
            pageSegMode = TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT
            setVariable(TessBaseAPI.VAR_CHAR_WHITELIST, ALLOWED_CHARS)
        }
    }

    companion object {
        private const val ALLOWED_CHARS =
            "0123456789" +
                "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz" +
                "АБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯ" +
                "абвгдеёжзийклмнопрстуфхцчшщъыьэюя" +
                ".,:;!?%+-=*/()[]{}<>№#\"' "
    }
}
