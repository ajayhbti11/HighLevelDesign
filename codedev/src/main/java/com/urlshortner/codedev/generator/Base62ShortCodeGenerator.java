package com.urlshortner.codedev.generator;

import org.springframework.stereotype.Component;

@Component
public final class Base62ShortCodeGenerator implements ShortCodeGenerator {

    private static final String ALPHABET =
            "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final int BASE = ALPHABET.length();

    @Override
    public String encode(long id) {
        if (id < 0) {
            throw new IllegalArgumentException("ID must be non-negative");
        }
        if (id == 0) {
            return "0";
        }
        StringBuilder result = new StringBuilder();
        while (id > 0) {
            int remainder = (int) (id % BASE);
            result.append(ALPHABET.charAt(remainder));
            id /= BASE;
        }
        return result.reverse().toString();
    }
}
