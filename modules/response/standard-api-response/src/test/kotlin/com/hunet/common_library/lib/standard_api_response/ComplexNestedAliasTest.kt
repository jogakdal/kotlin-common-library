package com.hunet.common_library.lib.standard_api_response

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonProperty
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * 복합 중첩 구조: List<Map<String, NestedChild>> 내부 Payload alias 변형 역직렬화 테스트
 */
private data class NestedChild(
    @JsonProperty("child_id") @JsonAlias("childId", "CHILD-ID") val childId: Long,
    @JsonProperty("child_label") @JsonAlias("childLabel", "child-label") val label: String
) : BasePayload

private data class WrapperPayload(
    @JsonProperty("entries") val entries: List<Map<String, NestedChild>>
) : BasePayload

class ComplexNestedAliasTest {
    @Test
    fun `중첩 리스트-맵 payload alias 변형 역직렬화`() {
        val json = """
            {
              "status":"SUCCESS",
              "version":"1.0",
              "datetime":"${Instant.now()}",
              "duration":0,
              "payload":{
                "entries":[
                  {
                    "k1": { "CHILD-ID":101, "child-label":"AAA" },
                    "k2": { "childId":102, "childLabel":"BBB" }
                  },
                  {
                    "k3": { "child_id":103, "child_label":"CCC" }
                  }
                ]
              }
            }
        """.trimIndent()
        val resp = StandardResponse.deserialize<WrapperPayload>(json)
        val list = resp.payload.entries
        assertEquals(3, list.flatMap { it.keys }.size)
        assertEquals(101, list[0]["k1"]?.childId)
        assertEquals("AAA", list[0]["k1"]?.label)
        assertEquals(102, list[0]["k2"]?.childId)
        assertEquals("BBB", list[0]["k2"]?.label)
        assertEquals(103, list[1]["k3"]?.childId)
        assertEquals("CCC", list[1]["k3"]?.label)
    }
}
