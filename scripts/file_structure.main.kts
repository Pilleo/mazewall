#!/usr/bin/env kotlin
import java.io.File

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println("Usage: kotlin file_structure.main.kts <path_to_file>")
        println("Supported: .kt, .kts, .md, .yaml, .yml, .xml, .json")
        System.exit(1)
    }

    val file = File(args[0])
    if (!file.exists()) {
        println("Error: File ${file.absolutePath} not found.")
        System.exit(1)
    }

    println("\nStructure of ${file.name}:")
    println("=".repeat(file.name.length + 14))

    when (file.extension.lowercase()) {
        "kt", "kts" -> outlineKotlin(file)
        "md" -> outlineMarkdown(file)
        "yaml", "yml" -> outlineYaml(file)
        "xml" -> outlineXml(file)
        "json" -> outlineJson(file)
        else -> println("  (unsupported file type — ${file.extension}. Supported: .kt, .kts, .md, .yaml, .yml, .xml, .json)\n  Line count: ${file.readLines().size}")
    }
    println()
}

// ─── KOTLIN / KTS ────────────────────────────────────────────────────────────

fun outlineKotlin(file: File) {
    val rawLines = file.readLines()
    val structure = mutableListOf<String>()
    val classKeywords = listOf("class", "interface", "object", "enum class", "fun interface")
    val modifiers = listOf("public", "private", "internal", "protected", "open", "sealed", "data",
                           "inner", "abstract", "value", "companion")

    var indentLevel = 0
    var i = 0

    while (i < rawLines.size) {
        val rawLine = rawLines[i]
        val lineTrim = rawLine.trim()

        if (lineTrim.startsWith("//") || lineTrim.startsWith("/*") || lineTrim.startsWith("*") ||
            lineTrim.startsWith("import ") || lineTrim.startsWith("package ")) {
            i++
            continue
        }

        val startIndex = i
        var consolidatedLine = lineTrim

        when {
            isFunStart(lineTrim) -> {
                var openParen = consolidatedLine.count { it == '(' }
                var closeParen = consolidatedLine.count { it == ')' }
                var nextIdx = i + 1
                while (openParen > closeParen && nextIdx < rawLines.size) {
                    val next = rawLines[nextIdx].trim()
                    consolidatedLine += " $next"
                    openParen = consolidatedLine.count { it == '(' }
                    closeParen = consolidatedLine.count { it == ')' }
                    nextIdx++
                }
                i = nextIdx - 1

                val funRegex = """(?:fun)\s+(\w+)(<.*?>)?\((.*?)\)""".toRegex()
                val match = funRegex.find(consolidatedLine)
                if (match != null) {
                    val (name, generics, params) = match.destructured
                    val cleanParams = params.replace(Regex("\\s+"), " ").trim()
                    val genericsStr = if (generics.isNotEmpty()) generics else ""
                    val paramsDisplay = if (cleanParams.length > 80) cleanParams.take(77) + "..." else cleanParams
                    val prefix = consolidatedLine.substringBefore("fun").trim()
                    val prefixStr = if (prefix.isNotEmpty()) "$prefix " else ""
                    structure.add("${"    ".repeat(indentLevel)}├── ${prefixStr}fun $name$genericsStr($paramsDisplay)")
                }
            }

            isClassStart(lineTrim, classKeywords, modifiers) -> {
                var nextIdx = i + 1
                while (!consolidatedLine.contains("{") && !consolidatedLine.contains(":") && nextIdx < rawLines.size) {
                    val next = rawLines[nextIdx].trim()
                    if (next.startsWith("fun ") || next.startsWith("val ") || next.startsWith("var ")) break
                    consolidatedLine += " $next"
                    nextIdx++
                }
                i = nextIdx - 1

                val classRegex = """(?:class|interface|object|enum class|fun interface)\s+(\w+)""".toRegex()
                val match = classRegex.find(consolidatedLine)
                if (match != null) {
                    val name = match.groupValues[1]
                    val prefix = consolidatedLine.substringBefore(name).trim()
                    structure.add("${"    ".repeat(indentLevel)}├── $prefix $name")
                }
            }

            isPropStart(lineTrim) -> {
                val propRegex = """(?:val|var)\s+(\w+)\s*:\s*([^=\n]+)""".toRegex()
                val match = propRegex.find(consolidatedLine)
                if (match != null) {
                    val (name, propType) = match.destructured
                    val cleanPropType = propType.split("=")[0].trim()
                    val prefix = consolidatedLine.substringBefore("val").substringBefore("var").trim()
                    val prefixStr = if (prefix.isNotEmpty()) "$prefix " else ""
                    val kind = if (consolidatedLine.contains("val")) "val" else "var"
                    structure.add("${"    ".repeat(indentLevel)}├── $prefixStr$kind $name: $cleanPropType")
                }
            }
        }

        // Accumulate braces across all lines consumed in this iteration
        var bracesOpen = 0
        var bracesClose = 0
        for (idx in startIndex..i) {
            val l = rawLines[idx].trim()
            if (!l.startsWith("//") && !l.startsWith("/*") && !l.startsWith("*")) {
                bracesOpen += l.count { it == '{' }
                bracesClose += l.count { it == '}' }
            }
        }
        indentLevel = maxOf(0, indentLevel + bracesOpen - bracesClose)
        i++
    }

    structure.forEach(::println)
}

fun isFunStart(line: String) = line.contains("fun ")
fun isPropStart(line: String) = line.contains("val ") || line.contains("var ")
fun isClassStart(line: String, classKeywords: List<String>, modifiers: List<String>): Boolean {
    if (line.contains("fun ") || line.contains("val ") || line.contains("var ")) return false
    return line.split(Regex("\\s+")).any { it in classKeywords }
}

// ─── MARKDOWN ─────────────────────────────────────────────────────────────────

fun outlineMarkdown(file: File) {
    val headingRegex = """^(#{1,6})\s+(.+)""".toRegex()
    var inCodeBlock = false
    var hasHeadings = false

    file.readLines().forEach { line ->
        val trimmed = line.trim()
        if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
            inCodeBlock = !inCodeBlock
            return@forEach
        }
        if (inCodeBlock) return@forEach

        val match = headingRegex.find(line) ?: return@forEach
        val (hashes, title) = match.destructured
        val depth = hashes.length
        val indent = "    ".repeat(depth - 1)
        val prefix = if (depth == 1) "╔══ #" else "├── ${"#".repeat(depth)}"
        println("$indent$prefix $title")
        hasHeadings = true
    }
    if (!hasHeadings) println("  (no headings found)")
}

// ─── YAML / YML ───────────────────────────────────────────────────────────────

fun outlineYaml(file: File) {
    val topKeyRegex = """^(\w[\w\-.]*):\s*""".toRegex()
    val secondKeyRegex = """^  (\w[\w\-.]*):\s*""".toRegex()
    var currentTop = ""

    file.readLines().forEach { line ->
        when {
            line.startsWith("#") || line.isBlank() -> return@forEach
            topKeyRegex.matches(line) -> {
                currentTop = topKeyRegex.find(line)!!.groupValues[1]
                println("├── $currentTop:")
            }
            secondKeyRegex.matches(line) -> {
                val key = secondKeyRegex.find(line)!!.groupValues[1]
                println("    ├── $key:")
            }
        }
    }
}

// ─── XML ──────────────────────────────────────────────────────────────────────

fun outlineXml(file: File) {
    val tagRegex = """<(/?)(\w[\w:]*)""".toRegex()
    val seen = mutableSetOf<String>()
    var indentLevel = 0

    file.readLines().forEach { line ->
        val trimmed = line.trim()
        if (trimmed.startsWith("<?") || trimmed.startsWith("<!--")) return@forEach

        val matches = tagRegex.findAll(trimmed)
        for (match in matches) {
            val closing = match.groupValues[1] == "/"
            val tag = match.groupValues[2]
            if (closing) {
                indentLevel = maxOf(0, indentLevel - 1)
            } else {
                val key = "$indentLevel:$tag"
                if (key !in seen) {
                    seen.add(key)
                    println("${"    ".repeat(indentLevel)}├── <$tag>")
                }
                if (!trimmed.contains("/>")) indentLevel++
            }
        }
    }
}

// ─── JSON ─────────────────────────────────────────────────────────────────────

fun outlineJson(file: File) {
    val keyRegex = """"(\w[\w\-.]*)"\s*:""".toRegex()
    var depth = 0
    val seenAtDepth = mutableSetOf<String>()

    file.readLines().forEach { line ->
        val trimmed = line.trim()
        depth += trimmed.count { it == '{' || it == '[' }
        depth -= trimmed.count { it == '}' || it == ']' }

        if (depth <= 2) { // Only show top-level (depth 1) and second-level (depth 2) keys
            keyRegex.findAll(trimmed).forEach { match ->
                val key = match.groupValues[1]
                val indent = "    ".repeat(maxOf(0, depth - 1))
                val depthKey = "$depth:$key"
                if (depthKey !in seenAtDepth) {
                    seenAtDepth.add(depthKey)
                    println("$indent├── \"$key\"")
                }
            }
        }
    }
}

main(args)
