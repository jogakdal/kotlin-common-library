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
        if (event.level != Level.ERROR) return

        val message = event.message ?: return
        if (!message.startsWith("Failed to set formula")) return

        // 이미 에러가 캡처되었으면 첫 번째 에러만 유지
        if (capturedError != null) return

        // 메시지 파싱: "Failed to set formula = SUM(...) into cell = 'SheetName'!B10"
        capturedError = parseErrorMessage(message)
    }

    /**
     * 에러 메시지를 파싱하여 FormulaErrorInfo를 생성합니다.
     */
    private fun parseErrorMessage(message: String): FormulaErrorInfo? {
        // 정규식으로 파싱: Failed to set formula = (...) into cell = '(...)'!(...)
        val regex = Regex("""Failed to set formula = (.+) into cell = '([^']+)'!(\w+)""")
        val match = regex.find(message) ?: return null

        val formula = match.groupValues[1]
        val sheetName = match.groupValues[2]
        val cellRef = match.groupValues[3]

        // 수식이 너무 긴 경우 함수명만 추출
        val shortFormula = if (formula.length > 100) {
            val funcMatch = Regex("""^([A-Z]+)\(""").find(formula)
            if (funcMatch != null) {
                "${funcMatch.groupValues[1]}(...)"
            } else {
                formula.take(50) + "..."
            }
        } else {
            formula
        }

        return FormulaErrorInfo(
            sheetName = sheetName,
            cellRef = cellRef,
            formula = shortFormula
        )
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
