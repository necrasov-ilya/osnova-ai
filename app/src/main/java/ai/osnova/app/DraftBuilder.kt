package ai.osnova.app

data class DraftCandidate(
    val text: String,
    val quality: Int,
    val cyrillicRatio: Float,
    val source: String
)

object DraftBuilder {
    fun build(raw: String, source: String = "ocr"): DraftCandidate? {
        val lines = raw
            .replace('\u00A0', ' ')
            .lineSequence()
            .flatMap { it.split(Regex("\\s{3,}")).asSequence() }
            .map { it.cleanupLine() }
            .filter { it.isReadableLine() }
            .distinctBy { it.lowercase() }
            .take(10)
            .toList()

        if (lines.isEmpty()) return null

        val joined = lines.joinToString("\n")
        val quality = joined.qualityScore()
        val ratio = joined.cyrillicRatio()
        if (quality < 28) return null

        val text = if (lines.size == 1) {
            lines.first()
        } else {
            lines.joinToString("\n") { line -> "- $line" }
        }
        return DraftCandidate(text = text.take(1200), quality = quality, cyrillicRatio = ratio, source = source)
    }

    private fun String.cleanupLine(): String {
        return trim()
            .replace(Regex("[|]{2,}"), " ")
            .replace(Regex("[_~`^]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '-', '—', '.', ',', ':', ';')
    }

    private fun String.isReadableLine(): Boolean {
        if (length < 4) return false
        val letters = count { it.isLetter() }
        val digits = count { it.isDigit() }
        val useful = letters + digits
        if (useful < 3) return false

        val allowed = count { it.isLetterOrDigit() || it.isWhitespace() || it in ".,:;!?%+-=*/()[]{}<>№#\"'" }
        if (allowed.toFloat() / length < 0.82f) return false

        val tokens = split(" ").filter { it.isNotBlank() }
        val oneLetterTokens = tokens.count { token -> token.count { it.isLetterOrDigit() } == 1 }
        if (tokens.size >= 4 && oneLetterTokens.toFloat() / tokens.size > 0.45f) return false

        val longWords = tokens.count { token -> token.count { it.isLetter() } >= 4 }
        if (longWords == 0 && useful < 8) return false

        val cyrillicLetters = count { it in 'А'..'я' || it == 'Ё' || it == 'ё' }
        val latinTokens = tokens.filter { token -> token.any { it in 'A'..'Z' || it in 'a'..'z' } }
        if (cyrillicLetters == 0 && latinTokens.size >= 4 && longWords == 0) {
            val averageLatinLength = latinTokens
                .map { token -> token.count { it.isLetter() } }
                .average()
            if (averageLatinLength < 3.2) return false
        }

        val mixedNoise = tokens.count { token ->
            token.any { it in 'А'..'я' || it == 'Ё' || it == 'ё' } &&
                token.any { it in 'A'..'Z' || it in 'a'..'z' }
        }
        return mixedNoise <= 1
    }

    private fun String.qualityScore(): Int {
        val letters = count { it.isLetter() }
        val cyrillic = count { it in 'А'..'я' || it == 'Ё' || it == 'ё' }
        val digits = count { it.isDigit() }
        val words = split(Regex("\\s+")).count { token -> token.count { it.isLetter() } >= 3 }
        val noise = count { !(it.isLetterOrDigit() || it.isWhitespace() || it in ".,:;!?%+-=*/()[]{}<>№#\"'-") }
        return letters + digits + words * 6 + cyrillic * 2 - noise * 8
    }

    private fun String.cyrillicRatio(): Float {
        val letters = count { it.isLetter() }
        if (letters == 0) return 0f
        val cyrillic = count { it in 'А'..'я' || it == 'Ё' || it == 'ё' }
        return cyrillic.toFloat() / letters
    }
}
