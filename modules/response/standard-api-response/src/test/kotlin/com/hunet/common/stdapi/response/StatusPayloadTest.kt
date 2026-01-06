@file:Suppress("NonAsciiCharacters", "SpellCheckingInspection")
package com.hunet.common.stdapi.response

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StatusPayloadTest {
    @Test
    fun `appendix 기본값은 변경 가능해야 한다`() {
        val payload = StatusPayload()
        payload.addAppendix("traceid", "ABC-123")
        assertEquals("ABC-123", payload.appendix["traceid"])
    }

    @Test
    fun `보조 생성자 appendix 복사 및 변경 가능`() {
        val src = mapOf("k1" to 100, "k2" to "v2")
        val payload = StatusPayload.of(code = "WARN", message = "경고", appendix = src)
        // 원본과 값 동일
        assertEquals(100, payload.appendix["k1"])
        assertEquals("v2", payload.appendix["k2"])
        // 변경 가능 여부
        payload.addAppendix("k3", true)
        assertEquals(true, payload.appendix["k3"])
    }

    @Test
    fun `StandardResponse build 후 StatusPayload 내용 검증`() {
        val p = StatusPayload(code = "OK", message = "성공")
        p.addAppendix("reqTime", 42L)
        val resp = StandardResponse.build(p)
        assertEquals(StandardStatus.SUCCESS, resp.status)
        val real = resp.getRealPayload<StatusPayload>()
        assertTrue(real !== null)
        assertEquals("OK", real.code)
        assertEquals("성공", real.message)
        assertEquals(42L, real.appendix["reqTime"])
    }

    @Test
    fun `StatusPayload 역직렬화 appendix 수정 기능`() {
        val json = """
            {
              "status": "SUCCESS",
              "version": "1.0",
              "datetime": "2025-10-01T00:00:00Z",
              "duration": 5,
              "payload": {
                "code": "OK",
                "message": "성공",
                "appendix": {
                  "first": 1,
                  "second": "two"
                }
              }
            }
        """.trimIndent()
        val resp = StandardResponse.deserialize<StatusPayload>(json)
        val payload = resp.payload
        // 기존 값 검증
        assertEquals(1, payload.appendix["first"])
        assertEquals("two", payload.appendix["second"])
        // 변경 가능 여부
        payload.addAppendix("third", 3)
        assertEquals(3, payload.appendix["third"])
    }
}
