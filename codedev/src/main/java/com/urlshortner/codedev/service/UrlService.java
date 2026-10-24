package com.urlshortner.codedev.service;

import com.urlshortner.codedev.entity.UrlMapping;

import java.time.Instant;

public interface UrlService {
    UrlMapping resolve(String shortCode);
    UrlMapping create(String longUrl, Instant expiresAt);
}
