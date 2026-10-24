package com.urlshortner.codedev.service;

import com.urlshortner.codedev.model.UrlMapping;
import org.springframework.stereotype.Service;

@Service
public class UrlServiceImpl implements UrlService {

    @Override
    public UrlMapping resolve(String shortCode) {
        // TODO: implement with actual data store lookup
        throw new UnsupportedOperationException("resolve not yet implemented");
    }
}
