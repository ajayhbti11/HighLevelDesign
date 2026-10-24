package com.urlshortner.codedev.repository;

import com.urlshortner.codedev.entity.UrlMapping;

import java.util.Optional;

public interface UrlRepository {
    Optional<UrlMapping> findByShortCode(String shortCode);
    UrlMapping save(UrlMapping mapping);
    boolean existsByShortCode(String shortCode);
    void deactivate(String shortCode);
}
