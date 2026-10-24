package com.urlshortner.codedev.entity;

import com.urlshortner.codedev.utils.UrlStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;

@Entity
@Table(name = "url_mapping")
public class UrlMapping implements Serializable {

    @Id
    @Column(name = "short_code", nullable = false, length = 16)
    private String shortCode;

    @Column(name = "long_url", nullable = false, length = 2048)
    private String longUrl;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private UrlStatus status;

    // JPA no-arg constructor
    protected UrlMapping() {}

    public UrlMapping(String shortCode, String longUrl, Instant createdAt, Instant expiresAt, UrlStatus status) {
        this.shortCode = shortCode;
        this.longUrl = longUrl;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.status = status;
    }

    public String shortCode() { return shortCode; }
    public String longUrl() { return longUrl; }
    public Instant createdAt() { return createdAt; }
    public Instant expiresAt() { return expiresAt; }
    public UrlStatus status() { return status; }

    public boolean isExpired(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }

    public boolean isActiveAt(Instant now) {
        return status == UrlStatus.ACTIVE && !isExpired(now);
    }
}
