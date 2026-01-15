@file:Suppress("NonAsciiCharacters", "SpellCheckingInspection")
package com.hunet.common.data.jpa.softdelete.test

import com.hunet.common.data.jpa.sequence.GenerateSequentialCode
import com.hunet.common.data.jpa.sequence.SequenceGenerator
import com.hunet.common.data.jpa.sequence.applySequentialCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SequenceGeneratorDirectTest {
    @Test
    fun `시퀀스 코드가 SeqEntity 에 정상 생성된다`() {
        // given
        val seqGen = object : SequenceGenerator {
            private var counter = 0L
            override fun generateKey(prefix: String, entity: Any?) = prefix + (++counter)
        }
        val entity = SeqEntity() // code 초기값 null
        // when
        applySequentialCode(entity, seqGen)
        // then
        assertNotNull(entity.code, "생성된 코드가 null 이면 안 된다")
        assertTrue(entity.code!!.startsWith("PX-"), "생성된 코드는 'PX-'로 시작해야 한다")
    }

    @Test
    fun `val 로만 선언된 엔티티 필드에도 시퀀스 코드가 생성된다`() {
        // given: val 로 선언되어 Kotlin mutable property 경로에서 제외됨 → direct field 경로 필수
        class FieldOnlySeqEntity(
            @field:GenerateSequentialCode(prefixExpression = "'FX-'")
            val code: String? = null
        )
        val seqGen = object : SequenceGenerator {
            private var counter = 0L
            override fun generateKey(prefix: String, entity: Any?) = prefix + (++counter)
        }
        val entity = FieldOnlySeqEntity()
        // when
        applySequentialCode(entity, seqGen)
        // then
        val field = entity.javaClass.getDeclaredField("code").apply { isAccessible = true }
        val value = field.get(entity) as? String
        assertNotNull(value, "생성된 코드가 null 이면 안 된다")
        assertTrue(value!!.startsWith("FX-"), "생성된 코드는 'FX-'로 시작해야 한다")
    }

    @Test
    fun `property 와 field 어노테이션이 모두 있을 때 property prefix 가 우선한다`() {
        // given: property-site + field-site 서로 다른 prefix -> property 경로가 먼저 처리되어야 함
        class CombinedSeqEntity(
            @GenerateSequentialCode(prefixExpression = "'CX-'")
            @field:GenerateSequentialCode(prefixExpression = "'IGNORED-'")
            var code: String? = null
        )
        val seqGen = object : SequenceGenerator {
            private var counter = 0L
            override fun generateKey(prefix: String, entity: Any?) = prefix + (++counter)
        }
        val entity = CombinedSeqEntity()
        // when
        applySequentialCode(entity, seqGen)
        // then
        assertNotNull(entity.code, "생성된 코드가 null 이면 안 된다")
        assertTrue(entity.code!!.startsWith("CX-"), "property prefix가 우선하여 'CX-'로 시작해야 한다")
    }

    @Test
    fun `이미 코드가 생성된 엔티티는 두 번째 호출에서 재생성되지 않는다`() {
        // given
        val seqGen = object : SequenceGenerator {
            private var counter = 0L
            override fun generateKey(prefix: String, entity: Any?) = prefix + (++counter)
        }
        val entity = SeqEntity()
        // when 첫 번째
        applySequentialCode(entity, seqGen)
        val first = entity.code
        // when 두 번째 (이미 값 존재 → 재생성 금지)
        applySequentialCode(entity, seqGen)
        val second = entity.code
        // then
        assertNotNull(first, "첫 번째 생성 코드가 null이면 안 된다")
        assertTrue(first!!.startsWith("PX-"), "첫 번째 코드가 'PX-'로 시작해야 한다")
        assertTrue(second!!.startsWith("PX-"), "두 번째 코드도 'PX-'로 시작해야 한다")
        assertEquals(first, second, "두 번째 호출에서 코드가 변경되면 안 된다")
    }
}
