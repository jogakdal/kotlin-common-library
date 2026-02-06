# TBEG 개발자 가이드

이 문서는 TBEG 라이브러리의 내부 아키텍처와 확장 방법을 설명합니다.

## 목차
1. [아키텍처 개요](#1-아키텍처-개요)
2. [파이프라인 패턴](#2-파이프라인-패턴)
3. [렌더링 전략](#3-렌더링-전략)
4. [위치 계산](#4-위치-계산)
5. [스트리밍 데이터 처리](#5-스트리밍-데이터-처리)
6. [테스트 작성 가이드](#6-테스트-작성-가이드)

---

## 1. 아키텍처 개요

### 1.1 모듈 구조

```
com.hunet.common.tbeg/
├── ExcelGenerator.kt          # 메인 진입점
├── ExcelGeneratorConfig.kt    # 설정 클래스
├── ExcelDataProvider.kt       # 데이터 제공 인터페이스
├── SimpleDataProvider.kt      # 기본 데이터 제공자 구현
├── DocumentMetadata.kt        # 문서 메타데이터
├── Enums.kt                   # 열거형 정의
├── async/                     # 비동기 처리
│   ├── GenerationJob.kt
│   ├── GenerationResult.kt
│   ├── ExcelGenerationListener.kt
│   └── ProgressInfo.kt
├── engine/
│   ├── core/                  # 핵심 유틸리티
│   │   ├── ExcelUtils.kt
│   │   ├── ChartProcessor.kt
│   │   ├── PivotTableProcessor.kt
│   │   └── XmlVariableProcessor.kt
│   ├── pipeline/              # 파이프라인 패턴
│   │   ├── ExcelPipeline.kt
│   │   ├── ExcelProcessor.kt
│   │   ├── ProcessingContext.kt
│   │   └── processors/        # 개별 프로세서
│   └── rendering/             # 렌더링 엔진
│       ├── RenderingStrategy.kt
│       ├── AbstractRenderingStrategy.kt
│       ├── XssfRenderingStrategy.kt
│       ├── SxssfRenderingStrategy.kt
│       ├── TemplateRenderingEngine.kt
│       ├── TemplateAnalyzer.kt
│       ├── PositionCalculator.kt
│       ├── StreamingDataSource.kt
│       └── WorkbookSpec.kt
├── exception/                 # 예외 클래스
└── spring/                    # Spring Boot 자동 설정
```

### 1.2 처리 흐름

```
템플릿 + 데이터
       │
       ▼
───────────────────────────────────────────────────────────────
                       ExcelPipeline
───────────────────────────────────────────────────────────────
   1. ChartExtractProcessor       - 차트 추출 (SXSSF 손실 방지)
   2. PivotExtractProcessor       - 피벗 테이블 정보 추출
   3. TemplateRenderProcessor     - 템플릿 렌더링 (데이터 바인딩)
   4. NumberFormatProcessor       - 숫자 서식 적용
   5. XmlVariableReplaceProcessor - XML 내 변수 치환
   6. PivotRecreateProcessor      - 피벗 테이블 재생성
   7. ChartRestoreProcessor       - 차트 복원
   8. MetadataProcessor           - 문서 메타데이터 적용
───────────────────────────────────────────────────────────────
       │
       ▼
  생성된 Excel
```

---

## 2. 파이프라인 패턴

### 2.1 ExcelPipeline

파이프라인은 여러 프로세서를 순차적으로 실행합니다.

```kotlin
class ExcelPipeline(vararg processors: ExcelProcessor) {
    fun execute(context: ProcessingContext): ProcessingContext
}
```

### 2.2 ExcelProcessor

각 프로세서는 특정 처리 단계를 담당합니다.

```kotlin
interface ExcelProcessor {
    fun process(context: ProcessingContext): ProcessingContext
}
```

### 2.3 ProcessingContext

프로세서 간 데이터를 전달하는 컨텍스트입니다.

```kotlin
data class ProcessingContext(
    val templateBytes: ByteArray,
    val dataProvider: ExcelDataProvider,
    val config: ExcelGeneratorConfig,
    val metadata: DocumentMetadata? = null,
    var resultBytes: ByteArray = ByteArray(0),
    var processedRowCount: Int = 0,
    // 프로세서 간 공유 데이터
    var chartInfoList: List<ChartInfo> = emptyList(),
    var pivotInfoList: List<PivotInfo> = emptyList(),
    var workbookSpec: WorkbookSpec? = null
)
```

### 2.4 프로세서 구현 예시

```kotlin
class TemplateRenderProcessor : ExcelProcessor {
    override fun process(context: ProcessingContext): ProcessingContext {
        val engine = TemplateRenderingEngine(context.config.streamingMode)

        val resultBytes = engine.process(
            ByteArrayInputStream(context.templateBytes),
            context.dataProvider,
            context.workbookSpec?.extractRequiredNames()
        )

        return context.copy(resultBytes = resultBytes)
    }
}
```

---

## 3. 렌더링 전략

### 3.1 Strategy 패턴

렌더링 모드(XSSF/SXSSF)에 따라 다른 전략을 사용합니다.

```kotlin
sealed interface RenderingStrategy {
    fun render(
        templateBytes: ByteArray,
        data: Map<String, Any>,
        context: RenderingContext
    ): ByteArray
}

class XssfRenderingStrategy : RenderingStrategy
class SxssfRenderingStrategy : RenderingStrategy
```

### 3.2 XSSF vs SXSSF

| 특성    | XSSF           | SXSSF       |
|-------|----------------|-------------|
| 메모리   | 전체 워크북 메모리 로드  | 윈도우 기반 스트리밍 |
| 행 삽입  | shiftRows() 지원 | 순차 출력만 가능   |
| 수식 참조 | 자동 조정          | 수동 조정 필요    |
| 대용량   | 제한적            | 적합          |

### 3.3 AbstractRenderingStrategy

공통 로직을 추상 클래스로 분리합니다.

```kotlin
abstract class AbstractRenderingStrategy : RenderingStrategy {
    protected fun evaluateCellContent(
        content: CellContent,
        data: Map<String, Any>,
        context: RenderingContext
    ): Any?

    protected fun applyCellStyle(cell: Cell, styleIndex: Short, workbook: Workbook)
}
```

---

## 4. 위치 계산

### 4.1 PositionCalculator

다중 repeat 영역의 위치를 계산합니다.

```kotlin
class PositionCalculator(
    repeatRegions: List<RepeatRegionSpec>,
    collectionSizes: Map<String, Int>,
    templateLastRow: Int = -1
) {
    // 모든 repeat 확장 정보 계산
    fun calculate(): List<RepeatExpansion>

    // 템플릿 위치 -> 최종 위치 변환
    fun getFinalPosition(templateRow: Int, templateCol: Int): Pair<Int, Int>

    // 범위의 최종 위치 계산
    fun getFinalRange(
        templateFirstRow: Int, templateLastRow: Int,
        templateFirstCol: Int, templateLastCol: Int
    ): CellRangeAddress

    // 실제 출력 행 정보 조회
    fun getRowInfo(actualRow: Int): RowInfo
}
```

### 4.2 RepeatExpansion

```kotlin
data class RepeatExpansion(
    val region: RepeatRegionSpec,    // 원본 repeat 영역
    val finalStartRow: Int,          // 확장 시작 행
    val finalStartCol: Int,          // 확장 시작 열
    val rowExpansion: Int,           // 행 확장량
    val colExpansion: Int,           // 열 확장량
    val itemCount: Int               // 아이템 수
)
```

### 4.3 위치 계산 규칙

1. **독립 요소**: 어느 repeat에도 영향받지 않으면 템플릿 위치 유지
2. **단일 영향**: 하나의 repeat에만 영향받으면 해당 확장량만큼 이동
3. **다중 영향**: 여러 repeat에 영향받으면 최대 오프셋 적용

### 4.4 열 그룹

같은 열 범위의 repeat는 서로 영향을 주고, 다른 열 그룹의 repeat는 독립적으로 확장됩니다.

```kotlin
data class ColumnGroup(
    val groupId: Int,
    val startCol: Int,
    val endCol: Int,
    val repeatRegions: List<RepeatRegionSpec>
)
```

---

## 5. 스트리밍 데이터 처리

### 5.1 StreamingDataSource

SXSSF 모드에서 Iterator를 순차적으로 소비합니다.

```kotlin
class StreamingDataSource(
    private val dataProvider: ExcelDataProvider,
    private val expectedSizes: Map<String, Int>
) : Closeable {
    // repeat 영역별 현재 아이템
    fun advanceToNextItem(repeatKey: RepeatKey): Any?
    fun getCurrentItem(repeatKey: RepeatKey): Any?
}
```

### 5.2 메모리 최적화 원칙

| 모드    | 메모리 정책           |
|-------|------------------|
| SXSSF | 현재 아이템만 메모리 유지   |
| XSSF  | 전체 데이터 메모리 로드 허용 |

### 5.3 DataProvider 조건

- `getItems()`는 같은 데이터를 다시 제공할 수 있어야 함
- 같은 컬렉션이 여러 repeat에서 사용되면 `getItems()` 재호출

---

## 6. 테스트 작성 가이드

### 6.1 테스트 유형

| 유형     | 위치                | 설명             |
|--------|-------------------|----------------|
| 단위 테스트 | `src/test/kotlin` | 개별 클래스/함수 테스트  |
| 통합 테스트 | `src/test/kotlin` | 전체 파이프라인 테스트   |
| 샘플 테스트 | `build/samples`   | 실제 Excel 파일 생성 |

### 6.2 테스트 템플릿

테스트용 템플릿은 `src/test/resources/templates/`에 위치합니다.

### 6.3 단위 테스트 예시

```kotlin
class PositionCalculatorTest {
    @Test
    fun `단일 repeat 확장 계산`() {
        val regions = listOf(
            RepeatRegionSpec("items", "item", 2, 2, 0, 2, RepeatDirection.DOWN)
        )
        val sizes = mapOf("items" to 5)

        val calculator = PositionCalculator(regions, sizes)
        val expansions = calculator.calculate()

        assertEquals(1, expansions.size)
        assertEquals(4, expansions[0].rowExpansion)  // (5-1) * 1행
    }
}
```

### 6.4 통합 테스트 예시

```kotlin
class ExcelGeneratorIntegrationTest {
    @Test
    fun `반복 데이터 처리`() {
        val data = mapOf(
            "items" to listOf(
                mapOf("name" to "A", "value" to 100),
                mapOf("name" to "B", "value" to 200)
            )
        )

        ExcelGenerator().use { generator ->
            val template = javaClass.getResourceAsStream("/templates/repeat.xlsx")!!
            val bytes = generator.generate(template, data)

            XSSFWorkbook(ByteArrayInputStream(bytes)).use { workbook ->
                val sheet = workbook.getSheetAt(0)
                assertEquals("A", sheet.getRow(0).getCell(0).stringCellValue)
                assertEquals("B", sheet.getRow(1).getCell(0).stringCellValue)
            }
        }
    }
}
```

### 6.5 테스트 실행

```bash
# 전체 테스트
./gradlew :tbeg:test

# 특정 테스트
./gradlew :tbeg:test --tests "*PositionCalculator*"
```

---

## 확장 포인트

### 커스텀 DataProvider

특수한 데이터 소스가 필요한 경우 `ExcelDataProvider`를 직접 구현합니다.

```kotlin
class DatabaseDataProvider(
    private val dataSource: DataSource
) : ExcelDataProvider {
    override fun getValue(name: String): Any? = /* SQL 쿼리 */
    override fun getItems(name: String): Iterator<Any>? = /* 스트리밍 쿼리 */
    override fun getItemCount(name: String): Int? = /* COUNT 쿼리 */
}
```

### 커스텀 프로세서

파이프라인에 새로운 처리 단계를 추가할 수 있습니다.

```kotlin
class WatermarkProcessor : ExcelProcessor {
    override fun process(context: ProcessingContext): ProcessingContext {
        // 워터마크 추가 로직
        return context
    }
}
```

---

## 다음 단계

- [API 레퍼런스](./reference/api-reference.md) - API 상세
- [설정 옵션](./reference/configuration.md) - 설정 옵션
