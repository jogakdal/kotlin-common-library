# Excel Generator 사용자 가이드

## 최신 버전 정보
<!-- version-info:start -->
```
Last updated: 2026-01-15
tbeg: 1.0.0-SNAPSHOT
```
<!-- version-info:end -->

## 문서 목록
- [템플릿 문법 레퍼런스](./reference/template-syntax.md)
- [API 레퍼런스](./reference/api-reference.md)
- [설정 옵션 레퍼런스](./reference/configuration.md)
- [기본 예제](./examples/basic-examples.md)
- [고급 예제](./examples/advanced-examples.md)
- [Spring Boot 예제](./examples/spring-boot-examples.md)
- [유지보수 개발자 가이드](./developer-guide.md)

---

## 목차
1. [소개](#1-소개)
2. [빠른 시작](#2-빠른-시작)
   - [기본 사용법 (Kotlin/Java)](#21-기본-사용법-kotlinjava)
   - [Spring Boot 환경](#22-spring-boot-환경)
3. [핵심 개념](#3-핵심-개념)
4. [주의 사항](#4-주의-사항)

---

## 1. 소개

Excel Generator는 Excel 템플릿에 데이터를 바인딩하여 보고서를 생성하는 라이브러리입니다.

### 주요 기능

| 기능 | 설명 |
|------|------|
| **템플릿 기반 생성** | Excel 템플릿에 데이터를 바인딩하여 보고서 생성 |
| **반복 데이터 처리** | `${repeat(...)}` 문법으로 리스트 데이터를 행/열로 확장 |
| **변수 치환** | `${변수명}` 문법으로 셀, 차트, 도형, 머리글/바닥글, 수식 인자 등에 값 바인딩 |
| **이미지 삽입** | 템플릿 셀에 동적 이미지 삽입 |
| **피벗 테이블** | 템플릿의 피벗 테이블을 데이터에 맞게 자동 재생성 |
| **파일 암호화** | 생성된 Excel 파일에 열기 암호 설정 |
| **문서 메타데이터** | 제목, 작성자, 키워드 등 문서 속성 설정 |
| **비동기 처리** | 대용량 데이터를 백그라운드에서 처리 |
| **지연 로딩** | DataProvider를 통한 메모리 효율적 데이터 처리 |

### 지원 환경

- **Java**: 21 이상
- **Kotlin**: 2.1.x
- **Spring Boot**: 3.4.x (선택)

---

## 2. 빠른 시작

### 2.1 기본 사용법 (Kotlin/Java)

#### 의존성 추가

**Gradle (Kotlin DSL)**
```kotlin
implementation("com.hunet.common:tbeg:1.0.0-SNAPSHOT")
```

**Gradle (Groovy)**
```groovy
implementation 'com.hunet.common:tbeg:1.0.0-SNAPSHOT'
```

**Maven**
```xml
<dependency>
    <groupId>com.hunet.common</groupId>
    <artifactId>tbeg</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

#### 템플릿 준비

Excel 파일(`.xlsx`)을 템플릿으로 사용합니다. 템플릿에 변수를 `${변수명}` 형식으로 작성합니다.

**예시 템플릿 (template.xlsx)**

| A | B | C |
|---|---|---|
| ${title} | | |
| 이름 | 직급 | 급여 |
| ${repeat(employees, A3:C3)} | | |
| ${emp.name} | ${emp.position} | ${emp.salary} |

#### Kotlin 예제

```kotlin
import com.hunet.common.tbeg.ExcelGenerator
import com.hunet.common.tbeg.SimpleDataProvider

// 데이터 클래스
data class Employee(val name: String, val position: String, val salary: Int)

fun main() {
    // 데이터 준비
    val data = mapOf(
        "title" to "2026년 직원 현황",
        "employees" to listOf(
            Employee("황용호", "부장", 8000),
            Employee("한용호", "과장", 6500),
            Employee("홍용호", "대리", 4500)
        )
    )

    // Excel 생성
    ExcelGenerator().use { generator ->
        val template = File("template.xlsx").inputStream()
        val bytes = generator.generate(template, data)

        // 파일로 저장
        File("output.xlsx").writeBytes(bytes)
    }
}
```

#### Java 예제

```java
import com.hunet.common.tbeg.ExcelGenerator;
import com.hunet.common.tbeg.SimpleDataProvider;
import java.io.*;
import java.util.*;

public class ExcelGeneratorExample {
    public static void main(String[] args) throws Exception {
        // 데이터 준비
        Map<String, Object> data = new HashMap<>();
        data.put("title", "2026년 직원 현황");
        data.put("employees", List.of(
            Map.of("name", "황용호", "position", "부장", "salary", 8000),
            Map.of("name", "한용호", "position", "과장", "salary", 6500),
            Map.of("name", "홍용호", "position", "대리", "salary", 4500)
        ));

        // Excel 생성
        try (ExcelGenerator generator = new ExcelGenerator();
             InputStream template = new FileInputStream("template.xlsx")) {

            byte[] bytes = generator.generate(template, data);

            // 파일로 저장
            try (FileOutputStream fos = new FileOutputStream("output.xlsx")) {
                fos.write(bytes);
            }
        }
    }
}
```

---

### 2.2 Spring Boot 환경

Spring Boot 환경에서는 `ExcelGenerator`가 자동으로 Bean으로 등록됩니다.

#### 의존성 추가

```kotlin
// build.gradle.kts
implementation("com.hunet.common:tbeg:1.0.0-SNAPSHOT")
```

#### 설정 (선택)

`application.yml`에서 기본 설정을 변경할 수 있습니다:

```yaml
hunet:
  excel:
    streaming-mode: auto           # auto, enabled, disabled
    streaming-row-threshold: 1000  # AUTO 모드에서 스트리밍으로 전환되는 행 수
    timestamp-format: yyyyMMdd_HHmmss  # 파일명 타임스탬프 형식
```

#### Service 클래스 예제

```kotlin
@Service
class ReportService(
    private val excelGenerator: ExcelGenerator,
    private val resourceLoader: ResourceLoader,
    private val employeeRepository: EmployeeRepository
) {
    fun generateEmployeeReport(): Path {
        // classpath에서 템플릿 로드
        val template = resourceLoader.getResource("classpath:templates/report.xlsx")

        // 데이터 준비
        val data = mapOf(
            "title" to "직원 현황 보고서",
            "date" to LocalDate.now().toString(),
            "employees" to employeeRepository.findAll()
        )

        // Excel 생성 및 파일 저장
        return excelGenerator.generateToFile(
            template = template.inputStream,
            dataProvider = SimpleDataProvider.of(data),
            outputDir = Path.of("/var/reports"),
            baseFileName = "employee_report"
        )
    }
}
```

#### Controller에서 다운로드 응답

```kotlin
@RestController
@RequestMapping("/api/reports")
class ReportController(
    private val excelGenerator: ExcelGenerator,
    private val resourceLoader: ResourceLoader
) {
    @GetMapping("/employees/download")
    fun downloadEmployeeReport(): ResponseEntity<Resource> {
        val template = resourceLoader.getResource("classpath:templates/report.xlsx")
        val data = mapOf(
            "title" to "직원 현황",
            "employees" to listOf(/* ... */)
        )

        val bytes = excelGenerator.generate(template.inputStream, data)

        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"report.xlsx\"")
            .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .body(ByteArrayResource(bytes))
    }
}
```

#### 대용량 비동기 처리 (API 서버)

대용량 Excel 생성 시 즉시 응답하고 백그라운드에서 처리할 수 있습니다.

```kotlin
@RestController
@RequestMapping("/api/reports")
class AsyncReportController(
    private val excelGenerator: ExcelGenerator,
    private val resourceLoader: ResourceLoader,
    private val eventPublisher: ApplicationEventPublisher
) {
    @PostMapping("/employees/async")
    fun generateReportAsync(): ResponseEntity<Map<String, String>> {
        val template = resourceLoader.getResource("classpath:templates/report.xlsx")

        val provider = simpleDataProvider {
            value("title", "대용량 직원 현황")
            value("date", LocalDate.now().toString())
            items("employees") {
                // 대용량 데이터 스트리밍
                employeeRepository.streamAll().iterator()
            }
        }

        // 비동기 작업 제출
        val job = excelGenerator.submit(
            template = template.inputStream,
            dataProvider = provider,
            outputDir = Path.of("/var/reports"),
            baseFileName = "large_report",
            listener = object : ExcelGenerationListener {
                override fun onCompleted(jobId: String, result: GenerationResult) {
                    // 완료 이벤트 발행 (이메일 발송, 푸시 알림 등)
                    eventPublisher.publishEvent(ReportReadyEvent(jobId, result.filePath))
                }
                override fun onFailed(jobId: String, error: Exception) {
                    eventPublisher.publishEvent(ReportFailedEvent(jobId, error.message))
                }
            }
        )

        // 즉시 jobId 반환 (HTTP 202 Accepted)
        return ResponseEntity.accepted().body(mapOf("jobId" to job.jobId))
    }
}
```

---

## 3. 핵심 개념

### 3.1 템플릿 문법

#### 변수 치환
```
${변수명}
```
- 셀, 차트, 도형, 머리글/바닥글, 수식 인자 등 Excel 내 거의 모든 곳에 사용할 수 있습니다.
- `${객체.속성}` 형식으로 객체 속성에도 접근할 수 있습니다.
- 치환자가 반드시 문자열 전체를 차지할 필요는 없습니다. 예: `보고서 제목: ${title}`

#### 반복 (repeat)
```
${repeat(컬렉션명, 범위, 변수명, 방향)}
```

| 파라미터 | 필수 | 설명 | 기본값 |
|----------|------|------|--------|
| 컬렉션명 | O | 데이터에서 전달한 리스트의 키 이름 | - |
| 범위 | O | 반복할 셀 범위 (예: A3:C3) | - |
| 변수명 | X | 각 항목을 참조할 때 사용할 별칭 | 컬렉션명 |
| 방향 | X | 확장 방향 (DOWN 또는 RIGHT) | DOWN |

- 이 마커는 워크북 내 어디에 있어도 됩니다 (다른 시트도 가능). `범위`로 지정된 영역이 반복됩니다.
- 변수명을 생략하면 컬렉션명으로 각 항목을 참조합니다.

**예시:**
```
${repeat(employees, A3:C3, emp)}
```
데이터의 `employees` 리스트를 A3:C3 범위에서 아래로 반복하며, 각 항목은 `${emp.name}` 형식으로 참조합니다.

#### 이미지
```
${image.이미지명}
```
해당 셀 위치에 이미지가 삽입됩니다.

### 3.2 DataProvider

데이터 제공 방식은 두 가지가 있습니다:

#### Map 기반 (간편)
```kotlin
val data = mapOf(
    "title" to "보고서",
    "items" to listOf(item1, item2, item3)
)
generator.generate(template, data)
```

#### SimpleDataProvider (지연 로딩)
```kotlin
val provider = simpleDataProvider {
    value("title", "보고서")
    items("employees") {
        // 실제로 데이터가 필요할 때 호출됨
        repository.streamAll().iterator()
    }
    image("logo", logoBytes)
    metadata {
        title = "월간 보고서"
        author = "작성자"
    }
}
generator.generate(template, provider)
```

### 3.3 비동기 처리

| 메서드 | 반환 타입 | 설명 |
|--------|-----------|------|
| `generate()` | `ByteArray` | 동기 생성, 바이트 배열 반환 |
| `generateToFile()` | `Path` | 동기 생성, 파일로 저장 |
| `generateAsync()` | `ByteArray` (suspend) | Kotlin Coroutine 비동기 |
| `generateFuture()` | `CompletableFuture<ByteArray>` | Java CompletableFuture |
| `submit()` | `GenerationJob` | 백그라운드 작업 + 리스너 콜백 |

---

## 4. 주의 사항

### 4.1 라이브러리 문법만 사용

> ⚠️ **중요**: 템플릿에는 본 라이브러리가 제공하는 문법(`${변수}`, `${repeat(...)}`, `${image.xxx}`)만 사용하세요.

이 라이브러리가 JXLS를 일부 사용하기는 하나, 셀 코멘트에 JXLS 등 다른 템플릿 엔진 명령어를 직접 작성하면 다음과 같은 문제가 발생할 수 있습니다:

- 라이브러리의 전처리 로직과 충돌
- 피벗 테이블, 레이아웃 보존 등 후처리 기능과 예기치 않은 상호작용
- 향후 버전에서 동작 변경 가능

### 4.2 템플릿 서식 규칙

템플릿에 작성된 서식은 생성된 Excel에 그대로 유지됩니다. 다음 규칙을 준수하세요:

1. **반복 영역의 서식**: 첫 번째 행(또는 열)에 원하는 서식을 적용하면 반복 생성되는 모든 행에 동일하게 적용됩니다.

2. **숫자 서식**: 템플릿 셀의 표시 형식이 "일반"인 경우, 숫자 데이터는 자동으로 숫자 서식이 적용됩니다. 특정 형식(통화, 백분율 등)을 원하면 템플릿에서 미리 설정하세요.

3. **피벗 테이블**: 템플릿의 피벗 테이블 스타일과 설정이 유지됩니다. 데이터 영역이 확장되어도 서식이 보존됩니다.

### 4.3 대용량 데이터 처리

대용량 데이터(수천 행 이상) 처리 시 권장 사항:

1. **DataProvider 사용**: Map 대신 `simpleDataProvider`를 사용하여 지연 로딩을 활용하세요.

2. **스트리밍 모드**: `ExcelGeneratorConfig`에서 `streamingMode = StreamingMode.ENABLED`를 설정하세요.

3. **비동기 처리**: API 서버에서는 `submit()` 메서드를 사용하여 백그라운드에서 처리하세요.

4. **수식 제한**: 템플릿에 참조 범위가 넓은 수식이 있으면 성능이 저하될 수 있습니다.

### 4.4 리소스 관리

`ExcelGenerator`는 `Closeable`을 구현합니다. 사용 후 반드시 `close()`를 호출하거나 `use` 블록을 사용하세요.

```kotlin
// Kotlin
ExcelGenerator().use { generator ->
    // 사용
}

// Java
try (ExcelGenerator generator = new ExcelGenerator()) {
    // 사용
}
```

Spring Boot 환경에서는 컨테이너가 자동으로 관리하므로 별도 처리가 필요 없습니다.

---

## 다음 단계

- [템플릿 문법 레퍼런스](./reference/template-syntax.md) - 상세 문법 및 고급 사용법
- [Spring Boot 예제](./examples/spring-boot-examples.md) - 실전 예제 코드
- [설정 옵션 레퍼런스](./reference/configuration.md) - 모든 설정 옵션 설명
