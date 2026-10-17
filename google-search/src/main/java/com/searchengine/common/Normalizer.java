package com.searchengine.common;

import java.text.Normalizer.Form;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Shared normalization: the single source of truth for turning raw surface text into an
 * ordered list of {@link Token}s (design §3, LLD §C).
 *
 * <p>Both the Parser (Req 4.3) and the Query_Processor (Req 7.1) call this exact procedure so
 * that an index term and a query term for the same surface text are always byte-identical.
 *
 * <p>The procedure is:
 * <ol>
 *   <li>Apply Unicode NFKC (compatibility composition) normalization.</li>
 *   <li>Case-fold to lower case.</li>
 *   <li>Split on Unicode whitespace and punctuation boundaries.</li>
 *   <li>Drop empty tokens (which also collapses runs of repeated whitespace/punctuation,
 *       since the boundaries between them yield no non-empty token).</li>
 * </ol>
 *
 * <p>This is a <strong>pure, deterministic</strong> function: the same input always produces
 * the same output, with no side effects and no dependence on external state. The class is not
 * instantiable.
 */
public final class Normalizer {

    private Normalizer() {
        // Utility class; not instantiable.
    }

    /**
     * Normalize {@code rawText} into an ordered list of tokens.
     *
     * @param rawText the raw surface text to normalize; must not be {@code null}
     * @return an unmodifiable, ordered list of non-empty {@link Token}s; empty when the input
     *     contains no token characters (for example, {@code null}-free empty or whitespace-only
     *     input)
     * @throws NullPointerException if {@code rawText} is {@code null}
     */
    public static List<Token> normalize(String rawText) {
        if (rawText == null) {
            throw new NullPointerException("rawText must not be null");
        }

        // Step 1: Unicode NFKC normalization (canonical + compatibility composition).
        String nfkc = java.text.Normalizer.normalize(rawText, Form.NFKC);

        // Step 2: case-fold to lower case. Use the root locale for deterministic, locale-independent
        // folding (avoids surprises such as the Turkish dotless-i mapping).
        String folded = nfkc.toLowerCase(java.util.Locale.ROOT);

        // Steps 3 & 4: split on Unicode whitespace/punctuation boundaries, dropping empty tokens.
        // Iterating by code point keeps supplementary characters (outside the BMP) intact.
        List<Token> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        int length = folded.length();
        for (int offset = 0; offset < length; ) {
            int codePoint = folded.codePointAt(offset);
            offset += Character.charCount(codePoint);

            if (isBoundary(codePoint)) {
                // A boundary terminates the current token (if any) and is itself dropped.
                flush(current, tokens);
            } else {
                current.appendCodePoint(codePoint);
            }
        }
        flush(current, tokens);

        return Collections.unmodifiableList(tokens);
    }

    /**
     * A code point is a token boundary when it is Unicode whitespace or punctuation. Runs of
     * such characters collapse because consecutive boundaries yield no non-empty token between
     * them.
     */
    private static boolean isBoundary(int codePoint) {
        if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
            return true;
        }
        int type = Character.getType(codePoint);
        switch (type) {
            case Character.CONNECTOR_PUNCTUATION:
            case Character.DASH_PUNCTUATION:
            case Character.START_PUNCTUATION:
            case Character.END_PUNCTUATION:
            case Character.INITIAL_QUOTE_PUNCTUATION:
            case Character.FINAL_QUOTE_PUNCTUATION:
            case Character.OTHER_PUNCTUATION:
                return true;
            default:
                return false;
        }
    }

    /**
     * Emit the buffered characters as a token when non-empty, then reset the buffer. Empty
     * buffers are dropped, which is what collapses repeated boundaries.
     */
    private static void flush(StringBuilder current, List<Token> tokens) {
        if (current.length() > 0) {
            tokens.add(Token.of(current.toString()));
            current.setLength(0);
        }
    }
}
