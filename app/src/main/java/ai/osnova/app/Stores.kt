package ai.osnova.app

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject

class ThemeStore(context: Context) {
    private val prefs = context.getSharedPreferences("theme", Context.MODE_PRIVATE)

    fun current(): OsnovaThemeMode {
        return runCatching {
            OsnovaThemeMode.valueOf(prefs.getString(KEY, OsnovaThemeMode.Mist.name) ?: OsnovaThemeMode.Mist.name)
        }.getOrDefault(OsnovaThemeMode.Mist)
    }

    fun toggle(): OsnovaThemeMode {
        val next = if (current() == OsnovaThemeMode.Mist) OsnovaThemeMode.Ink else OsnovaThemeMode.Mist
        prefs.edit().putString(KEY, next.name).apply()
        return next
    }

    companion object {
        private const val KEY = "mode"
    }
}

class NoteStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("notes", Context.MODE_PRIVATE)

    fun all(): List<Note> {
        val raw = prefs.getString(KEY_NOTES, "[]") ?: "[]"
        val array = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val body = item.optString("body")
                val createdAt = item.optLong("createdAt")
                add(
                    Note(
                    id = item.optLong("id"),
                    title = item.optString("title"),
                    body = body,
                    blocks = parseBlocks(item.optJSONArray("blocks"), body, createdAt),
                    createdAt = createdAt,
                    updatedAt = item.optLong("updatedAt")
                )
                )
            }
        }.sortedByDescending { it.updatedAt }
    }

    fun get(id: Long): Note? = all().firstOrNull { it.id == id }

    fun create(title: String): Note {
        val now = System.currentTimeMillis()
        val note = Note(
            id = now,
            title = title.ifBlank { "Новая заметка" },
            body = "",
            blocks = emptyList(),
            createdAt = now,
            updatedAt = now
        )
        save(note)
        return note
    }

    fun save(note: Note) {
        val next = all().filterNot { it.id == note.id } + note.copy(updatedAt = System.currentTimeMillis())
        persist(next)
    }

    private fun persist(notes: List<Note>) {
        val array = JSONArray()
        notes.sortedByDescending { it.updatedAt }.forEach { note ->
            array.put(
                JSONObject()
                    .put("id", note.id)
                    .put("title", note.title)
                    .put("body", note.markdown)
                    .put("blocks", blocksToJson(note.blocks))
                    .put("createdAt", note.createdAt)
                    .put("updatedAt", note.updatedAt)
            )
        }
        prefs.edit().putString(KEY_NOTES, array.toString()).apply()
    }

    private fun parseBlocks(array: JSONArray?, legacyBody: String, createdAt: Long): List<NoteBlock> {
        if (array == null || array.length() == 0) {
            return if (legacyBody.isBlank()) {
                emptyList()
            } else {
                listOf(
                    NoteBlock(
                        id = createdAt,
                        markdown = legacyBody,
                        rawSource = legacyBody,
                        status = NoteBlockStatus.Ready,
                        createdAt = createdAt,
                        updatedAt = createdAt
                    )
                )
            }
        }
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val status = runCatching {
                    NoteBlockStatus.valueOf(item.optString("status", NoteBlockStatus.Ready.name))
                }.getOrDefault(NoteBlockStatus.Ready)
                add(
                    NoteBlock(
                        id = item.optLong("id"),
                        markdown = item.optString("markdown"),
                        rawSource = item.optString("rawSource"),
                        status = status,
                        createdAt = item.optLong("createdAt"),
                        updatedAt = item.optLong("updatedAt")
                    )
                )
            }
        }
    }

    private fun blocksToJson(blocks: List<NoteBlock>): JSONArray {
        val array = JSONArray()
        blocks.forEach { block ->
            array.put(
                JSONObject()
                    .put("id", block.id)
                    .put("markdown", block.markdown)
                    .put("rawSource", block.rawSource)
                    .put("status", block.status.name)
                    .put("createdAt", block.createdAt)
                    .put("updatedAt", block.updatedAt)
            )
        }
        return array
    }

    companion object {
        private const val KEY_NOTES = "items"
    }
}

class InsertEngine {
    fun shouldInsert(currentBody: String, candidate: String): Boolean {
        val clean = candidate.cleanForNote()
        if (clean.length < 8) return false
        val recent = currentBody.takeLast(2200).cleanForCompare()
        val normalized = clean.cleanForCompare()
        if (normalized.length < 8) return false
        if (recent.contains(normalized.take(normalized.length.coerceAtMost(80)))) return false
        if (coverage(recent, normalized) >= 0.58f) return false
        return similarity(recent.takeLast(420), normalized.take(420)) < 0.72f
    }

    fun insert(currentBody: String, candidate: String): String {
        val clean = candidate.cleanForNote()
        if (currentBody.isBlank()) return clean
        return currentBody.trimEnd() + "\n\n" + clean
    }

    private fun String.cleanForNote(): String {
        return lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
            .take(1200)
    }

    private fun String.cleanForCompare(): String {
        return lowercase()
            .replace(Regex("[^a-zа-я0-9]+"), " ")
            .trim()
    }

    private fun similarity(a: String, b: String): Float {
        if (a.isBlank() || b.isBlank()) return 0f
        val left = a.split(" ").filter { it.length > 2 }.toSet()
        val right = b.split(" ").filter { it.length > 2 }.toSet()
        if (left.isEmpty() || right.isEmpty()) return 0f
        val intersection = left.count { it in right }
        return intersection.toFloat() / (left.size + right.size - intersection).coerceAtLeast(1)
    }

    private fun coverage(recent: String, candidate: String): Float {
        val recentUnits = recent.semanticUnits()
        val candidateUnits = candidate.semanticUnits()
        if (recentUnits.isEmpty() || candidateUnits.isEmpty()) return 0f
        val matched = candidateUnits.count { it in recentUnits }
        return matched.toFloat() / candidateUnits.size
    }

    private fun String.semanticUnits(): Set<String> {
        val tokens = split(" ")
            .map { it.trim() }
            .filter { it.length > 2 }
        if (tokens.size < 3) return tokens.toSet()
        return tokens.windowed(size = 3, step = 1)
            .map { it.joinToString(" ") }
            .toSet()
    }
}

fun palette(mode: OsnovaThemeMode): OsnovaPalette {
    val accent = Color.rgb(172, 41, 84)
    return when (mode) {
        OsnovaThemeMode.Mist -> OsnovaPalette(
            background = Color.rgb(245, 242, 235),
            surface = Color.rgb(255, 252, 246),
            surfaceStrong = Color.WHITE,
            text = Color.rgb(20, 20, 19),
            muted = Color.rgb(112, 96, 91),
            border = Color.argb(42, 20, 20, 19),
            accent = accent,
            accentSoft = Color.rgb(244, 218, 226),
            cameraPanel = Color.rgb(20, 20, 19)
        )

        OsnovaThemeMode.Ink -> OsnovaPalette(
            background = Color.rgb(20, 20, 19),
            surface = Color.rgb(32, 31, 30),
            surfaceStrong = Color.rgb(42, 40, 39),
            text = Color.rgb(245, 242, 235),
            muted = Color.rgb(184, 170, 163),
            border = Color.argb(62, 245, 242, 235),
            accent = accent,
            accentSoft = Color.rgb(77, 31, 47),
            cameraPanel = Color.rgb(8, 8, 7)
        )
    }
}
