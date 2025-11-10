package com.hunet.common.data.jpa.sequence

import com.hunet.common.util.getAnnotation
import org.springframework.expression.spel.standard.SpelExpressionParser
import org.springframework.expression.spel.support.StandardEvaluationContext
import org.springframework.stereotype.Component
import kotlin.reflect.KClass
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible

interface SequenceGenerator {
    fun generateKey(prefix: String, entity: Any? = null): Any?
}

@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class GenerateSequentialCode(
    val prefixExpression: String = "",
    val prefixProvider: KClass<out PrefixProvider> = DefaultPrefixProvider::class,
)

interface PrefixProvider { fun determinePrefix(target: Any): String }

@Component
class DefaultPrefixProvider : PrefixProvider {
    override fun determinePrefix(target: Any): String =
        throw IllegalStateException("GenerateSequentialCode.prefixExpression 이 지정되지 않았습니다.")
}

internal val spelExpressionParser by lazy { SpelExpressionParser() }

@Suppress("UNCHECKED_CAST")
fun applySequentialCode(entity: Any, sequenceGenerator: SequenceGenerator) {
    // Kotlin 프로퍼티(@PROPERTY) 처리
    entity::class.memberProperties
        .filterIsInstance<kotlin.reflect.KMutableProperty1<*, *>>()
        .filter { it.getAnnotation<GenerateSequentialCode>() != null }
        .forEach { rawProp ->
            val prop = rawProp as kotlin.reflect.KMutableProperty1<Any, Any?>
            prop.isAccessible = true
            val current = (prop.get(entity) as? String).orEmpty()
            if (current.isBlank()) {
                val ann = prop.getAnnotation<GenerateSequentialCode>()
                    ?: throw IllegalStateException("@GenerateSequentialCode가 사라졌습니다")
                val prefix = if (ann.prefixExpression.isNotBlank()) {
                    spelExpressionParser.parseExpression(ann.prefixExpression)
                        .getValue(StandardEvaluationContext(entity)) as String
                } else {
                    ann.prefixProvider.java.getDeclaredConstructor().newInstance().determinePrefix(entity)
                }
                sequenceGenerator.generateKey(prefix, entity)?.let { generated ->
                    prop.setter.call(entity, generated)
                }
            }
        }
    // Java 필드(@FIELD) 처리 fallback
    entity.javaClass.declaredFields
        .filter { it.getAnnotation(GenerateSequentialCode::class.java) != null }
        .forEach { field ->
            field.isAccessible = true
            val current = (field.get(entity) as? String).orEmpty()
            if (current.isBlank()) {
                val ann = field.getAnnotation(GenerateSequentialCode::class.java)
                val prefix = if (ann.prefixExpression.isNotBlank()) {
                    spelExpressionParser.parseExpression(ann.prefixExpression)
                        .getValue(StandardEvaluationContext(entity)) as String
                } else {
                    ann.prefixProvider.java.getDeclaredConstructor().newInstance().determinePrefix(entity)
                }
                sequenceGenerator.generateKey(prefix, entity)?.let { generated ->
                    field.set(entity, generated)
                }
            }
        }
}
