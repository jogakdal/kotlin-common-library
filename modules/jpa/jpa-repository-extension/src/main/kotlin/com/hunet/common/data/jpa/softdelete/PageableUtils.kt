package com.hunet.common.data.jpa.softdelete

import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import java.util.Optional

/**
 * 페이지네이션 빌더.
 * - pageSize > 0 이면 PageRequest 반환
 * - pageSize <= 0 이면 정렬 정보만 가진 unpaged Pageable 반환
 */
fun <T : Number> buildPageable(pageNo: T, pageSize: T, sort: Sort = Sort.unsorted()): Pageable {
    val page = pageNo.toLong().coerceAtLeast(0).toInt()
    val size = pageSize.toLong()
    return if (size > 0) {
        PageRequest.of(page, size.toInt(), sort)
    } else {
        object : Pageable {
            override fun getPageNumber(): Int = 0
            override fun getPageSize(): Int = 0
            override fun getOffset(): Long = 0
            override fun getSort(): Sort = sort
            override fun next(): Pageable = this
            override fun previousOrFirst(): Pageable = this
            override fun first(): Pageable = this
            override fun withPage(pageNumber: Int): Pageable = this
            override fun hasPrevious(): Boolean = false
            override fun isPaged(): Boolean = false
            override fun isUnpaged(): Boolean = true
            override fun toOptional(): Optional<Pageable> = Optional.empty()
        }
    }
}
