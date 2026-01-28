# TBEG 유지보수 개발자 가이드

## 목차
1. [아키텍처 개요](#1-아키텍처-개요)
2. [모듈 구조](#2-모듈-구조)
3. [핵심 컴포넌트](#3-핵심-컴포넌트)
4. [처리 파이프라인](#4-처리-파이프라인)
5. [렌더링 전략](#5-렌더링-전략)
6. [서식 유지 원칙](#6-서식-유지-원칙)
7. [테스트 가이드](#7-테스트-가이드)
8. [확장 포인트](#8-확장-포인트)
9. [알려진 제한 사항](#9-알려진-제한-사항)

---

## 1. 아키텍처 개요

### 설계 원칙

1. **템플릿 기반**: 디자이너가 작성한 Excel 템플릿을 기반으로 데이터만 바인딩
2. **서식 완전 보존**: 템플릿의 모든 서식을 생성 결과에 그대로 유지
3. **전략 패턴**: XSSF/SXSSF 렌더링 전략으로 메모리 효율성과 기능 완전성 중 선택
4. **파이프라인 패턴**: 독립적인 프로세서들의 조합으로 처리 흐름 구성
5. **지연 로딩**: 대용량 데이터 처리를 위한 Iterator 기반 데이터 제공

### 기술 스택

| 기술 | 버전 | 용도 |
|------|------|------|
| Apache POI | 5.x | Excel 파일 조작 (XSSF/SXSSF) |
| Kotlin Coroutines | 1.8.x | 비동기 처리 |
| Spring Boot | 3.4.x | 자동 설정 (선택) |

### 전체 아키텍처

```
┌─────────────────────────────────────────────────────────────┐
│                     ExcelGenerator                          │
│                    (Public API)                             │
└─────────────────────┬───────────────────────────────────────┘
                      │
┌─────────────────────▼───────────────────────────────────────┐
│                    ExcelPipeline                            │
│  ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐            │
│  │ Extract │→│ Render  │→│ Restore │→│Metadata │→ ...       │
│  │  Chart  │ │Template │ │  Chart  │ │         │            │
│  └─────────┘ └─────────┘ └─────────┘ └─────────┘            │
└─────────────────────┬───────────────────────────────────────┘
                      │
┌─────────────────────▼───────────────────────────────────────┐
│              TemplateRenderingEngine                        │
│  ┌─────────────────────┐    ┌──────────────────────┐        │
│  │XssfRenderingStrategy│    │SxssfRenderingStrategy│        │
│  │ (비스트리밍)           │ or │ (스트리밍)             │        │
│  └─────────────────────┘    └──────────────────────┘        │
└─────────────────────────────────────────────────────────────┘
```

---

## 2. 모듈 구조

```
src/main/kotlin/com/hunet/common/tbeg/
├── ExcelGenerator.kt                       # 메인 진입점 (Public API)
├── ExcelDataProvider.kt                    # 데이터 제공 인터페이스
├── SimpleDataProvider.kt                   # 간단한 DataProvider 구현
├── ExcelGeneratorConfig.kt                 # 설정 클래스
├── DocumentMetadata.kt                     # 문서 메타데이터
├── Enums.kt                                # StreamingMode, FileNamingMode 등 열거형
│
├── async/                                  # 비동기 처리
│   ├── ExcelGenerationListener.kt          # 생성 이벤트 리스너
│   ├── GenerationJob.kt                    # 비동기 작업 핸들
│   ├── GenerationResult.kt                 # 생성 결과
│   └── ProgressInfo.kt                     # 진행률 정보
│
├── engine/                                 # 내부 엔진 (internal)
│   ├── core/                               # 핵심 프로세서
│   │   ├── ChartProcessor.kt               # 차트 추출/복원
│   │   ├── PivotTableProcessor.kt          # 피벗 테이블 처리
│   │   ├── XmlVariableProcessor.kt         # XML 내 변수 치환
│   │   └── ExcelUtils.kt                   # 유틸리티 함수
│   │
│   ├── pipeline/                           # 처리 파이프라인
│   │   ├── ExcelPipeline.kt                # 파이프라인 정의
│   │   ├── ExcelProcessor.kt               # 프로세서 인터페이스
│   │   ├── ProcessingContext.kt            # 처리 컨텍스트
│   │   └── processors/                     # 개별 프로세서
│   │       ├── ChartExtractProcessor.kt
│   │       ├── ChartRestoreProcessor.kt
│   │       ├── MetadataProcessor.kt
│   │       ├── NumberFormatProcessor.kt
│   │       ├── PivotExtractProcessor.kt
│   │       ├── PivotRecreateProcessor.kt
│   │       ├── TemplateRenderProcessor.kt
│   │       └── XmlVariableReplaceProcessor.kt
│   │
│   └── rendering/                          # 렌더링 전략
│       ├── RenderingStrategy.kt            # 렌더링 전략 인터페이스
│       ├── AbstractRenderingStrategy.kt    # 공통 로직
│       ├── XssfRenderingStrategy.kt        # XSSF (비스트리밍)
│       ├── SxssfRenderingStrategy.kt       # SXSSF (스트리밍)
│       ├── TemplateRenderingEngine.kt      # 렌더링 엔진
│       ├── TemplateAnalyzer.kt             # 템플릿 분석기
│       ├── WorkbookSpec.kt                 # 워크북/시트/셀 명세
│       ├── ImageInserter.kt                # 이미지 삽입
│       ├── FormulaAdjuster.kt              # 수식 조정
│       ├── RepeatExpansionProcessor.kt     # 반복 영역 확장
│       └── SheetLayoutApplier.kt           # 레이아웃 적용
│
├── exception/                              # 예외 클래스
│   ├── TemplateProcessingException.kt
│   ├── MissingTemplateDataException.kt
│   └── FormulaExpansionException.kt
│
└── spring/                                 # Spring Boot 통합
    ├── TbegAutoConfiguration.kt            # 자동 설정
    └── TbegProperties.kt                   # 설정 속성
```

---

## 3. 핵심 컴포넌트

### 3.1 ExcelGenerator

메인 진입점으로, 파이프라인을 조율합니다.

**주요 책임:**
- 동기/비동기 API 제공
- 파이프라인 실행
- 리소스 관리 (Closeable)

```kotlin
class ExcelGenerator(config: ExcelGeneratorConfig) : Closeable {
    private val pipeline = ExcelPipeline(
        ChartExtractProcessor(chartProcessor),
        PivotExtractProcessor(pivotTableProcessor),
        TemplateRenderProcessor(),
        NumberFormatProcessor(),
        XmlVariableReplaceProcessor(xmlVariableProcessor),
        PivotRecreateProcessor(pivotTableProcessor),
        ChartRestoreProcessor(chartProcessor),
        MetadataProcessor()
    )
}
```

### 3.2 TemplateAnalyzer

템플릿을 분석하여 `WorkbookSpec`을 생성합니다.

**주요 책임:**
- 마커 파싱 (`${repeat(...)}`, `=TBEG_REPEAT(...)`, `${image.xxx}`, `=TBEG_IMAGE(...)`)
- 셀 내용 분석 (변수, 수식, 정적 값)
- 반복 영역 식별
- 병합 셀, 조건부 서식, 헤더/푸터 정보 추출

**지원 마커:**
```kotlin
// 반복 마커
private val REPEAT_PATTERN = Regex("""\$\{repeat\s*\(...\)\}""")
private val FORMULA_REPEAT_PATTERN = Regex("""TBEG_REPEAT\s*\(...\)""")

// 이미지 마커
private val IMAGE_LEGACY_PATTERN = Regex("""\$\{image\.(\w+)}""")
private val IMAGE_PATTERN = Regex("""\$\{image\(...\)}""")
private val FORMULA_IMAGE_PATTERN = Regex("""TBEG_IMAGE\s*\(...\)""")
```

### 3.3 RenderingStrategy

렌더링 전략 인터페이스와 구현체입니다.

```kotlin
interface RenderingStrategy {
    val name: String
    fun render(
        templateBytes: ByteArray,
        blueprint: WorkbookSpec,
        data: Map<String, Any>,
        config: ExcelGeneratorConfig
    ): RenderingResult
}
```

| 구현체 | 특징 |
|--------|------|
| `XssfRenderingStrategy` | 모든 POI 기능 지원, `shiftRows()`로 행 삽입, 수식 자동 조정 |
| `SxssfRenderingStrategy` | 메모리 효율적, 행 플러시로 대용량 처리, 일부 기능 제한 |

### 3.4 ProcessingContext

파이프라인 전체에서 공유되는 컨텍스트입니다.

```kotlin
class ProcessingContext(
    val templateBytes: ByteArray,
    val dataProvider: ExcelDataProvider,
    val config: ExcelGeneratorConfig,
    val metadata: DocumentMetadata?
) {
    var resultBytes: ByteArray = templateBytes
    var chartInfo: ChartProcessor.ChartInfo? = null
    var pivotTableInfos: List<PivotTableProcessor.PivotTableInfo> = emptyList()
    var variableResolver: ((String) -> String)? = null
    var processedRowCount: Int = 0
    var requiredNames: RequiredNames? = null
}
```

---

## 4. 처리 파이프라인

`ExcelPipeline`은 여러 `ExcelProcessor`를 순차적으로 실행합니다.

### 파이프라인 흐름

```
1. ChartExtractProcessor
   └─ 스트리밍 모드에서 차트 정보 추출 및 임시 저장

2. PivotExtractProcessor
   └─ 피벗 테이블 정보 추출 및 템플릿에서 제거

3. TemplateRenderProcessor (핵심)
   └─ TemplateAnalyzer로 템플릿 분석
   └─ RenderingStrategy로 반복 영역 확장 및 변수 치환
   └─ XSSF 또는 SXSSF 전략 선택

4. NumberFormatProcessor
   └─ 숫자 데이터에 자동 서식 적용

5. XmlVariableReplaceProcessor
   └─ 차트 제목, 도형 텍스트, 헤더/푸터 등 XML 내 변수 치환

6. PivotRecreateProcessor
   └─ 확장된 데이터 소스로 피벗 테이블 재생성

7. ChartRestoreProcessor
   └─ 스트리밍 모드에서 차트 복원

8. MetadataProcessor
   └─ 문서 메타데이터 적용 (제목, 작성자 등)
```

### 프로세서 인터페이스

```kotlin
interface ExcelProcessor {
    val name: String
    fun process(context: ProcessingContext): ProcessingContext
    fun shouldSkip(context: ProcessingContext): Boolean = false
}
```

---

## 5. 렌더링 전략

### 5.1 AbstractRenderingStrategy

두 전략의 공통 로직을 담당합니다.

**템플릿 메서드 패턴:**
```kotlin
abstract class AbstractRenderingStrategy : RenderingStrategy {
    // 훅 메서드 - 하위 클래스에서 오버라이드
    protected open fun beforeProcessSheets(...) {}
    protected abstract fun processSheet(...)
    protected open fun afterProcessSheets(...) {}

    // 공통 로직
    protected fun processCellContent(...): Boolean { ... }
    protected fun substituteVariable(...): Any? { ... }
}
```

### 5.2 XssfRenderingStrategy

**특징:**
- `XSSFWorkbook` 사용
- `shiftRows()`로 행 삽입 공간 확보
- `copyRowFrom()`으로 템플릿 행 복사
- 수식 참조 자동 조정
- 모든 POI 기능 사용 가능

**처리 흐름:**
```kotlin
override fun processSheet(...) {
    // 반복 영역 확장 (뒤에서부터 처리)
    for (repeatRow in repeatRows.reversed()) {
        expandRowsDown(sheet, repeatRow, items)
        // 또는 expandColumnsRight(...)
    }

    // 변수 치환
    substituteVariablesXssf(sheet, blueprint, data)
}
```

### 5.3 SxssfRenderingStrategy

**특징:**
- `SXSSFWorkbook` 사용 (메모리 효율적)
- 행 플러시로 메모리 사용량 최소화
- 조건부 서식/레이아웃 별도 복원 필요
- 일부 기능 제한 (위 행 참조 불가)

**처리 흐름:**
```kotlin
override fun processSheet(...) {
    // 템플릿 구조 기반으로 행 생성
    for (rowSpec in blueprint.rows) {
        when (rowSpec) {
            is RowSpec.StaticRow -> writeStaticRow(...)
            is RowSpec.RepeatRow -> writeRepeatRows(...)
        }
    }

    // 주기적 행 플러시
    if (currentRow % flushInterval == 0) {
        sheet.flushRows()
    }
}
```

---

## 6. 서식 유지 원칙

> **중요**: 이 원칙은 Excel 생성 관련 모든 코드 수정 시 반드시 준수해야 합니다.

### 6.1 템플릿 서식 완전 보존

템플릿에 작성된 모든 서식(정렬, 글꼴, 색상, 테두리, 채우기 등)은 생성된 Excel에 동일하게 적용되어야 합니다.

### 6.2 자동/반복 생성 셀의 서식 상속

- **반복 행**: 템플릿의 첫 번째 데이터 행 서식 적용
- **피벗 테이블 헤더**: 템플릿의 헤더 행 서식 적용
- **피벗 테이블 데이터**: 템플릿의 데이터 행 서식 적용

### 6.3 숫자 서식 자동 지정 예외

자동 생성되는 셀의 데이터가 숫자 타입이고, 템플릿 셀의 "표시 형식"이 "일반"인 경우에만 숫자 서식을 자동 지정합니다.

### 6.4 StyleInfo에서 유지해야 하는 서식 속성

```kotlin
data class StyleInfo(
    val horizontalAlignment: HorizontalAlignment?,   // 가로 정렬
    val verticalAlignment: VerticalAlignment?,       // 세로 정렬
    val fontBold: Boolean,                           // 굵게
    val fontItalic: Boolean,                         // 기울임꼴
    val fontUnderline: Byte,                         // 밑줄
    val fontStrikeout: Boolean,                      // 취소선
    val fontName: String?,                           // 글꼴 이름
    val fontSize: Short?,                            // 글꼴 크기
    val fontColorRgb: ByteArray?,                    // 글꼴 색상
    val fillForegroundColorRgb: ByteArray?,          // 채우기 색상
    val fillPatternType: FillPatternType?,           // 채우기 패턴
    val borderTop: BorderStyle?,                     // 테두리
    val borderBottom: BorderStyle?,
    val borderLeft: BorderStyle?,
    val borderRight: BorderStyle?,
    val dataFormat: Short                            // 표시 형식
)
```

---

## 7. 테스트 가이드

### 7.1 테스트 파일 위치

```
src/test/kotlin/com/hunet/common/tbeg/
├── engine/
│   └── TemplateRenderingEngineTest.kt  # 렌더링 엔진 테스트
├── ExcelGeneratorSample.kt             # 샘플 실행 (Kotlin)
└── spring/
    └── ExcelGeneratorSpringBootSample.kt

src/test/java/com/hunet/common/tbeg/
└── ExcelGeneratorJavaSample.java       # 샘플 실행 (Java)

src/test/resources/templates/
└── template.xlsx                       # 테스트 템플릿
```

### 7.2 테스트 실행

```bash
# 전체 테스트
./gradlew :tbeg:test

# 특정 테스트
./gradlew :tbeg:test --tests "*TemplateRenderingEngineTest*"

# 샘플 실행
./gradlew :tbeg:runSample
./gradlew :tbeg:runJavaSample
```

### 7.3 테스트 작성 가이드

```kotlin
@Test
fun `반복 데이터 생성 시 서식이 보존되어야 함`() {
    // Given
    val template = javaClass.getResourceAsStream("/templates/styled.xlsx")!!
    val data = mapOf(
        "items" to listOf(
            mapOf("name" to "item1", "value" to 100),
            mapOf("name" to "item2", "value" to 200)
        )
    )

    // When
    val generator = ExcelGenerator()
    val bytes = generator.generate(template, data)

    // Then
    XSSFWorkbook(ByteArrayInputStream(bytes)).use { workbook ->
        val sheet = workbook.getSheetAt(0)
        val dataRow = sheet.getRow(2)
        val cell = dataRow.getCell(0)

        // 서식 검증
        assertEquals(HorizontalAlignment.CENTER, cell.cellStyle.alignment)
        assertTrue(workbook.getFontAt(cell.cellStyle.fontIndex).bold)
    }

    generator.close()
}
```

---

## 8. 확장 포인트

### 8.1 새로운 마커 추가

`TemplateAnalyzer`에서 새로운 마커 패턴을 추가할 수 있습니다.

```kotlin
// 예: ${if(condition)} 마커 추가
private val IF_MARKER_PATTERN = Regex("""\$\{if\(([^)]+)\)\}""")

private fun analyzeStringContent(text: String): CellContent {
    IF_MARKER_PATTERN.find(text)?.let { match ->
        return CellContent.ConditionalMarker(match.groupValues[1])
    }
    // ...
}
```

### 8.2 새로운 프로세서 추가

`ExcelProcessor` 인터페이스를 구현하고 파이프라인에 추가합니다.

```kotlin
class ConditionalFormattingProcessor : ExcelProcessor {
    override val name = "ConditionalFormatting"

    override fun process(context: ProcessingContext): ProcessingContext {
        // 구현
        return context.copy(resultBytes = processedBytes)
    }
}

// ExcelGenerator에서 파이프라인에 추가
private val pipeline = ExcelPipeline(
    // ...
    ConditionalFormattingProcessor(),
    // ...
)
```

### 8.3 커스텀 DataProvider 구현

```kotlin
class DatabaseStreamDataProvider(
    private val dataSource: DataSource
) : ExcelDataProvider {
    override fun getItems(name: String): Iterator<Any>? {
        val connection = dataSource.connection
        val statement = connection.prepareStatement("SELECT * FROM $name")
        val resultSet = statement.executeQuery()
        return ResultSetIterator(resultSet)
    }
    // ...
}
```

---

## 9. 알려진 제한 사항

### 9.1 TBEG 라이브러리가 해결한 POI/SXSSF 제약

다음은 Apache POI 또는 SXSSF의 기본 제약이지만, TBEG 엔진이 별도 로직으로 해결한 항목들입니다.

| 항목         | POI/SXSSF 기본 제약        | TBEG 해결 방식                                                                                 |
|------------|------------------------|--------------------------------------------------------------------------------------------|
| 조건부 서식     | SXSSF에서 시트 정리 시 제거됨    | `SheetLayoutApplier.applyConditionalFormattings()` - 템플릿에서 추출 후 범위 조정하여 재적용, dxfId 리플렉션 복원 |
| 병합 셀       | SXSSF에서 반복 영역 확장 시 미적용 | `SheetLayoutApplier.applyMergedRegions()` - 반복 항목별로 병합 영역 복사                               |
| 머리글/바닥글 변수 | POI API로 직접 치환 불가      | `SheetLayoutApplier.applyHeaderFooter()` - 홀수/짝수/첫 페이지 헤더 개별 처리                            |
| 차트 보존      | SXSSF 처리 시 차트 손실       | `ChartProcessor` - ZIP 레벨에서 차트 XML 추출 후 처리 완료 시 복원                                         |
| 차트 내 변수    | POI Chart API로 접근 불가   | `XmlVariableProcessor` - XML 직접 스캔하여 변수 치환                                                 |
| 도형/커넥터     | SXSSF 처리 시 손실 가능       | `ChartProcessor` - drawing*.xml에서 도형/커넥터 포함하여 복원                                           |
| 수식 범위 조정   | SXSSF에서 shiftRows 불가   | `FormulaAdjuster` - 행/열 확장에 따른 수식 참조 자동 조정                                                 |
| 피벗 테이블     | 데이터 확장 시 소스 범위 불일치     | `PivotTableProcessor` - 확장된 범위로 피벗 재생성, 스타일/캐시 보존                                          |

### 9.2 남아있는 제한 사항

#### ⛔ 해결 불가 (Fundamental Limits)

다음은 Excel 자체의 근본적인 설계 한계로, 해결이 불가능한 항목들입니다.

| 항목              | 설명                                         | 원인                                 |
|-----------------|--------------------------------------------|------------------------------------|
| Excel 255 인수 제한 | `SUM(A1,A3,A5,...)` 형태의 비연속 참조가 255개 초과 불가 | Excel 수식 엔진의 하드 리밋. Excel 자체의 제약 |

#### 🔧 해결 가능 (Future Upgrade)

다음은 추가 개발을 통해 향후 해결 가능한 항목들입니다.

| 항목 | 현재 상태 | 해결 방안 |
|------|----------|----------|
| 중첩 repeat문 | 외부 repeat만 처리되고 내부 repeat은 무시됨. `buildRowSpecs()`에서 외부 repeat 범위 내의 행은 `skipUntil`로 건너뛰어 내부 repeat이 RepeatRow로 생성되지 않음 | 재귀적 분석: repeat 영역을 트리 구조로 분석 후 inside-out 처리. 또는 multi-pass 렌더링으로 가장 내부 repeat부터 순차 확장 |
| 플러시된 행 참조 | SXSSF 모드에서 이미 플러시된 행의 데이터 참조 불가 | Multi-pass 처리: 1차에서 데이터 구조 분석 후 참조 위치 계산, 2차에서 렌더링. 또는 SXSSF 완료 후 XSSF로 후처리 |
| 계산 필드/항목 | 피벗 테이블 재생성 시 계산 필드가 제거됨 | `cacheFields`의 계산 필드 XML 파싱 및 재생성 로직 추가 |
| 날짜/숫자 그룹화 | 피벗 테이블 재생성 시 그룹화 설정 초기화 | `pivotTableDefinition.xml`의 `fieldGroup` 요소 파싱 및 복원 |

### 9.3 성능 권장 사항

| 항목 | 권장 사항 |
|------|----------|
| 대용량 템플릿 | 수백 MB 이상의 템플릿은 메모리 부족 가능. 템플릿 최적화 권장 |
| 피벗 캐시 | 피벗 테이블이 많으면 캐시 재구축으로 처리 시간 증가 |
| 이미지 | 대용량 이미지 다수 삽입 시 성능 저하. 이미지 크기 최적화 권장 |
| 스트리밍 모드 | 10,000행 이상 데이터는 `StreamingMode.ENABLED` 권장 |

---

## 관련 문서

- [사용자 가이드](./user-guide.md)
- [API 레퍼런스](./reference/api-reference.md)
- [템플릿 문법 레퍼런스](./reference/template-syntax.md)
- [설정 옵션 레퍼런스](./reference/configuration.md)
