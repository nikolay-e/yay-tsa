package com.yaytsa.server.infrastructure.persistence.repository;

import com.yaytsa.server.infrastructure.persistence.entity.ItemEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

public interface ItemRepositoryCustom {
    Page<ItemEntity> findAllRandomized(Specification<ItemEntity> spec, Pageable pageable);
}
