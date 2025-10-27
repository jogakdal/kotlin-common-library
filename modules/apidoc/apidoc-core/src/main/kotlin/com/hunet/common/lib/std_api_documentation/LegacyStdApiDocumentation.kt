@file:Suppress("DEPRECATION")
package com.hunet.common.lib.std_api_documentation

// 레거시 패키지 호환을 위한 Deprecated 래퍼 및 typealias 정의.
// 신규 패키지: com.hunet.common.apidoc.*
// 점진적 이전을 위해 v0.x 기간 유지 후 제거 예정.

@Deprecated("Use com.hunet.common.apidoc.core.buildDocument", ReplaceWith("com.hunet.common.apidoc.core.buildDocument(identifier, tag, summary, description, pathParameters, queryParameters, formParameters, queryObject, requestObject, responseObject)"))
fun buildDocument(
    identifier: String,
    tag: String,
    summary: String,
    description: String,
    pathParameters: List<com.epages.restdocs.apispec.ParameterDescriptorWithType> = emptyList(),
    queryParameters: List<com.epages.restdocs.apispec.ParameterDescriptorWithType> = emptyList(),
    formParameters: List<com.epages.restdocs.apispec.ParameterDescriptorWithType> = emptyList(),
    queryObject: Any? = null,
    requestObject: Any? = null,
    responseObject: Any? = null
): org.springframework.restdocs.mockmvc.RestDocumentationResultHandler =
    com.hunet.common.apidoc.core.buildDocument(
        identifier,
        tag,
        summary,
        description,
        pathParameters,
        queryParameters,
        formParameters,
        queryObject,
        requestObject,
        responseObject
    )

@Deprecated("Use com.hunet.common.apidoc.enum.DescriptiveEnum")
typealias DescriptiveEnum = com.hunet.common.apidoc.enum.DescriptiveEnum
@Deprecated("Use com.hunet.common.apidoc.enum.ExceptionCode")
typealias ExceptionCode = com.hunet.common.apidoc.enum.ExceptionCode
@Deprecated("Use com.hunet.common.apidoc.enum.EnumConstant")
typealias EnumConstant = com.hunet.common.apidoc.enum.EnumConstant
@Deprecated("Use com.hunet.common.apidoc.annotations.SwaggerDescription")
typealias SwaggerDescription = com.hunet.common.apidoc.annotations.SwaggerDescription
@Deprecated("Use com.hunet.common.apidoc.annotations.SwaggerDescribable")
typealias SwaggerDescribable = com.hunet.common.apidoc.annotations.SwaggerDescribable
@Deprecated("Use com.hunet.common.apidoc.annotations.Sequence")
typealias Sequence = com.hunet.common.apidoc.annotations.Sequence

