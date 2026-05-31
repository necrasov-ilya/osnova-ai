package ai.osnova.app

import android.Manifest
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private lateinit var themeStore: ThemeStore
    private lateinit var noteStore: NoteStore
    private lateinit var modelManager: ModelManager
    private val insertEngine = InsertEngine()

    private var mode = OsnovaThemeMode.Mist
    private var colors = palette(OsnovaThemeMode.Mist)
    private var currentNote: Note? = null
    private var bodyEditor: EditText? = null
    private var queueList: LinearLayout? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var analyzer: TextRecognitionAnalyzer? = null
    private var pendingCameraHost: FrameLayout? = null
    private val pendingGemma = mutableMapOf<Long, Pair<String, Job>>()
    private var gemmaDisabledUntil = 0L
    private var lastGemmaBusyShownAt = 0L
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val cameraPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            pendingCameraHost?.let { openCameraPanel(it) }
        } else {
            Toast.makeText(this, "Камера нужна для live-конспекта", Toast.LENGTH_SHORT).show()
        }
    }
    private val gemmaReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != GemmaWorkerService.ACTION_RESULT) return
            val requestId = intent.getLongExtra(GemmaWorkerService.EXTRA_REQUEST_ID, -1L)
            val pending = pendingGemma.remove(requestId) ?: return
            pending.second.cancel()
            val error = intent.getStringExtra(GemmaWorkerService.EXTRA_ERROR)
            val result = intent.getStringExtra(GemmaWorkerService.EXTRA_RESULT_TEXT).orEmpty()
            if (error != null) {
                gemmaDisabledUntil = SystemClock.elapsedRealtime() + GEMMA_COOLDOWN_MS
                addQueue("Gemma", gemmaQueueError(error), false)
            }
            insertRecognizedBlock(result.ifBlank { pending.first })
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        themeStore = ThemeStore(this)
        noteStore = NoteStore(this)
        modelManager = ModelManager(this)
        mode = themeStore.current()
        colors = palette(mode)
        lifecycleScope.launch {
            if (modelManager.status().ready) showHome() else showModelGate()
        }
        ContextCompat.registerReceiver(
            this,
            gemmaReceiver,
            IntentFilter(GemmaWorkerService.ACTION_RESULT),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCamera()
        runCatching { unregisterReceiver(gemmaReceiver) }
        cameraExecutor.shutdown()
    }

    private fun showModelGate() {
        val root = verticalRoot()
        root.gravity = Gravity.CENTER_HORIZONTAL
        rootPadding(root, left = 22, top = 48, right = 22, bottom = 28)

        val title = text("OSNOVA", 46, colors.text, Typeface.create("serif", Typeface.BOLD))
        val status = text("модели нужны на устройстве", 22, colors.text, Typeface.create("sans-serif-condensed", Typeface.BOLD))
        val detail = text("OCR + Gemma", 16, colors.muted, Typeface.DEFAULT)
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            visibility = View.GONE
        }
        val button = pillButton("Скачать модели")

        root.addView(space(36))
        root.addView(title)
        root.addView(status, matchWrap())
        root.addView(detail, matchWrap())
        root.addView(space(26))
        root.addView(modelCard("OCR", "ML Kit + Tesseract rus/eng", "быстрый текст и русский fallback"))
        root.addView(modelCard("Gemma", "Gemma 4 E2B", "структура конспекта на устройстве"))
        root.addView(space(22))
        root.addView(progress, matchHeight(dp(8)))
        root.addView(space(16))
        root.addView(button, matchHeight(dp(58)))

        button.setOnClickListener {
            button.isEnabled = false
            progress.visibility = View.VISIBLE
            lifecycleScope.launch {
                modelManager.downloadMissing()
                    .collect { item ->
                        detail.text = item.detail
                        progress.progress = item.progress
                        if (item.done) {
                            showHome()
                        }
                    }
            }.invokeOnCompletion { error ->
                if (error != null) {
                    button.isEnabled = true
                    detail.text = error.message ?: "не удалось скачать"
                    Toast.makeText(this, detail.text, Toast.LENGTH_LONG).show()
                }
            }
        }

        setContentView(root)
    }

    private fun showHome() {
        stopCamera()
        val root = verticalRoot()
        rootPadding(root, left = 18, top = 34, right = 18, bottom = 30)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = text("OSNOVA", 38, colors.text, Typeface.create("serif", Typeface.BOLD))
        val theme = smallPill(if (mode == OsnovaThemeMode.Mist) "туман" else "ночь")
        theme.setOnClickListener {
            mode = themeStore.toggle()
            colors = palette(mode)
            showHome()
        }
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(theme, LinearLayout.LayoutParams(dp(108), dp(46)))
        root.addView(header)
        root.addView(space(22))

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        noteStore.all().ifEmpty {
            listOf(noteStore.create("Пара по теме"))
        }.forEach { note ->
            list.addView(noteCard(note), matchWrap().apply { bottomMargin = dp(12) })
        }

        val scroll = ScrollView(this).apply { addView(list) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = round(colors.surfaceStrong, dp(30), colors.border)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val add = pillButton("+")
        add.textSize = 26f
        add.setOnClickListener { showCreateDialog() }
        bottom.addView(add, LinearLayout.LayoutParams(dp(72), dp(58)))
        root.addView(bottom, matchHeight(dp(78)))

        setContentView(root)
    }

    private fun showCreateDialog() {
        val input = EditText(this).apply {
            hint = "Название"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            textSize = 20f
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }
        AlertDialog.Builder(this)
            .setTitle("Новая заметка")
            .setView(input)
            .setPositiveButton("Создать") { _, _ ->
                val note = noteStore.create(input.text.toString())
                showEditor(note.id)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showEditor(noteId: Long) {
        currentNote = noteStore.get(noteId) ?: return showHome()
        val note = currentNote ?: return
        val root = verticalRoot()
        rootPadding(root, left = 16, top = 30, right = 16, bottom = 30)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val back = smallPill("←")
        back.textSize = 24f
        back.setOnClickListener {
            saveCurrentNote()
            showHome()
        }
        val title = text(note.title, 28, colors.text, Typeface.create("sans-serif-condensed", Typeface.BOLD))
        header.addView(back, LinearLayout.LayoutParams(dp(54), dp(46)))
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(header)

        val cameraHost = FrameLayout(this).apply {
            visibility = View.GONE
            alpha = 0f
        }
        root.addView(cameraHost, matchHeight(dp(230)).apply { topMargin = dp(14) })

        queueList = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
        }
        root.addView(queueList, matchHeight(dp(54)).apply { topMargin = dp(10) })

        bodyEditor = EditText(this).apply {
            setText(note.body)
            hint = "Конспект появится здесь"
            gravity = Gravity.TOP or Gravity.START
            textSize = 20f
            typeface = Typeface.create("serif", Typeface.NORMAL)
            setTextColor(colors.text)
            setHintTextColor(colors.muted)
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = round(colors.surface, dp(24), colors.border)
            minLines = 10
        }
        root.addView(bodyEditor, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(12) })

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = round(colors.surfaceStrong, dp(34), colors.border)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val camera = pillButton("камера")
        val save = smallPill("готово")
        camera.setOnClickListener {
            if (cameraHost.visibility == View.VISIBLE) {
                closeCameraPanel(cameraHost)
            } else {
                openCameraPanel(cameraHost)
            }
        }
        save.setOnClickListener {
            saveCurrentNote()
            Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show()
        }
        bottom.addView(camera, LinearLayout.LayoutParams(0, dp(58), 1f))
        bottom.addView(save, LinearLayout.LayoutParams(dp(112), dp(58)).apply { leftMargin = dp(8) })
        root.addView(bottom, matchHeight(dp(78)).apply { topMargin = dp(10) })

        setContentView(root)
    }

    private fun openCameraPanel(host: FrameLayout) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            pendingCameraHost = host
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }
        pendingCameraHost = null
        host.removeAllViews()
        val previewView = PreviewView(this).apply {
            background = round(colors.cameraPanel, dp(24), colors.border)
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        val badge = text("ищу текст", 15, Color.WHITE, Typeface.DEFAULT_BOLD).apply {
            background = round(Color.argb(190, 20, 20, 28), dp(18), Color.TRANSPARENT)
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        host.addView(previewView, FrameLayout.LayoutParams(-1, -1))
        host.addView(badge, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(14)
        })
        host.visibility = View.VISIBLE
        host.animate().alpha(1f).setDuration(260).setInterpolator(DecelerateInterpolator()).start()
        queueList?.visibility = View.VISIBLE
        startCamera(
            previewView = previewView,
            onText = { text -> runOnUiThread { onRecognizedText(text) } },
            onState = { state ->
                runOnUiThread {
                    if (host.isAttachedToWindow) badge.text = state
                }
            }
        )
    }

    private fun closeCameraPanel(host: FrameLayout) {
        host.animate().alpha(0f).setDuration(220).withEndAction {
            host.visibility = View.GONE
            host.removeAllViews()
            queueList?.visibility = View.GONE
            stopCamera()
        }.start()
    }

    private fun startCamera(previewView: PreviewView, onText: (String) -> Unit, onState: (String) -> Unit) {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            runCatching {
                val provider = providerFuture.get()
                cameraProvider = provider
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                analyzer?.close()
                analyzer = TextRecognitionAnalyzer(
                    onText = onText,
                    onState = onState,
                    cyrillicOcr = CyrillicOcrEngine(this)
                )
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(cameraExecutor, analyzer!!) }

                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }.onFailure { error ->
                onState("камера не открылась")
                addQueue("Камера", error.javaClass.simpleName.take(28), false)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        runCatching { cameraProvider?.unbindAll() }
        runCatching { analyzer?.close() }
        analyzer = null
    }

    private fun onRecognizedText(raw: String) {
        val draft = DraftBuilder.build(raw, source = "camera")
        if (draft == null) {
            addQueue("Gate", "шум", false)
            return
        }

        if (pendingGemma.isNotEmpty()) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastGemmaBusyShownAt > 5_000) {
                lastGemmaBusyShownAt = now
                addQueue("Gemma", "ждёт", false)
            }
            return
        }

        addQueue("OCR", "текст найден", false)
        val fallbackBlock = draft.text
        if (SystemClock.elapsedRealtime() < gemmaDisabledUntil) {
            addQueue("Gemma", "пауза", false)
            insertRecognizedBlock(fallbackBlock)
            return
        }

        val requestId = System.currentTimeMillis()
        val timeoutJob = lifecycleScope.launch {
            delay(28_000)
            pendingGemma.remove(requestId)
            gemmaDisabledUntil = SystemClock.elapsedRealtime() + GEMMA_COOLDOWN_MS
            addQueue("Gemma", "таймаут", false)
            insertRecognizedBlock(fallbackBlock)
        }
        pendingGemma[requestId] = fallbackBlock to timeoutJob
        val intent = Intent(this, GemmaWorkerService::class.java)
            .setAction(GemmaWorkerService.ACTION_SUMMARIZE)
            .putExtra(GemmaWorkerService.EXTRA_REQUEST_ID, requestId)
            .putExtra(GemmaWorkerService.EXTRA_RAW_TEXT, fallbackBlock)
        runCatching {
            startService(intent)
            addQueue("Gemma", "обрабатывает", false)
        }.onFailure {
            timeoutJob.cancel()
            pendingGemma.remove(requestId)
            gemmaDisabledUntil = SystemClock.elapsedRealtime() + GEMMA_COOLDOWN_MS
            addQueue("Gemma", "не запущена", false)
            insertRecognizedBlock(fallbackBlock)
        }
    }

    private fun insertRecognizedBlock(block: String) {
        val editor = bodyEditor ?: return
        val current = editor.text.toString()
        if (insertEngine.shouldInsert(current, block)) {
            editor.setText(insertEngine.insert(current, block))
            editor.setSelection(editor.text.length)
            saveCurrentNote()
            addQueue("Блок", "добавлен", true)
        }
    }

    private fun saveCurrentNote() {
        val note = currentNote ?: return
        val body = bodyEditor?.text?.toString() ?: note.body
        val saved = note.copy(body = body)
        currentNote = saved
        noteStore.save(saved)
    }

    private fun addQueue(title: String, detail: String, done: Boolean) {
        val list = queueList ?: return
        list.visibility = View.VISIBLE
        val item = TextView(this).apply {
            text = "$title · $detail"
            textSize = 13f
            setTextColor(if (done) Color.WHITE else colors.text)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(12), 0)
            background = round(if (done) colors.accent else colors.accentSoft, dp(22), Color.TRANSPARENT)
        }
        list.addView(item, LinearLayout.LayoutParams(-2, dp(42)).apply { rightMargin = dp(8) })
        if (list.childCount > 3) list.removeViewAt(0)
    }

    private fun gemmaQueueError(error: String): String {
        val normalized = error.lowercase()
        return when {
            "npu" in normalized -> "NPU не поднялась"
            "gpu" in normalized -> "GPU не поднялась"
            "timeout" in normalized -> "таймаут"
            "sha256" in normalized -> "битая модель"
            "not found" in normalized || "не найдена" in normalized -> "модель не найдена"
            "failed" in normalized -> "runtime не стартовал"
            else -> error.take(34)
        }
    }

    private fun noteCard(note: Note): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = round(colors.surface, dp(24), colors.border)
            addView(text(note.title, 24, colors.text, Typeface.create("sans-serif-condensed", Typeface.BOLD)))
            addView(text(note.summary, 16, colors.muted, Typeface.DEFAULT))
            setOnClickListener { showEditor(note.id) }
        }
    }

    private fun modelCard(name: String, model: String, detail: String): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = round(colors.surface, dp(24), colors.border)
            addView(text(name, 24, colors.text, Typeface.create("sans-serif-condensed", Typeface.BOLD)))
            addView(text(model, 17, colors.text, Typeface.DEFAULT_BOLD))
            addView(text(detail, 15, colors.muted, Typeface.DEFAULT))
        }.also {
            it.layoutParams = matchWrap().apply { bottomMargin = dp(12) }
        }
    }

    private fun verticalRoot(): LinearLayout {
        applyWindowChrome()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colors.background)
        }
    }

    private fun pillButton(label: String): Button {
        return Button(this).apply {
            text = label
            textSize = 18f
            setAllCaps(false)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = round(colors.accent, dp(30), Color.TRANSPARENT)
        }
    }

    private fun smallPill(label: String): Button {
        return Button(this).apply {
            text = label
            textSize = 16f
            setAllCaps(false)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colors.text)
            background = round(colors.surface, dp(24), colors.border)
        }
    }

    private fun text(value: String, size: Int, color: Int, face: Typeface): TextView {
        return TextView(this).apply {
            text = value
            textSize = size.toFloat()
            setTextColor(color)
            typeface = face
            includeFontPadding = true
        }
    }

    private fun round(color: Int, radius: Int, stroke: Int) = android.graphics.drawable.GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
        if (stroke != Color.TRANSPARENT) setStroke(1, stroke)
    }

    private fun space(height: Int) = Space(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(height))
    }

    private fun matchWrap() = LinearLayout.LayoutParams(-1, -2)

    private fun matchHeight(height: Int) = LinearLayout.LayoutParams(-1, height)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun rootPadding(root: View, left: Int, top: Int, right: Int, bottom: Int) {
        val baseLeft = dp(left)
        val baseTop = dp(top)
        val baseRight = dp(right)
        val baseBottom = dp(bottom)
        root.setPadding(baseLeft, baseTop, baseRight, baseBottom)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                baseLeft + bars.left,
                baseTop + bars.top,
                baseRight + bars.right,
                baseBottom + bars.bottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun applyWindowChrome() {
        window.statusBarColor = colors.background
        window.navigationBarColor = colors.background
        window.decorView.systemUiVisibility = if (mode == OsnovaThemeMode.Mist) {
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        } else {
            0
        }
    }

    companion object {
        private const val GEMMA_COOLDOWN_MS = 60_000L
    }

}
