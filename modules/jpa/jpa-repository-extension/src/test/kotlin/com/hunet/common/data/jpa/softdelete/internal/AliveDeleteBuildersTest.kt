@file:Suppress("NonAsciiCharacters", "SpellCheckingInspection")
package com.hunet.common.data.jpa.softdelete.internal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible

// 간단한 DeleteMarkInfo 스텁을 만들어 빌더를 검증한다.
private data class DummyEntity(
    var deletedAt: LocalDateTime? = null,
    var deletedFlag: String? = null,
)

private fun infoFor(
    fieldName: String,
    deleteMark: DeleteMarkValue?,
    aliveMark: DeleteMarkValue?
): DeleteMarkInfo {
    val fieldProp = DummyEntity::class.memberProperties.first { it.name == fieldName }
    fieldProp.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    val kprop = fieldProp as KMutableProperty1<Any, Any?>
    return DeleteMarkInfo(
        field = kprop,
        dbColumnName = fieldName,
        fieldName = fieldName,
        deleteMark = deleteMark,
        aliveMark = aliveMark,
    )
}

class AliveDeleteBuildersTest {
    @Test
    fun `존재하는 삭제 마크가 NULL 일 때, 파라미터 없이 IS NULL 생성`() {
        val info = infoFor("deletedAt", deleteMark = DeleteMarkValue.NOW, aliveMark = DeleteMarkValue.NULL)
        val p = AlivePredicateBuilder.build(info, alias = "e", paramName = "aliveMarkValue")
        assertTrue(p.fragment.contains("e.deletedAt IS NULL"))
        assertTrue(p.params.isEmpty())
    }

    @Test
    fun `존재하는 삭제 마크가 NOT NULL 일 때, 파라미터 없이 IS NOT NULL 생성`() {
        val info = infoFor("deletedAt", deleteMark = DeleteMarkValue.NOW, aliveMark = DeleteMarkValue.NOT_NULL)
        val p = AlivePredicateBuilder.build(info, alias = "e", paramName = "aliveMarkValue")
        assertTrue(p.fragment.contains("e.deletedAt IS NOT NULL"))
        assertTrue(p.params.isEmpty())
    }

    @Test
    fun `존재하는 삭제 마크가 VALUE와 유사할 때 (YES), 파라미터와 함께 동등성 생성`() {
        val info = infoFor("deletedFlag", deleteMark = DeleteMarkValue.NOT_NULL, aliveMark = DeleteMarkValue.YES)
        val p = AlivePredicateBuilder.build(info, alias = "e", paramName = "aliveMarkValue")
        assertTrue(p.fragment.contains("e.deletedFlag = :aliveMarkValue"))
        assertEquals("Y", p.params["aliveMarkValue"]) // YES 의 valueFunc
    }

    @Test
    fun `삭제 시 NULL 설정은 리터럴 NULL 사용`() {
        val info = infoFor("deletedFlag", deleteMark = DeleteMarkValue.NULL, aliveMark = DeleteMarkValue.YES)
        val s = DeletePredicateBuilder.buildSetClause(info, columnName = "`deleted_flag`", paramName = "deleteMark")
        assertEquals("`deleted_flag` = NULL", s.fragment)
        assertTrue(s.params.isEmpty())
        assertTrue(!s.physicalDelete)
    }

    @Test
    fun `삭제 시 현재 시간 바인딩`() {
        val info = infoFor("deletedAt", deleteMark = DeleteMarkValue.NOW, aliveMark = DeleteMarkValue.YES)
        val s = DeletePredicateBuilder.buildSetClause(info, columnName = "`deleted_at`", paramName = "deleteMark")
        assertTrue(s.fragment.contains(":deleteMark"))
        val v = s.params["deleteMark"]
        assertTrue(v is LocalDateTime)
    }

    @Test
    fun `삭제 시 NOT NULL 기본값 바인딩 (타입 기반), deleteMarkValue 미제공 시`() {
        val info = infoFor("deletedFlag", deleteMark = DeleteMarkValue.NOT_NULL, aliveMark = DeleteMarkValue.YES)
        val s = DeletePredicateBuilder.buildSetClause(info, columnName = "`deleted_flag`", paramName = "deleteMark")
        assertTrue(s.fragment.contains(":deleteMark"))
        // String 타입일 때 기본값은 DeleteMarkValue.getDefaultDeleteMarkValue 정책에 따름(현재는 빈 문자열)
        assertEquals("", s.params["deleteMark"])
    }

    @Test
    fun `삭제 시 사용자 정의 열거형 값은 제공된 리터럴 사용`() {
        val info = infoFor("deletedFlag", deleteMark = DeleteMarkValue.YES, aliveMark = DeleteMarkValue.NULL)
        val s = DeletePredicateBuilder.buildSetClause(info, columnName = "`deleted_flag`", paramName = "deleteMark")
        assertEquals("Y", s.params["deleteMark"]) // YES 의 valueFunc
    }
}
