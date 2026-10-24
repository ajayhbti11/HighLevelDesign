package com.urlshortner.codedev.cache;

import com.urlshortner.codedev.entity.UrlMapping;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

@Service
public class RedisUrlCache implements UrlCache {

    private static final String KEY_PREFIX = "url:";
    private static final Duration TTL = Duration.ofHours(24);

    private final RedisTemplate<String, UrlMapping> redisTemplate;

    public RedisUrlCache(RedisTemplate<String, UrlMapping> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Optional<UrlMapping> get(String shortCode) {
        UrlMapping value = redisTemplate.opsForValue().get(KEY_PREFIX + shortCode);
        return Optional.ofNullable(value);
    }

    @Override
    public void put(String shortCode, UrlMapping mapping) {
        redisTemplate.opsForValue().set(KEY_PREFIX + shortCode, mapping, TTL);
    }

    @Override
    public void evict(String shortCode) {
        redisTemplate.delete(KEY_PREFIX + shortCode);
    }
}
