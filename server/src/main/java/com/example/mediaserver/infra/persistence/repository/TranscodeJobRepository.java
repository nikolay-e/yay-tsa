package com.example.mediaserver.infra.persistence.repository;

import com.example.mediaserver.infra.persistence.entity.TranscodeJobEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface TranscodeJobRepository extends JpaRepository<TranscodeJobEntity, UUID> {
    List<TranscodeJobEntity> findByStatus(String status);
    List<TranscodeJobEntity> findBySessionId(UUID sessionId);
    long countByStatus(String status);
}
