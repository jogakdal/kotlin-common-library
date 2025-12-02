package com.hunet.common.test.support

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.hamcrest.Matchers
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.restdocs.AutoConfigureRestDocs
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.restdocs.RestDocumentationExtension
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.result.MockMvcResultMatchers
import com.hunet.common.support.DataFeed
import org.springframework.util.LinkedMultiValueMap
import org.springframework.util.MultiValueMap
import kotlin.reflect.full.memberProperties

@Suppress("SpringJavaInjectionPointsAutowiringInspection")
@ActiveProfiles("local")
@SpringBootTest
@ExtendWith(RestDocumentationExtension::class, SpringExtension::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@AutoConfigureMockMvc(addFilters = true)
@AutoConfigureRestDocs
abstract class AbstractControllerTest {
    @Autowired lateinit var mockMvc: MockMvc
    @Autowired(required = false) var dataFeed: DataFeed? = null

    val objectMapper = jacksonObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
    val prettyPrinter = objectMapper.writerWithDefaultPrettyPrinter()

    fun ResultActions.checkData(key: String, data: Any) =
        andExpect(
            MockMvcResultMatchers.jsonPath(
                "$.payload${when { key.isEmpty() -> ""; key[0] == '[' -> key; else -> ".${key}" }}",
                Matchers.`is`(data)
            )
        )

    fun ResultActions.checkSize(key: String, size: Int) = andExpect(
        MockMvcResultMatchers.jsonPath(
            "$.payload${if (key.isEmpty()) "" else ".${key}"}",
            Matchers.hasSize<Int>(size)
        )
    )

    fun prettyPrint(json: String) {
        println(prettyPrinter.writeValueAsString(objectMapper.readTree(json)))
    }

    open fun dtoToParam(value: Any) = objectMapper.writeValueAsString(value)
    open fun dtoToQueryParams(obj: Any): MultiValueMap<String, String> {
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

    val ResultActions.respJson: String
        get() = this.andReturn().response.contentAsString
}
