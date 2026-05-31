package ai.osnova.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

data class RequiredModel(
    val id: String,
    val version: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String?,
    val url: String
)

data class ModelStatus(
    val ocrReady: Boolean,
    val gemmaReady: Boolean,
    val gemmaFile: File,
    val detail: String
) {
    val ready: Boolean get() = ocrReady && gemmaReady
}

data class DownloadProgress(
    val title: String,
    val detail: String,
    val progress: Int,
    val done: Boolean = false
)

class ModelManager(private val context: Context) {
    val gemmaModel = RequiredModel(
        id = "gemma-4-e2b-it-sm8750",
        version = "main",
        fileName = "gemma-4-E2B-it_qualcomm_sm8750.litertlm",
        sizeBytes = 3_016_294_400L,
        sha256 = "41dd675fbe735b6029012b5576a5716bac614fd8156de0128db4c9dff3cebd4e",
        url = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it_qualcomm_sm8750.litertlm?download=true"
    )

    suspend fun status(): ModelStatus = withContext(Dispatchers.IO) {
        val ocr = isOcrReady()
        val gemma = gemmaFile()
        val gemmaReady = gemma.exists() && gemma.length() > 1_000_000_000L
        val detail = when {
            ocr && gemmaReady -> "Модели готовы"
            !ocr && !gemmaReady -> "Нужны OCR и Gemma"
            !ocr -> "Нужен OCR-модуль"
            else -> "Нужна Gemma"
        }
        ModelStatus(ocrReady = ocr, gemmaReady = gemmaReady, gemmaFile = gemma, detail = detail)
    }

    fun downloadMissing(): Flow<DownloadProgress> = channelFlow {
        val first = status()
        if (!first.ocrReady) {
            send(DownloadProgress("OCR", "Запрашиваю модуль ML Kit", 4))
            installOcrModule { progress ->
                send(DownloadProgress("OCR", "Скачивание OCR-модуля", progress.coerceIn(5, 40)))
            }
            send(DownloadProgress("OCR", "OCR-модуль готов", 42))
        }

        val afterOcr = status()
        if (!afterOcr.gemmaReady) {
            ensureNetwork()
            downloadGemma { progress, detail ->
                send(DownloadProgress("Gemma", detail, 42 + (progress * 0.56f).toInt()))
            }
        }

        val finalStatus = status()
        if (!finalStatus.ready) {
            error("Не удалось подготовить модели: ${finalStatus.detail}")
        }
        send(DownloadProgress("Готово", "OCR и Gemma на устройстве", 100, done = true))
    }

    fun gemmaFile(): File {
        val external = externalGemmaFile()
        return if (external != null && external.exists() && external.length() > 1_000_000_000L) {
            external
        } else {
            internalGemmaFile()
        }
    }

    private fun internalGemmaFile(): File {
        return File(context.filesDir, "models/${gemmaModel.id}/${gemmaModel.version}/${gemmaModel.fileName}")
    }

    private fun externalGemmaFile(): File? {
        val root = context.getExternalFilesDir("models") ?: return null
        return File(root, "${gemmaModel.id}/${gemmaModel.version}/${gemmaModel.fileName}")
    }

    private suspend fun isOcrReady(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val client = ModuleInstall.getClient(context)
            val response = client.areModulesAvailable(recognizer).await()
            recognizer.close()
            response.areModulesAvailable()
        }.getOrDefault(false)
    }

    private suspend fun installOcrModule(onProgress: suspend (Int) -> Unit) {
        withContext(Dispatchers.Main) {
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val client = ModuleInstall.getClient(context)
            val request = ModuleInstallRequest.newBuilder()
                .addApi(recognizer)
                .build()
            onProgress(8)
            val response = client.installModules(request).await()
            onProgress(if (response.areModulesAlreadyInstalled()) 40 else 28)
            recognizer.close()
        }
    }

    private suspend fun downloadGemma(onProgress: suspend (Int, String) -> Unit) = withContext(Dispatchers.IO) {
        val target = internalGemmaFile()
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, target.name + ".part")
        if (temp.exists()) temp.delete()

        onProgress(0, "Подключаюсь к Hugging Face")
        val connection = (URL(gemmaModel.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            requestMethod = "GET"
        }

        connection.inputStream.use { input ->
            FileOutputStream(temp).use { output ->
                val expected = connection.contentLengthLong.takeIf { it > 0 } ?: gemmaModel.sizeBytes
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var copied = 0L
                var lastProgress = -1
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    copied += read
                    val progress = ((copied * 100) / expected).toInt().coerceIn(0, 99)
                    if (progress != lastProgress && progress % 2 == 0) {
                        lastProgress = progress
                        onProgress(progress, "Gemma ${copied.asGb()} / ${expected.asGb()}")
                    }
                }
            }
        }

        if (gemmaModel.sha256 != null) {
            onProgress(99, "Проверяю sha256")
            val actual = sha256(temp)
            check(actual.equals(gemmaModel.sha256, ignoreCase = true)) {
                "sha256 не совпал"
            }
        }

        if (target.exists()) target.delete()
        check(temp.renameTo(target)) { "Не удалось сохранить Gemma" }
        onProgress(100, "Gemma сохранена")
    }

    private fun ensureNetwork() {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork ?: error("Нет сети для скачивания Gemma")
        val caps = manager.getNetworkCapabilities(network) ?: error("Нет сети для скачивания Gemma")
        check(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            "Нет сети для скачивания Gemma"
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun Long.asGb(): String = String.format(Locale.US, "%.2f GB", this / 1_073_741_824.0)
}

class GemmaRuntime(private val context: Context, private val modelFile: File) : AutoCloseable {
    private var engine: Any? = null
    private var conversation: Any? = null

    suspend fun initialize(): String = withContext(Dispatchers.IO) {
        if (!modelFile.exists()) error("Gemma не найдена: ${modelFile.absolutePath}")
        if (engine != null) return@withContext "Gemma уже готова"

        val backendClass = Class.forName("com.google.ai.edge.litertlm.Backend")
        val backend = runCatching {
            backendClass.classes.firstOrNull { it.simpleName == "GPU" }
                ?.constructors
                ?.firstOrNull { it.parameterCount == 0 }
                ?.newInstance()
        }.getOrNull()

        val configClass = Class.forName("com.google.ai.edge.litertlm.EngineConfig")
        val config = configClass.constructors
            .sortedBy { it.parameterCount }
            .firstNotNullOfOrNull { constructor ->
                runCatching {
                    val params = constructor.parameterTypes.map { type ->
                        when {
                            type == String::class.java -> modelFile.absolutePath
                            backend != null && type.isAssignableFrom(backend.javaClass) -> backend
                            type == Boolean::class.javaPrimitiveType -> false
                            type == Int::class.javaPrimitiveType -> 0
                            type == Long::class.javaPrimitiveType -> 0L
                            type == Float::class.javaPrimitiveType -> 0f
                            type == Double::class.javaPrimitiveType -> 0.0
                            else -> null
                        }
                    }.toTypedArray()
                    constructor.newInstance(*params)
                }.getOrNull()
            } ?: error("Не удалось создать EngineConfig LiteRT-LM")

        val engineClass = Class.forName("com.google.ai.edge.litertlm.Engine")
        val createdEngine = engineClass.constructors.first().newInstance(config)
        engineClass.methods.first { it.name == "initialize" && it.parameterCount == 0 }.invoke(createdEngine)
        val createdConversation = engineClass.methods.first { it.name == "createConversation" && it.parameterCount == 0 }
            .invoke(createdEngine)
        engine = createdEngine
        conversation = createdConversation
        "Gemma запущена"
    }

    suspend fun summarize(rawText: String): String = withContext(Dispatchers.IO) {
        val conv = conversation ?: return@withContext fallbackSummary(rawText)
        val prompt = """
            Преврати распознанный с доски текст в короткий блок конспекта.
            Не добавляй факты, которых нет в тексте.

            Текст:
            $rawText
        """.trimIndent()

        runCatching {
            val method = conv.javaClass.methods.firstOrNull { it.name == "sendMessage" && it.parameterTypes.size == 1 }
                ?: error("sendMessage не найден")
            val response = method.invoke(conv, prompt)
            val text = response?.let { value ->
                value.javaClass.methods.firstOrNull { it.name == "getText" && it.parameterCount == 0 }
                    ?.invoke(value)
                    ?.toString()
                    ?: value.toString()
            } ?: fallbackSummary(rawText)
            text.ifBlank { fallbackSummary(rawText) }
        }.getOrElse { fallbackSummary(rawText) }
    }

    override fun close() {
        runCatching { conversation?.javaClass?.methods?.firstOrNull { it.name == "close" }?.invoke(conversation) }
        runCatching { engine?.javaClass?.methods?.firstOrNull { it.name == "close" }?.invoke(engine) }
        conversation = null
        engine = null
    }

    private fun fallbackSummary(rawText: String): String {
        return rawText.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
            .take(900)
    }
}
