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

대용량 데이터 처리 시 메모리 효율성과 처리 속도를 개선하기 위해 SXSSF 스트리밍을 지원합니다.

### streaming-mode 설정
- `enabled` (기본값): SXSSF 스트리밍 사용, 대용량 데이터에 최적화
- `disabled`: XSSF 사용, 아래 행 참조 수식이 있는 템플릿에서 사용

### 스트리밍 모드 장점 (ENABLED)

| 항목 | DISABLED (XSSF) | ENABLED (SXSSF) |
|------|-----------------|-----------------|
| 메모리 사용량 | 행 수에 비례해 증가 | 상수 수준 유지 |
| 처리 속도 | 기준 | 약 3배 빠름 |
| 50,000행 처리 | 느림 + 높은 메모리 | 빠름 + 안정적 |

### 스트리밍 모드에서도 지원되는 기능

다음 기능들은 라이브러리 내부에서 우회 처리되어 스트리밍 모드에서도 정상 작동합니다:

- ✅ **피벗 테이블**: 자동으로 DISABLED 모드로 전환하여 처리 (SXSSF에서 헤더 행 손실 방지)
- ✅ **레이아웃 보존**: 후처리에서 XSSF로 복원
- ✅ **데이터 유효성 검사**: 후처리에서 확장
- ✅ **수식 내 변수 치환**: 전처리/후처리에서 처리
- ✅ **이미지 삽입**: 지원

### 스트리밍 모드 제한사항

다음은 SXSSF의 근본적인 제한으로 우회할 수 없습니다:

- ⚠️ **아래 행 참조 수식**: 템플릿에서 현재 행보다 아래 행을 참조하는 수식
  - 예: 1행에 `=SUM(A2:A100)` 같은 수식이 있고, 2행 이하가 반복 확장되는 경우
  - 해결책: 수식을 데이터 영역 아래에 배치하거나, 스트리밍 모드 비활성화

### 권장 사용 시나리오

| 시나리오 | 권장 모드 |
|----------|----------|
| 1,000행 이하 단순 보고서 | `disabled` |
| 10,000행 이상 대용량 데이터 | `enabled` |
| 복잡한 수식이 많은 템플릿 | `disabled` |
| 단순 데이터 목록 내보내기 | `enabled` |

## 설정 (application.yml)

```yaml
hunet:
  tbeg:
    streaming-mode: enabled   # enabled, disabled
    file-naming-mode: timestamp
    preserve-template-layout: true
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
