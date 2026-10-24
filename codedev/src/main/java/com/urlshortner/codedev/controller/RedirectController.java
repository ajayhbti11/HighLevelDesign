package com.urlshortner.codedev.controller;

import com.urlshortner.codedev.model.UrlMapping;
import com.urlshortner.codedev.service.UrlService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
public class RedirectController {

    private final UrlService urlService;

    public RedirectController(UrlService urlService) {
        this.urlService = urlService;
    }

    @GetMapping("/{shortCode}")
    public ResponseEntity<Void> redirect(
            @PathVariable String shortCode) {

        UrlMapping mapping = urlService.resolve(shortCode);

        return ResponseEntity
                .status(HttpStatus.FOUND)
                .location(URI.create(mapping.longUrl()))
                .build();
    }
}