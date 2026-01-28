# TBEG 사용자 가이드

## 최신 버전 정보
<!-- version-info:start -->
```
Last updated: 2026-01-27
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

TBEG(Template Based Excel Generator)는 Excel 템플릿에 데이터를 바인딩하여 보고서를 생성하는 라이브러리입니다.

### 주요 기능

| 기능            | 설명                                               |
|---------------|--------------------------------------------------|
| **템플릿 기반 생성** | Excel 템플릿에 데이터를 바인딩하여 보고서 생성                     |
| **반복 데이터 처리** | `${repeat(...)}` 문법으로 리스트 데이터를 행/열로 확장           |
| **변수 치환**     | `${변수명}` 문법으로 셀, 차트, 도형, 머리글/바닥글, 수식 인자 등에 값 바인딩 |
| **이미지 삽입**    | 템플릿 셀에 동적 이미지 삽입                                 |
| **피벗 테이블**    | 템플릿의 피벗 테이블을 데이터에 맞게 자동 재생성                      |
| **파일 암호화**    | 생성된 Excel 파일에 열기 암호 설정                           |
| **문서 메타데이터**  | 제목, 작성자, 키워드 등 문서 속성 설정                          |
| **비동기 처리**    | 대용량 데이터를 백그라운드에서 처리                              |
| **지연 로딩**     | DataProvider를 통한 메모리 효율적 데이터 처리                  |

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

|   | A           | B                                | C             |
|---|-------------|----------------------------------|---------------|
| 1 | ${title}    | ${repeat(employees, A3:C3, emp)} |               |
| 2 | 이름          | 직급                               | 급여            |
| 3 | ${emp.name} | ${emp.position}                  | ${emp.salary} |

#### Kotlin 예제

```kotlin
import com.hunet.common.tbeg.ExcelGenerator
import java.io.File

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
  tbeg:
    streaming-mode: enabled           # enabled, disabled
    file-naming-mode: timestamp       # none, timestamp
    timestamp-format: yyyyMMdd_HHmmss
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

        // 비동기 작업 제출 (파일로 저장)
        val job = excelGenerator.submitToFile(
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

> **텍스트 형식 vs 수식 형식**: `repeat`와 `image` 마커는 텍스트 형식(`${...}`)과 수식 형식(`=TBEG_...`) 두 가지로 작성할 수 있습니다. 기능은 동일하며, 수식 형식은 Excel에서 범위나 셀을 지정할 때 마우스 드래그 등 Excel의 셀 참조 기능을 활용할 수 있다는 장점이 있습니다.

#### 변수 치환
```
${변수명}
```
- 셀, 차트, 도형, 머리글/바닥글, 수식 인자 등 Excel 내 거의 모든 곳에 사용할 수 있습니다.
- `${객체.속성}` 형식으로 객체 속성에도 접근할 수 있습니다.
- 치환자가 반드시 문자열 전체를 차지할 필요는 없습니다. 예: `보고서 제목: ${title}`

#### 반복 (repeat)
> 리스트 데이터의 각 항목에 대해 지정된 셀 범위를 복제하여 행(또는 열) 방향으로 확장합니다.

**텍스트 형식:**
```
${repeat(컬렉션명, 범위, 변수명, 방향)}
```

**수식 형식:**
```
=TBEG_REPEAT(컬렉션명, 범위, 변수명, 방향)
```

| 파라미터 | 필수 | 설명                                 | 기본값  |
|------|----|------------------------------------|------|
| 컬렉션명 | O  | 데이터에서 전달한 리스트의 키 이름                | -    |
| 범위   | O  | 반복할 셀 범위 (예: A3:C3) 또는 Named Range | -    |
| 변수명  | X  | 각 항목을 참조할 때 사용할 별칭                 | 컬렉션명 |
| 방향   | X  | 확장 방향 (DOWN 또는 RIGHT)              | DOWN |

- 마커는 워크북 내 어디에 있어도 됩니다 (다른 시트도 가능). `범위`로 지정된 영역이 반복됩니다.
- Named Range 지원: `${repeat(employees, DataRange, emp)}`

**예시:**
```
${repeat(employees, A3:C3, emp)}
```
또는
```
=TBEG_REPEAT(employees, A3:C3, emp, DOWN)
```

#### 이미지
> 마커가 위치한 셀(또는 지정된 위치)에 이미지를 삽입합니다.

**텍스트 형식:**
```
${image.이미지명}
${image(이미지명, 위치, 크기)}
```

**수식 형식:**
```
=TBEG_IMAGE(이미지명)
=TBEG_IMAGE(이미지명, 범위)
=TBEG_IMAGE(이미지명, 위치, 크기)
```

해당 셀 위치에 이미지가 삽입됩니다.

> 상세 파라미터 설명은 [템플릿 문법 레퍼런스 - 이미지 삽입](./reference/template-syntax.md#4-이미지-삽입)을 참조하세요.

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

대량의 데이터로 Excel을 생성하면 블로킹 시간이 길어질 수 있으므로, 상황에 맞게 필요하면 비동기 처리 방식을 선택하세요.

모든 생성 메서드는 **바이트 배열 반환**과 **파일 저장** 두 가지 형태를 제공합니다.

| 처리 방식      | 바이트 배열 반환                                           | 파일 저장                                                |
|------------|-----------------------------------------------------|------------------------------------------------------|
| **동기**     | `generate()` → `ByteArray`                          | `generateToFile()` → `Path`                          |
| **코루틴**    | `generateAsync()` → `ByteArray` (suspend)           | `generateToFileAsync()` → `Path` (suspend)           |
| **Future** | `generateFuture()` → `CompletableFuture<ByteArray>` | `generateToFileFuture()` → `CompletableFuture<Path>` |
| **백그라운드**  | `submit()` → `GenerationJob`                        | `submitToFile()` → `GenerationJob`                   |

#### 권장 사용 패턴

**일반적인 경우** - 동기 처리:
```kotlin
val bytes = generator.generate(template, data)
```

**블로킹을 피해야 하는 경우** - 백그라운드 작업:
```kotlin
// 즉시 반환, 백그라운드에서 파일로 생성
val job = generator.submitToFile(
    template = template,
    dataProvider = provider,
    outputDir = Path.of("/var/reports"),
    baseFileName = "large_report",
    listener = object : ExcelGenerationListener {
        override fun onCompleted(jobId: String, result: GenerationResult) {
            // 완료 시 후속 처리 (알림, UI 갱신 등)
            val filePath = result.filePath!!
        }
    }
)
// API 서버: 즉시 응답 반환
// GUI 애플리케이션: UI 블로킹 없이 진행
// 배치 작업: 다른 작업과 병렬 처리
```

#### 비동기 메서드 비교

비동기 메서드는 세 가지 방식을 제공합니다. 각 방식마다 바이트 배열/파일 저장 버전이 있습니다.

| 구분         | 코루틴                     | Future                   | 백그라운드            |
|------------|-------------------------|--------------------------|------------------|
| **바이트 배열** | `generateAsync()`       | `generateFuture()`       | `submit()`       |
| **파일 저장**  | `generateToFileAsync()` | `generateToFileFuture()` | `submitToFile()` |
| **반환 타입**  | suspend 함수              | `CompletableFuture<T>`   | `GenerationJob`  |
| **Kotlin** | ✅ 권장                    | ✅ 사용 가능                  | ✅ 사용 가능          |
| **Java**   | ❌ 어려움                   | ✅ 권장                     | ✅ 사용 가능          |
| **리스너/취소** | ❌                       | ❌                        | ✅                |

**코루틴 (generateAsync / generateToFileAsync)** - Kotlin 전용:
```kotlin
// suspend 함수이므로 코루틴 내에서만 호출 가능
// Spring WebFlux, Ktor 등 코루틴 기반 프레임워크에서 사용
suspend fun createReport(): ByteArray {
    return generator.generateAsync(template, provider)
}

suspend fun createReportFile(): Path {
    return generator.generateToFileAsync(template, provider, outputDir, "report")
}
```

**Future (generateFuture / generateToFileFuture)** - Java/Kotlin 공용:
```kotlin
// 어디서든 호출 가능, 완료 시 콜백으로 결과 처리
generator.generateFuture(template, provider)
    .thenAccept { bytes -> saveToStorage(bytes) }
    .exceptionally { error -> handleError(error) }

generator.generateToFileFuture(template, provider, outputDir, "report")
    .thenAccept { path -> notifyUser(path) }
    .exceptionally { error -> handleError(error) }
```

**백그라운드 (submit / submitToFile)** - 리스너 지원:
```kotlin
// 즉시 반환, 진행률/완료 콜백 지원, 취소 가능
// submit: 바이트 배열로 결과 반환 (result.bytes)
val job = generator.submit(template, provider,
    listener = object : ExcelGenerationListener {
        override fun onCompleted(jobId: String, result: GenerationResult) {
            val bytes = result.bytes!!
        }
    }
)

// submitToFile: 파일로 저장 (result.filePath)
val job = generator.submitToFile(template, provider, outputDir, "report",
    listener = object : ExcelGenerationListener {
        override fun onCompleted(jobId: String, result: GenerationResult) {
            val filePath = result.filePath!!
        }
    }
)
job.cancel()  // 필요 시 취소 가능
```

**선택 기준:**
- **Kotlin + 코루틴 환경**: `generateAsync` / `generateToFileAsync`
- **Java 또는 코루틴 없는 Kotlin**: `generateFuture` / `generateToFileFuture`
- **진행률 모니터링, 작업 취소 필요**: `submit` / `submitToFile`

#### 지연 로딩과 비동기 처리 비교

이 두 개념은 목적이 다르며, 상황에 따라 개별 또는 조합하여 사용합니다.

| 구분      | 지연 로딩 (DataProvider) | 비동기 처리           |
|---------|----------------------|------------------|
| **목적**  | 메모리 효율성              | 즉시 응답 후 백그라운드 작업 |
| **동작**  | 데이터를 필요할 때 로드        | 작업을 백그라운드에서 실행   |
| **효과**  | 전체 데이터를 메모리에 올리지 않음  | 호출자가 즉시 반환받음     |
| **블로킹** | 동기적 (완료까지 대기)        | 비블로킹 (즉시 반환)     |

**선택 가이드:**

| 상황                     | 권장 방식                               |
|------------------------|-------------------------------------|
| 데이터가 커서 메모리 부담이 큼      | 지연 로딩 (`simpleDataProvider`)        |
| 생성 시간이 길어서 블로킹을 피해야 함  | 비동기 (`submit()` / `submitToFile()`) |
| 대용량 데이터 + 블로킹 방지 모두 필요 | **지연 로딩 + 비동기 조합**                  |

> 상세 예제는 [고급 예제 - 비동기 처리](./examples/advanced-examples.md#2-비동기-처리)를 참조하세요.

---

## 4. 주의 사항

### 4.1 템플릿 서식 규칙

템플릿에 작성된 서식은 생성된 Excel에 그대로 유지됩니다. 다음 규칙을 준수하세요:

1. **반복 영역의 서식**: repeat 마커에서 반복 범위로 지정한 셀에 원하는 서식을 적용하면, 반복 생성되는 모든 셀에 동일하게 적용됩니다.

2. **숫자 서식**: 템플릿 셀의 표시 형식이 "일반"인 경우, 숫자 데이터는 자동으로 숫자 서식이 적용됩니다. 특정 형식(통화, 백분율 등)을 원하면 템플릿에 미리 설정하세요.

3. **피벗 테이블**: 템플릿의 피벗 테이블 스타일과 설정이 유지됩니다. 데이터 영역이 확장되어도 서식이 보존됩니다.

### 4.2 대용량 데이터 처리

대용량 데이터(수천 행 이상) 처리 시 권장 사항:

1. **DataProvider 사용**: Map 대신 `simpleDataProvider`를 사용하여 지연 로딩을 활용하세요.

2. **비동기 처리**: 블로킹을 피해야 하는 경우 `submit()` 또는 `submitToFile()` 메서드를 사용하여 백그라운드에서 처리하세요.

### 4.3 템플릿 작성 팁

합계 수식(`SUM`, `AVERAGE` 등)은 **데이터 영역 아래**에 배치하면 최적의 성능을 얻을 수 있습니다.

```
| 이름 |    금액    |  ← 헤더
| ... |    ...    |  ← 반복 데이터 영역
| 합계 | =SUM(...) |  ← 수식은 아래에
```

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
