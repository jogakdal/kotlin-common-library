package com.hunet.common_library.lib.standard_api_response

import com.fasterxml.jackson.annotation.JsonProperty
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private data class AmbiguousPayload(
    @JsonProperty("user_id") val snake: Long? = null,
    @JsonProperty("userId") val camel: Long? = null
): BasePayload

class AliasConflictResolutionTest {
    @AfterTest
    fun cleanup() {
        clearAliasCaches()
        AliasConflictConfig.mode = AliasConflictMode.WARN
        AliasConflictConfig.resolution = AliasConflictResolution.FIRST_WIN
    }

    private fun wrap(fragment: String) = "{" +
        "\"status\":\"SUCCESS\"," +
        "\"version\":\"1.0\"," +
        "\"datetime\":\"2025-01-01T00:00:00Z\"," +
        "\"duration\":0," +
        "\"payload\":$fragment}".trimIndent()

    @Test
    fun firstWin_userId_input_maps_to_snake_property() {
        AliasConflictConfig.mode = AliasConflictMode.WARN
        AliasConflictConfig.resolution = AliasConflictResolution.FIRST_WIN
        clearAliasCaches()
        val json = wrap("{\"userId\":10}")
        val resp = StandardResponse.deserialize<AmbiguousPayload>(json)
        assertEquals(10, resp.payload.snake)
        assertNull(resp.payload.camel)
    }

    @Test
    fun bestMatch_userId_input_maps_to_camel_property() {
        AliasConflictConfig.mode = AliasConflictMode.WARN
        AliasConflictConfig.resolution = AliasConflictResolution.BEST_MATCH
        clearAliasCaches()
        val json = wrap("{\"userId\":15}")
        val resp = StandardResponse.deserialize<AmbiguousPayload>(json)
        assertEquals(15, resp.payload.camel)
        assertNull(resp.payload.snake)
    }

    @Test
    fun bestMatch_user_id_input_prefers_snake_property() {
        AliasConflictConfig.mode = AliasConflictMode.WARN
        AliasConflictConfig.resolution = AliasConflictResolution.BEST_MATCH
        clearAliasCaches()
        val json = wrap("{\"user_id\":25}")
        val resp = StandardResponse.deserialize<AmbiguousPayload>(json)
        assertEquals(25, resp.payload.snake)
        assertNull(resp.payload.camel)
    }
}
