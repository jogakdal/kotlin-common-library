package com.hunet.common_library.lib.std_api_documentation

import com.epages.restdocs.apispec.ParameterDescriptorWithType
import com.epages.restdocs.apispec.SimpleType
import com.hunet.common_library.test_support.AbstractControllerTest
import io.swagger.v3.oas.annotations.media.Schema
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springdoc.core.annotations.ParameterObject
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration
import org.springframework.test.context.TestPropertySource
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get as mvcGet
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.util.LinkedMultiValueMap
import org.springframework.util.MultiValueMap
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.reflect.full.memberProperties
import kotlin.test.assertContains

@SpringBootApplication(
    exclude = [
        DataSourceAutoConfiguration::class,
        HibernateJpaAutoConfiguration::class,
        DataSourceTransactionManagerAutoConfiguration::class
    ]
)
open class DocTestApplication

@Schema(description = "Test Document Specification")
data class SearchQuery(
    @Schema(description = "키워드") val keyword: String? = null,
    @Schema(description = "활성 여부") val enabled: Boolean? = null
)

@RestController
class DocDemoController {
    @GetMapping("/doc-demo")
    fun demo(
        @ParameterObject request: SearchQuery,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): Map<String, Any> = mapOf(
        "keyword" to (request.keyword ?: ""),
        "enabled" to (request.enabled ?: false),
        "page" to page,
        "size" to size
    )
}

@TestPropertySource(properties = [
    // 스니펫 생성 시 부가 변환기 개입 최소화
    "spring.mvc.log-request-details=true"
])
class DocumentationHelperIntegrationTest : AbstractControllerTest() {
    override fun dtoToQueryParams(obj: Any): MultiValueMap<String, String> {
        val map = LinkedMultiValueMap<String, String>()
        obj::class.memberProperties.forEach { p ->
            val v = p.getter.call(obj) ?: return@forEach
            when (v) {
                is Iterable<*> -> v.filterNotNull().forEach { map.add(p.name, it.toString()) }
                is Array<*> -> v.filterNotNull().forEach { map.add(p.name, it.toString()) }
                else -> map.add(p.name, v.toString())
            }
        }
        return map
    }

    @Test
    fun `queryObject와 queryParameters 병합이 openapi3 스니펫에 반영된다`() {
        val identifier = "doc-demo-snippet"
        val queryParam = SearchQuery(keyword = "테스트", enabled = true)

        mockMvc.perform(
            mvcGet("/doc-demo")
                .queryParams(dtoToQueryParams(queryParam))
                .queryParam("page", "1")
                .queryParam("size", "10")
        )
            .andExpect(status().isOk)
            .andDo(
                buildDocument(
                    identifier = identifier,
                    tag = "DocDemo",
                    summary = "문서화 헬퍼 통합 테스트",
                    description = "queryObject와 명시적 queryParameters 병합 확인",
                    // 명시적으로 page, size를 정의
                    queryParameters = listOf(
                        ParameterDescriptorWithType("page").type(SimpleType.NUMBER).description("페이지 번호"),
                        ParameterDescriptorWithType("size").type(SimpleType.NUMBER).description("페이지 크기"),
                    ),
                    queryObject = queryParam
                )
            )

        var snippet = Paths.get("build", "generated-snippets", identifier, "http-request.adoc")
        assertTrue(Files.exists(snippet), "http-request.adoc snippet not found: $snippet")
        var json = Files.readString(snippet)

        assertContains(json, "page=")
        assertContains(json, "size=")
        assertContains(json, "keyword=")
        assertContains(json, "enabled=")

        snippet = Paths.get("build", "generated-snippets", identifier, "http-response.adoc")
        assertTrue(Files.exists(snippet), "http-response.adoc snippet not found: $snippet")
        json = Files.readString(snippet)

        assertContains(json, "\"keyword\" : \"테스트\"")
        assertContains(json, "\"enabled\" : true")
        assertContains(json, "\"page\" : 1")
        assertContains(json, "\"size\" : 10")
    }
}
