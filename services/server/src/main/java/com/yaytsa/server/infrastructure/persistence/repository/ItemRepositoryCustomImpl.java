package com.yaytsa.server.infrastructure.persistence.repository;

import com.yaytsa.server.infrastructure.persistence.entity.ItemEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;

public class ItemRepositoryCustomImpl implements ItemRepositoryCustom {
    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public Page<ItemEntity> findAllRandomized(Specification<ItemEntity> spec, Pageable pageable) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<ItemEntity> query = cb.createQuery(ItemEntity.class);
        Root<ItemEntity> root = query.from(ItemEntity.class);

        if (spec != null) {
            Predicate predicate = spec.toPredicate(root, query, cb);
            if (predicate != null) {
                query.where(predicate);
            }
        }

        query.orderBy(cb.asc(cb.function("random", Double.class)));

        List<ItemEntity> items = entityManager.createQuery(query)
            .setFirstResult((int) pageable.getOffset())
            .setMaxResults(pageable.getPageSize())
            .getResultList();

        CriteriaQuery<Long> countQuery = cb.createQuery(Long.class);
        Root<ItemEntity> countRoot = countQuery.from(ItemEntity.class);
        countQuery.select(cb.count(countRoot));
        if (spec != null) {
            Predicate countPredicate = spec.toPredicate(countRoot, countQuery, cb);
            if (countPredicate != null) {
                countQuery.where(countPredicate);
            }
        }
        long total = entityManager.createQuery(countQuery).getSingleResult();

        return new PageImpl<>(items, pageable, total);
    }
}
