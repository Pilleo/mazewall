#!/usr/bin/env kotlin
import java.io.File

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println("Usage: kotlin file_structure.main.kts <path_to_kotlin_file>")
        System.exit(1)
    }

    val file = File(args[0])
    if (!file.exists()) {
        println("Error: File ${file.absolutePath} not found.")
        System.exit(1)
    }

    val rawLines = file.readLines()
    val structure = mutableListOf<String>()

    // Keywords to identify declarations
    val classKeywords = listOf("class", "interface", "object", "enum class", "fun interface")
    val modifiers = listOf("public", "private", "internal", "protected", "open", "sealed", "data", "inner", "abstract", "value", "companion")

    var indentLevel = 0
    var i = 0

    while (i < rawLines.size) {
        val rawLine = rawLines[i]
        val lineTrim = rawLine.trim()

        // Skip comments, package, imports
        if (lineTrim.startsWith("//") || lineTrim.startsWith("/*") || lineTrim.startsWith("*") || 
            lineTrim.startsWith("import ") || lineTrim.startsWith("package ")) {
            i++
            continue
        }

        val startIndex = i

        // Reconstruct multi-line signatures
        var consolidatedLine = lineTrim
        
        // 1. Detect Functions (fun)
        if (isFunStart(lineTrim)) {
            var openParenCount = consolidatedLine.count { it == '(' }
            var closeParenCount = consolidatedLine.count { it == ')' }
            var nextIndex = i + 1
            
            while (openParenCount > closeParenCount && nextIndex < rawLines.size) {
                val nextLine = rawLines[nextIndex].trim()
                consolidatedLine += " " + nextLine
                openParenCount = consolidatedLine.count { it == '(' }
                closeParenCount = consolidatedLine.count { it == ')' }
                nextIndex++
            }
            i = nextIndex - 1 // Skip processed lines
            
            // Match and print function signature
            val funRegex = """(?:fun)\s+(\w+)(<.*?>)?\((.*?)\)""".toRegex()
            val match = funRegex.find(consolidatedLine)
            if (match != null) {
                val (name, generics, params) = match.destructured
                val cleanParams = params.replace(Regex("\\s+"), " ").trim()
                val genericsStr = if (generics.isNotEmpty()) generics else ""
                val paramsDisplay = if (cleanParams.length > 80) cleanParams.take(77) + "..." else cleanParams
                
                val prefix = consolidatedLine.substringBefore("fun").trim()
                val prefixStr = if (prefix.isNotEmpty()) "$prefix " else ""
                val indentStr = "    ".repeat(indentLevel)
                structure.add("$indentStr├── ${prefixStr}fun $name$genericsStr($paramsDisplay)")
            }
        }
        // 2. Detect Classes / Interfaces / Objects
        else if (isClassStart(lineTrim, classKeywords, modifiers)) {
            var nextIndex = i + 1
            while (!consolidatedLine.contains("{") && !consolidatedLine.contains(":") && nextIndex < rawLines.size) {
                val nextLine = rawLines[nextIndex].trim()
                if (nextLine.startsWith("fun ") || nextLine.startsWith("val ") || nextLine.startsWith("var ")) {
                    break
                }
                consolidatedLine += " " + nextLine
                nextIndex++
            }
            i = nextIndex - 1
            
            val classRegex = """(?:class|interface|object|enum class|fun interface)\s+(\w+)""".toRegex()
            val match = classRegex.find(consolidatedLine)
            if (match != null) {
                val name = match.groupValues[1]
                val prefix = consolidatedLine.substringBefore(name).trim()
                val indentStr = "    ".repeat(indentLevel)
                structure.add("$indentStr├── $prefix $name")
            }
        }
        // 3. Detect Properties / Fields (val/var)
        else if (isPropStart(lineTrim)) {
            val propRegex = """(?:val|var)\s+(\w+)\s*:\s*([^=\n]+)""".toRegex()
            val match = propRegex.find(consolidatedLine)
            if (match != null) {
                val (name, propType) = match.destructured
                val cleanPropType = propType.split("=")[0].trim()
                val prefix = consolidatedLine.substringBefore("val").substringBefore("var").trim()
                val prefixStr = if (prefix.isNotEmpty()) "$prefix " else ""
                val kind = if (consolidatedLine.contains("val")) "val" else "var"
                val indentStr = "    ".repeat(indentLevel)
                structure.add("$indentStr├── $prefixStr$kind $name: $cleanPropType")
            }
        }

        // Count braces across all processed lines in this step to adjust indentLevel accurately
        var bracesOpen = 0
        var bracesClose = 0
        for (idx in startIndex..i) {
            val l = rawLines[idx].trim()
            if (!l.startsWith("//") && !l.startsWith("/*") && !l.startsWith("*")) {
                bracesOpen += l.count { it == '{' }
                bracesClose += l.count { it == '}' }
            }
        }

        // Adjust indent level safely
        indentLevel = maxOf(0, indentLevel + bracesOpen - bracesClose)

        i++
    }

    println("\nStructure of ${file.name}:")
    println("=".repeat(file.name.length + 14))
    structure.forEach(::println)
    println()
}

fun isFunStart(line: String): Boolean {
    return line.contains("fun ") || line.startsWith("fun ")
}

fun isPropStart(line: String): Boolean {
    return line.contains("val ") || line.contains("var ") || line.startsWith("val ") || line.startsWith("var ")
}

fun isClassStart(line: String, classKeywords: List<String>, modifiers: List<String>): Boolean {
    if (line.contains("fun ") || line.contains("val ") || line.contains("var ")) return false
    val tokens = line.split(Regex("\\s+"))
    return tokens.any { it in classKeywords }
}

main(args)
