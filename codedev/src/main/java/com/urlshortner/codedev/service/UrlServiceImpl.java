package com.urlshortner.codedev.service;

import com.urlshortner.codedev.cache.UrlCache;
import com.urlshortner.codedev.entity.UrlMapping;
import com.urlshortner.codedev.event.ClickEventPublisher;
import com.urlshortner.codedev.exception.DuplicateShortCodeException;
import com.urlshortner.codedev.exception.UrlGoneException;
import com.urlshortner.codedev.exception.UrlNotFoundException;
import com.urlshortner.codedev.generator.IdGenerator;
import com.urlshortner.codedev.generator.ShortCodeGenerator;
import com.urlshortner.codedev.repository.UrlRepository;
import com.urlshortner.codedev.utils.UrlStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

@Service
public class UrlServiceImpl implements UrlService {

    private final UrlRepository repository;
    private final UrlCache cache;
    private final ShortCodeGenerator codeGenerator;
    private final IdGenerator idGenerator;
    private final ClickEventPublisher eventPublisher;
    private final Clock clock;

    public UrlServiceImpl(
            UrlRepository repository,
            UrlCache cache,
            ShortCodeGenerator codeGenerator,
            IdGenerator idGenerator,
            ClickEventPublisher eventPublisher,
            Clock clock) {
        this.repository = repository;
        this.cache = cache;
        this.codeGenerator = codeGenerator;
        this.idGenerator = idGenerator;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    @Override
    public UrlMapping create(String longUrl, Instant expiresAt) {
        validateUrl(longUrl);

        Instant now = clock.instant();

        if (expiresAt != null && !expiresAt.isAfter(now)) {
            throw new IllegalArgumentException("Expiry must be in the future");
        }

        for (int attempt = 0; attempt < 3; attempt++) {
            String code = codeGenerator.encode(idGenerator.nextId());

            UrlMapping mapping = new UrlMapping(
                    code, longUrl, now, expiresAt, UrlStatus.ACTIVE);

            try {
                return repository.save(mapping);
            } catch (DuplicateShortCodeException ex) {
                // Retry only for a short-code uniqueness conflict.
            }
        }

        throw new IllegalStateException("Unable to allocate a unique short code");
    }

    @Override
    public UrlMapping resolve(String code) {
        Optional<UrlMapping> cached = cache.get(code);

        UrlMapping mapping = cached.orElseGet(() ->
                repository.findByShortCode(code)
                        .orElseThrow(UrlNotFoundException::new));

        if (!mapping.isActiveAt(clock.instant())) {
            throw new UrlGoneException();
        }

        if (cached.isEmpty()) {
            cache.put(code, mapping);
        }

        eventPublisher.publish(code);

        return mapping;
    }

    private void validateUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("URL must not be blank");
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new IllegalArgumentException("URL must use http or https scheme");
        }
        if (url.length() > 2048) {
            throw new IllegalArgumentException("URL exceeds maximum length of 2048");
        }
    }
}
