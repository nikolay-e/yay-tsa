package dev.yaytsa.persistence.library

import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort

class OffsetBasedPageRequest(
    private val offset: Long,
    private val limit: Int,
    private val sort: Sort = Sort.unsorted(),
) : Pageable {
    init {
        require(offset >= 0) { "Offset must not be negative" }
        require(limit >= 1) { "Limit must be at least one" }
    }

    override fun getOffset(): Long = offset

    override fun getPageSize(): Int = limit

    override fun getPageNumber(): Int = (offset / limit).toInt()

    override fun getSort(): Sort = sort

    override fun hasPrevious(): Boolean = offset >= limit

    override fun previousOrFirst(): Pageable =
        if (hasPrevious()) {
            OffsetBasedPageRequest(maxOf(0, offset - limit), limit, sort)
        } else {
            first()
        }

    override fun next(): Pageable = OffsetBasedPageRequest(offset + limit, limit, sort)

    override fun first(): Pageable = OffsetBasedPageRequest(0, limit, sort)

    override fun withPage(pageNumber: Int): Pageable = OffsetBasedPageRequest(pageNumber.toLong() * limit, limit, sort)

    override fun isPaged(): Boolean = true
}
