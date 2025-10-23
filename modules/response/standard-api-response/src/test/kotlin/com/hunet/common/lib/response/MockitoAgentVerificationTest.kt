package com.hunet.common.lib.response

import org.junit.jupiter.api.Test
import org.mockito.Mockito
import kotlin.test.assertEquals

// final 클래스 mocking 으로 inline mock-maker 동작을 트리거하여 javaagent 설정 검증
class FinalClass { fun greet() = "hi" }

class MockitoAgentVerificationTest {
    @Test
    fun mockFinalClass() {
        val mock = Mockito.mock(FinalClass::class.java)
        Mockito.`when`(mock.greet()).thenReturn("hello")
        assertEquals("hello", mock.greet())
    }
}

