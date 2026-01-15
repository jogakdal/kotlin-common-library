package com.hunet.common.data.jpa.softdelete.internal

import java.time.LocalDateTime

/**
 * Alive/Delete 마크 처리 표준화 빌더들
 */
data class AlivePredicate(
    val fragment: String, // e.g., " AND e.deletedAt IS NULL" or " AND e.deletedAt = :aliveMarkValue"
    val params: Map<String, Any?> = emptyMap(),
)

data class DeleteSetClause(
    val fragment: String, // e.g., "`deleted_at` = NULL" or "`deleted_at` = :deleteMark"
    val params: Map<String, Any?> = emptyMap(),
    val physicalDelete: Boolean = false,
)

object AlivePredicateBuilder {
    fun build(info: DeleteMarkInfo?, alias: String = "e", paramName: String = "aliveMarkValue"): AlivePredicate {
        if (info == null) return AlivePredicate("")
        return when (info.aliveMark) {
            DeleteMarkValue.NULL -> AlivePredicate(" AND $alias.${info.fieldName} IS NULL")
            DeleteMarkValue.NOT_NULL -> AlivePredicate(" AND $alias.${info.fieldName} IS NOT NULL")
            else -> AlivePredicate(
                fragment = " AND $alias.${info.fieldName} = :$paramName",
                params = mapOf(paramName to info.aliveMarkValue)
            )
        }
    }
}

object DeleteDefaultValueProvider {
    /** 기본값 제공 정책: DeleteMarkValue 별 합리적 기본값 제공 */
    fun provide(info: DeleteMarkInfo): Any? {
        return when (info.deleteMark) {
            DeleteMarkValue.NULL -> null
            DeleteMarkValue.NOW -> LocalDateTime.now()
            DeleteMarkValue.NOT_NULL -> DeleteMarkValue.getDefaultDeleteMarkValue(info)
            null -> null
            else -> info.deleteMarkValue
        }
    }
}

object DeletePredicateBuilder {
    /** 네이티브 SQL setClause 용 빌더 (백틱 컬럼명 그대로 유지) */
    fun buildSetClause(info: DeleteMarkInfo?, columnName: String, paramName: String = "deleteMark"): DeleteSetClause {
        if (info == null) return DeleteSetClause(fragment = "", params = emptyMap(), physicalDelete = true)
        return when (info.deleteMark) {
            DeleteMarkValue.NULL -> DeleteSetClause(fragment = "$columnName = NULL")
            DeleteMarkValue.NOW -> DeleteSetClause(
                fragment = "$columnName = :$paramName",
                params = mapOf(paramName to LocalDateTime.now())
            )
            DeleteMarkValue.NOT_NULL -> {
                val value = DeleteDefaultValueProvider.provide(info)
                DeleteSetClause(
                    fragment = "$columnName = :$paramName",
                    params = mapOf(paramName to value)
                )
            }
            else -> DeleteSetClause(
                fragment = "$columnName = :$paramName",
                params = mapOf(paramName to info.deleteMarkValue)
            )
        }
    }
}
