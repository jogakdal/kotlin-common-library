package com.hunet.common.data.jpa.sequence

import com.hunet.common.util.getAnnotation
import com.hunet.common.util.hasAnnotation
import com.hunet.common.util.hasDirectAnnotation
import com.hunet.common.util.getDirectAnnotation
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
    /**
     * 처리 전략:
     * 1) Kotlin 프로퍼티: hasAnnotation(getAnnotation) 사용 → @property / @field / @get / constructor param 등에 붙은
     *    다양한 선언 위치 지원 (통합 탐색 필요).
     * 2) Java 필드 fallback: 이미 Kotlin 프로퍼티 루프에서 간접(backing field) 어노테이션까지 커버했으므로
     *    중복 방지 + 의미적 명확성을 위해 '직접 선언' 어노테이션만(hasDirectAnnotation) 스캔.
     */
    // Kotlin 프로퍼티(@PROPERTY 등) 처리
    entity::class.memberProperties
        .filterIsInstance<kotlin.reflect.KMutableProperty1<*, *>>()
        .filter { it.hasAnnotation<GenerateSequentialCode>() }
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
    // Java 필드(@FIELD) 처리: 직접 선언(@field)에만 반응 (Kotlin 프로퍼티에서 이미 커버된 경우 중복 회피)
    entity.javaClass.declaredFields
        .filter { it.hasDirectAnnotation<GenerateSequentialCode>() }
        .forEach { field ->
            field.isAccessible = true
            val current = (field.get(entity) as? String).orEmpty()
            if (current.isBlank()) {
                val ann = field.getDirectAnnotation<GenerateSequentialCode>() ?: return@forEach
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
