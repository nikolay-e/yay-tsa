package com.example.mediaserver.infra.persistence.repository;

import com.example.mediaserver.infra.persistence.entity.SessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SessionRepository extends JpaRepository<SessionEntity, UUID> {
    Optional<SessionEntity> findByUserIdAndDeviceId(UUID userId, String deviceId);
    List<SessionEntity> findAllByUserId(UUID userId);
}
