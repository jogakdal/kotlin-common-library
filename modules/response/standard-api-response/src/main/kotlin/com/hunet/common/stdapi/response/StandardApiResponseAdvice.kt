package com.hunet.common.stdapi.response

import com.fasterxml.jackson.databind.JsonNode
import com.hunet.common.logging.commonLogger
import com.hunet.common.util.getAnnotation
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.MethodParameter
import org.springframework.http.MediaType
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.http.server.ServletServerHttpRequest
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import kotlin.reflect.KFunction
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.KParameter
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberProperties
import kotlin.time.DurationUnit
import kotlin.time.toDuration
import java.time.Duration as JavaDuration
import kotlin.time.Duration as KtDuration

/**
 * 공통 표준 응답 처리 (duration 주입 + case convention 적용).
 */
@ControllerAdvice
class StandardApiResponseAdvice(
    @Value("\${stdapi.response.case.enabled:\${standard-api-response.case.enabled:true}}")
    private val caseEnabled: Boolean = true,

    @Value("\${stdapi.response.case.default:\${standard-api-response.case.default:IDENTITY}}")
    private val defaultCaseName: String = "IDENTITY",

    @Value("\${stdapi.response.case.query-override:\${standard-api-response.case.query-override:true}}")
    private val queryOverride: Boolean = true,

    @Value("\${stdapi.response.case.header-override:\${standard-api-response.case.header-override:true}}")
    private val headerOverride: Boolean = true,

    @Value("\${stdapi.response.case.query-param:\${standard-api-response.case.query-param:case}}")
    private val queryParamName: String = "case",

    @Value("\${stdapi.response.case.header-name:\${standard-api-response.case.header-name:X-Response-Case}}")
    private val headerName: String = "X-Response-Case"
) : ResponseBodyAdvice<Any> {
    companion object { val LOG by commonLogger() }

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

        val processed: Any? = try {
            val attr = servletRequest?.getAttribute(RequestTimingFilter.ATTR_START_NANOS)
            if (attr != null) {
                val startNanos = when (attr) {
                    is Number -> attr.toLong()
                    is String -> attr.toLongOrNull()
                    else -> null
                }
                if (startNanos != null) {
                    val elapsed = System.nanoTime() - startNanos
                    injectDuration(body, elapsed)
                } else body
            } else body
        } catch (e: Exception) {
            LOG.error("Failed to calculate request duration: ${e.message}")
            body
        }
        return applyCase(processed, servletRequest)
    }

    private fun applyCase(body: Any?, request: HttpServletRequest?): Any? {
        if (body !is StandardResponse<*>) return body
        val targetCase = resolveCase(body, request)
        if (targetCase == CaseConvention.IDENTITY) return body
        return try {
            val mapper = Jackson.json
            val tree = mapper.valueToTree<JsonNode>(body)
            transformAllKeys(tree, targetCase)
        } catch (e: Exception) {
            LOG.error("Failed to apply case convention: ${e.message}")
            body
        }
    }

    private fun resolveCase(body: StandardResponse<*>, request: HttpServletRequest?): CaseConvention {
        if (!caseEnabled) return CaseConvention.IDENTITY
        if (queryOverride) extractQueryParamCase(request)?.let { return it }
        if (headerOverride) request?.getHeader(headerName)?.let { hdr ->
            runCatching { CaseConvention.valueOf(hdr.uppercase()) }.getOrNull()?.let { return it }
        }
        body.payload::class.java.getAnnotation(ResponseCase::class.java)?.value?.let { return it }
        return runCatching { CaseConvention.valueOf(defaultCaseName.uppercase()) }.getOrElse { CaseConvention.IDENTITY }
    }

    private fun extractQueryParamCase(request: HttpServletRequest?): CaseConvention? {
        val qs = request?.queryString ?: return null
        for (p in qs.split('&')) {
            val idx = p.indexOf('=')
            val key = if (idx >= 0) p.substring(0, idx) else p
            if (key == queryParamName) {
                val raw = if (idx >= 0) p.substring(idx + 1) else ""
                val decoded = URLDecoder.decode(raw, StandardCharsets.UTF_8)
                return runCatching { CaseConvention.valueOf(decoded.uppercase()) }.getOrNull()
            }
        }
        return null
    }

    @Suppress("UNCHECKED_CAST")
    private fun injectDuration(body: Any?, elapsedNanos: Long): Any? {
        if (body == null) return null
        val kClass = body::class
        val allProps = kClass.memberProperties
        val targets = allProps.filter { it.getAnnotation<InjectDuration>() != null }
        if (targets.isEmpty()) return body
        var mutated = false
        targets.forEach { prop ->
            val value = convertForProperty(prop as KProperty1<Any, *>, elapsedNanos)
            (prop as? KMutableProperty1<Any, Any?>)?.let { m ->
                try { m.setter.call(body, value); mutated = true } catch (_: Exception) {}
            }
        }
        if (mutated) return body
        if (kClass.isData) {
            val copyFn = kClass.members.firstOrNull { it.name == "copy" } as? KFunction<Any>
            if (copyFn != null) {
                val args = mutableMapOf<KParameter, Any?>()
                args[copyFn.parameters.first()] = body
                val map = allProps.associateBy { it.name }
                for (p in copyFn.parameters.drop(1)) {
                    val nm = p.name ?: continue
                    val prop = map[nm] as? KProperty1<Any, *> ?: continue

                    args[p] = if (prop.getAnnotation<InjectDuration>() != null) convertForProperty(prop, elapsedNanos)
                    else prop.get(body)
                }
                return runCatching { copyFn.callBy(args) }.getOrElse { body }
            }
        }
        return body
    }

    private fun convertForProperty(prop: KProperty1<Any, *>, elapsedNanos: Long): Any? {
        val ann = prop.getAnnotation<InjectDuration>() ?: return null
        val v = ann.unit.convert(elapsedNanos, TimeUnit.NANOSECONDS)
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
