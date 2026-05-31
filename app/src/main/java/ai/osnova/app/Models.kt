package ai.osnova.app

data class Note(
    val id: Long,
    val title: String,
    val body: String,
    val blocks: List<NoteBlock> = emptyList(),
    val createdAt: Long,
    val updatedAt: Long
) {
    val summary: String
        get() {
            val source = blocks.firstOrNull { it.markdown.isNotBlank() }?.markdown ?: body
            val clean = source.lineSequence().map { it.trim().trimStart('#', '-', '*', ' ') }.firstOrNull { it.isNotEmpty() }
            return clean?.take(96) ?: "Пока пусто"
        }

    val markdown: String
        get() = if (blocks.isEmpty()) body else blocks.joinToString("\n\n") { it.markdown }.trim()
}

data class NoteBlock(
    val id: Long,
    val markdown: String,
    val rawSource: String,
    val status: NoteBlockStatus,
    val createdAt: Long,
    val updatedAt: Long
)

enum class NoteBlockStatus {
    Generating,
    Ready,
    Edited,
    Failed
}

enum class ProcessingStage {
    Sampling,
    StableRegion,
    RawDraft,
    GeneratingMarkdown,
    Inserted,
    Failed
}

data class LiveProcessingTask(
    val id: Long,
    val stage: ProcessingStage,
    val detail: String,
    val rawDraft: String = "",
    val blockId: Long? = null
)

enum class OsnovaThemeMode {
    Mist,
    Ink
}

data class OsnovaPalette(
    val background: Int,
    val surface: Int,
    val surfaceStrong: Int,
    val text: Int,
    val muted: Int,
    val border: Int,
    val accent: Int,
    val accentSoft: Int,
    val cameraPanel: Int
)

enum class ModelGateState {
    Checking,
    Missing,
    Downloading,
    Ready,
    Failed
}

data class ProcessingItem(
    val id: Long,
    val title: String,
    val detail: String,
    val done: Boolean
)
