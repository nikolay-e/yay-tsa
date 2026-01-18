package com.yaytsa.server.infrastructure.persistence.query;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

public class OffsetBasedPageRequest implements Pageable {

  private final int offset;
  private final int limit;
  private final Sort sort;

  public OffsetBasedPageRequest(int offset, int limit, Sort sort) {
    if (offset < 0) {
      throw new IllegalArgumentException("Offset must be non-negative");
    }
    if (limit < 1) {
      throw new IllegalArgumentException("Limit must be positive");
    }
    this.offset = offset;
    this.limit = limit;
    this.sort = sort != null ? sort : Sort.unsorted();
  }

  public OffsetBasedPageRequest(int offset, int limit) {
    this(offset, limit, Sort.unsorted());
  }

  @Override
  public int getPageNumber() {
    return offset / limit;
  }

  @Override
  public int getPageSize() {
    return limit;
  }

  @Override
  public long getOffset() {
    return offset;
  }

  @Override
  public Sort getSort() {
    return sort;
  }

  @Override
  public Pageable next() {
    return new OffsetBasedPageRequest(offset + limit, limit, sort);
  }

  @Override
  public Pageable previousOrFirst() {
    return offset >= limit ? new OffsetBasedPageRequest(offset - limit, limit, sort) : first();
  }

  @Override
  public Pageable first() {
    return new OffsetBasedPageRequest(0, limit, sort);
  }

  @Override
  public Pageable withPage(int pageNumber) {
    return new OffsetBasedPageRequest(pageNumber * limit, limit, sort);
  }

  @Override
  public boolean hasPrevious() {
    return offset > 0;
  }
}
