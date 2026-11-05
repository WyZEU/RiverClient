package dev.wyz.clientcore.lyrics

/** One sung word: when it starts and how long it is held, in milliseconds from the start of the track. */
class LyricWord(val startMs: Long, val durationMs: Long, val text: String)

/**
 * One line of a song and the moment it starts, in milliseconds from the start of the track.
 * [words] is the real timing of each word when the source has it, otherwise null.
 */
class LyricLine(val timeMs: Long, val text: String, val words: List<LyricWord>? = null)

/**
 * Reads the three synced-lyrics formats River gets.
 *
 * LRC (`[01:23.45] words`) from lrclib.net: a line can carry several stamps (a chorus sung
 * twice), the fraction is hundredths or thousandths, and an empty line marks a pause. Some files
 * also time each word (`<00:12.30>word`); those times are kept.
 *
 * NetEase's word format (`[lineStart,lineLength](wordStart,wordLength,0)word...`), which times
 * every word and how long it is held.
 */
object LrcParser {
    private val STAMP = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]")
    private val WORD_STAMP = Regex("<(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?>")
    private val OFFSET = Regex("^\\[offset:\\s*([+-]?\\d+)]", RegexOption.IGNORE_CASE)
    private val YRC_LINE = Regex("^\\[(\\d+),(\\d+)]")
    private val YRC_WORD = Regex("\\((\\d+),(\\d+),\\d+\\)")
    private val KRC_WORD = Regex("<(\\d+),(\\d+),\\d+>")
    /** Tokens that are only punctuation or brackets (NetEase splits ", " and "（" out as words). */
    /** NetEase credit lines ("作词 : X", "Producer: Y"), shown before the song starts. */
    private val CREDIT = Regex("^(作词|作曲|编曲|制作人|混音|母带|和声|吉他|贝斯|鼓|录音|producer|composer|lyricist|lyrics|written by|mixed by|mastered by)\\s*[:：]", RegexOption.IGNORE_CASE)
    private val PUNCTUATION_ONLY = Regex("^[\\p{Punct}\\s（）「」『』，。！？、…—–]*$")

    fun parse(raw: String): List<LyricLine> {
        val lines = ArrayList<LyricLine>()
        var offset = 0L
        for (rawLine in raw.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            OFFSET.find(line)?.let { offset = it.groupValues[1].toLongOrNull() ?: 0L; continue }
            val stamps = STAMP.findAll(line).toList()
            // Only stamps at the very start belong to the line; one inside the words is text.
            val leading = ArrayList<MatchResult>()
            var cursor = 0
            for (stamp in stamps) {
                if (stamp.range.first != cursor) break
                leading += stamp
                cursor = stamp.range.last + 1
            }
            if (leading.isEmpty()) continue
            val body = line.substring(cursor)
            val text = WORD_STAMP.replace(body, "").replace(Regex("\\s+"), " ").trim()
            val words = wordStamps(body, offset)
            for (stamp in leading) {
                // A line sung twice only has word times for where it is written; the repeat is guessed.
                lines += LyricLine(millis(stamp) - offset, text, if (leading.size == 1) words else null)
            }
        }
        // Stable, so two lines with the same stamp stay in the order the file had them.
        return lines.map { LyricLine(it.timeMs.coerceAtLeast(0L), it.text, it.words) }.sortedBy { it.timeMs }
    }

    /** `<00:12.30>Hello <00:12.80>world <00:13.40>` to timed words, or null when the line has none. */
    private fun wordStamps(body: String, offset: Long): List<LyricWord>? {
        val marks = WORD_STAMP.findAll(body).toList()
        if (marks.isEmpty()) return null
        val words = ArrayList<LyricWord>()
        for ((i, mark) in marks.withIndex()) {
            val end = if (i + 1 < marks.size) marks[i + 1].range.first else body.length
            val text = body.substring(mark.range.last + 1, end).trim()
            if (text.isEmpty() || PUNCTUATION_ONLY.matches(text)) continue
            val start = millis(mark) - offset
            val next = if (i + 1 < marks.size) millis(marks[i + 1]) - offset else -1L
            words += LyricWord(start, if (next > start) next - start else 0L, text)
        }
        return words.ifEmpty { null }
    }

    private fun millis(stamp: MatchResult): Long {
        val minutes = stamp.groupValues[1].toLong()
        val seconds = stamp.groupValues[2].toLong()
        val fraction = stamp.groupValues[3].padEnd(3, '0').take(3).toLong()
        return (minutes * 60 + seconds) * 1000 + fraction
    }

    /** NetEase's word-timed lyrics. Credit lines (JSON) and empty lines are skipped. */
    fun parseYrc(raw: String): List<LyricLine> {
        val lines = ArrayList<LyricLine>()
        for (rawLine in raw.lineSequence()) {
            val line = rawLine.trim()
            val head = YRC_LINE.find(line) ?: continue
            val marks = YRC_WORD.findAll(line).toList()
            val words = ArrayList<LyricWord>()
            for ((i, mark) in marks.withIndex()) {
                val end = if (i + 1 < marks.size) marks[i + 1].range.first else line.length
                val text = line.substring(mark.range.last + 1, end).trim()
                if (text.isEmpty() || PUNCTUATION_ONLY.matches(text)) continue
                words += LyricWord(mark.groupValues[1].toLong(), mark.groupValues[2].toLong(), text)
            }
            if (words.isEmpty()) continue
            val text = words.joinToString(" ") { it.text }
            if (CREDIT.containsMatchIn(text)) continue
            lines += LyricLine(head.groupValues[1].toLong(), text, words)
        }
        return lines.sortedBy { it.timeMs }
    }

    /**
     * KuGou's word-timed lyrics (`[lineStart,lineLength]<wordOffset,wordLength,0>word...`, word
     * times counted from the start of the line). The title line and the credits are skipped, and
     * pieces with no space between them ("hel", "lo ") are joined back into one word.
     */
    fun parseKrc(raw: String): List<LyricLine> {
        val offset = raw.lineSequence().firstNotNullOfOrNull { OFFSET.find(it.trim()) }?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val lines = ArrayList<LyricLine>()
        var first = true
        for (rawLine in raw.lineSequence()) {
            val line = rawLine.trim()
            val head = YRC_LINE.find(line) ?: continue
            val start = head.groupValues[1].toLong() - offset
            val marks = KRC_WORD.findAll(line).toList()
            val words = ArrayList<LyricWord>()
            var joinNext = false
            for ((i, mark) in marks.withIndex()) {
                val end = if (i + 1 < marks.size) marks[i + 1].range.first else line.length
                val piece = line.substring(mark.range.last + 1, end)
                val text = piece.trim()
                if (text.isEmpty()) {
                    joinNext = false
                    continue
                }
                val wordStart = start + mark.groupValues[1].toLong()
                val wordEnd = wordStart + mark.groupValues[2].toLong()
                val last = words.lastOrNull()
                val cjk = Character.isIdeographic(text.first().code)
                if (joinNext && last != null && !cjk) {
                    words[words.lastIndex] = LyricWord(last.startMs, wordEnd - last.startMs, last.text + text)
                } else {
                    words += LyricWord(wordStart, wordEnd - wordStart, text)
                }
                joinNext = !piece.last().isWhitespace() && !Character.isIdeographic(text.last().code)
            }
            val sung = words.filterNot { PUNCTUATION_ONLY.matches(it.text) }
            if (sung.isEmpty()) continue
            val text = sung.joinToString(" ") { it.text }
            // The dash can be a piece of its own, so it is looked for before punctuation goes.
            val isTitle = first && words.joinToString(" ") { it.text }.contains(" - ")
            first = false
            // Nobody sings a line in under 200 ms; those are credits packed in before the song.
            if (isTitle || head.groupValues[2].toLong() < 200L || text.contains('：') || CREDIT.containsMatchIn(text)) continue
            lines += LyricLine(start.coerceAtLeast(0L), text, sung)
        }
        return lines.sortedBy { it.timeMs }
    }

    /** Index of the line being sung at [positionMs], or -1 before the first line. */
    fun indexAt(lines: List<LyricLine>, positionMs: Long): Int {
        var low = 0
        var high = lines.size - 1
        var found = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (lines[mid].timeMs <= positionMs) {
                found = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return found
    }
}
