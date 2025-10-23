package com.hunet.common.lib.standard_api_response

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.serialization.Serializable
import kotlinx.serialization.Contextual
import java.time.Instant

@ResponseCase(CaseConvention.SNAKE_CASE)
@Serializable
data class UserInfoPayload(
    val userId: Long = 0,
    val firstName: String = "",
    val lastName: String = "",
    @Contextual val createdAt: Instant = Instant.parse("2025-09-16T00:00:00Z")
): BasePayload

@Serializable
data class SimplePayload(val simpleValue: Int = 1): BasePayload

class SerializationCaseTest {

    private fun buildUser(): StandardResponse<UserInfoPayload> = StandardResponse.build(
        UserInfoPayload(userId = 7, firstName = "용호", lastName = "황")
    )

    @Test
    fun `@ResponseCase 기본 snake_case 적용`() {
        val json = buildUser().toJson()
        assertTrue(json.contains("\"status\":"))
        assertTrue(json.contains("\"version\":"))
        assertTrue(json.contains("\"datetime\":"))
        assertTrue(json.contains("\"duration\":"))
        assertTrue(json.contains("\"payload\":"))
        assertTrue(json.contains("\"user_id\":"))
        assertTrue(json.contains("\"first_name\":"))
        assertTrue(json.contains("\"last_name\":"))
        assertTrue(json.contains("\"created_at\":"))
    }

    @Test
    fun `파라미터로 kebab-case 지정(오버라이드)`() {
        val json = buildUser().toJson(case = CaseConvention.KEBAB_CASE)
        assertTrue(json.contains("\"status\":"))
        assertTrue(json.contains("\"version\":"))
        assertTrue(json.contains("\"datetime\":"))
        assertTrue(json.contains("\"duration\":"))
        assertTrue(json.contains("\"payload\":"))
        assertTrue(json.contains("\"user-id\":"))
        assertTrue(json.contains("\"first-name\":"))
        assertTrue(json.contains("\"last-name\":"))
        assertTrue(json.contains("\"created-at\":"))
    }

    @Test
    fun `PascalCase로 변환`() {
        val json = buildUser().toJson(case = CaseConvention.PASCAL_CASE)
        assertTrue(json.contains("\"Status\":"))
        assertTrue(json.contains("\"Version\":"))
        assertTrue(json.contains("\"Datetime\":"))
        assertTrue(json.contains("\"Duration\":"))
        assertTrue(json.contains("\"Payload\":"))
        assertTrue(json.contains("\"UserId\":"))
        assertTrue(json.contains("\"FirstName\":"))
        assertTrue(json.contains("\"LastName\":"))
        assertTrue(json.contains("\"CreatedAt\":"))
    }

    @Test
    fun `IDENTITY 케이스는 원래 필드명 유지`() {
        val resp = StandardResponse.build(SimplePayload(simpleValue = 42))
        val json = resp.toJson(case = CaseConvention.IDENTITY)
        assertTrue(json.contains("\"status\":"))
        assertTrue(json.contains("\"version\":"))
        assertTrue(json.contains("\"datetime\":"))
        assertTrue(json.contains("\"duration\":"))
        assertTrue(json.contains("\"payload\":"))
        assertTrue(json.contains("\"simpleValue\":42"))
    }

    @Test
    fun `camelCase 명시적 적용`() {
        val json = buildUser().toJson(case = CaseConvention.CAMEL_CASE)
        assertTrue(json.contains("\"status\":"))
        assertTrue(json.contains("\"version\":"))
        assertTrue(json.contains("\"datetime\":"))
        assertTrue(json.contains("\"duration\":"))
        assertTrue(json.contains("\"payload\":"))
        assertTrue(json.contains("\"userId\":"))
        assertTrue(json.contains("\"firstName\":"))
        assertTrue(json.contains("\"lastName\":"))
        assertTrue(json.contains("\"createdAt\":"))
    }

    @Test
    fun `payload 블록 snake_case 변환 확인`() {
        val json = buildUser().toJson(case = CaseConvention.SNAKE_CASE)
        val payloadIdx = json.indexOf("\"payload\"")
        assertTrue(payloadIdx >= 0)
        val fragment = json.substring(payloadIdx)
        assertTrue(fragment.contains("user_id"))
        assertTrue(fragment.contains("first_name"))
    }

    @Test
    fun `숫자 및 acronym 토큰 분할 변환`() {
        @Serializable
        data class AcronymPayload(val apiURLVersion2: String = "v2", val userID: Long = 1): BasePayload

        val resp = StandardResponse.build(AcronymPayload())
        val snake = resp.toJson(case = CaseConvention.SNAKE_CASE)
        assertTrue(snake.contains("api_url_version2"), snake)
        assertTrue(snake.contains("user_id"), snake)
        val pascal = resp.toJson(case = CaseConvention.PASCAL_CASE)
        assertTrue(pascal.contains("ApiUrlVersion2"), pascal)
        assertTrue(pascal.contains("UserId"), pascal)
    }

    @Test
    fun `SCREAMING_SNAKE_CASE 변환`() {
        val json = buildUser().toJson(case = CaseConvention.SCREAMING_SNAKE_CASE)
        assertTrue(json.contains("\"USER_ID\":"), json)
        assertTrue(json.contains("\"FIRST_NAME\":"), json)
        assertTrue(json.contains("\"LAST_NAME\":"), json)
        assertTrue(json.contains("\"CREATED_AT\":"), json)
    }
}
