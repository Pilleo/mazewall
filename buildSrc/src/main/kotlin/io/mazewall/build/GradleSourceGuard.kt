package io.mazewall.build

/** Bounded Kotlin/Groovy lexical guard; it does not perform compiler or call-graph analysis. */
internal object GradleSourceGuard {
    private data class Token(val text: String, val line: Int)
    private val identifier = Regex("[A-Za-z_$][\\w$]*")

    fun findings(source: String, groovy: Boolean): List<Int> {
        val tokens = Lexer(source, groovy).scan().filterNot { it.text == "\n" }
        val text = tokens.map { it.text }
        val pairs = pairs(text)
        val deferred = deferred(tokens, pairs)
        val aliases = mutableSetOf("configurations")
        text.forEachIndexed { i, value ->
            if (value in setOf("val", "var", "def") && i + 2 < text.size) {
                var end = i + 2
                while (end < text.size && text[end] !in setOf(";", "}") && tokens[end].line == tokens[i].line) end++
                val expression = text.subList(i + 2, end)
                if ("=" in expression && expression.any { it in aliases }) aliases.add(text[i + 1])
            }
        }
        return text.indices.filter { i ->
            if (i == 0 || text[i - 1] != ".") return@filter false
            var resolution = text[i] in setOf("asPath", "singleFile")
            if (text[i] in setOf("files", "getFiles")) {
                var start = i - 2
                while (start >= 0) {
                    if (text[start] in setOf(")", "]")) start = pairs.getValue(start) - 1
                    else if (start >= 2 && text[start - 1] == ".") start -= 2
                    else break
                }
                resolution = start >= 0 && text[start] in aliases
            }
            resolution && deferred.none { (start, end) -> start < i && i < end }
        }.map { tokens[it].line }.distinct().sorted()
    }

    private fun pairs(text: List<String>): Map<Int, Int> {
        val stack = ArrayDeque<Int>()
        val pairs = mutableMapOf<Int, Int>()
        text.forEachIndexed { i, token ->
            if (token in setOf("(", "[", "{")) stack.addLast(i)
            else if (token in setOf(")", "]", "}")) {
                val opening = stack.removeLastOrNull()
                require(opening != null && text[opening] == mapOf(")" to "(", "]" to "[", "}" to "{")[token]) { "unbalanced delimiters" }
                pairs[opening] = i
                pairs[i] = opening
            }
        }
        require(stack.isEmpty()) { "unbalanced delimiters" }
        return pairs
    }

    private fun afterExpression(text: List<String>, pairs: Map<Int, Int>, call: Int, body: Int): Int {
        var start = call
        while (start >= 2 && text[start - 1] == "." && identifier.matches(text[start - 2])) start -= 2
        var end = pairs.getValue(body)
        if (body - 1 == call + 1 && text[body - 1] == "(" && pairs[body - 1] == end + 1) end++
        while (start > 0 && text[start - 1] == "(" && pairs[start - 1] == end + 1) {
            val opening = start - 1
            val before = text.getOrElse(opening - 1) { "" }
            if (before in setOf(")", "]") || identifier.matches(before) && before !in setOf("return", "throw")) break
            start = opening
            end++
        }
        return end + 1
    }

    private fun deferred(tokens: List<Token>, pairs: Map<Int, Int>): List<Pair<Int, Int>> {
        val text = tokens.map { it.text }
        val ranges = mutableListOf<Pair<Int, Int>>()
        text.forEachIndexed { i, value ->
            if (value == "{") {
                var previous = i - 1
                if (previous >= 0 && text[previous] == ")") previous = pairs.getValue(previous) - 1
                else if (previous >= 0 && text[previous] == "(") previous--
                if (previous >= 0 && text[previous] in setOf("doFirst", "doLast", "provider")) {
                    val name = text[previous]
                    val provider = previous >= 2 && text.subList(previous - 2, previous) == listOf("providers", ".")
                    val declaration = previous > 0 && text[previous - 1] in setOf("fun", "def", "void")
                    val after = afterExpression(text, pairs, previous, i)
                    val suffix = text.drop(after).take(2)
                    val invoked = text.getOrNull(after) == "(" || suffix in listOf(listOf(".", "call"), listOf(".", "invoke"))
                    val consumed = name == "provider" && suffix in listOf(listOf(".", "get"), listOf(".", "getOrElse"), listOf(".", "getOrNull"), listOf(".", "orNull"))
                    if (!declaration && !invoked && !consumed && (name != "provider" || provider)) ranges.add(i to pairs.getValue(i))
                }
            }
            if (value != "(" || i == 0 || text[i - 1] in setOf("if", "while", "for")) return@forEachIndexed
            val name = i - 1
            var start = name - 1
            while (start >= 0 && text[start] !in setOf("{", "}", ";")) start--
            val header = text.subList(start + 1, name)
            val kotlinMethod = "fun" in header && "=" !in header
            val groovyMethod = header.lastOrNull() in setOf(">", "void", "def", "Iterable", "List", "Collection", "String") && "=" !in header && "return" !in header
            val annotated = header.indices.any { header[it] == "@" && "TaskAction" in header.drop(it + 1) }
            if ((!kotlinMethod && !groovyMethod) || (!annotated && text[name] != "asArguments")) return@forEachIndexed
            var end = pairs.getValue(i) + 1
            while (end < text.size && text[end] !in setOf("{", "=", ";", "}")) end++
            if (end == text.size || text[end] in setOf(";", "}")) return@forEachIndexed
            if (text[end] == "{") ranges.add(end to pairs.getValue(end))
            else if (kotlinMethod) {
                val body = end + 1
                end = body
                while (end < text.size) {
                    if (text[end] in setOf("}", ";")) break
                    if (end > body && tokens[end].line > tokens[end - 1].line && text[end - 1] !in setOf("=", "+", ".", ",") && text[end] != ".") break
                    if (text[end] in setOf("(", "[", "{")) end = pairs.getValue(end)
                    end++
                }
                ranges.add(body - 1 to end)
            }
        }
        return ranges
    }

    private class Lexer(private val source: String, private val groovy: Boolean) {
        private var position = 0
        private val tokens = mutableListOf<Token>()

        fun scan(): List<Token> {
            code(false)
            return tokens
        }

        private fun emit(text: String, offset: Int) {
            tokens.add(Token(text, source.take(offset).count { it == '\n' } + 1))
        }

        private fun string(delimiter: String, closing: String, interpolate: Boolean) {
            val start = position
            position += delimiter.length
            emit("<string>", start)
            while (position < source.length) {
                val i = position
                if (source.startsWith(closing, i)) {
                    position += closing.length
                    return
                }
                if (source[i] == '\\' && delimiter !in setOf("\"\"\"", "$/")) position += 2
                else if (delimiter == "$/" && (source.startsWith("$$", i) || source.startsWith("$/", i))) position += 2
                else if (interpolate && source.startsWith("\${", i)) {
                    emit("(", i)
                    position += 2
                    code(true)
                    emit(")", position - 1)
                } else if (interpolate && groovy && source[i] == '$') {
                    val match = Regex("\\$([A-Za-z_]\\w*(?:\\.[A-Za-z_]\\w*)*)").find(source, i)?.takeIf { it.range.first == i }
                    if (match == null) position++
                    else {
                        emit("(", i)
                        Regex("[A-Za-z_]\\w*|\\.").findAll(match.groupValues[1]).forEach { emit(it.value, i + 1 + it.range.first) }
                        emit(")", i + match.value.length)
                        position += match.value.length
                    }
                } else position++
            }
            throw IllegalArgumentException("unterminated string")
        }

        private fun code(interpolation: Boolean) {
            var braces = 0
            while (position < source.length) {
                val i = position
                val char = source[i]
                if (interpolation && char == '}' && braces == 0) {
                    position++
                    return
                }
                when {
                    source.startsWith("//", i) -> position = source.indexOf('\n', i).takeIf { it >= 0 } ?: source.length
                    source.startsWith("/*", i) -> {
                        var depth = 1
                        position += 2
                        while (depth > 0 && position < source.length) {
                            when {
                                source.startsWith("/*", position) -> { depth++; position += 2 }
                                source.startsWith("*/", position) -> { depth--; position += 2 }
                                else -> position++
                            }
                        }
                        require(depth == 0) { "unterminated comment" }
                    }
                    source.startsWith("\"\"\"", i) || source.startsWith("'''", i) -> {
                        val delimiter = source.substring(i, i + 3)
                        string(delimiter, delimiter, delimiter == "\"\"\"")
                    }
                    char == '"' || char == '\'' -> string(char.toString(), char.toString(), char == '"')
                    groovy && source.startsWith("$/", i) -> string("$/", "/$", true)
                    groovy && char == '/' && tokens.lastOrNull { it.text != "\n" }?.text in setOf(null, "=", "(", "[", ",", ":", "return") -> string("/", "/", true)
                    char == '\n' -> { emit("\n", i); position++ }
                    char.isWhitespace() -> position++
                    else -> {
                        val match = identifier.find(source, i)?.takeIf { it.range.first == i }
                        val text = match?.value ?: char.toString()
                        emit(text, i)
                        position += text.length
                        if (char == '{') braces++
                        else if (char == '}') braces--
                    }
                }
            }
            require(!interpolation) { "unterminated interpolation" }
        }
    }
}
