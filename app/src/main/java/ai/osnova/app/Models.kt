package ai.osnova.app

data class Note(
    val id: Long,
    val title: String,
    val body: String,
    val createdAt: Long,
    val updatedAt: Long
) {
    val summary: String
        get() {
            val clean = body.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
            return clean?.take(96) ?: "Пока пусто"
        }
}

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
