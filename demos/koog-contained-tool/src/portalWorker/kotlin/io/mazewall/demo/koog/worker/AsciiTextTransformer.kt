package io.mazewall.demo.koog.worker

import io.mazewall.demo.koog.TextBounds
import io.mazewall.demo.koog.TextTransformerService

/** ASCII-only case conversion preserves Unicode and cannot expand the UTF-8 result. */
public class AsciiTextTransformer : TextTransformerService {
    override fun uppercaseAscii(text: String): String {
        TextBounds.requireBounded(text)
        val result = buildString(text.length) {
            text.forEach { character ->
                append(if (character in 'a'..'z') character.uppercaseChar() else character)
            }
        }
        TextBounds.requireBounded(result)
        return result
    }
}
