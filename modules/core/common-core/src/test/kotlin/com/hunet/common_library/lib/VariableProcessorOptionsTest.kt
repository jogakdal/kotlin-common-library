package com.hunet.common_library.lib

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

private class TestRegistry : VariableResolverRegistry {
    override val resolvers = mapOf(
        "name" to { args: List<Any?> -> (args.firstOrNull() ?: "NO_NAME").toString() },
        "upper" to { args: List<Any?> -> args.firstOrNull()?.toString()?.uppercase().orEmpty() },
        "sum" to { args: List<Any?> -> args.filterIsInstance<Number>().sumOf { it.toLong() } }
    )
}

class VariableProcessorOptionsTest {
    private val processor = VariableProcessor(listOf(TestRegistry()))

    @Test
    fun `기본값 문법 - 파라미터 미제공 시 fallback 사용`() {
        val r = processor.process(
            template = "User=%{name|guest}%",
            options = VariableProcessor.Options(enableDefaultValue = true)
        )
        assertEquals("User=guest", r)
    }

    @Test
    fun `기본값 문법 - 파라미터 제공 시 기본값 무시`() {
        val r = processor.process(
            template = "User=%{name|guest}%",
            options = VariableProcessor.Options(enableDefaultValue = true),
            params = arrayOf("name" to "Hwang Yongho")
        )
        assertEquals("User=Hwang Yongho", r)
    }

    @Test
    fun `미등록 토큰 ignoreMissing=true 시 원문 유지`() {
        val r = processor.process(
            template = "X=%{unknown}% Y=%{sum}%",
            options = VariableProcessor.Options(ignoreMissing = true),
            params = arrayOf("sum" to listOf(1,2,3))
        )
        assertEquals("X=%{unknown}% Y=6", r)
    }

    @Test
    fun `미등록 토큰 기본값 존재 시 기본값 적용`() {
        val r = processor.process(
            template = "X=%{unknown|def}%",
            options = VariableProcessor.Options(ignoreMissing = true, enableDefaultValue = true)
        )
        assertEquals("X=def", r)
    }

    @Test
    fun `이스케이프 문자로 기본값 구분자 문자 포함`() {
        val r = processor.process(
            template = "Val=%{name|foo\\|bar}%",
            options = VariableProcessor.Options(enableDefaultValue = true)
        )
        assertEquals("Val=foo|bar", r)
    }

    @Test
    fun `기본값 + Resolver 미존재 + ignoreMissing=false 시 예외`() {
        assertThrows(IllegalArgumentException::class.java) {
            processor.process(
                template = "Val=%{xxx|fallback}%",
                options = VariableProcessor.Options(enableDefaultValue = true, ignoreMissing = false)
            )
        }
    }
}
