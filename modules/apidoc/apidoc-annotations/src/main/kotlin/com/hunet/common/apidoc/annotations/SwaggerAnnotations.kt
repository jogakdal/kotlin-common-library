package com.hunet.common.apidoc.annotations

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Deprecated("Use standard OpenAPI @Schema instead; will be removed in a future release.")
annotation class SwaggerDescribable

@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
@Deprecated("Use @SwaggerDescription or @Schema instead; this will be removed in a future release.")
annotation class RequestDescription(val name: String = "", val description: String = "", val optional: Boolean = false)

@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class SwaggerDescription(val description: String = "", val optional: Boolean = false)

@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class Sequence(val value: Int)
