package com.hunet.common.stdapi.response

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.core.MethodParameter
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.http.MediaType
import org.springframework.http.converter.StringHttpMessageConverter
import org.springframework.http.server.ServletServerHttpRequest
import org.springframework.http.server.ServletServerHttpResponse
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

@kotlinx.serialization.Serializable
data class InnerPayload(
    @InjectDuration
    var innerDuration: Long? = null,
    val value: String = "X"
) : BasePayload

@kotlinx.serialization.Serializable
data class NestedPayload(
    @InjectDuration
    var nestedDuration: Long? = null,
    val inner: InnerPayload = InnerPayload(),
    val list: List<InnerPayload> = listOf(InnerPayload(), InnerPayload())
) : BasePayload

class ItemsDurationAndDeserializeTest {

    @Test
    fun `items의 current 프로퍼티는 리턴해 주는 list의 요소의 갯수이다`() {
        val content = listOf("A", "B")
        val pageSize = 5
        val page = PageImpl(content, PageRequest.of(2, pageSize, Sort.by("id")), 12)
        val payload = PageListPayload.fromPage(page) { BasePayloadImpl() }
        val resp = StandardResponse.build(payload)
        val items = resp.payload.pageable.items
        assertEquals(content.size.toLong(), items.current, "Items.current는 실제 리턴된 요소의 수가 되어야 한다.")
        assertEquals(page.totalElements, items.total, "Items.total은 실제 총 요소 수와 일치해야 한다.")
    }

    @Test
    fun `deserialize 실패 시 FAILURE와 ErrorPayload를 리턴한다`() {
        val invalidJson = """{\n  \"status\": \"SUCCESS\", \"version\": \"1.0\" }""" // payload 누락
        val resp = StandardResponse.deserialize<ErrorPayload>(invalidJson)
        assertEquals(StandardStatus.FAILURE, resp.status)
        assertTrue(resp.payload.errors.isNotEmpty())
        assertEquals("E_DESERIALIZE_FAIL", resp.payload.errors.first().code)
    }

    @Test
    fun `payload 내부에 존재하는 duration 자동 생성 프로퍼티도 처리가 되어야 한다`() {
        val original = StandardResponse.build(NestedPayload())
        assertNull(original.payload.nestedDuration)
        assertNull(original.payload.inner.innerDuration)
        assertTrue(original.payload.list.all { it.innerDuration == null })

        val mockReq = MockHttpServletRequest().apply {
            setAttribute(RequestTimingFilter.ATTR_START_NANOS, System.nanoTime() - 5_000_000) // ~5ms ago
        }
        val mockResp = MockHttpServletResponse()
        val serverReq = ServletServerHttpRequest(mockReq)
        val serverResp = ServletServerHttpResponse(mockResp)

        val advice = StandardApiResponseAdvice(
            caseEnabled = false,
            defaultCaseName = "IDENTITY",
            queryOverride = false,
            headerOverride = false,
            queryParamName = "case",
            headerName = "X-Response-Case"
        )

        val processed = advice.beforeBodyWrite(
            body = original,
            returnType = MethodParameter(StringHttpMessageConverter::class.java.methods.first(), -1),
            selectedContentType = MediaType.APPLICATION_JSON,
            selectedConverterType = StringHttpMessageConverter::class.java,
            request = serverReq,
            response = serverResp
        ) as StandardResponse<*>

        assertNotNull(processed.duration)
        assertTrue((processed.duration ?: -1) >= 0)
        val nested = processed.payload as NestedPayload
        assertNotNull(nested.nestedDuration, "nestedDuration 필드는 duration이 inject되어야 한다.")
        assertTrue((nested.nestedDuration ?: -1) >= 0)
        assertNotNull(nested.inner.innerDuration, "inner object의 innerDuration 필드도 inject되어야 한다.")
        assertTrue((nested.inner.innerDuration ?: -1) >= 0)
        nested.list.forEach { ip ->
            assertNotNull(ip.innerDuration, "list element 내의 innerDuration 필드도 inject되어야 한다.")
            assertTrue((ip.innerDuration ?: -1) >= 0)
        }
    }
}
