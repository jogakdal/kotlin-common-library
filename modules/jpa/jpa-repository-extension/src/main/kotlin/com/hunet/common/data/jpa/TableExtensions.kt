package com.hunet.common.data.jpa

import jakarta.persistence.Table

/**
 * 엔티티 클래스의 @Table(name=…) 값을 반환합니다.
 *
 * - Hibernate 프록시(CGLIB)일 가능성을 고려하여 현재 클래스에 없으면 슈퍼클래스에서 한 번 더 찾습니다.
 * - @Table 이 반드시 존재해야 하며 없으면 IllegalStateException 을 던집니다.
 */
inline val Any.nativeTableName: String
    get() = (
        this::class.java.getAnnotation(Table::class.java)
            ?: this::class.java.superclass?.getAnnotation(Table::class.java)
    )?.name ?: throw IllegalStateException("엔티티 ${this::class.simpleName}에 @Table(name=…)이 없습니다")

