package com.yaytsa.server.infra.persistence.repository;

import com.yaytsa.server.infra.persistence.entity.GenreEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface GenreRepository extends JpaRepository<GenreEntity, UUID> {
    Optional<GenreEntity> findByName(String name);
}
