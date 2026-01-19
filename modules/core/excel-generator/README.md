# Excel Generator

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
implementation("com.hunet.common:excel-generator:1.0.0-SNAPSHOT")
```

## 빠른 시작

### Kotlin

```kotlin
import com.hunet.common.excel.ExcelGenerator
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

## Apache POI 구현체

이 라이브러리는 내부적으로 Apache POI를 사용하며, 상황에 따라 두 가지 구현체를 선택적으로 사용합니다.

### XSSF (XML Spreadsheet Format)
- `.xlsx` 파일을 처리하는 기본 구현체
- 전체 워크북을 메모리에 로드하여 **모든 Excel 기능을 완전히 지원**
- 셀 스타일, 수식, 피벗 테이블, 차트 등 복잡한 기능 처리에 적합
- 대용량 데이터 처리 시 **메모리 사용량이 높음**

### SXSSF (Streaming XSSF)
- 대용량 데이터 쓰기를 위한 **스트리밍 구현체**
- 설정된 윈도우 크기만큼의 행만 메모리에 유지하고, 나머지는 디스크에 임시 저장
- **메모리 효율적**으로 수십만 행 이상의 데이터 처리 가능
- 제한사항: 이미 디스크로 플러시된 행에는 접근 불가, 일부 기능(피벗 테이블 등) 제한

### streaming-mode 설정
- `auto` (기본값): 데이터 크기에 따라 자동 선택 (`streaming-row-threshold` 기준)
- `always`: 항상 SXSSF 사용
- `never`: 항상 XSSF 사용

## 설정 (application.yml)

```yaml
hunet:
  excel:
    streaming-mode: auto
    streaming-row-threshold: 1000
    formula-processing: true
    file-naming-mode: timestamp
```

## 문서

상세 문서는 아래 링크를 참고하세요:

- [사용자 가이드](../../../apidoc/excel-generator/user-guide.md)
- [템플릿 문법 레퍼런스](../../../apidoc/excel-generator/reference/template-syntax.md)
- [API 레퍼런스](../../../apidoc/excel-generator/reference/api-reference.md)
- [설정 옵션 레퍼런스](../../../apidoc/excel-generator/reference/configuration.md)
- [기본 예제](../../../apidoc/excel-generator/examples/basic-examples.md)
- [고급 예제](../../../apidoc/excel-generator/examples/advanced-examples.md)
- [Spring Boot 예제](../../../apidoc/excel-generator/examples/spring-boot-examples.md)
- [유지보수 개발자 가이드](../../../apidoc/excel-generator/developer-guide.md)

## 샘플 실행

```bash
# Kotlin 샘플
./gradlew :excel-generator:runKotlinSample

# Java 샘플
./gradlew :excel-generator:runJavaSample

# Spring Boot 샘플
./gradlew :excel-generator:runSpringBootSample
```
