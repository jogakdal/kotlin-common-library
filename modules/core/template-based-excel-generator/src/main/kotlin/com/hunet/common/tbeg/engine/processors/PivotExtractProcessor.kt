package com.hunet.common.tbeg.engine.processors

import com.hunet.common.tbeg.PivotTableProcessor
import com.hunet.common.tbeg.engine.ExcelProcessor
import com.hunet.common.tbeg.engine.ProcessingContext

/**
 * 피벗 테이블 추출 프로세서.
 *
 * 템플릿에서 피벗 테이블 정보를 추출하고 제거합니다.
 * 반복 영역 처리 시 피벗 테이블이 함께 확장되는 것을 방지하기 위함입니다.
 *
 * 추출된 정보는 context.pivotTableInfos에 저장되며,
 * 나중에 [PivotRecreateProcessor]가 재생성합니다.
 */
internal class PivotExtractProcessor(
    private val pivotTableProcessor: PivotTableProcessor
) : ExcelProcessor {

    override val name: String = "PivotExtract"

    override fun process(context: ProcessingContext): ProcessingContext {
        val (pivotTableInfos, bytesWithoutPivot) = pivotTableProcessor.extractAndRemove(context.resultBytes)
        context.pivotTableInfos = pivotTableInfos
        context.resultBytes = bytesWithoutPivot
        return context
    }
}
