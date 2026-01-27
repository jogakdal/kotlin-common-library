package com.hunet.common.tbeg.engine.rendering

import com.hunet.common.tbeg.ExcelDataProvider
import com.hunet.common.tbeg.StreamingMode
import com.hunet.common.lib.VariableProcessor
import java.io.InputStream
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * 템플릿 렌더링 엔진 - Excel 템플릿 기반 데이터 바인딩
 *
 * ## 지원 문법
 * - `${변수명}` - 단순 변수 치환
 * - `${item.field}` - 반복 항목의 필드 접근
 * - `${object.method()}` - 메서드 호출 (예: `${employees.size()}`)
 * - `${repeat(collection, range, var)}` - 반복 처리
 * - `${image.name}` - 이미지 삽입
 * - `HYPERLINK("${url}", "${text}")` - 수식 내 변수 치환
 *
 * ## 처리 방식
 * - **비스트리밍 모드**: XSSF 기반 템플릿 변환 (shiftRows + copyRowFrom)
 * - **스트리밍 모드**: SXSSF 기반 순차 생성 (청사진 기반)
 *
 * ## Strategy Pattern
 * 내부적으로 RenderingStrategy를 사용하여 XSSF/SXSSF 모드를 분리합니다.
 * - [XssfRenderingStrategy]: 비스트리밍 모드
 * - [SxssfRenderingStrategy]: 스트리밍 모드
 */
class TemplateRenderingEngine(
    streamingMode: StreamingMode = StreamingMode.ENABLED
) {
    private val analyzer = TemplateAnalyzer()
    private val variableProcessor = VariableProcessor(emptyList())
    private val imageInserter = ImageInserter()
    private val repeatExpansionProcessor = RepeatExpansionProcessor()
    private val sheetLayoutApplier = SheetLayoutApplier()

    private val fieldCache = mutableMapOf<Pair<Class<*>, String>, Field?>()
    private val getterCache = mutableMapOf<Pair<Class<*>, String>, Method?>()

    private val strategy: RenderingStrategy = when (streamingMode) {
        StreamingMode.DISABLED -> XssfRenderingStrategy()
        StreamingMode.ENABLED -> SxssfRenderingStrategy()
    }

    /**
     * 렌더링 컨텍스트 생성
     */
    private fun createRenderingContext(): RenderingContext = RenderingContext(
        analyzer = analyzer,
        imageInserter = imageInserter,
        repeatExpansionProcessor = repeatExpansionProcessor,
        sheetLayoutApplier = sheetLayoutApplier,
        evaluateText = ::evaluateText,
        resolveFieldPath = ::resolveFieldPath
    )

    /**
     * 템플릿에 데이터를 바인딩하여 Excel 생성
     */
    fun process(template: InputStream, data: Map<String, Any>): ByteArray {
        val templateBytes = template.readBytes()
        val renderingContext = createRenderingContext()
        return strategy.render(templateBytes, data, renderingContext)
    }

    /**
     * 템플릿에 DataProvider 데이터를 바인딩하여 Excel 생성
     *
     * @param template 템플릿 입력 스트림
     * @param dataProvider 데이터 제공자
     * @param requiredNames 템플릿에서 필요로 하는 데이터 이름 (선택적)
     */
    fun process(
        template: InputStream,
        dataProvider: ExcelDataProvider,
        requiredNames: RequiredNames? = null
    ): ByteArray {
        val data = buildDataMap(dataProvider, requiredNames)
        return process(template, data)
    }

    private fun buildDataMap(
        dataProvider: ExcelDataProvider,
        requiredNames: RequiredNames?
    ): Map<String, Any> = buildMap {
        requiredNames?.let { names ->
            names.variables.forEach { name ->
                dataProvider.getValue(name)?.let { put(name, it) }
            }
            names.collections.forEach { name ->
                dataProvider.getItems(name)?.let { put(name, it.asSequence().toList()) }
            }
            names.images.forEach { name ->
                dataProvider.getImage(name)?.let { put("image.$name", it) }
            }
        }
    }

    private fun evaluateText(text: String, data: Map<String, Any>): String {
        @Suppress("UNCHECKED_CAST")
        return variableProcessor.processWithData(text, data as Map<String, Any?>)
    }

    private fun resolveFieldPath(obj: Any?, fieldPath: String): Any? {
        if (obj == null) return null

        val fields = fieldPath.split(".")
        var current: Any? = obj

        for (field in fields) {
            current = when (current) {
                is Map<*, *> -> current[field]
                else -> resolveField(current!!, field)
            }
            if (current == null) break
        }

        return current
    }

    /**
     * 리플렉션으로 필드/getter 값을 가져옴 (캐싱 적용)
     */
    private fun resolveField(obj: Any, field: String): Any? {
        val clazz = obj::class.java
        val cacheKey = clazz to field

        val cachedField = fieldCache.getOrPut(cacheKey) {
            runCatching {
                clazz.getDeclaredField(field).apply { isAccessible = true }
            }.getOrNull()
        }

        if (cachedField != null) {
            return runCatching { cachedField.get(obj) }.getOrNull()
        }

        val cachedGetter = getterCache.getOrPut(cacheKey) {
            runCatching {
                clazz.getMethod("get${field.replaceFirstChar { it.uppercase() }}")
            }.getOrNull()
        }

        return cachedGetter?.let { runCatching { it.invoke(obj) }.getOrNull() }
    }
}
