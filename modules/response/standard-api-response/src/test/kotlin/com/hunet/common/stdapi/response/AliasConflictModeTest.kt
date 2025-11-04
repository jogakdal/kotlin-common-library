package com.hunet.common.stdapi.response

import com.fasterxml.jackson.annotation.JsonProperty
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private data class ConflictPayload(
    @JsonProperty("user_id") val userId: Long,
    @JsonProperty("userId") val userIdDup: Long
): BasePayload

class AliasConflictModeTest {
    @AfterTest
    fun tearDown() {
        clearAliasCaches()
        AliasConflictConfig.mode = AliasConflictMode.WARN
        AliasConflictConfig.resolution = AliasConflictResolution.FIRST_WIN
    }

    @Test
    fun `ERROR 모드에서 canonical 충돌 시 예외 발생`() {
        clearAliasCaches()
        AliasConflictConfig.mode = AliasConflictMode.ERROR
        assertFailsWith<IllegalStateException> {
            StandardResponse.build(ConflictPayload(1, 2)).toJson()
        }
    }

    @Test
    fun `WARN 모드에서 충돌은 예외 없이 진행`() {
        clearAliasCaches()
        AliasConflictConfig.mode = AliasConflictMode.WARN
        val json = StandardResponse.build(ConflictPayload(3, 4)).toJson()
        assertTrue(json.contains("user_id"), json)
    }
}
