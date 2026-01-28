# TBEG (Template Based Excel Generator)

Excel 템플릿에 데이터를 바인딩하여 보고서를 생성하는 라이브러리입니다.

## 주요 기능

- **템플릿 기반 생성**: Excel 템플릿에 데이터를 바인딩하여 보고서 생성
- **반복 데이터 처리**: `${repeat(...)}` 문법으로 리스트 데이터를 행/열로 확장
- **변수 치환**: `${변수명}` 문법으로 셀, 차트, 도형, 머리글/바닥글, 수식 인자 등에 값 바인딩
- **이미지 삽입**: 템플릿 셀에 동적 이미지 삽입
- **피벗 테이블**: 템플릿의 피벗 테이블을 데이터에 맞게 자동 재생성
- **파일 암호화**: 생성된 Excel 파일에 열기 암호 설정
- **문서 메타데이터**: 제목, 작성자, 키워드 등 문서 속성 설정
- **비동기 처리**: 대용량 데이터를 백그라운드에서 처리
- **지연 로딩**: DataProvider를 통한 메모리 효율적 데이터 처리

## 의존성 추가

```kotlin
// build.gradle.kts
implementation("com.hunet.common:tbeg:1.0.0-SNAPSHOT")
```

## 빠른 시작

### Kotlin

```kotlin
import com.hunet.common.tbeg.ExcelGenerator
import java.io.File

data class Employee(val name: String, val position: String, val salary: Int)

fun main() {
    val data = mapOf(
        "title" to "직원 현황",
        "employees" to listOf(
            Employee("황용호", "부장", 8000),
            Employee("한용호", "과장", 6500)
        )
    )

    ExcelGenerator().use { generator ->
        val template = File("template.xlsx").inputStream()
        val bytes = generator.generate(template, data)
        File("output.xlsx").writeBytes(bytes)
    }
}
```

### Spring Boot

```kotlin
@Service
class ReportService(
    private val excelGenerator: ExcelGenerator,
    private val resourceLoader: ResourceLoader
) {
    fun generateReport(): ByteArray {
        val template = resourceLoader.getResource("classpath:templates/report.xlsx")
        val data = mapOf("title" to "보고서", "items" to listOf(...))
        return excelGenerator.generate(template.inputStream, data)
    }
}
```

Spring Boot 환경에서는 `ExcelGenerator`가 자동으로 Bean으로 등록됩니다.

## 템플릿 문법

### 변수 치환
```
${title}
${employee.name}
```

### 반복 데이터
```
${repeat(employees, A3:C3, emp, DOWN)}
```

### 이미지
```
${image.logo}
```

## 스트리밍 모드

대용량 데이터 처리 시 메모리 효율성과 처리 속도를 개선하는 모드입니다.

### streaming-mode 설정

| 모드 | 설명 | 권장 상황 |
|------|------|----------|
| `enabled` (기본값) | 대용량 데이터에 최적화 | 10,000행 이상, 단순 데이터 목록 |
| `disabled` | 모든 Excel 기능 완벽 지원 | 1,000행 이하, 복잡한 수식 템플릿 |

### 성능 비교

| 항목 | disabled | enabled |
|------|----------|---------|
| 메모리 사용량 | 행 수에 비례 | 일정 수준 유지 |
| 처리 속도 | 기준 | 약 3배 빠름 |
| 50,000행 처리 | 높은 메모리 사용 | 빠르고 안정적 |

### 템플릿 작성 팁

합계 수식(`SUM`, `AVERAGE` 등)은 **데이터 영역 아래**에 배치하면 최적의 성능을 얻을 수 있습니다.

```
# 권장 배치
| 이름 | 금액 |        ← 헤더
| ... | ...  |        ← 반복 데이터 영역
| 합계 | =SUM(...) |  ← 수식은 아래에
```

## 설정 (application.yml)

```yaml
hunet:
  tbeg:
    streaming-mode: enabled   # enabled, disabled
    file-naming-mode: timestamp
    preserve-template-layout: true
```

## 프로젝트 구조

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

### 아키텍처 개요

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
│  ┌────────────────┐        ┌────────────────┐               │
│  │ XSSF Strategy  │   or   │ SXSSF Strategy │               │
│  │ (비스트리밍)      │        │ (스트리밍)       │               │
│  └────────────────┘        └────────────────┘               │
└─────────────────────────────────────────────────────────────┘
```

## 문서

상세 문서는 아래 링크를 참고하세요:

- [사용자 가이드](../../../apidoc/tbeg/user-guide.md)
- [템플릿 문법 레퍼런스](../../../apidoc/tbeg/reference/template-syntax.md)
- [API 레퍼런스](../../../apidoc/tbeg/reference/api-reference.md)
- [설정 옵션 레퍼런스](../../../apidoc/tbeg/reference/configuration.md)
- [기본 예제](../../../apidoc/tbeg/examples/basic-examples.md)
- [고급 예제](../../../apidoc/tbeg/examples/advanced-examples.md)
- [Spring Boot 예제](../../../apidoc/tbeg/examples/spring-boot-examples.md)
- [유지보수 개발자 가이드](../../../apidoc/tbeg/developer-guide.md)

## 샘플 실행

```bash
# Kotlin 샘플
./gradlew :tbeg:runSample

# Java 샘플
./gradlew :tbeg:runJavaSample

# Spring Boot 샘플
./gradlew :tbeg:runSpringBootSample
```
