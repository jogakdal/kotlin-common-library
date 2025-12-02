@file:Suppress("NonAsciiCharacters", "SpellCheckingInspection")
package com.hunet.common.stdapi.response

import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import java.util.UUID

@Serializable
data class TracePayload(
    val message: String = "ok"
): BasePayload

class TraceIdSerializationTest {

    @Test
    fun `traceid가 생성 시 설정되고 역직렬화로 동일 UUID 값 복원된다`() {
        val tid = UUID.randomUUID().toString()
        val payload = TracePayload("hello")
        val resp = StandardResponse.build(
            payload = payload,
            status = StandardStatus.SUCCESS,
            version = "1.0",
            duration = 10L,
            traceid = tid
        )
        // 객체 상태 확인
        assertEquals(tid, resp.traceid)
        assertEquals("hello", resp.payload.message)

        // 역직렬화 검증: 동일 UUID 값 복원
        val json = """
            {
              "status":"SUCCESS",
              "version":"1.0",
              "datetime":"2025-11-30T00:00:00Z",
              "duration":10,
              "traceid":"$tid",
              "payload": { "message": "hello" }
            }
        """.trimIndent()
        val parsed = StandardResponse.deserialize<TracePayload>(json)
        assertEquals(tid, parsed.traceid)
        assertEquals("hello", parsed.payload.message)
    }

    @Test
    fun `traceid가 입력 JSON에 없을 때 기본값 빈 문자열`() {
        val json = """
            {
              "status":"SUCCESS",
              "version":"1.0",
              "datetime":"2025-11-30T00:00:00Z",
              "duration":1,
              "payload": { "message": "hi" }
            }
        """.trimIndent()

        val parsed = StandardResponse.deserialize<TracePayload>(json)
        assertEquals("", parsed.traceid)
        assertEquals("hi", parsed.payload.message)
    }

    @Test
    fun `traceid가 혼합 케이스 키로 들어와도 정상 복원된다`() {
        val tid = UUID.randomUUID().toString()
        val json = """
            {
              "STATUS":"SUCCESS",
              "VERSION":"1.0",
              "DATE_TIME":"2025-11-30T00:00:00Z",
              "DuRaTiOn":2,
              "TrAcE_Id":"$tid",
              "PAY_load": { "message": "case" }
            }
        """.trimIndent()

        val parsed = StandardResponse.deserialize<TracePayload>(json)
        assertEquals(tid, parsed.traceid)
        assertEquals("case", parsed.payload.message)
    }
}
