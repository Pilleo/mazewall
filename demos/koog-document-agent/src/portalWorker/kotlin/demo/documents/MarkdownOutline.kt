package demo.documents

class MarkdownOutline : DocumentOutlineService {
    private data class Fence(
        val marker: Char,
        val length: Int,
    )

    override fun outline(markdown: String): String {
        require(markdown.length <= 65_536 && Charsets.UTF_8.newEncoder().canEncode(markdown)) { "Markdown must be bounded valid Unicode" }
        require(markdown.toByteArray(Charsets.UTF_8).size <= 65_536) { "Markdown exceeds 64 KiB" }
        val result = StringBuilder()
        var count = 0
        var bytes = 0
        var fence: Fence? = null
        for (line in markdown.lineSequence()) {
            val indent = line.takeWhile { it == ' ' }.length
            if (indent > 3) continue
            val content = line.drop(indent)
            val marker = content.firstOrNull()
            val run = content.takeWhile { it == marker }.length
            val active = fence
            if (active != null) {
                if (marker == active.marker && run >= active.length && content.substring(run).trim(' ', '\t').isEmpty()) fence = null
                continue
            }
            if ((marker == '`' || marker == '~') && run >= 3 && (marker != '`' || '`' !in content.substring(run))) {
                fence = Fence(marker, run)
                continue
            }
            val hashes = content.takeWhile { it == '#' }.length
            if (hashes !in 1..6) continue
            val start = indent + hashes
            if (start < line.length && line[start] != ' ' && line[start] != '\t') continue
            var title = line.substring(start).trim(' ', '\t')
            var closing = title.length
            while (closing > 0 && title[closing - 1] == '#') closing--
            if (closing < title.length && (closing == 0 || title[closing - 1] == ' ' || title[closing - 1] == '\t')) {
                title = title.substring(0, closing).trimEnd(' ', '\t')
            }
            if (title.isEmpty()) title = "(untitled)"
            require(++count <= 128) { "Markdown exceeds 128 headings" }
            bytes += title.toByteArray(Charsets.UTF_8).size + if (count > 1) 1 else 0
            require(bytes <= 16_384) { "Outline exceeds 16 KiB" }
            if (count > 1) result.append('\n')
            result.append(title)
        }
        return result.toString()
    }
}
