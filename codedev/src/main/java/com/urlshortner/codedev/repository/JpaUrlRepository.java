package com.urlshortner.codedev.repository;

import com.urlshortner.codedev.entity.UrlMapping;
import com.urlshortner.codedev.exception.DuplicateShortCodeException;
import com.urlshortner.codedev.utils.UrlStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class JpaUrlRepository implements UrlRepository {

    // Inner Spring Data JPA repository
    public interface UrlJpaRepository extends JpaRepository<UrlMapping, String> {
        Optional<UrlMapping> findByShortCode(String shortCode);

        boolean existsByShortCode(String shortCode);

        @Modifying
        @Query("UPDATE UrlMapping u SET u.status = :status WHERE u.shortCode = :shortCode")
        void updateStatus(@Param("shortCode") String shortCode, @Param("status") UrlStatus status);
    }

    private final UrlJpaRepository jpaRepository;

    public JpaUrlRepository(UrlJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Optional<UrlMapping> findByShortCode(String shortCode) {
        return jpaRepository.findByShortCode(shortCode);
    }

    @Override
    @Transactional
    public UrlMapping save(UrlMapping mapping) {
        try {
            return jpaRepository.save(mapping);
        } catch (DataIntegrityViolationException ex) {
            throw new DuplicateShortCodeException(
                    "Short code already exists: " + mapping.shortCode());
        }
    }

    @Override
    public boolean existsByShortCode(String shortCode) {
        return jpaRepository.existsByShortCode(shortCode);
    }

    @Override
    @Transactional
    public void deactivate(String shortCode) {
        jpaRepository.updateStatus(shortCode, UrlStatus.DISABLED);
    }
}
