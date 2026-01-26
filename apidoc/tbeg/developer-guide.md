# Excel Generator 유지보수 개발자 가이드

## 목차
1. [아키텍처 개요](#1-아키텍처-개요)
2. [모듈 구조](#2-모듈-구조)
3. [핵심 클래스](#3-핵심-클래스)
4. [처리 파이프라인](#4-처리-파이프라인)
5. [서식 유지 원칙](#5-서식-유지-원칙)
6. [테스트 가이드](#6-테스트-가이드)
7. [확장 포인트](#7-확장-포인트)
8. [알려진 제한 사항](#8-알려진-제한-사항)

---

## 1. 아키텍처 개요

### 설계 원칙

1. **템플릿 기반**: 디자이너가 작성한 Excel 템플릿을 기반으로 데이터만 바인딩
2. **서식 완전 보존**: 템플릿의 모든 서식을 생성 결과에 그대로 유지
3. **JXLS 추상화**: 사용자가 JXLS를 직접 다루지 않도록 친화적인 DSL 제공
4. **지연 로딩**: 대용량 데이터 처리를 위한 Iterator 기반 데이터 제공
5. **비동기 지원**: Coroutine, CompletableFuture, 리스너 콜백 지원

### 기술 스택

| 기술 | 버전 | 용도 |
|------|------|------|
| JXLS | 2.x | 템플릿 엔진 (jx:each, jx:area 등) |
| Apache POI | 5.x | Excel 파일 조작 |
| Kotlin Coroutines | 1.8.x | 비동기 처리 |
| Spring Boot | 3.4.x | 자동 설정 (선택) |

---

## 2. 모듈 구조

```
modules/core/tbeg/
├── src/main/kotlin/com/hunet/common/excel/
│   ├── ExcelGenerator.kt           # 메인 진입점
│   ├── ExcelGeneratorConfig.kt     # 설정 클래스
│   ├── ExcelDataProvider.kt        # 데이터 제공 인터페이스
│   ├── SimpleDataProvider.kt       # 기본 구현체
│   ├── DocumentMetadata.kt         # 문서 메타데이터
│   │
│   ├── TemplatePreprocessor.kt     # 템플릿 전처리 (repeat → jx:each)
│   ├── LayoutProcessor.kt          # 레이아웃 보존 (열 폭, 행 높이)
│   ├── DataValidationProcessor.kt  # 데이터 유효성 검사 확장
│   ├── PivotTableProcessor.kt      # 피벗 테이블 재생성
│   ├── XmlVariableProcessor.kt     # XML 변수 치환 (차트, 도형)
│   │
│   ├── ExcelUtils.kt               # 유틸리티 함수
│   ├── Enums.kt                    # 열거형 (StreamingMode 등)
│   │
│   ├── TemplateProcessingException.kt  # 예외 클래스
│   ├── FormulaExpansionException.kt
│   └── FormulaErrorCapturingAppender.kt
│
├── src/main/kotlin/com/hunet/common/excel/async/
│   ├── GenerationJob.kt            # 비동기 작업 인터페이스
│   ├── DefaultGenerationJob.kt     # 기본 구현
│   ├── GenerationResult.kt         # 결과 데이터 클래스
│   ├── ExcelGenerationListener.kt  # 리스너 인터페이스
│   └── ProgressInfo.kt             # 진행률 정보
│
└── src/main/kotlin/com/hunet/common/excel/spring/
    ├── ExcelGeneratorAutoConfiguration.kt  # Spring Boot 자동 설정
    └── ExcelGeneratorProperties.kt         # 프로퍼티 바인딩
```

---

## 3. 핵심 클래스

### 3.1 ExcelGenerator

메인 진입점으로, 템플릿 처리 파이프라인을 조율합니다.

**주요 책임:**
- 동기/비동기 API 제공
- 처리 파이프라인 실행
- 리소스 관리 (Closeable)

**핵심 메서드:**
```kotlin
private fun processTemplate(
    template: InputStream,
    dataProvider: ExcelDataProvider,
    output: OutputStream
): Int
```

### 3.2 TemplatePreprocessor

사용자 친화적인 `${repeat(...)}` 마커를 JXLS `jx:each` 코멘트로 변환합니다.

**주요 책임:**
- repeat 마커 파싱
- jx:each 코멘트 생성
- 불완전한 마커 검증

**처리 예시:**
```
입력: ${repeat(employees, A3:C3, emp, DOWN)}
출력: 셀 A3 코멘트에 "jx:each(items="employees" var="emp" lastCell="C3")"
```

### 3.3 LayoutProcessor

JXLS 처리 후 원본 템플릿의 열 폭과 행 높이를 복원합니다.

**주요 책임:**
- 처리 전 레이아웃 백업
- 처리 후 레이아웃 복원

### 3.4 PivotTableProcessor

피벗 테이블이 포함된 템플릿을 처리합니다.

**주요 책임:**
- JXLS 처리 전 피벗 테이블 정보 추출 및 삭제
- JXLS 처리 후 피벗 테이블 재생성
- 피벗 캐시 데이터 소스 범위 조정
- 서식 보존 (StyleInfo)

**핵심 데이터 클래스:**
```kotlin
data class StyleInfo(
    val horizontalAlignment: HorizontalAlignment?,
    val verticalAlignment: VerticalAlignment?,
    val fontBold: Boolean,
    val fontItalic: Boolean,
    val fontName: String?,
    val fontSize: Short?,
    val fontColorRgb: ByteArray?,
    val fillForegroundColorRgb: ByteArray?,
    val fillPatternType: FillPatternType?,
    // ... 기타 서식 속성
)
```

### 3.5 XmlVariableProcessor

셀 이외의 위치(차트, 도형, 머리글 등)의 변수를 치환합니다.

**주요 책임:**
- Excel 패키지(.xlsx = ZIP) 내 XML 파일 탐색
- `${변수}` 패턴 치환
- XML 특수문자 이스케이프

### 3.6 DataValidationProcessor

데이터 유효성 검사 규칙을 반복 영역에 확장합니다.

**주요 책임:**
- 처리 전 유효성 검사 규칙 백업
- 처리 후 확장된 범위에 규칙 적용

---

## 4. 처리 파이프라인

`ExcelGenerator.processTemplate()` 메서드의 처리 순서:

```
1. 템플릿 전처리 (TemplatePreprocessor)
   - ${repeat(...)} → jx:each 변환
   - ${image.xxx} → jx:image 변환
   - jx:area 자동 추가

2. 워크북 전처리
   - 이미지 플레이스홀더 처리
   - 이미지 명령 완성 (lastCell, imageType)

3. 수식 보호
   - ${변수} 포함 수식 추출 → 빈 셀로 변환
   - JXLS 처리 후 복원

4. 레이아웃/유효성 검사 백업
   - LayoutProcessor.backup()
   - DataValidationProcessor.backup()

5. 피벗 테이블 추출 (PivotTableProcessor)
   - 피벗 정보 저장 및 삭제

6. JXLS 처리
   - JxlsHelper.processTemplate()
   - 수식 확장 오류 감지

7. 수식 복원
   - 저장해둔 수식 재설정

8. 후처리
   - 숫자 서식 자동 적용
   - 레이아웃 복원
   - 데이터 유효성 검사 확장
   - 피벗 테이블 재생성
   - XML 변수 치환
   - 메타데이터 적용
```

---

## 5. 서식 유지 원칙

> **중요**: 이 원칙은 Excel 생성 관련 모든 코드 수정 시 반드시 준수해야 합니다.

### 5.1 템플릿 서식 완전 보존

템플릿에 작성된 모든 서식(정렬, 글꼴, 색상, 테두리, 채우기 등)은 생성된 Excel에 동일하게 적용되어야 합니다.

### 5.2 자동/반복 생성 셀의 서식 상속

피벗 테이블을 포함한 자동 생성 또는 반복 생성되는 셀은 템플릿의 기준 셀 서식을 모두 적용해야 합니다:

- **피벗 테이블 헤더 행**: 템플릿의 헤더 행 서식 적용
- **피벗 테이블 데이터 행**: 템플릿의 첫 번째 데이터 행 서식 적용
- **피벗 테이블 Grand Total 행**: 피벗 테이블 기본 스타일에 위임

### 5.3 숫자 서식 자동 지정 예외

자동 생성되는 셀의 데이터가 숫자 타입이고, 템플릿 셀의 "표시 형식"이 없거나 "일반"인 경우에만 "숫자" 범주로 자동 지정합니다. 이 경우에도 해당 셀의 나머지 모든 서식은 원칙 1, 2를 준수합니다.

### 5.4 StyleInfo에서 유지해야 하는 서식 속성

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
    val borderTop: BorderStyle?,                     // 상단 테두리
    val borderBottom: BorderStyle?,                  // 하단 테두리
    val borderLeft: BorderStyle?,                    // 좌측 테두리
    val borderRight: BorderStyle?,                   // 우측 테두리
    val dataFormat: Short                            // 표시 형식
)
```

---

## 6. 테스트 가이드

### 6.1 테스트 파일 위치

```
src/test/kotlin/com/hunet/common/excel/
├── ExcelGeneratorTest.kt           # 통합 테스트
├── TemplatePreprocessorTest.kt     # 전처리기 단위 테스트
├── PivotTableTest.kt               # 피벗 테이블 테스트
├── ExcelGeneratorSample.kt         # 샘플 실행 (Kotlin)
└── spring/
    └── ExcelGeneratorSpringBootSample.kt  # Spring Boot 샘플

src/test/java/com/hunet/common/excel/
├── ExcelGeneratorJavaSample.java   # 샘플 실행 (Java)
└── spring/
    └── ExcelGeneratorSpringBootJavaSample.java

src/test/resources/templates/
└── template.xlsx                   # 테스트 템플릿
```

### 6.2 테스트 실행

```bash
# 전체 테스트
./gradlew :tbeg:test

# 특정 테스트
./gradlew :tbeg:test --tests "*PivotTableTest*"

# 샘플 실행
./gradlew :tbeg:runKotlinSample
./gradlew :tbeg:runJavaSample
./gradlew :tbeg:runSpringBootSample
```

### 6.3 테스트 작성 가이드

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

        // 서식 검증
        val cell = dataRow.getCell(0)
        assertEquals(HorizontalAlignment.CENTER, cell.cellStyle.alignment)
        assertTrue(workbook.getFontAt(cell.cellStyle.fontIndex).bold)
    }

    generator.close()
}
```

---

## 7. 확장 포인트

### 7.1 새로운 마커 추가

`TemplatePreprocessor`에서 새로운 마커를 추가할 수 있습니다.

```kotlin
// 예: ${if(condition)} 마커 추가
private val IF_MARKER_PATTERN = Regex("""\$\{if\(([^)]+)\)\}""")

private fun parseIfMarker(cellValue: String): IfMarker? {
    // 구현
}
```

### 7.2 새로운 후처리기 추가

`ExcelGenerator.processTemplate()`에 새로운 처리기를 추가할 수 있습니다.

```kotlin
// 예: 조건부 서식 처리기
private val conditionalFormatProcessor = ConditionalFormatProcessor()

// processTemplate() 내에서
.let { bytes -> conditionalFormatProcessor.process(bytes, dataProvider) }
```

### 7.3 커스텀 DataProvider 구현

```kotlin
class DatabaseStreamDataProvider(
    private val dataSource: DataSource
) : ExcelDataProvider {

    override fun getItems(name: String): Iterator<Any>? {
        // JDBC ResultSet을 Iterator로 변환
        val connection = dataSource.connection
        val statement = connection.prepareStatement("SELECT * FROM $name")
        val resultSet = statement.executeQuery()
        return ResultSetIterator(resultSet)
    }

    // 기타 메서드 구현
}
```

---

## 8. 알려진 제한 사항

### 8.1 JXLS 관련

- **jx:if 미지원**: 조건부 출력은 데이터 전처리로 대체 권장
- **스트리밍 모드 제한**: SXSSF 사용 시 일부 기능 제한
- **대용량 수식**: 수만 행에 걸친 수식 참조 시 `FormulaExpansionException` 발생 가능

### 8.2 피벗 테이블 관련

- **피벗 차트**: 피벗 테이블 재생성 시 연결이 해제될 수 있음
- **계산 필드/항목**: 재생성 시 제거될 수 있음
- **그룹화**: 날짜/숫자 그룹화 설정이 초기화될 수 있음

### 8.3 서식 관련

- **조건부 서식**: 반복 영역에서 조건부 서식 확장 미지원
- **테마 색상**: 테마 기반 색상은 RGB로 변환되어 저장됨

### 8.4 성능

- **메모리**: 대용량 템플릿(수백 MB)은 메모리 부족 가능
- **피벗 캐시**: 피벗 테이블이 많으면 처리 시간 증가

---

## 관련 문서

- [사용자 가이드](./user-guide.md)
- [API 레퍼런스](./reference/api-reference.md)
- [템플릿 문법 레퍼런스](./reference/template-syntax.md)
- [설정 옵션 레퍼런스](./reference/configuration.md)
