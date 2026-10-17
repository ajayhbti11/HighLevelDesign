package com.searchengine.common;

import java.util.Objects;

/**
 * Token: a single normalized unit of text produced by the shared {@code normalize()}
 * procedure (Req 4.3, 7.1).
 *
 * <p>Position is assigned by context (index or query), so it is intentionally not part
 * of the Token itself (see design §3).
 */
public final class Token {

    private final String text;

    private Token(String text) {
        this.text = Objects.requireNonNull(text, "token text must not be null");
    }

    public static Token of(String text) {
        return new Token(text);
    }

    public String text() {
        return text;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Token)) {
            return false;
        }
        return text.equals(((Token) o).text);
    }

    @Override
    public int hashCode() {
        return text.hashCode();
    }

    @Override
    public String toString() {
        return "Token(" + text + ")";
    }
}
