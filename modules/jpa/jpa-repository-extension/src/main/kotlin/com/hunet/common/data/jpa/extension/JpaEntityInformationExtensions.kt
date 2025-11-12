package com.hunet.common.data.jpa.extension

import org.springframework.data.jpa.repository.support.JpaEntityInformation
import com.hunet.common.util.getAnnotation

inline fun <reified A: Annotation> JpaEntityInformation<*, *>.getAnnotation() = this.javaType.getAnnotation<A>()
inline fun <reified A: Annotation> JpaEntityInformation<*, *>.hasAnnotation() = getAnnotation<A>() != null

@Suppress("UNCHECKED_CAST")
fun <A: Annotation> JpaEntityInformation<*, *>.getAnnotation(annotationClass: Class<A>): A? =
    this.javaType.getAnnotation(annotationClass)
fun <A: Annotation> JpaEntityInformation<*, *>.hasAnnotation(annotationClass: Class<A>) =
    getAnnotation(annotationClass) != null
