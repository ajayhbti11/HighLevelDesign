package com.urlshortner.codedev.service;

import com.urlshortner.codedev.cache.UrlCache;
import com.urlshortner.codedev.entity.UrlMapping;
import com.urlshortner.codedev.event.ClickEventPublisher;
import com.urlshortner.codedev.exception.UrlGoneException;
import com.urlshortner.codedev.exception.UrlNotFoundException;
import com.urlshortner.codedev.generator.IdGenerator;
import com.urlshortner.codedev.generator.ShortCodeGenerator;
import com.urlshortner.codedev.repository.UrlRepository;
import com.urlshortner.codedev.utils.UrlStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UrlServiceImplTest {

    @Mock
    private UrlRepository repository;

    @Mock
    private UrlCache cache;

    @Mock
    private ShortCodeGenerator codeGenerator;

    @Mock
    private IdGenerator idGenerator;

    @Mock
    private ClickEventPublisher eventPublisher;

    private final Clock clock = Clock.fixed(Instant.now(), ZoneOffset.UTC);

    private UrlServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new UrlServiceImpl(repository, cache, codeGenerator, idGenerator, eventPublisher, clock);
    }

    // --- validateUrl tests (exercised via create()) ---

    @Test
    void createWithBlankUrlThrows() {
        assertThatThrownBy(() -> service.create("", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("URL must not be blank");
    }

    @Test
    void createWithTooLongUrlThrows() {
        String longUrl = "https://" + "a".repeat(2042);
        assertThatThrownBy(() -> service.create(longUrl, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds maximum length");
    }

    @Test
    void createWithNonHttpUrlThrows() {
        assertThatThrownBy(() -> service.create("ftp://example.com", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Only http and https URLs are allowed");
    }

    @Test
    void createWithMalformedUrlThrows() {
        assertThatThrownBy(() -> service.create("http://[invalid", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid URL format");
    }

    // --- resolve() tests ---

    @Test
    void resolveReturnsCachedMappingOnCacheHit() {
        UrlMapping activeMapping = new UrlMapping("abc", "https://example.com",
                Instant.now(clock), null, UrlStatus.ACTIVE);
        when(cache.get("abc")).thenReturn(Optional.of(activeMapping));

        UrlMapping result = service.resolve("abc");

        assertThat(result).isEqualTo(activeMapping);
        verify(repository, never()).findByShortCode(any());
    }

    @Test
    void resolveThrowsUrlNotFoundWhenNotInCacheOrDb() {
        when(cache.get("xyz")).thenReturn(Optional.empty());
        when(repository.findByShortCode("xyz")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolve("xyz"))
                .isInstanceOf(UrlNotFoundException.class);
    }

    @Test
    void resolveThrowsUrlGoneForExpiredMapping() {
        // Mapping expired in the past (expiresAt is before clock.instant())
        UrlMapping expiredMapping = new UrlMapping("abc", "https://example.com",
                Instant.EPOCH, Instant.EPOCH.plusSeconds(1), UrlStatus.ACTIVE);
        when(cache.get("abc")).thenReturn(Optional.of(expiredMapping));

        assertThatThrownBy(() -> service.resolve("abc"))
                .isInstanceOf(UrlGoneException.class);
    }

    @Test
    void resolveThrowsUrlGoneForDisabledMapping() {
        UrlMapping disabledMapping = new UrlMapping("abc", "https://example.com",
                Instant.EPOCH, null, UrlStatus.DISABLED);
        when(cache.get("abc")).thenReturn(Optional.of(disabledMapping));

        assertThatThrownBy(() -> service.resolve("abc"))
                .isInstanceOf(UrlGoneException.class);
    }
}
