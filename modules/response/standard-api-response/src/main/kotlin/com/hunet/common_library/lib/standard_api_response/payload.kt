package com.hunet.common_library.lib.standard_api_response

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import io.swagger.v3.oas.annotations.media.Schema
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import org.springframework.data.domain.Page

interface BasePayload {
    companion object {
        inline fun <reified P : BasePayload> JsonObject.deserializePayload(): P =
            this["payload"]?.let { elem ->
                Jackson.json.readValue<P>(elem.toString())
            } ?: throw Exception("Payload is null")
    }
}

@PublishedApi
internal object Jackson {
    val json: ObjectMapper = ObjectMapper()
        .registerModule(KotlinModule.Builder().build())
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
}

@Serializable
open class BasePayloadImpl : BasePayload

@Schema
@Serializable
open class ErrorDetail(
    @Schema(description = "오류 코드", required = true)
    val code: String = "",

    @Schema(description = "오류 메시지", required = true)
    val message: String = ""
)

@Schema
@Serializable
open class ErrorPayload(
    @Schema(description = "오류 리스트", required = true)
    val errors: MutableList<ErrorDetail> = mutableListOf(),

    @Schema(description = "부가 정보")
    @Contextual
    val appendix: MutableMap<String, @Contextual Any> = mutableMapOf()
): BasePayload {
    constructor(code: String, message: String, appendix: Map<String, Any>? = null) : this(
        errors = mutableListOf(ErrorDetail(code = code, message = message)),
        appendix = if (appendix != null) appendix.toMutableMap() else mutableMapOf()
    )

    fun addError(code: String, message: String) {
        errors.add(ErrorDetail(code = code, message = message))
    }

    fun addAppendix(key: String, value: @Contextual Any) {
        appendix[key] = value
    }
}

@Schema
@Serializable
open class PageListPayload<P: BasePayload>(
    @Schema(description = "페이지 리스트")
    open var pageable: PageableList<P>
) : BasePayload {
    constructor(
        items: List<P> = listOf(),
        totalItems: Long = 0L,
        pageSize: Long = 1L,
        currentPage: Long = 1L,
        orderInfo: OrderInfo? = null
    ) : this(
        pageable = PageableList.build(
            items = items,
            totalItems = totalItems,
            pageSize = pageSize,
            currentPage = currentPage,
            orderInfo = orderInfo
        )
    )
    constructor(page: Page<P>) : this(
        pageable = PageableList.build(
            items = page.toList(),
            totalItems = page.totalElements,
            pageSize = page.size.toLong(),
            currentPage = page.number.toLong() + 1,
            orderInfo = OrderInfo(page.sort)
        )
    )

    companion object {
        inline fun <P : BasePayload, reified E> fromPage(page: Page<E>, noinline mapper: (E) -> P) = PageListPayload(
            pageable = PageableList.build(
                items = page.content.map(mapper),
                totalItems = page.totalElements,
                pageSize = page.size.toLong(),
                currentPage = page.number.toLong() + 1,
                orderInfo = OrderInfo(page.sort)
            )
        )
    }
}

typealias PageablePayload<P> = PageableList<P>
typealias IncrementalPayload<P, I> = IncrementalList<P, I>

@Schema
@Serializable
open class IncrementalListPayload<P, I>(
    @Schema(description = "더보기 리스트")
    open var incremental: IncrementalList<P, I>
) : BasePayload {
    constructor(
        items: List<P> = listOf(),
        startIndex: I? = null,
        endIndex: I? = null,
        totalItems: Long = 0L,
        cursorField: String = "",
        expandable: Boolean = false,
        orderInfo: OrderInfo? = null
    ) : this(
        incremental = IncrementalList.build(
            items = items,
            startIndex = startIndex,
            endIndex = endIndex,
            totalItems = totalItems,
            cursorField = cursorField,
            expandable = expandable,
            orderInfo = orderInfo
        )
    )
}
