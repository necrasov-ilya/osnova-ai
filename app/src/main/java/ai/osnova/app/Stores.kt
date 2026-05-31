package ai.osnova.app

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

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
                add(
                    Note(
                        id = item.optLong("id"),
                        title = item.optString("title"),
                        body = item.optString("body"),
                        createdAt = item.optLong("createdAt"),
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
                    .put("body", note.body)
                    .put("createdAt", note.createdAt)
                    .put("updatedAt", note.updatedAt)
            )
        }
        prefs.edit().putString(KEY_NOTES, array.toString()).apply()
    }

    companion object {
        private const val KEY_NOTES = "items"
    }
}

class InsertEngine {
    fun shouldInsert(currentBody: String, candidate: String): Boolean {
        val clean = candidate.cleanForNote()
        if (clean.length < 8) return false
        val recent = currentBody.takeLast(1500).cleanForCompare()
        val normalized = clean.cleanForCompare()
        if (normalized.length < 8) return false
        if (recent.contains(normalized.take(normalized.length.coerceAtMost(80)))) return false
        return similarity(recent.takeLast(280), normalized.take(280)) < 0.72f
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
}

fun palette(mode: OsnovaThemeMode): OsnovaPalette {
    val accent = Color.rgb(124, 92, 255)
    return when (mode) {
        OsnovaThemeMode.Mist -> OsnovaPalette(
            background = Color.rgb(238, 241, 247),
            surface = Color.rgb(251, 250, 247),
            surfaceStrong = Color.WHITE,
            text = Color.rgb(22, 24, 31),
            muted = Color.rgb(103, 108, 121),
            border = Color.argb(38, 31, 35, 48),
            accent = accent,
            accentSoft = Color.rgb(229, 224, 255),
            cameraPanel = Color.rgb(18, 19, 26)
        )

        OsnovaThemeMode.Ink -> OsnovaPalette(
            background = Color.rgb(17, 18, 24),
            surface = Color.rgb(28, 29, 38),
            surfaceStrong = Color.rgb(38, 40, 52),
            text = Color.rgb(245, 244, 238),
            muted = Color.rgb(167, 169, 184),
            border = Color.argb(60, 255, 255, 255),
            accent = accent,
            accentSoft = Color.rgb(58, 48, 114),
            cameraPanel = Color.rgb(8, 9, 14)
        )
    }
}
