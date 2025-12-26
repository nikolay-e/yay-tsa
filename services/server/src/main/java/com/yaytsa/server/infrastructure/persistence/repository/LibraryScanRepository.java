package com.yaytsa.server.infrastructure.persistence.repository;

import com.yaytsa.server.infrastructure.persistence.entity.LibraryScanEntity;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface LibraryScanRepository extends JpaRepository<LibraryScanEntity, UUID> {

    @Query("SELECT ls FROM LibraryScanEntity ls ORDER BY ls.startedAt DESC")
    List<LibraryScanEntity> findAllOrderByStartedAtDesc(Pageable pageable);

    default List<LibraryScanEntity> findRecentScans(int limit) {
        return findAllOrderByStartedAtDesc(PageRequest.of(0, limit));
    }

    List<LibraryScanEntity> findByStatus(String status);

    List<LibraryScanEntity> findByLibraryRootOrderByStartedAtDesc(String libraryRoot);
}
