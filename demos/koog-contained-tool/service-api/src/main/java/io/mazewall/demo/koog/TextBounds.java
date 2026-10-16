package io.mazewall.demo.koog;

import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;

/** Application data bound, independent of Portal framing and Koog JSON encoding. */
public final class TextBounds {
    public static final int MAX_TEXT_BYTES = 64 * 1024;

    private TextBounds() { }

    public static void requireBounded(String text) {
        if (text == null || text.length() > MAX_TEXT_BYTES) {
            throw new IllegalArgumentException("text exceeds 64 KiB application bound");
        }
        try {
            if (StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(text)).remaining() > MAX_TEXT_BYTES) {
                throw new IllegalArgumentException("text exceeds 64 KiB UTF-8 application bound");
            }
        } catch (CharacterCodingException malformed) {
            throw new IllegalArgumentException("text must be valid Unicode", malformed);
        }
    }
}
