@file:Suppress("NonAsciiCharacters", "SpellCheckingInspection")
package com.hunet.common.stdapi.response

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class StandardCallbackResultTest {

    @Test
    fun `Kotlin 콜백 - 기본값(status=SUCCESS, version=1_0) 적용`() {
        val resp = StandardResponse.build(callback = {
            StandardCallbackResult(StatusPayload(code = "OK", message = "완료"))
        })
        assertEquals(StandardStatus.SUCCESS, resp.status)
        assertEquals("1.0", resp.version)
        assertEquals("OK", resp.payload.code)
        assertTrue(resp.duration != null)
        // traceid 기본값은 빈 문자열
        assertEquals("", resp.traceid)
    }

    @Test
    fun `Kotlin 콜백 - status, version 명시 시 오버라이드`() {
        val resp = StandardResponse.build(callback = {
            StandardCallbackResult(
                payload = StatusPayload(code = "PING", message = "OK"),
                status = StandardStatus.FAILURE,
                version = "2.0"
            )
        })
        assertEquals(StandardStatus.FAILURE, resp.status)
        assertEquals("2.0", resp.version)
        val payload = resp.payload
        assertEquals("PING", payload.code)
    }

    @Test
    fun `Kotlin 빌더 - traceid 전달`() {
        val tid = UUID.randomUUID().toString()
        val resp = StandardResponse.build(StatusPayload("OK", "정상"), StandardStatus.SUCCESS, "1.0", 3L, tid)
        assertEquals(tid, resp.traceid)
        assertEquals(3L, resp.duration)
    }

    @Test
    fun `Kotlin 콜백 - ErrorPayload 반환 시 상태 기본은 FAILURE 아님(기본 SUCCESS), 명시하면 적용`() {
        val successWrap = StandardResponse.build(callback = {
            StandardCallbackResult(ErrorPayload().apply { addError("E", "x") })
        })
        assertEquals(StandardStatus.SUCCESS, successWrap.status)
        assertTrue(successWrap.payload.errors.isNotEmpty())

        val failureWrap = StandardResponse.build(callback = {
            StandardCallbackResult(ErrorPayload().apply { addError("E", "x") }, status = StandardStatus.FAILURE)
        })
        assertEquals(StandardStatus.FAILURE, failureWrap.status)
    }
}
