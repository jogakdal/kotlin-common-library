package com.hunet.common.data.jpa.softdelete.annotation

import com.hunet.common.data.jpa.softdelete.internal.DeleteMarkInfo
import com.hunet.common.data.jpa.softdelete.internal.DeleteMarkValue
import com.hunet.common.util.getAnnotation
import jakarta.persistence.Column
import kotlin.reflect.KClass
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible

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
