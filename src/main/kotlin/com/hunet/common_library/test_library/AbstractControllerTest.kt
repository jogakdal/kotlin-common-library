package com.hunet.common_library.test_library

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.hamcrest.Matchers
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.restdocs.RestDocumentationContextProvider
import org.springframework.restdocs.RestDocumentationExtension
import org.springframework.restdocs.mockmvc.MockMvcRestDocumentation
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.result.MockMvcResultMatchers
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext

@ActiveProfiles("local")
@SpringBootTest
@ExtendWith(RestDocumentationExtension::class, SpringExtension::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
abstract class AbstractControllerTest {
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var dataFeed: DataFeed

    val objectMapper = jacksonObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
    val prettyPrinter = objectMapper.writerWithDefaultPrettyPrinter()

    @BeforeEach
    internal fun setUp(context: WebApplicationContext, restDocumentation: RestDocumentationContextProvider) {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply<DefaultMockMvcBuilder>(MockMvcRestDocumentation.documentationConfiguration(restDocumentation))
                .build()
    }

    fun ResultActions.checkData(key: String, data: Any) =
            andExpect(MockMvcResultMatchers.jsonPath("$.payload${when {
                key.isEmpty() -> { "" }
                key[0] == '[' -> { key }
                else -> { ".${key}" }
            }}", Matchers.`is`(data)))

    fun ResultActions.checkSize(key: String, size: Int) = andExpect(
        MockMvcResultMatchers.jsonPath(
            "$.payload${if (key.isEmpty()) "" else ".${key}"}",
            Matchers.hasSize<Int>(size)
        )
    )

    fun prettyPrint(json: String) {
        println(prettyPrinter.writeValueAsString(objectMapper.readTree(json)))
    }

    fun dtoToParam(value: Any) = objectMapper.writeValueAsString(value)

    val ResultActions.respJson: String
        get() = this.andReturn().response.contentAsString
}
