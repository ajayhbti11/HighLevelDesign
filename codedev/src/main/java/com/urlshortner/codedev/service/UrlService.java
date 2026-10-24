package com.urlshortner.codedev.service;

import com.urlshortner.codedev.model.UrlMapping;

public interface UrlService {
    UrlMapping resolve(String shortCode);
}
