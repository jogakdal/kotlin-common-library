package com.hunet.common.data.jpa.softdelete.annotation

import com.hunet.common.data.jpa.softdelete.internal.DeleteMarkInfo
import com.hunet.common.data.jpa.softdelete.internal.DeleteMarkValue
import jakarta.persistence.Column
import kotlin.reflect.KAnnotatedElement
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible
import kotlin.reflect.jvm.javaField

@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class DeleteMark(val aliveMark: DeleteMarkValue, val deletedMark: DeleteMarkValue)

val <T : Any> KClass<T>.deleteMarkInfo: DeleteMarkInfo?
    get() = memberProperties.firstOrNull { it.findAnnotation<DeleteMark>() != null }
        ?.apply { isAccessible = true }?.let { prop ->
            val ann = prop.findAnnotation<DeleteMark>()!!
            val colName = prop.getAnnotation<Column>()?.name?.takeIf { it.isNotBlank() } ?: prop.name
            DeleteMarkInfo(
                field = prop,
                dbColumnName = colName,
                fieldName = prop.name,
                deleteMark = ann.deletedMark,
                aliveMark = ann.aliveMark
            )
        }

inline fun <reified A : Annotation> KAnnotatedElement.getAnnotation(): A? {
    findAnnotation<A>()?.let { return it }
    return when (this) {
        is KProperty1<*, *> -> this.javaField?.getAnnotation(A::class.java)
        is KClass<*> -> this.java.getAnnotation(A::class.java)
        else -> null
    }
}

inline fun <reified A : Annotation> KAnnotatedElement.isExistAnnotation() = this.getAnnotation<A>() != null
