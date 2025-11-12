@file:Suppress("NonAsciiCharacters", "SpellCheckingInspection")
package com.hunet.common.data.jpa.sequence

import com.hunet.common.util.getAnnotation
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.reflect.full.memberProperties

class SequenceGeneratorMigrationTest {
    private val testSequenceGenerator = object : SequenceGenerator {
        override fun generateKey(prefix: String, entity: Any?): Any? {
            return "${prefix}001"
        }
    }

    data class TestEntity(
        @GenerateSequentialCode(prefixExpression = "'TEST_'")
        var code: String = "",

        @GenerateSequentialCode(prefixExpression = "'ORDER_'")
        var orderNumber: String = "",

        var name: String = "test"
    )

    @Test
    fun `어노테이션 검색 유틸 정상 동작`() {
        val codeProperty = TestEntity::class.memberProperties.first { it.name == "code" }
        val nameProperty = TestEntity::class.memberProperties.first { it.name == "name" }

        val codeAnnotation = codeProperty.getAnnotation<GenerateSequentialCode>()
        val nameAnnotation = nameProperty.getAnnotation<GenerateSequentialCode>()

        assertNotNull(codeAnnotation)
        assertEquals("'TEST_'", codeAnnotation?.prefixExpression)

        assertNull(nameAnnotation)
    }

    @Test
    fun `시퀀스 코드 적용 시 어노테이션 기반 필터링 정상 동작`() {
        val entity = TestEntity()

        applySequentialCode(entity, testSequenceGenerator)

        assertEquals("TEST_001", entity.code)
        assertEquals("ORDER_001", entity.orderNumber)
        assertEquals("test", entity.name, "annotation이 없으면 변경되지 않아야 한다.")
    }

    @Test
    fun `이미 값 있는 필드는 변경되지 않음`() {
        val entity = TestEntity(code = "EXISTING", orderNumber = "", name = "test")

        applySequentialCode(entity, testSequenceGenerator)

        assertEquals("EXISTING", entity.code, "이미 값이 있는 필드는 변경되지 않아야 한다.")
        assertEquals("ORDER_001", entity.orderNumber, "빈 값인 필드는 생성되어야 한다.")
    }

    @Test
    fun `프리픽스 표현식 정상 처리`() {
        val entity = TestEntity()

        applySequentialCode(entity, testSequenceGenerator)

        assertEquals("TEST_001", entity.code)
        assertEquals("ORDER_001", entity.orderNumber)
    }

    class JavaFieldTestEntity {
        @JvmField
        @GenerateSequentialCode(prefixExpression = "'JAVA_'")
        var javaFieldCode: String = ""

        var normalField: String = ""
    }

    @Test
    fun `자바 필드 어노테이션 정상 처리`() {
        val entity = JavaFieldTestEntity()

        applySequentialCode(entity, testSequenceGenerator)

        assertEquals("JAVA_001", entity.javaFieldCode)
        assertEquals("", entity.normalField, "annotation이 없으면 변경되지 않아야 한다.")
    }

    @Test
    fun `시퀀스 코드 생성 어노테이션 속성 정상 추출`() {
        val codeProperty = TestEntity::class.memberProperties.first { it.name == "code" }
        val annotation = codeProperty.getAnnotation<GenerateSequentialCode>()

        assertNotNull(annotation)
        assertEquals("'TEST_'", annotation!!.prefixExpression)
        assertEquals(DefaultPrefixProvider::class, annotation.prefixProvider)
    }
}
