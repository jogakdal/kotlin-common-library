package com.hunet.common.apidoc.enum

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.reflect.KClass

class DescriptiveEnumReplaceDescriptionTest {
    private fun process(desc: String, type: KClass<out DescriptiveEnum>) =
        DescriptiveEnum.replaceDescription(desc, type)

    enum class Color(
        override val value: String,
        override val description: String,
        override val describable: Boolean
    ) : DescriptiveEnum {
        RED("R", "빨강", true),
        BLUE("B", "파랑", false),
        GREEN("G", "녹색", true)
    }

    enum class Empty(
        override val value: String,
        override val description: String,
        override val describable: Boolean
    ) : DescriptiveEnum {
        NONE("N", "없음", false)
    }

    @Test
    fun `마커 없는 문자열은 변경되지 않는다`() {
        val original = "Color list"
        assertEquals(original, process(original, Color::class))
    }

    @Test
    fun `단일 마커가 단일 describable enum 항목을 치환한다`() {
        val original = "사용 가능한 색상: {$}DESCRIPTION{$}" // RED, GREEN 이 describable
        val expected = "사용 가능한 색상: 'R': 빨강, 'G': 녹색"
        assertEquals(expected, process(original, Color::class))
    }

    @Test
    fun `여러 마커는 각각 치환된다`() {
        val original = "첫번째={$}DESCRIPTION{$}|두번째={$}DESCRIPTION{$}"
        val expectedPart = "'R': 빨강, 'G': 녹색"
        assertEquals("첫번째=${expectedPart}|두번째=${expectedPart}", process(original, Color::class))
    }

    @Test
    fun `모든 enum이 describable=false 인 경우 빈 문자열로 치환된다`() {
        val original = "비어 있음: {$}DESCRIPTION{$}"
        val expected = "비어 있음: "
        assertEquals(expected, process(original, Empty::class))
    }
}
