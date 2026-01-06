@file:Suppress("NonAsciiCharacters", "SpellCheckingInspection")
package com.hunet.common.stdapi.response

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.Serializable
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonAlias

@Serializable
@ResponseCase(CaseConvention.IDENTITY)
private data class AliasPayload(
    @JsonProperty("user_id") val userId: Long,
    @JsonProperty("1st_name") @JsonAlias("FIRST_NAME", "first_name") val firstName: String,
    val normalField: String
): BasePayload

class FieldAliasTest {

    @Test
    fun `alias 적용 후 케이스 변환(kebab) 직렬화`() {
        val resp = StandardResponse.build(AliasPayload(5, "황", "Value"))
        val kebab = resp.toJson(case = CaseConvention.KEBAB_CASE)
        assertTrue(kebab.contains("\"user-id\""), kebab)
        assertTrue(kebab.contains("\"1st-name\""), kebab)
        assertTrue(kebab.contains("\"normal-field\""), kebab)
    }

    @Test
    fun `IDENTITY 케이스에서 alias 그대로 유지 직렬화`() {
        val resp = StandardResponse.build(AliasPayload(7, "용호", "ABC"))
        val json = resp.toJson(case = CaseConvention.IDENTITY)
        assertTrue(json.contains("\"user_id\""), json)
        assertTrue(json.contains("\"1st_name\""), json)
        assertTrue(json.contains("\"normalField\""), json)
    }

    @Test
    fun `다양한 alias 변형 역직렬화 허용`() {
        val json = """
            {
              "status":"SUCCESS",
              "version":"1.0",
              "datetime":"2025-09-16T00:00:00Z",
              "duration":0,
              "payload":{
                "USER-ID":11,
                "1ST_NAME":"용호",
                "normalField":"Z"
              }
            }
        """.trimIndent()
        val resp = StandardResponse.deserialize<AliasPayload>(json)
        assertEquals(11, resp.payload.userId)
        assertEquals("용호", resp.payload.firstName)
        assertEquals("Z", resp.payload.normalField)
    }
}
