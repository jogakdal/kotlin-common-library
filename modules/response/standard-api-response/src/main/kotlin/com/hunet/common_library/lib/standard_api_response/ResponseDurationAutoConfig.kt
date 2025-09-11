package com.hunet.common_library.lib.standard_api_response

import com.hunet.common_library.lib.logger.commonLogger
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.MethodParameter
import org.springframework.http.MediaType
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.http.server.ServletServerHttpRequest
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice
import java.util.concurrent.TimeUnit
import kotlin.reflect.KFunction
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.KParameter
import kotlin.reflect.KProperty1
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberProperties
import kotlin.time.DurationUnit
import kotlin.time.toDuration
import java.time.Duration as JavaDuration
import kotlin.time.Duration as KtDuration

/**
 * 요청 소요 시간을 채워 넣을 필드/프로퍼티에 사용하는 애노테이션.
 * 기본 단위: 밀리초(ms)
 */
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class InjectDuration(val unit: TimeUnit = TimeUnit.MILLISECONDS)

/**
 * 요청 시작 시각을 나노초 단위(System.nanoTime())로 요청 속성에 저장하는 필터.
 */
class RequestTimingFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        if (request.getAttribute(ATTR_START_NANOS) == null)
            request.setAttribute(ATTR_START_NANOS, System.nanoTime())

        filterChain.doFilter(request, response)
    }

    companion object {
        const val ATTR_START_NANOS = "REQUEST_START_NANOS"
    }
}

/**
 * @InjectDuration이 붙은 필드(또는 프로퍼티)에 요청 처리 시간을 주입하는 ResponseBodyAdvice.
 */
@ControllerAdvice
class StandardResponseDurationAdvice : ResponseBodyAdvice<Any> {
    companion object {
        val LOG by commonLogger()
    }

    override fun supports(returnType: MethodParameter, converterType: Class<out HttpMessageConverter<*>>) = true

    override fun beforeBodyWrite(
        body: Any?,
        returnType: MethodParameter,
        selectedContentType: MediaType,
        selectedConverterType: Class<out HttpMessageConverter<*>>,
        request: ServerHttpRequest,
        response: ServerHttpResponse
    ): Any? {
        if (!MediaType.APPLICATION_JSON.includes(selectedContentType)) return body

        val servletRequest = (request as? ServletServerHttpRequest)?.servletRequest
        try {
            val attr = servletRequest?.getAttribute(RequestTimingFilter.ATTR_START_NANOS) ?: return body
            val startNanos = when (attr) {
                is Number -> attr.toLong()
                is String -> attr.toLongOrNull()
                else -> null
            } ?: return body

            val elapsedNanos = System.nanoTime() - startNanos
            return injectDuration(body, elapsedNanos)
        } catch (e: Exception) {
            LOG.error("Failed to calculate request duration, error message = ${e.message}")
            return body
        }
    }

    /**
     * 경과 시간(elapsedNanos)을 @InjectDuration 애노테이션이 붙은 필드에 주입.
     * - 가변(mutable) 프로퍼티면 직접 setter 호출
     * - 불변(immutable) + data class 인 경우 copy 사용
     * - 그 외는 원본 반환
     */
    @Suppress("UNCHECKED_CAST")
    private fun injectDuration(body: Any?, elapsedNanos: Long): Any? {
        if (body == null) return null

        val kClass = body::class
        val allProps = kClass.memberProperties
        val annotatedProps = allProps.filter { it.findAnnotation<InjectDuration>() != null }
        if (annotatedProps.isEmpty()) return body

        var mutated = false

        annotatedProps.forEach { prop ->
            val value = convertForProperty(prop as KProperty1<Any, *>, elapsedNanos)
            val mutable = prop as? KMutableProperty1<Any, Any?>
            if (mutable != null) {
                try {
                    mutable.setter.call(body, value)
                    mutated = true
                } catch (e: Exception) {
                    LOG.error("Failed to apply @InjectDuration via mutable property setter: ${e.message}")
                }
            }
        }
        if (mutated) return body

        if (kClass.isData) {
            val copyFun = kClass.members.firstOrNull { it.name == "copy" } as? KFunction<Any>
            if (copyFun != null) {
                val args = mutableMapOf<KParameter, Any?>()
                args[copyFun.parameters.first()] = body // this

                val propsByName = allProps.associateBy { it.name }
                for (param in copyFun.parameters.drop(1)) {
                    val name = param.name ?: continue
                    val prop = propsByName[name] as? KProperty1<Any, *> ?: continue
                    val hasDuration = prop.findAnnotation<InjectDuration>() != null
                    val value = if (hasDuration) {
                        convertForProperty(prop, elapsedNanos)
                    } else {
                        prop.get(body)
                    }
                    args[param] = value
                }

                return try {
                    copyFun.callBy(args)
                } catch (e: Exception) {
                    LOG.error("Failed to apply @InjectDuration via data class copy: ${e.message}")
                    body
                }
            }
        }
        return body
    }

    /**
     * 나노초 경과 시간을 프로퍼티 타입에 맞게 변환.
     * 지원 타입: Long, Int, Double, String, java.time.Duration, kotlin.time.Duration
     * (그 외 숫자형은 Long 값 사용)
     */
    private fun convertForProperty(prop: KProperty1<Any, *>, elapsedNanos: Long): Any? {
        val ann = prop.findAnnotation<InjectDuration>() ?: return null
        val v: Long = ann.unit.convert(elapsedNanos, TimeUnit.NANOSECONDS)

        return when (prop.returnType.classifier) {
            Long::class -> v
            Int::class -> v.toInt()
            Double::class -> v.toDouble()
            String::class -> v.toString()
            JavaDuration::class -> when (ann.unit) {
                TimeUnit.NANOSECONDS -> JavaDuration.ofNanos(v)
                TimeUnit.MICROSECONDS -> JavaDuration.ofNanos(TimeUnit.MICROSECONDS.toNanos(v))
                TimeUnit.MILLISECONDS -> JavaDuration.ofMillis(v)
                TimeUnit.SECONDS -> JavaDuration.ofSeconds(v)
                TimeUnit.MINUTES -> JavaDuration.ofMinutes(v)
                TimeUnit.HOURS -> JavaDuration.ofHours(v)
                TimeUnit.DAYS -> JavaDuration.ofDays(v)
            }
            KtDuration::class -> {
                val unit = when (ann.unit) {
                    TimeUnit.NANOSECONDS -> DurationUnit.NANOSECONDS
                    TimeUnit.MICROSECONDS -> DurationUnit.MICROSECONDS
                    TimeUnit.MILLISECONDS -> DurationUnit.MILLISECONDS
                    TimeUnit.SECONDS -> DurationUnit.SECONDS
                    TimeUnit.MINUTES -> DurationUnit.MINUTES
                    TimeUnit.HOURS -> DurationUnit.HOURS
                    TimeUnit.DAYS -> DurationUnit.DAYS
                }
                v.toDuration(unit)
            }
            else -> v
        }
    }
}

/**
 * 자동 구성:
 *   standard-api-response.auto-duration-calculation.active=true 일 때 활성. 비활성 기본 (active 미지정 또는 false)
 *   filter-order 로 필터 우선순위 지정 (기본 Int.MIN_VALUE).
 */
@Configuration
@ConditionalOnWebApplication
@ConditionalOnProperty(
    prefix = "standard-api-response.auto-duration-calculation",
    name = ["active"],
    havingValue = "true",
    matchIfMissing = false
)
class StandardApiResponseAutoDurationConfiguration {
    @Bean
    fun requestTimingFilter() = RequestTimingFilter()

    @Bean
    fun requestTimingFilterRegistration(
        filter: RequestTimingFilter,
        @Value("\${standard-api-response.auto-duration-calculation.filter-order:#{T(java.lang.Integer).MIN_VALUE}}")
        order: Int
    ): FilterRegistrationBean<RequestTimingFilter> =
        FilterRegistrationBean(filter).apply { this.order = order }

    @Bean
    fun standardResponseDurationAdvice() = StandardResponseDurationAdvice()
}
