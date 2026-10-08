package io.mazewall.demo.koog;

/** A deterministic first-party transformation; no paths, policies or operation selectors. */
public interface TextTransformerService {
    String uppercaseAscii(String text);
}
