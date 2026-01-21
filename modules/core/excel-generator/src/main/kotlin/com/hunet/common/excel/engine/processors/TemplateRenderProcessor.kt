package com.hunet.common.excel.engine.processors

import com.hunet.common.excel.engine.ExcelProcessor
import com.hunet.common.excel.engine.ProcessingContext
import com.hunet.common.excel.engine.TemplateRenderingEngine
import java.io.ByteArrayInputStream

/**
 * 템플릿 렌더링 프로세서.
 *
 * TemplateRenderingEngine을 사용하여 템플릿에 데이터를 바인딩합니다.
 * 스트리밍 모드 설정에 따라 내부적으로 XSSF 또는 SXSSF 전략을 사용합니다.
 *
 * - 반복 영역 확장
 * - 변수 치환
 * - 이미지 삽입
 * - 수식 조정
 */
internal class TemplateRenderProcessor : ExcelProcessor {

    override val name: String = "TemplateRender"

    override fun process(context: ProcessingContext): ProcessingContext {
        // processedRowCount 계산
        var totalRows = 0
        context.dataProvider.getAvailableNames().forEach { name ->
            context.dataProvider.getItems(name)?.let { iterator ->
                totalRows += iterator.asSequence().count()
            }
        }
        context.processedRowCount = totalRows

        // 템플릿 렌더링
        val engine = TemplateRenderingEngine(context.config.streamingMode)
        context.resultBytes = engine.process(
            ByteArrayInputStream(context.resultBytes),
            context.dataProvider
        )
        return context
    }
}
