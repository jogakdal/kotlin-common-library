package com.hunet.common_library.lib

import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * 템플릿 문자열 내부의 변수 토큰을 지정된 Resolver 들을 통해 해석/치환하는 경량 유틸.
 * (Java 사용자는 VariableProcessorJava 유틸 및 OptionsBuilder 참고)
 *
 * 특징:
 * - 토큰 구분자 커스터마이징 (기본: "%{", "}%")
 * - 가변 파라미터 전달 (단일 값 / Collection ⇒ Resolver 에 List 로 전달)
 * - 대소문자 무시 옵션 (ignoreCase=true) 지원 (단, 충돌 키 존재 시 예외)
 * - 미등록 토큰 무시 옵션(ignoreMissing) 및 기본값 문법 %{token|fallback}% 지원
 * - Spring 환경에서는 `VariableResolverRegistry` 다중 빈 자동 수집
 * - 순수 Kotlin / Java 환경 new 로 직접 생성 가능
 *
 * Quick Start: common-core 모듈 README 의 "Quick Start" 스니펫 (Kotlin/Java) 참조.
 * 상세 옵션 표: common-core/docs/variable-processor.md
 */

/**
 * Registry of variable resolver functions.
 *
 * 각 엔트리는 템플릿 내 토큰 이름을 키로,
 * 파라미터 리스트(List<Any?>)를 받아 실제 치환값을 리턴하는 함수를 값으로 가진다.
 * 하나의 Resolver 는 0..N 개 인자를 받을 수 있으며, 호출 시 Collection 전달값은 평탄화되어 List 로 넘어온다.
 */
interface VariableResolverRegistry {
    val resolvers: Map<String, (List<Any?>) -> Any>
}

/**
 * 템플릿 문자열 안의 토큰을 찾아 변수 해석을 수행하는 프로세서.
 * 기본 토큰 구분자는 "%{token}%" 형태.
 * 필요 시 다른 구분자(open / close)를 지정하여 동적으로 치환 가능.
 *
 * 예)
 *  기본: "Hello %{name}%" -> open="%{", close="}%"
 *  커스텀: open="\${", close="}" -> "Hello \${name}" 형태 지원
 */
@Component
class VariableProcessor(registries: List<VariableResolverRegistry>) {
    private val resolverMap: Map<String,(List<Any?>)->Any> =
        registries.flatMap { it.resolvers.entries }.associate { it.key to it.value }

    private val resolverMapInsensitive: Map<String,(List<Any?>)->Any> =
        resolverMap.entries.groupBy { it.key.lowercase() }.mapValues { (_, v) -> v.first().value }

    private val lowerKeyCollisions: Map<String,List<String>> =
        resolverMap.keys.groupBy { it.lowercase() }.filter { it.value.size > 1 }

    /** 토큰 구분자 정의 */
    data class Delimiters(val open: String = "%{", val close: String = "}%") {
        init {
            require(open.isNotEmpty()) { "open delimiter는 비어 있을 수 없습니다" }
            require(close.isNotEmpty()) { "close delimiter는 비어 있을 수 없습니다" }
        }
    }

    private val patternCache = ConcurrentHashMap<Delimiters, Regex>()

    private fun regexOf(delims: Delimiters): Regex = patternCache.computeIfAbsent(delims) {
        Regex("${Regex.escape(it.open)}(.*?)${Regex.escape(it.close)}")
    }

    /** 변환 옵션 */
    data class Options(
        val delimiters: Delimiters = Delimiters(),
        val ignoreCase: Boolean = true,
        val ignoreMissing: Boolean = false,
        val enableDefaultValue: Boolean = false,
        val defaultDelimiter: Char = '|',
        val escapeChar: Char = '\\'
    )

    private fun internalProcess(
        template: String,
        params: Array<out Pair<String, Any?>>,
        options: Options
    ): String {
        if (template.isEmpty()) return template
        if (options.ignoreCase && lowerKeyCollisions.isNotEmpty()) {
            throw IllegalStateException(
                "대소문자 무시(ignoreCase=true) 불가: 충돌 토큰 = " + lowerKeyCollisions.values.joinToString { "$it" }
            )
        }
        val origParamMap = params.toMap()
        val paramMapInsensitive = if (!options.ignoreCase) emptyMap() else origParamMap.mapKeys { it.key.lowercase() }
        val regex = regexOf(options.delimiters)

        return regex.replace(template) { m ->
            val rawToken = m.groupValues[1]
            val (tokenName, defaultValue) = parseToken(rawToken, options)
            val lookupKey = if (!options.ignoreCase) tokenName else tokenName.lowercase()
            val function = if (!options.ignoreCase) resolverMap[lookupKey] else resolverMapInsensitive[lookupKey]

            if (function == null) {
                if (defaultValue != null) {
                    if (options.ignoreMissing) return@replace defaultValue
                    else throw IllegalArgumentException("지원하지 않는 변수명: $tokenName")
                }
                if (options.ignoreMissing) return@replace m.value
                throw IllegalArgumentException("지원하지 않는 변수명: $tokenName")
            }
            val argument = if (!options.ignoreCase) origParamMap[tokenName] else paramMapInsensitive[lookupKey]
            val args = when (argument) {
                null -> emptyList()
                is Collection<*> -> argument.toList()
                else -> listOf(argument)
            }
            if (args.isEmpty() && defaultValue != null) return@replace defaultValue
            function(args).toString()
        }
    }

    private fun parseToken(raw: String, options: Options): Pair<String, String?> {
        if (!options.enableDefaultValue) return raw to null
        var escape = false
        val sbName = StringBuilder()
        var defaultPart: StringBuilder? = null
        for (c in raw) {
            when {
                escape -> { (defaultPart ?: sbName).append(c); escape = false }
                c == options.escapeChar -> escape = true
                c == options.defaultDelimiter && defaultPart == null -> defaultPart = StringBuilder()
                else -> (defaultPart ?: sbName).append(c)
            }
        }
        return if (defaultPart == null) raw to null else sbName.toString() to defaultPart.toString()
    }

    /** 기본 동작 (ignoreCase=true) */
    fun process(template: String, vararg params: Pair<String, Any?>): String =
        internalProcess(template, params, Options(ignoreCase = true))

    /** Map 파라미터 버전 (ignoreCase=true) */
    fun process(template: String, params: Map<String, Any?>): String =
        internalProcess(template, params.entries.map { it.key to it.value }.toTypedArray(), Options(ignoreCase = true))

    /** ignoreCase 값을 직접 지정하는 오버로드 */
    fun process(template: String, ignoreCase: Boolean, vararg params: Pair<String, Any?>): String =
        internalProcess(template, params, Options(ignoreCase = ignoreCase))

    /** 커스텀 구분자를 지정하는 오버로드 (vararg) */
    fun process(template: String, delimiters: Delimiters, vararg params: Pair<String, Any?>): String =
        internalProcess(template, params, Options(delimiters = delimiters))

    /** 커스텀 구분자 + Map */
    fun process(template: String, delimiters: Delimiters, params: Map<String, Any?>): String =
        internalProcess(template, params.entries.map { it.key to it.value }.toTypedArray(), Options(delimiters = delimiters))

    /** 모든 설정(구분자/ignoreMissing/기본값/대소문자)을 한 번에 지정 (vararg) */
    fun process(template: String, options: Options, vararg params: Pair<String, Any?>): String =
        internalProcess(template, params, options)

    /** 모든 설정 + Map */
    fun process(template: String, options: Options, params: Map<String, Any?>): String =
        internalProcess(template, params.entries.map { it.key to it.value }.toTypedArray(), options)
}
