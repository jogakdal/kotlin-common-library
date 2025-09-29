package com.hunet.common_library.lib.standard_api_response

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonProperty
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.time.Instant

// 자식 Payload: Map 값 타입
data class ChildPayload(
    @JsonProperty("child_name")
    val childName: String? = null,

    @JsonProperty("child_age")
    @JsonAlias("age_of_child", "age")
    val childAge: Int? = null
) : BasePayload

// 부모 Payload: Map<String, ChildPayload>
data class ParentPayload(
    @JsonProperty("items_map")
    val items: Map<String, ChildPayload> = emptyMap()
) : BasePayload

class MapAliasTest {

    @Test
    fun `맵 값 payload alias 및 케이스 변환 직렬화`() {
        val payload = ParentPayload(items = mapOf("a" to ChildPayload(childName = "황용호", childAge = 20)))
        val json = StandardResponse.build(payload).toJson(case = CaseConvention.SNAKE_CASE, pretty = false)
        listOf("items_map", "child_name", "child_age").forEach { key ->
            assert(json.contains("\"$key\"")) { "Expected key $key in json: $json" }
        }
    }

    @Test
    fun `맵 값 payload alias 변형 역직렬화`() {
        val json = """
            {
              "status":"SUCCESS",
              "version":"1.0",
              "datetime":"${Instant.now()}",
              "duration":0,
              "payload":{
                "items-map":{
                  "a":{
                    "child-name":"황용호",
                    "age":20
                  }
                }
              }
            }
        """.trimIndent()
        val json2 = """
            {
              "status":"SUCCESS",
              "version":"1.0",
              "datetime":"${Instant.now()}",
              "duration":0,
              "payload":{
                "items-map":{
                  "a":{
                    "child-name":"황용호",
                    "child-age":20
                  }
                }
              }
            }
        """.trimIndent()
        val json3 = """
            {
              "status":"SUCCESS",
              "version":"1.0",
              "datetime":"${Instant.now()}",
              "duration":0,
              "payload":{
                "items-map":{
                  "a":{
                    "child-name":"황용호",
                    "age-of-child":20
                  }
                }
              }
            }
        """.trimIndent()
        var resp = StandardResponse.deserialize<ParentPayload>(json)
        val child = resp.payload.items["a"]
        assertNotNull(child)
        assertEquals("황용호", child!!.childName)
        assertEquals(20, child.childAge)

        resp = StandardResponse.deserialize<ParentPayload>(json2)
        assertEquals(20, resp.payload.items["a"]?.childAge)

        resp = StandardResponse.deserialize<ParentPayload>(json3)
        assertEquals(20, resp.payload.items["a"]?.childAge)
    }
}
