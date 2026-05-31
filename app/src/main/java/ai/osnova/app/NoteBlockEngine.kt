package ai.osnova.app

object NoteBlockEngine {
    fun appendGenerating(note: Note, blockId: Long, rawSource: String, markdown: String): Note {
        val now = System.currentTimeMillis()
        val block = NoteBlock(
            id = blockId,
            markdown = markdown,
            rawSource = rawSource,
            status = NoteBlockStatus.Generating,
            createdAt = now,
            updatedAt = now
        )
        return note.copy(blocks = note.blocks + block, body = (note.blocks + block).toMarkdown())
    }

    fun update(note: Note, blockId: Long, markdown: String, status: NoteBlockStatus): Note {
        val now = System.currentTimeMillis()
        val next = note.blocks.map { block ->
            if (block.id == blockId) {
                block.copy(markdown = markdown, status = status, updatedAt = now)
            } else {
                block
            }
        }
        return note.copy(blocks = next, body = next.toMarkdown())
    }

    fun edit(note: Note, blockId: Long, markdown: String): Note {
        return update(note, blockId, markdown, NoteBlockStatus.Edited)
    }

    fun remove(note: Note, blockId: Long): Note {
        val next = note.blocks.filterNot { it.id == blockId }
        return note.copy(blocks = next, body = next.toMarkdown())
    }

    fun List<NoteBlock>.toMarkdown(): String {
        return joinToString("\n\n") { it.markdown.trim() }.trim()
    }
}
