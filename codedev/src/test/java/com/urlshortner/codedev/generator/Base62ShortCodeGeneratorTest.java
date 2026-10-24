package com.urlshortner.codedev.generator;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Base62ShortCodeGeneratorTest {

    private final Base62ShortCodeGenerator generator = new Base62ShortCodeGenerator();

    @Test
    void encode0ReturnsZero() {
        assertThat(generator.encode(0)).isEqualTo("0");
    }

    @Test
    void encode62Returns10() {
        assertThat(generator.encode(62)).isEqualTo("10");
    }

    @Test
    void negativeIdThrows() {
        assertThrows(IllegalArgumentException.class, () -> generator.encode(-1));
    }

    @Test
    void encodedStringContainsOnlyBase62Chars() {
        String result = generator.encode(123456789L);
        assertThat(result).matches("[0-9a-zA-Z]+");
    }
}
