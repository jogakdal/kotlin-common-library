package com.hunet.common.stdapi.response

import com.hunet.common.test.support.AbstractControllerTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration
import org.springframework.test.context.TestPropertySource
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.Contextual
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get as mvcGet
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootApplication(
    exclude = [
        DataSourceAutoConfiguration::class,
        HibernateJpaAutoConfiguration::class,
        DataSourceTransactionManagerAutoConfiguration::class
    ]
)
class TestApplication

@ResponseCase(CaseConvention.SNAKE_CASE)
@Serializable
data class DemoPayload(
    val userId: Long,
    val firstName: String,
    val lastName: String,
    @Contextual val createdAt: Instant = Instant.now()
): BasePayload

@RestController
class DemoController {
    @GetMapping("/demo")
    fun demo(): StandardResponse<DemoPayload> = StandardResponse.build(
        DemoPayload(10,"용호","황")
    )
}

@TestPropertySource(properties = [
    "standard-api-response.case.default=IDENTITY",
    "standard-api-response.auto-duration-calculation.active=true"
])
class ApiResponseIntegrationTest : AbstractControllerTest() {

    @Test
    fun `기본 snake_case 적용`() {
        val json = mockMvc.perform(mvcGet("/demo"))
            .andExpect(status().isOk)
            .andReturn().response.contentAsString
        assertTrue(json.contains("\"user_id\""), json)
        assertTrue(json.contains("\"first_name\""), json)
        assertTrue(json.contains("\"last_name\""), json)
        assertTrue(Regex("\"duration\"\\s*:\\s*\\d+").containsMatchIn(json), json)
    }

    @Test
    fun `query parameter로 kebab-case 지정`() {
        val json = mockMvc.perform(mvcGet("/demo?case=KEBAB_CASE"))
            .andExpect(status().isOk)
            .andReturn().response.contentAsString
        assertTrue(json.contains("\"user-id\""), json)
        assertTrue(json.contains("\"first-name\""), json)
    }

    @Test
    fun `헤더로 PascalCase 지정`() {
        val json = mockMvc.perform(mvcGet("/demo").header("X-Response-Case","PASCAL_CASE"))
            .andExpect(status().isOk)
            .andReturn().response.contentAsString
        assertTrue(json.contains("\"UserId\""), json)
        assertTrue(json.contains("\"FirstName\""), json)
    }

    @Test
    fun `identity 요청 camelCase 유지`() {
        val json = mockMvc.perform(mvcGet("/demo?case=identity"))
            .andExpect(status().isOk)
            .andReturn().response.contentAsString
        assertTrue(json.contains("\"userId\""), json)
        assertTrue(json.contains("\"firstName\""), json)
    }
}
