package com.yaytsa.server.infra.persistence.repository;

import com.yaytsa.server.infra.persistence.entity.ItemEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

public interface ItemRepositoryCustom {
    Page<ItemEntity> findAllRandomized(Specification<ItemEntity> spec, Pageable pageable);
}
