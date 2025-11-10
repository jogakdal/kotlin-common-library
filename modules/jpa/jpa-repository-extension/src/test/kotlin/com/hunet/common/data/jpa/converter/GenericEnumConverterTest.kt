package com.hunet.common.data.jpa.converter

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

// 최상위 enum 정의들 (reflection 접근 문제 방지)
enum class TestColor(val value: String) {
    RED("R"), GREEN("G"), BLUE("B");
    companion object { fun fromValue(value: String): TestColor = entries.first { it.value == value } }
    override fun toString(): String = value
}

enum class TestCaseInsensitive(val value: String) {
    FOO("foo"), BAR("bar");
    companion object {
        fun fromValue(value: String): TestCaseInsensitive =
            entries.firstOrNull { it.value.equals(value, ignoreCase = true) }
                ?: throw IllegalArgumentException("No match for $value")
    }
}

enum class TestNumbering(val value: Int) {
    ONE(1), TWO(2), THREE(3);
    companion object { fun fromValue(value: Int): TestNumbering = entries.first { it.value == value } }
}

enum class TestWithoutValue { A, B }

enum class TestNoCompanion(val value: String) { A("A"), B("B") }

enum class TestBadFromValue(val value: String) {
    A("A"), B("B");
    companion object { fun somethingElse(v: String) = A }
}

// Fallback NONE 동작을 검증하기 위한 enum
enum class TestFallback(val value: String) {
    NONE(""), ALPHA("A"), BETA("B");
    companion object {
        fun fromValue(value: String): TestFallback =
            entries.firstOrNull { it.value.equals(value, ignoreCase = true) } ?: NONE
    }
}

/**
 * GenericEnumConverter 단위 테스트.
 */
class GenericEnumConverterTest {
    @Test fun `정상 enum을 DB 컬럼으로 변환`() {
        val converter = object : GenericEnumConverter<TestColor, String>(TestColor::class.java) {}
        assertEquals("R", converter.convertToDatabaseColumn(TestColor.RED))
    }
    @Test fun `DB 컬럼 값을 enum으로 변환`() {
        val converter = object : GenericEnumConverter<TestColor, String>(TestColor::class.java) {}
        assertEquals(TestColor.GREEN, converter.convertToEntityAttribute("G"))
    }
    @Test fun `attribute가 null이면 DB 컬럼 null 반환`() {
        val converter = object : GenericEnumConverter<TestColor, String>(TestColor::class.java) {}
        assertNull(converter.convertToDatabaseColumn(null))
    }
    @Test fun `DB 데이터가 null이면 enum null 반환`() {
        val converter = object : GenericEnumConverter<TestColor, String>(TestColor::class.java) {}
        assertNull(converter.convertToEntityAttribute(null))
    }
    @Test fun `value 속성 없는 enum 변환시 IllegalStateException`() {
        val converter = object : GenericEnumConverter<TestWithoutValue, String>(TestWithoutValue::class.java) {}
        val ex = assertThrows(IllegalStateException::class.java) { converter.convertToDatabaseColumn(TestWithoutValue.A) }
        assertTrue(ex.message!!.contains("TestWithoutValue"))
    }
    @Test fun `companion object 없는 enum DB데이터 변환시 IllegalStateException`() {
        val converter = object : GenericEnumConverter<TestNoCompanion, String>(TestNoCompanion::class.java) {}
        val ex = assertThrows(IllegalStateException::class.java) { converter.convertToEntityAttribute("A") }
        assertTrue(ex.message!!.contains("TestNoCompanion"))
    }
    @Test fun `companion에 fromValue 없는 enum DB데이터 변환시 IllegalStateException`() {
        val converter = object : GenericEnumConverter<TestBadFromValue, String>(TestBadFromValue::class.java) {}
        val ex = assertThrows(IllegalStateException::class.java) { converter.convertToEntityAttribute("A") }
        assertTrue(ex.message!!.contains("TestBadFromValue"))
    }
    @Test fun `fromValue가 대소문자 무시 로직을 포함한 enum 변환`() {
        val converter = object : GenericEnumConverter<TestCaseInsensitive, String>(TestCaseInsensitive::class.java) {}
        assertEquals(TestCaseInsensitive.FOO, converter.convertToEntityAttribute("FOO"))
        assertEquals(TestCaseInsensitive.BAR, converter.convertToEntityAttribute("bar"))
    }
    @Test fun `Int 값을 갖는 enum 변환`() {
        val converter = object : GenericEnumConverter<TestNumbering, Int>(TestNumbering::class.java, Int::class.java) {}
        assertEquals(2, converter.convertToDatabaseColumn(TestNumbering.TWO))
        assertEquals(TestNumbering.THREE, converter.convertToEntityAttribute(3))
    }
    @Test fun `잘못된 valueType 지정으로 fromValue 시그니처 미스매치 발생`() {
        val converter = object : GenericEnumConverter<TestColor, Int>(TestColor::class.java, Int::class.java) {}
        val ex = assertThrows(IllegalStateException::class.java) { converter.convertToEntityAttribute(1) }
        assertTrue(ex.message!!.contains("fromValue("))
    }
    @Test fun `알 수 없는 값이 Fallback NONE으로 매핑`() {
        val converter = object : GenericEnumConverter<TestFallback, String>(TestFallback::class.java) {}
        assertEquals(TestFallback.NONE, converter.convertToEntityAttribute("UNKNOWN"))
        assertEquals("A", converter.convertToDatabaseColumn(TestFallback.ALPHA))
    }
}
