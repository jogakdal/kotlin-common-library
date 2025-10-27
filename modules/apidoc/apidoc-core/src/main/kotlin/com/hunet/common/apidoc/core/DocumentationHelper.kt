package com.hunet.common.apidoc.core

import com.hunet.common.apidoc.enum.DescriptiveEnum
import com.hunet.common.apidoc.annotations.Sequence
import com.hunet.common.apidoc.annotations.SwaggerDescribable
import com.hunet.common.apidoc.annotations.SwaggerDescription
import com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper
import com.epages.restdocs.apispec.ParameterDescriptorWithType
import com.epages.restdocs.apispec.ResourceDocumentation
import com.epages.restdocs.apispec.ResourceSnippetParameters
import com.epages.restdocs.apispec.SimpleType
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.restdocs.mockmvc.RestDocumentationResultHandler
import org.springframework.restdocs.operation.preprocess.Preprocessors
import org.springframework.restdocs.payload.FieldDescriptor
import org.springframework.restdocs.payload.JsonFieldType
import org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath
import org.springframework.restdocs.payload.PayloadDocumentation.subsectionWithPath
import org.springframework.restdocs.snippet.Attributes
import java.lang.reflect.AnnotatedElement
import java.time.*
import java.util.*
import kotlin.reflect.KAnnotatedElement
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.javaField
import kotlin.reflect.jvm.javaGetter

/**
 * 스프링 REST Docs용 문서 스니펫을 생성하는 헬퍼 함수
 *
 * 전달된 임의의 객체(responseObject)를 리플렉션으로 순회하여,
 * @ResponseDescriptable, @ResponseDescription, @Schema 어노테이션 정보를 기반으로
 * FieldDescriptor 리스트를 자동 생성하고,
 * ResourceSnippetParameters에 tag/summary/description,
 * 경로(pathParameters)·쿼리(queryParameters) 파라미터 정의를 바인딩한 뒤
 * MockMvcRestDocumentationWrapper.document(...) 호출값 반환
 *
 * @param identifier       스니펫 식별자(예: "get-user")
 * @param tag              API 그룹 태그(예: "User")
 * @param summary          간략 설명(예: "단일 사용자 조회")
 * @param description      상세 설명(예: "주어진 userId로 사용자를 조회합니다.")
 * @param pathParameters   URL 경로 변수 목록 (ParameterDescriptorWithType 리스트)
 * @param queryParameters  쿼리 스트링 변수 목록 (ParameterDescriptorWithType 리스트)
 * @param responseObject   문서화할 응답 객체 인스턴스 (어노테이션이 달린 DTO/VO 등)
 * @return                  RestDocumentationResultHandler (MockMvc.andDo()에 바로 사용)
 *
 * @sample
 * ```kotlin
 * val sample = userService.getUser("jogakdal")  // API response에 해당하는 객체
 * mockMvc.perform(get("/api/users/{userId}", "jogakdal"))
 *     .andExpect(status().isOk)
 *     .andDo(buildDocument(
 *         identifier      = "get-user",
 *         tag             = "User",
 *         summary         = "단일 사용자 조회",
 *         description     = "userId로 사용자를 조회하여 반환합니다.",
 *         pathParameters  = listOf(ParameterDescriptorWithType("userId", "string", "사용자 ID")),
 *         responseObject  = sample
 *     ))
 * ```
 */
fun buildDocument(
    identifier: String,
    tag: String,
    summary: String,
    description: String,
    pathParameters: List<ParameterDescriptorWithType> = emptyList(),
    queryParameters: List<ParameterDescriptorWithType> = emptyList(),
    formParameters: List<ParameterDescriptorWithType> = emptyList(),
    queryObject: Any? = null,
    requestObject: Any? = null,
    responseObject: Any? = null
): RestDocumentationResultHandler = MockMvcRestDocumentationWrapper.document(
    identifier = identifier,
    Preprocessors.preprocessRequest(Preprocessors.prettyPrint()),
    Preprocessors.preprocessResponse(Preprocessors.prettyPrint()),
    ResourceDocumentation.resource(
        ResourceSnippetParameters.builder().apply {
            tag(tag)
            summary(summary)
            description(description)
            if (pathParameters.isNotEmpty()) pathParameters(*pathParameters.toTypedArray())
            run {
                val inferred: List<ParameterDescriptorWithType> =
                    queryObject?.let {
                        buildDescriptors(it, "").map { fd ->
                            ParameterDescriptorWithType(fd.path).type(jsonTypeToParamType(fd.type)).also { d ->
                                val desc: String? = try {
                                    val m = fd::class.java.methods.firstOrNull {
                                        it.name == "getDescription" && it.parameterCount == 0
                                    }
                                    (m?.invoke(fd) as? String)
                                } catch (_: Exception) { null }
                                if (!desc.isNullOrBlank()) d.description(desc)
                            }
                        }
                    } ?: emptyList()
                val merged = (queryParameters + inferred)
                if (merged.isNotEmpty()) queryParameters(*merged.toTypedArray())
            }
            if (formParameters.isNotEmpty()) formParameters(*formParameters.toTypedArray())
            if (requestObject != null) requestFields(
                buildDescriptors(requestObject, "").mapIndexed { idx, fd -> fd.apply { attributes(Attributes.key("order").value(idx)) } }
            )
            if (responseObject != null) responseFields(
                buildDescriptors(responseObject, parentPath = "").mapIndexed { idx, fd -> fd.apply { attributes(Attributes.key("order").value(idx)) } }
            )
        }.build()
    )
)

/**
 * @SwaggerDescribable, @Schema 어노테이션이 붙은 인스턴스에서
 * @SwaggerDescription 또는 @Schema 어노테이션이 붙어 있는 필드들에 대해 스프링 REST Docs용 FieldDescriptor 리스트를 생성한다.
 * 각 필드의 타입에 @SwaggerDescribable 또는 @Schema 어노테이션이 붙어 있으면 재귀적으로 FieldDescriptor 리스트를 생성한다.
 *
 * @param instance   FieldDescriptor를 생성할 객체 인스턴스
 * @param parentPath 현재 탐색 중인 JSON 경로(최상위에서는 빈 문자열)
 * @return           생성된 FieldDescriptor 목록
 */
fun buildDescriptors(instance: Any, parentPath: String = ""): List<FieldDescriptor> {
    if (instance is DescriptiveEnum) {
        val constants: List<DescriptiveEnum> =
            (instance::class.java.enumConstants?.filterIsInstance<DescriptiveEnum>() ?: emptyList()).filter { it.describable }.sortedBy { constant ->
                try {
                    val enumConst = constant as Enum<*>
                    val enumClass: Class<*> =
                        enumConst.javaClass.enclosingClass?.takeIf { it.isEnum }
                            ?: enumConst.javaClass.superclass?.takeIf { it.isEnum }
                            ?: enumConst.javaClass
                    enumClass.getField(enumConst.name).getAnnotation<Sequence>()?.value ?: Int.MAX_VALUE
                } catch (_: Exception) {
                    Int.MAX_VALUE
                }
            }
        return constants.map { constant ->
            val enumName = (constant as Enum<*>).name
            val path = if (parentPath.isEmpty()) enumName else "$parentPath.$enumName"
            (if (parentPath.isEmpty()) fieldWithPath(path) else subsectionWithPath(path)).description(constant.toDescription()).type(determineJsonFieldTypeByValue(instance.value)).optional()
        }
    }
    return instance::class.takeIf { it.isExistAnnotation<SwaggerDescribable>() || it.isExistAnnotation<Schema>() }?.memberProperties.orEmpty().mapNotNull { prop ->
        prop.getAnnotation<Schema>()?.let { schemaAnn -> Triple(prop, schemaAnn.description, !schemaAnn.required) } ?: prop.getAnnotation<SwaggerDescription>()?.let { rd -> Triple(prop, rd.description, rd.optional) }
    }.sortedBy { (prop, _, _) -> prop.getAnnotation<Sequence>()?.value ?: Int.MAX_VALUE }.flatMap { (prop, description, optional) ->
        val value = prop.getter.call(instance)
        val path = if (parentPath.isEmpty()) prop.name else "$parentPath.${prop.name}"
        (if (parentPath.isEmpty()) fieldWithPath(path) else subsectionWithPath(path)).description((value as? DescriptiveEnum)?.let { DescriptiveEnum.replaceDescription(description, it::class) } ?: description).let { fd -> if (optional || prop.returnType.isMarkedNullable) fd.optional() else fd }.type(determineJsonFieldTypeByValue(value)).let { fd ->
            listOf(fd) +
                (value as? List<*>)?.filterNotNull()?.flatMap { buildDescriptors(it, "$path[]") }.orEmpty() +
                (value as? Array<*>)?.filterNotNull()?.flatMap { buildDescriptors(it, "$path[]") }.orEmpty() +
                (value as? Map<*, *>)?.values?.filterNotNull()?.flatMap { buildDescriptors(it, "$path.*") }.orEmpty() +
                (value as? Set<*>)?.filterNotNull()?.flatMap { buildDescriptors(it, "$path[]") }.orEmpty() +
                (value.takeIf { it != null && (it::class.isExistAnnotation<SwaggerDescribable>() || it::class.isExistAnnotation<Schema>()) }?.let { buildDescriptors(it, path) }.orEmpty())
        }
    }
}

private fun determineJsonFieldTypeByValue(value: Any?): JsonFieldType = when (value) {
    null -> JsonFieldType.OBJECT
    is Number -> JsonFieldType.NUMBER
    is String,
    is Date,
    is LocalDate,
    is LocalTime,
    is LocalDateTime,
    is Instant,
    is Enum<*>,
    is Duration -> JsonFieldType.STRING
    is Boolean -> JsonFieldType.BOOLEAN
    is List<*>, is Array<*> -> JsonFieldType.ARRAY
    is Set<*> -> JsonFieldType.ARRAY
    else -> JsonFieldType.OBJECT
}

inline fun <reified A : Annotation> KAnnotatedElement.getAnnotation(): A? {
    this.findAnnotation<A>()?.let { return it }
    if (this is KProperty1<*, *>) {
        this.javaField?.getAnnotation(A::class.java)?.let { return it }
        this.javaGetter?.getAnnotation(A::class.java)?.let { return it }
        val ownerClass = (this.javaField?.declaringClass ?: this.javaGetter?.declaringClass)?.kotlin
        ownerClass?.primaryConstructor
            ?.parameters
            ?.firstOrNull { it.name == this.name }
            ?.findAnnotation<A>()
            ?.let { return it }
        return null
    }
    if (this is KClass<*>) {
        this.java.getAnnotation(A::class.java)?.let { return it }
    }
    return null
}

inline fun <reified A : Annotation> AnnotatedElement.getAnnotation(): A? =
    this.getAnnotation(A::class.java)

inline fun <reified A : Annotation> KAnnotatedElement.isExistAnnotation(): Boolean =
    this.getAnnotation<A>() != null

private fun jsonTypeToParamType(type: Any?): SimpleType = when (type) {
    is JsonFieldType -> when (type) {
        JsonFieldType.NUMBER -> SimpleType.NUMBER
        JsonFieldType.BOOLEAN -> SimpleType.BOOLEAN
        else -> SimpleType.STRING
    }
    else -> SimpleType.STRING
}
