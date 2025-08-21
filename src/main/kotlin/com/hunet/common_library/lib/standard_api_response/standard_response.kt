package com.hunet.common_library.lib.standard_api_response

import com.fasterxml.jackson.annotation.JsonIgnore
import com.hunet.common_library.lib.standard_api_response.BasePayload.Companion.deserializePayload
import com.hunet.common_library.lib.std_api_documentation.*
import com.hunet.common_library.lib.std_api_documentation.DescriptiveEnum.Companion.DESCRIPTION_MARKER
import io.swagger.v3.oas.annotations.media.Schema
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.springframework.data.domain.Page
import org.springframework.data.domain.Sort
import java.time.Duration
import java.time.Instant
import kotlin.math.min

@EnumConstant
enum class StandardStatus(
    override val value: String,
    override val description: String,
    override val describable: Boolean = true
) : DescriptiveEnum {
    NONE("", "없음"),
    SUCCESS("SUCCESS", "성공"),
    FAILURE("FAILURE", "실패");
    companion object {
        const val DESCRIPTION = "표준 응답 상태($DESCRIPTION_MARKER)"

        fun fromString(text: String): StandardStatus =
            entries.firstOrNull { it.value.equals(text, ignoreCase = true) } ?: SUCCESS
        fun toDescription() = entries.joinToString(", ") { it.toDescription() }
    }
}

@EnumConstant
enum class OrderDirection(
    override val value: String,
    override val description: String,
    override val describable: Boolean = true
) : DescriptiveEnum {
    ASC("asc", "내림차순"),
    DESC("desc", "오름차순");

    companion object {
        const val DESCRIPTION = "정렬 방향($DESCRIPTION_MARKER)"

        fun fromString(text: String): OrderDirection =
            entries.firstOrNull { it.value.equals(text, ignoreCase = true) } ?: ASC
        fun toDescription() = entries.joinToString(", ") { it.toDescription() }
    }

    override fun toString(): String = value
}

@Schema
@Serializable
class OrderBy(
    @Schema(description = "정렬 필드명")
    val field: String = "",

    @Schema(description = OrderDirection.DESCRIPTION)
    val direction: OrderDirection = OrderDirection.ASC
)

@Schema
@Serializable
class OrderInfo(
    @Schema(description = "정렬 여부")
    val sorted: Boolean? = true,

    @Schema(description = "정렬 필드 리스트", required = true)
    val `by`: List<OrderBy> = listOf(OrderBy())
) {
    constructor(sort: Sort) : this(
        sorted = sort.isSorted,
        by = sort.toList().map { OrderBy(it.property, OrderDirection.fromString(it.direction.name)) }
    )
}

@SwaggerDescribable
@Serializable
class PageInfo(
    @SwaggerDescription(description = "페이지 사이즈")
    val size: Long = 0L,

    @SwaggerDescription(description = "현재 페이지 번호")
    val current: Long = 1L,

    @SwaggerDescription(description = "총 페이지 수")
    val total: Long = 1L
) {
    constructor(page: Page<*>) : this(
        size = page.size.toLong(),
        current = page.number.toLong() + 1,
        total = page.totalPages.toLong()
    )

    companion object {
        fun calcTotalPages(totalItems: Long, pageSize: Long): Long =
            if (pageSize > 0) (totalItems + pageSize - 1) / pageSize else totalItems
    }
}

@SwaggerDescribable
@Serializable
data class Items<T>(
    @SwaggerDescription(description = "총 아이템 수")
    val total: Long? = null,

    @SwaggerDescription(description = "현재 아이템 수")
    val current: Long? = null,

    @SwaggerDescription(description = "아이템 리스트")
    val list: List<T> = listOf()
) {
    constructor(page: Page<T>) : this(
        total = page.totalElements,
        current = page.size.toLong(),
        list = page.toList()
    )

    companion object {
        fun <T> build(totalItems: Long, items: List<T>): Items<T> =
            Items(total = totalItems, current = items.size.toLong(), list = items)
    }
}

@Schema
@Serializable
abstract class BaseList<T>

@Schema
@Serializable
open class PageableList<T>(
    @Schema(description = "페이지 정보")
    val page: PageInfo = PageInfo(),

    @Schema(description = "정렬 정보")
    val order: OrderInfo? = null,

    @Schema(description = "아이템 리스트")
    open val items: Items<T> = Items()
) : BasePayload, BaseList<T>() {
    constructor(page: Page<T>) : this(page = PageInfo(page), order = OrderInfo(page.sort), items = Items(page))

    companion object {
        fun <T> build(
            items: List<T>,
            totalItems: Long,
            pageSize: Long,
            currentPage: Long,
            orderInfo: OrderInfo? = null
        ): PageableList<T> = PageableList(
            page = PageInfo(
                size = pageSize.takeIf { it > 0 } ?: 1,
                current = currentPage,
                total = PageInfo.calcTotalPages(totalItems, pageSize.takeIf { it > 0 } ?: 1)
            ),
            order = orderInfo,
            items = Items.build(totalItems, items)
        )

        inline fun <P : BasePayload, reified E> fromPage(page: Page<E>, mapper: (E) -> P) = PageableList(
            page = PageInfo(page),
            order = OrderInfo(page.sort),
            items = Items.build(
                totalItems = page.totalElements,
                items = page.content.map(mapper)
            )
        )
    }

    @JsonIgnore
    val itemsAsList: List<T> = items.list
}

@Schema
@Serializable
class CursorInfo<P>(
    @Schema(description = "기준 필드", required = true)
    val field: String = "",

    @Schema(description = "시작 인덱스", required = true)
    val start: P? = null,

    @Schema(description = "끝 인덱스", required = true)
    val end: P? = null,

    @Schema(description = "추가 확장 가능 여부")
    val expandable: Boolean? = false
) {
    companion object {
        inline fun <reified P> buildFromTotal(
            startIndex: Long,
            howMany: Long,
            totalItems: Long,
            field: String,
            convertIndex: (field: String, index: Long) -> P? = { _, index ->
                when (P::class) {
                    Long::class, Int::class -> index as P
                    else -> null
                }
            }
        ): CursorInfo<P> = run {
            val safeStart = startIndex.takeIf { it >= 0L } ?: 0L
            val safeHowMany = howMany.takeIf { it >= 1L } ?: 1L

            if (totalItems <= 0L || safeStart >= totalItems) CursorInfo(
                field = field,
                start = convertIndex(field, totalItems),
                end = convertIndex(field, totalItems),
                expandable = false
            )
            else CursorInfo(
                field = field,
                start = convertIndex(field, safeStart),
                end = convertIndex(
                    field,
                    safeStart + (min(safeHowMany, totalItems - safeStart).takeIf { it > 0 }?.minus(1) ?: 0L)
                ),
                expandable = safeStart + safeHowMany < totalItems
            )
        }
    }
}

/**
 * IncrementalList는 커서 기반 더보기 형태의 리스트입니다.
 * Generic 타입 T는 아이템의 타입을 나타내며, P는 커서 필드의 타입을 나타냅니다.
 */
@Schema
@Serializable
class IncrementalList<T, P>(
    @Schema(description = "커서 정보")
    val cursor: CursorInfo<P>? = null,
    @Schema(description = "정렬 정보")
    val order: OrderInfo? = null,

    @Schema(description = "아이템 리스트")
    val items: Items<T> = Items()
) : BasePayload, BaseList<T>() {
    companion object {
        fun <T, P> build(
            items: List<T>,
            startIndex: P?,
            endIndex: P?,
            totalItems: Long,
            cursorField: String,
            expandable: Boolean = false,
            orderInfo: OrderInfo? = null,
        ): IncrementalList<T, P> = IncrementalList(
            cursor = CursorInfo(field = cursorField, start = startIndex, end = endIndex, expandable = expandable),
            order = orderInfo,
            items = Items.build(totalItems, items)
        )

        inline fun <T, reified P> buildFromTotal(
            items: List<T>,
            startIndex: Long,
            howMany: Long,
            totalItems: Long,
            cursorField: String,
            orderInfo: OrderInfo? = null,
            convertIndex: (field: String, index: Long) -> P? = { _, index ->
                when (P::class) {
                    Long::class, Int::class -> index as P
                    else -> null
                }
            }
        ): IncrementalList<T, P> = IncrementalList(
            cursor = CursorInfo.buildFromTotal(
                startIndex = startIndex,
                howMany = howMany,
                totalItems = totalItems,
                field = cursorField,
                convertIndex = convertIndex
            ),
            order = orderInfo,
            items = Items.build(totalItems, items)
        )
    }

    @JsonIgnore
    val itemsAsList: List<T> = items.list
}

data class StandardCallbackResult (
    val payload: BasePayload,
    val status: StandardStatus? = null,
    val version: String? = null
)

@Schema
data class StandardResponse<T : BasePayload> (
    @Sequence(1)
    @Schema(description = "오류 코드")
    val status: StandardStatus? = StandardStatus.SUCCESS,

    @Sequence(2)
    @Schema(description = "API version")
    val version: String,

    @Sequence(3)
    @Schema(description= "API 응답 시각")
    @Contextual
    val datetime: Instant,

    @Sequence(4)
    @Schema(description = "API 처리 시간(밀리초)")
    val duration: Long? = 0L,

    @Sequence(5)
    @Schema(description = "결과 payload")
    val payload: T
) {
    inline fun <reified T: BasePayload> getRealPayload(): T? = payload as? T

    companion object {
        // callback 함수는 payload, status, version 정보를 StandardCallbackResult 객체로 묶어서 반환해야 한다.
        @Suppress("UNCHECKED_CAST")
        fun <T : BasePayload> build(
            payload: T? = null,
            callback: (() -> StandardCallbackResult)? = null,
            status: StandardStatus = StandardStatus.SUCCESS,
            version: String = "1.0",
            duration: Long? = null
        ): StandardResponse<T> = run {
            val startTime = Instant.now()
            val callbackResult =
                if (payload == null)
                    if (callback != null) callback() else throw Exception("Payload cannot be null without a callback")
                else StandardCallbackResult(payload, status, version)

            val endTime = Instant.now()
            StandardResponse<T>(
                status = callbackResult.status ?: status,
                version = callbackResult.version ?: version,
                datetime = endTime,
                duration = duration ?: Duration.between(startTime, endTime).toMillis(),
                payload = callbackResult.payload as T
            )
        }

        inline fun <reified T: BasePayload> deserialize(jsonString: String): StandardResponse<T> = try {
            JsonConfig.json.parseToJsonElement(jsonString).jsonObject.let { json ->
                StandardResponse(
                    status = json.get("status")?.jsonPrimitive?.content?.let {
                        StandardStatus.fromString(it)
                    } ?: StandardStatus.SUCCESS,
                    version = json["version"]?.jsonPrimitive?.content ?: "1.0",
                    datetime = when (val dt = json["datetime"]) {
                        null -> Instant.now()
                        is JsonPrimitive -> if (dt.isString) Instant.parse(dt.content) else Instant.now()
                        else -> Instant.now()
                    },
                    duration = json["duration"]?.jsonPrimitive?.long ?: 0L,
                    payload = json.deserializePayload() as T
                )
            }
        } catch (e: Exception) {
            StandardResponse(
                status = StandardStatus.FAILURE,
                version = "1.0",
                datetime = Instant.now(),
                duration = 0L,
                payload = BasePayloadImpl() as T
            )
        }
    }
}

typealias DefaultResponse = StandardResponse<BasePayload>
