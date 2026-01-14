package com.hunet.common.excel

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase

/**
 * JXLS PoiTransformer의 수식 에러 로그를 캡처하는 Logback Appender.
 *
 * JXLS가 수식을 설정하다가 실패하면 다음과 같은 에러 로그를 남깁니다:
 * ```
 * ERROR org.jxls.transform.poi.PoiTransformer -- Failed to set formula = SUM(...) into cell = 'Sheet1'!B10
 * ```
 *
 * 이 Appender는 해당 로그를 캡처하여 에러 정보를 추출합니다.
 */
internal class FormulaErrorCapturingAppender : AppenderBase<ILoggingEvent>() {

    private var capturedError: FormulaErrorInfo? = null

    /**
     * 캡처된 수식 에러 정보
     */
    data class FormulaErrorInfo(
        val sheetName: String,
        val cellRef: String,
        val formula: String
    )

    override fun append(event: ILoggingEvent) {
        // ERROR 레벨이고 "Failed to set formula" 메시지인 경우만 처리
        // 이미 에러가 캡처되었으면 첫 번째 에러만 유지
        if (event.level != Level.ERROR || capturedError != null) return

        event.message
            ?.takeIf { it.startsWith("Failed to set formula") }
            ?.let { parseErrorMessage(it) }
            ?.also { capturedError = it }
    }

    /**
     * 에러 메시지를 파싱하여 FormulaErrorInfo를 생성합니다.
     */
    private fun parseErrorMessage(message: String): FormulaErrorInfo? {
        // 정규식으로 파싱: Failed to set formula = (...) into cell = '(...)'!(...)
        val match = ERROR_MESSAGE_PATTERN.find(message) ?: return null
        val (formula, sheetName, cellRef) = match.destructured

        return FormulaErrorInfo(
            sheetName = sheetName,
            cellRef = cellRef,
            formula = formula.shortenFormula()
        )
    }

    private fun String.shortenFormula() = if (length > 100) {
        FUNCTION_NAME_PATTERN.find(this)?.let { "${it.groupValues[1]}(...)" }
            ?: take(50) + "..."
    } else this

    companion object {
        private val ERROR_MESSAGE_PATTERN =
            Regex("""Failed to set formula = (.+) into cell = '([^']+)'!(\w+)""")
        private val FUNCTION_NAME_PATTERN = Regex("""^([A-Z]+)\(""")
    }

    /**
     * 캡처된 에러 정보를 반환합니다.
     */
    fun getCapturedError(): FormulaErrorInfo? = capturedError

    /**
     * 캡처된 에러를 초기화합니다.
     */
    fun clear() {
        capturedError = null
    }
}
