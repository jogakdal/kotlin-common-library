package com.hunet.common.stdapi.response

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.filter.OncePerRequestFilter
import java.util.concurrent.TimeUnit

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
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        if (request.getAttribute(ATTR_START_NANOS) == null) {
            request.setAttribute(ATTR_START_NANOS, System.nanoTime())
        }
        filterChain.doFilter(request, response)
    }
    companion object { const val ATTR_START_NANOS = "REQUEST_START_NANOS" }
}

/**
 * 자동 구성:
 *   standard-api-response.auto-duration-calculation.active=true 일 때 활성.
 *   filter-order 로 필터 우선순위 지정 (기본 Int.MIN_VALUE)
 */
@Configuration
@ConditionalOnWebApplication
@ConditionalOnProperty(
    prefix = "stdapi.response.auto-duration-calculation",
    name = ["active"],
    havingValue = "true",
    matchIfMissing = false
)
class StandardApiResponseAutoConfiguration {
    @Bean fun requestTimingFilter() = RequestTimingFilter()

    @Bean
    fun requestTimingFilterRegistration(
        filter: RequestTimingFilter,
        @Value("\${stdapi.response.auto-duration-calculation.filter-order:\${standard-api-response.auto-duration-calculation.filter-order:#{T(java.lang.Integer).MIN_VALUE}}}") order: Int
    ): FilterRegistrationBean<RequestTimingFilter> = FilterRegistrationBean(filter).apply { this.order = order }
}
