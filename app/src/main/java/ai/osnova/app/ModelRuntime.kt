package ai.osnova.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
    val cyrillicOcrReady: Boolean,
    val gemmaReady: Boolean,
    val gemmaFile: File,
    val detail: String
) {
    val ready: Boolean get() = ocrReady && cyrillicOcrReady && gemmaReady
}

data class DownloadProgress(
    val title: String,
    val detail: String,
    val progress: Int,
    val done: Boolean = false
)

class ModelManager(private val context: Context) {
    val gemmaModel = RequiredModel(
        id = "gemma-4-e2b-it",
        version = "main",
        fileName = "gemma-4-E2B-it.litertlm",
        sizeBytes = 2_588_147_712L,
        sha256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
        url = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm?download=true"
    )

    val cyrillicOcrModels = listOf(
        RequiredModel(
            id = "tessdata-fast",
            version = "main",
            fileName = "rus.traineddata",
            sizeBytes = 3_861_738L,
            sha256 = "e16e5e036cce1d9ec2b00063cf8b54472625b9e14d893a169e2b0dedeb4df225",
            url = "https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/main/rus.traineddata"
        ),
        RequiredModel(
            id = "tessdata-fast",
            version = "main",
            fileName = "eng.traineddata",
            sizeBytes = 4_113_088L,
            sha256 = "7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2",
            url = "https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/main/eng.traineddata"
        )
    )

    suspend fun status(): ModelStatus = withContext(Dispatchers.IO) {
        val ocr = isOcrReady()
        val cyrillicOcr = isCyrillicOcrReady()
        val gemma = gemmaFile()
        val gemmaReady = isModelReady(gemma, gemmaModel)
        val detail = when {
            ocr && cyrillicOcr && gemmaReady -> "Модели готовы"
            !ocr && !cyrillicOcr && !gemmaReady -> "Нужны OCR, русский OCR и Gemma"
            !ocr -> "Нужен OCR-модуль"
            !cyrillicOcr -> "Нужен русский OCR"
            else -> "Нужна Gemma"
        }
        ModelStatus(
            ocrReady = ocr,
            cyrillicOcrReady = cyrillicOcr,
            gemmaReady = gemmaReady,
            gemmaFile = gemma,
            detail = detail
        )
    }

    fun downloadMissing(): Flow<DownloadProgress> = channelFlow {
        val first = status()
        if (!first.ocrReady) {
            send(DownloadProgress("OCR", "Запрашиваю модуль ML Kit", 4))
            installOcrModule { progress ->
                send(DownloadProgress("OCR", "Скачивание OCR-модуля", progress.coerceIn(5, 20)))
            }
            send(DownloadProgress("OCR", "OCR-модуль готов", 20))
        }

        val afterMlKit = status()
        if (!afterMlKit.cyrillicOcrReady) {
            ensureNetwork()
            downloadCyrillicOcr { progress, detail ->
                send(DownloadProgress("Русский OCR", detail, 20 + (progress * 0.14f).toInt()))
            }
        }

        val afterOcr = status()
        if (!afterOcr.gemmaReady) {
            ensureNetwork()
            downloadGemma { progress, detail ->
                send(DownloadProgress("Gemma", detail, 34 + (progress * 0.65f).toInt()))
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
        return if (external != null && isModelReady(external, gemmaModel)) {
            external
        } else {
            internalGemmaFile()
        }
    }

    fun tesseractDataRoot(): File = File(context.filesDir, "tesseract")

    private fun internalGemmaFile(): File {
        return File(context.filesDir, "models/${gemmaModel.id}/${gemmaModel.version}/${gemmaModel.fileName}")
    }

    private fun externalGemmaFile(): File? {
        val root = context.getExternalFilesDir("models") ?: return null
        return File(root, "${gemmaModel.id}/${gemmaModel.version}/${gemmaModel.fileName}")
    }

    private fun tessdataFile(model: RequiredModel): File {
        return File(tesseractDataRoot(), "tessdata/${model.fileName}")
    }

    private fun isModelReady(file: File, model: RequiredModel): Boolean {
        return file.exists() && file.length() == model.sizeBytes
    }

    private fun isCyrillicOcrReady(): Boolean {
        return cyrillicOcrModels.all { model -> isModelReady(tessdataFile(model), model) }
    }

    private suspend fun isOcrReady(): Boolean = withContext(Dispatchers.IO) {
        withTimeoutOrNull(1_500) {
            runCatching {
                val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                val client = ModuleInstall.getClient(context)
                val response = client.areModulesAvailable(recognizer).await()
                recognizer.close()
                response.areModulesAvailable()
            }.getOrDefault(true)
        } ?: true
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
        downloadFile(gemmaModel, target, onProgress)
    }

    private suspend fun downloadCyrillicOcr(onProgress: suspend (Int, String) -> Unit) = withContext(Dispatchers.IO) {
        cyrillicOcrModels.forEachIndexed { index, model ->
            val prefix = if (model.fileName.startsWith("rus")) "русский" else "английский"
            downloadFile(model, tessdataFile(model)) { progress, _ ->
                val combined = (((index + progress / 100f) / cyrillicOcrModels.size) * 100).toInt()
                onProgress(combined, "Скачиваю OCR: $prefix")
            }
        }
        onProgress(100, "Русский OCR готов")
    }

    private suspend fun downloadFile(
        model: RequiredModel,
        target: File,
        onProgress: suspend (Int, String) -> Unit
    ) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, target.name + ".part")
        if (temp.exists()) temp.delete()

        onProgress(0, "Подключаюсь")
        val connection = (URL(model.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            requestMethod = "GET"
        }

        connection.inputStream.use { input ->
            FileOutputStream(temp).use { output ->
                val expected = connection.contentLengthLong.takeIf { it > 0 } ?: model.sizeBytes
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
                        onProgress(progress, "${model.fileName} ${copied.asSize()} / ${expected.asSize()}")
                    }
                }
            }
        }

        if (model.sha256 != null) {
            onProgress(99, "Проверяю sha256")
            val actual = sha256(temp)
            check(actual.equals(model.sha256, ignoreCase = true)) {
                "sha256 не совпал"
            }
        }

        if (target.exists()) target.delete()
        check(temp.renameTo(target)) { "Не удалось сохранить ${model.fileName}" }
        onProgress(100, "${model.fileName} сохранён")
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

    private fun Long.asSize(): String {
        return if (this >= 100_000_000L) {
            String.format(Locale.US, "%.2f GB", this / 1_073_741_824.0)
        } else {
            String.format(Locale.US, "%.1f MB", this / 1_048_576.0)
        }
    }
}

class GemmaRuntime(private val context: Context, private val modelFile: File) : AutoCloseable {
    private var engine: Engine? = null
    private var conversation: Conversation? = null

    suspend fun initialize(): String = withContext(Dispatchers.IO) {
        if (!modelFile.exists()) error("Gemma не найдена: ${modelFile.absolutePath}")
        if (engine != null) return@withContext "Gemma уже готова"

        val attempts = listOf(
            "GPU" to Backend.GPU(),
            "CPU" to Backend.CPU(4)
        )
        val errors = mutableListOf<String>()
        var initialized: Engine? = null
        for ((name, backend) in attempts) {
            val engineAttempt = runCatching { createEngine(backend) }
                .onFailure { throwable ->
                    errors += "$name: ${throwable.message ?: throwable.javaClass.simpleName}"
                }
                .getOrNull()
            if (engineAttempt != null) {
                initialized = engineAttempt
                break
            }
        }
        val readyEngine = initialized ?: error("LiteRT-LM не запустил Gemma (${errors.joinToString("; ")})")

        engine = readyEngine
        conversation = readyEngine.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(
                    "Ты превращаешь распознанный учебный материал в короткий блок конспекта. " +
                        "Пиши по-русски, не выдумывай факты и явно отмечай нечитаемые места."
                ),
                samplerConfig = SamplerConfig(topK = 10, topP = 0.9, temperature = 0.35)
            )
        )
        "Gemma запущена (${readyEngine.engineConfig.backend.name})"
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
            val response = conv.sendMessage(prompt)
            val text = response.text()
            text.ifBlank { fallbackSummary(rawText) }
        }.getOrElse { fallbackSummary(rawText) }
    }

    override fun close() {
        runCatching { conversation?.close() }
        runCatching { engine?.close() }
        conversation = null
        engine = null
    }

    private fun createEngine(backend: Backend): Engine {
        val config = EngineConfig(
            modelPath = modelFile.absolutePath,
            backend = backend,
            maxNumTokens = 1024,
            cacheDir = File(context.cacheDir, "litertlm").also { it.mkdirs() }.absolutePath
        )
        return Engine(config).also { it.initialize() }
    }

    private fun fallbackSummary(rawText: String): String {
        return rawText.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
            .take(900)
    }

    private fun Message.text(): String {
        return contents.contents
            .filterIsInstance<Content.Text>()
            .joinToString("\n") { it.text }
            .ifBlank { toString() }
    }
}
