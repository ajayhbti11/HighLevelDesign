package com.urlshortner.codedev.cache;

import com.urlshortner.codedev.entity.UrlMapping;

import java.util.Optional;

public interface UrlCache {
    Optional<UrlMapping> get(String shortCode);
    void put(String shortCode, UrlMapping mapping);
    void evict(String shortCode);
}
