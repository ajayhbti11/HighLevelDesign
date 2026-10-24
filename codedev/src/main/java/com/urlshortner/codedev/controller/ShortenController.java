package com.urlshortner.codedev.controller;

import com.urlshortner.codedev.entity.UrlMapping;
import com.urlshortner.codedev.service.UrlService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.time.Instant;

@RestController
@RequestMapping("/api")
public class ShortenController {

    private final UrlService urlService;

    public ShortenController(UrlService urlService) {
        this.urlService = urlService;
    }

    @PostMapping("/shorten")
    public ResponseEntity<ShortenResponse> shorten(@Valid @RequestBody ShortenRequest request) {
        UrlMapping mapping = urlService.create(request.longUrl(), request.expiresAt());

        String shortUrl = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/{shortCode}")
                .buildAndExpand(mapping.shortCode())
                .toUriString();

        URI location = URI.create(shortUrl);

        return ResponseEntity.created(location)
                .body(new ShortenResponse(mapping.shortCode(), shortUrl));
    }

    public record ShortenRequest(
            @NotBlank String longUrl,
            Instant expiresAt
    ) {}

    public record ShortenResponse(
            String shortCode,
            String shortUrl
    ) {}
}
