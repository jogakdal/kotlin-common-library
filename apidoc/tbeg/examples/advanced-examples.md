# TBEG 고급 예제

## 목차
1. [지연 로딩 (DataProvider)](#1-지연-로딩-dataprovider)
2. [비동기 처리](#2-비동기-처리)
3. [피벗 테이블](#3-피벗-테이블)
4. [수식에서 변수 사용](#4-수식에서-변수-사용)
5. [하이퍼링크](#5-하이퍼링크)
6. [다중 시트](#6-다중-시트)
7. [대용량 데이터 처리](#7-대용량-데이터-처리)

---

## 1. 지연 로딩 (DataProvider)

대용량 데이터를 처리할 때 모든 데이터를 메모리에 로드하지 않고 필요할 때 로드합니다.

### Kotlin 코드

```kotlin
import com.hunet.common.tbeg.ExcelGenerator
import com.hunet.common.tbeg.simpleDataProvider
import java.io.File
import java.time.LocalDate

data class Employee(val name: String, val position: String, val salary: Int)

// 대용량 데이터를 시뮬레이션하는 함수
fun fetchEmployeesFromDatabase(): Iterator<Employee> = sequence {
    val positions = listOf("사원", "대리", "과장", "차장", "부장")
    repeat(10000) { i ->
        yield(Employee(
            name = "직원${i + 1}",
            position = positions[i % positions.size],
            salary = 3000 + (i % 5) * 1000
        ))
    }
}.iterator()

fun main() {
    val provider = simpleDataProvider {
        value("title", "직원 현황 보고서")
        value("date", LocalDate.now().toString())

        // 지연 로딩: 이 블록은 실제로 데이터가 필요할 때 호출됨
        items("employees") {
            fetchEmployeesFromDatabase()
        }
    }

    println("Provider 생성 완료 (데이터는 아직 로드되지 않음)")

    ExcelGenerator().use { generator ->
        val template = File("template.xlsx").inputStream()
        val bytes = generator.generate(template, provider)
        File("output.xlsx").writeBytes(bytes)
    }

    println("Excel 생성 완료")
}
```

### Java 코드

```java
import com.hunet.common.tbeg.ExcelGenerator;
import com.hunet.common.tbeg.SimpleDataProvider;
import java.io.*;
import java.time.LocalDate;
import java.util.*;

public class LazyLoading {

    public record Employee(String name, String position, int salary) {}

    public static Iterator<Employee> fetchEmployeesFromDatabase() {
        String[] positions = {"사원", "대리", "과장", "차장", "부장"};
        List<Employee> employees = new ArrayList<>();
        for (int i = 0; i < 10000; i++) {
            employees.add(new Employee(
                "직원" + (i + 1),
                positions[i % positions.length],
                3000 + (i % 5) * 1000
            ));
        }
        return employees.iterator();
    }

    public static void main(String[] args) throws Exception {
        SimpleDataProvider provider = SimpleDataProvider.builder()
            .value("title", "직원 현황 보고서")
            .value("date", LocalDate.now().toString())
            // 지연 로딩
            .itemsFromSupplier("employees", LazyLoading::fetchEmployeesFromDatabase)
            .build();

        try (ExcelGenerator generator = new ExcelGenerator();
             InputStream template = new FileInputStream("template.xlsx")) {

            byte[] bytes = generator.generate(template, provider);
            try (FileOutputStream output = new FileOutputStream("output.xlsx")) {
                output.write(bytes);
            }
        }
    }
}
```

---

## 2. 비동기 처리

### 2.1 Kotlin Coroutines

```kotlin
import com.hunet.common.tbeg.ExcelGenerator
import com.hunet.common.tbeg.simpleDataProvider
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Path

fun main() = runBlocking {
    val provider = simpleDataProvider {
        value("title", "비동기 보고서")
        items("data") { generateData().iterator() }
    }

    ExcelGenerator().use { generator ->
        val template = File("template.xlsx").inputStream()

        // 비동기 생성
        val path = generator.generateToFileAsync(
            template = template,
            dataProvider = provider,
            outputDir = Path.of("./output"),
            baseFileName = "async_report"
        )

        println("파일 생성됨: $path")
    }
}

fun generateData() = (1..1000).map { mapOf("id" to it, "value" to it * 10) }
```

### 2.2 Java CompletableFuture

```java
import com.hunet.common.tbeg.ExcelGenerator;
import com.hunet.common.tbeg.SimpleDataProvider;
import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class AsyncWithFuture {
    public static void main(String[] args) throws Exception {
        SimpleDataProvider provider = SimpleDataProvider.builder()
            .value("title", "비동기 보고서")
            .items("data", generateData())
            .build();

        try (ExcelGenerator generator = new ExcelGenerator();
             InputStream template = new FileInputStream("template.xlsx")) {

            CompletableFuture<Path> future = generator.generateToFileFuture(
                template,
                provider,
                Path.of("./output"),
                "async_report"
            );

            // 완료 시 콜백
            future.thenAccept(path -> {
                System.out.println("파일 생성됨: " + path);
            });

            // 완료 대기
            Path result = future.get();
        }
    }

    private static List<Map<String, Object>> generateData() {
        List<Map<String, Object>> data = new ArrayList<>();
        for (int i = 1; i <= 1000; i++) {
            data.add(Map.of("id", i, "value", i * 10));
        }
        return data;
    }
}
```

### 2.3 백그라운드 작업 + 리스너

API 서버에서 즉시 응답하고 백그라운드에서 처리합니다.

```kotlin
import com.hunet.common.tbeg.ExcelGenerator
import com.hunet.common.tbeg.async.ExcelGenerationListener
import com.hunet.common.tbeg.async.GenerationResult
import com.hunet.common.tbeg.simpleDataProvider
import java.io.File
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

fun main() {
    val latch = CountDownLatch(1)

    val provider = simpleDataProvider {
        value("title", "백그라운드 보고서")
        items("data") { (1..5000).map { mapOf("id" to it) }.iterator() }
    }

    ExcelGenerator().use { generator ->
        val template = File("template.xlsx").inputStream()

        val job = generator.submitToFile(
            template = template,
            dataProvider = provider,
            outputDir = Path.of("./output"),
            baseFileName = "background_report",
            listener = object : ExcelGenerationListener {
                override fun onStarted(jobId: String) {
                    println("[시작] Job ID: $jobId")
                }

                override fun onCompleted(jobId: String, result: GenerationResult) {
                    println("[완료] 파일: ${result.filePath}")
                    println("[완료] 처리 행: ${result.rowsProcessed}")
                    println("[완료] 소요 시간: ${result.durationMs}ms")
                    latch.countDown()
                }

                override fun onFailed(jobId: String, error: Exception) {
                    println("[실패] ${error.message}")
                    latch.countDown()
                }

                override fun onCancelled(jobId: String) {
                    println("[취소됨]")
                    latch.countDown()
                }
            }
        )

        println("작업 제출됨: ${job.jobId}")
        println("(API 서버에서는 여기서 HTTP 202 반환)")

        // 작업 취소 예시
        // job.cancel()

        latch.await(60, TimeUnit.SECONDS)
    }
}
```

---

## 3. 피벗 테이블

템플릿에 피벗 테이블이 있으면 데이터 확장 후 자동으로 재생성됩니다.

### 템플릿 구성

1. **데이터 시트**: 피벗 테이블의 소스 데이터가 될 범위
2. **피벗 테이블 시트**: 피벗 테이블이 배치된 시트

### 템플릿 (pivot_template.xlsx)

**Sheet1 (데이터)**:

|   | A                                | B               | C             |
|---|----------------------------------|-----------------|---------------|
| 1 | ${repeat(employees, A3:C3, emp)} |                 |               |
| 2 | 이름                               | 직급              | 급여            |
| 3 | ${emp.name}                      | ${emp.position} | ${emp.salary} |

**Sheet2 (피벗)**:
- 피벗 테이블: 소스 범위 = Sheet1!A:C
- 행: 직급
- 값: 급여 합계

### Kotlin 코드

```kotlin
import com.hunet.common.tbeg.ExcelGenerator
import java.io.File

data class Employee(val name: String, val position: String, val salary: Int)

fun main() {
    val data = mapOf(
        "employees" to listOf(
            Employee("황용호", "부장", 8000),
            Employee("홍용호", "부장", 8500),
            Employee("한용호", "과장", 6500),
            Employee("김용호", "과장", 6800),
            Employee("이용호", "대리", 4500),
            Employee("박용호", "대리", 4200),
            Employee("최용호", "사원", 3500)
        )
    )

    ExcelGenerator().use { generator ->
        val template = File("pivot_template.xlsx").inputStream()
        val bytes = generator.generate(template, data)
        File("pivot_output.xlsx").writeBytes(bytes)
    }
}
```

### 결과 피벗 테이블

| 직급 | 급여 합계 |
|------|----------|
| 부장 | 16,500 |
| 과장 | 13,300 |
| 대리 | 8,700 |
| 사원 | 3,500 |
| **총합계** | **42,000** |

---

## 4. 수식에서 변수 사용

### 템플릿 (formula_template.xlsx)

|   | A      | B                           | C |
|---|--------|-----------------------------|---|
| 1 | 시작 행   | ${startRow}                 |   |
| 2 | 종료 행   | ${endRow}                   |   |
| 3 |        |                             |   |
| 4 | 데이터1   | 100                         |   |
| 5 | 데이터2   | 200                         |   |
| 6 | 데이터3   | 300                         |   |
| 7 |        |                             |   |
| 8 | 합계     | =SUM(B${startRow}:B${endRow}) |   |

### Kotlin 코드

```kotlin
import com.hunet.common.tbeg.ExcelGenerator
import java.io.File

fun main() {
    val data = mapOf(
        "startRow" to 4,
        "endRow" to 6
    )

    ExcelGenerator().use { generator ->
        val template = File("formula_template.xlsx").inputStream()
        val bytes = generator.generate(template, data)
        File("formula_output.xlsx").writeBytes(bytes)
    }
}
```

### 결과

|   | A      | B                  |
|---|--------|--------------------|
| 1 | 시작 행   | 4                  |
| 2 | 종료 행   | 6                  |
| 3 |        |                    |
| 4 | 데이터1   | 100                |
| 5 | 데이터2   | 200                |
| 6 | 데이터3   | 300                |
| 7 |        |                    |
| 8 | 합계     | =SUM(B4:B6) -> 600  |

---

## 5. 하이퍼링크

### 템플릿 (link_template.xlsx)

셀 A1에 하이퍼링크를 설정합니다:
- 텍스트: `${linkText}`
- 하이퍼링크 주소: `${url}`

### Kotlin 코드

```kotlin
import com.hunet.common.tbeg.ExcelGenerator
import java.io.File

fun main() {
    val data = mapOf(
        "linkText" to "휴넷 홈페이지 바로가기",
        "url" to "https://www.hunet.co.kr"
    )

    ExcelGenerator().use { generator ->
        val template = File("link_template.xlsx").inputStream()
        val bytes = generator.generate(template, data)
        File("link_output.xlsx").writeBytes(bytes)
    }
}
```

---

## 6. 다중 시트

### 템플릿 (multi_sheet_template.xlsx)

**Summary 시트**:

|   | A       | B              |
|---|---------|----------------|
| 1 | 제목      | ${title}       |
| 2 | 총 직원 수  | ${totalCount}  |

**Employees 시트**:

|   | A                                | B               | C             |
|---|----------------------------------|-----------------|---------------|
| 1 | ${repeat(employees, A3:C3, emp)} |                 |               |
| 2 | 이름                               | 직급              | 급여            |
| 3 | ${emp.name}                      | ${emp.position} | ${emp.salary} |

### Kotlin 코드

```kotlin
import com.hunet.common.tbeg.ExcelGenerator
import java.io.File

data class Employee(val name: String, val position: String, val salary: Int)

fun main() {
    val employees = listOf(
        Employee("황용호", "부장", 8000),
        Employee("한용호", "과장", 6500),
        Employee("홍용호", "대리", 4500)
    )

    val data = mapOf(
        "title" to "직원 현황",
        "totalCount" to employees.size,
        "employees" to employees
    )

    ExcelGenerator().use { generator ->
        val template = File("multi_sheet_template.xlsx").inputStream()
        val bytes = generator.generate(template, data)
        File("multi_sheet_output.xlsx").writeBytes(bytes)
    }
}
```

---

## 7. 대용량 데이터 처리

### 권장 설정

```kotlin
import com.hunet.common.tbeg.ExcelGenerator
import com.hunet.common.tbeg.ExcelGeneratorConfig
import com.hunet.common.tbeg.StreamingMode
import com.hunet.common.tbeg.simpleDataProvider
import java.io.File
import java.nio.file.Path

fun main() {
    // 대용량 데이터용 설정
    val config = ExcelGeneratorConfig(
        streamingMode = StreamingMode.ENABLED,  // 스트리밍 모드 강제 활성화
        progressReportInterval = 1000           // 1000행마다 진행률 보고
    )

    // 지연 로딩으로 데이터 제공
    val provider = simpleDataProvider {
        value("title", "대용량 보고서")
        items("data") {
            // 100만 건 데이터 시뮬레이션
            (1..1_000_000).asSequence().map {
                mapOf("id" to it, "value" to it * 10)
            }.iterator()
        }
    }

    ExcelGenerator(config).use { generator ->
        val template = File("template.xlsx").inputStream()

        val path = generator.generateToFile(
            template = template,
            dataProvider = provider,
            outputDir = Path.of("./output"),
            baseFileName = "large_report"
        )

        println("파일 생성됨: $path")
    }
}
```

### 대용량 처리 팁

1. **스트리밍 모드 사용**: `StreamingMode.ENABLED`로 메모리 사용량 최소화

2. **지연 로딩 활용**: `items()` 블록에서 Iterator를 반환하여 필요할 때만 데이터 로드

3. **비동기 처리**: API 서버에서는 `submit()` 또는 `submitToFile()`로 백그라운드 처리

4. **진행률 모니터링**: `progressReportInterval`을 설정하여 진행률 확인

---

## 다음 단계

- [Spring Boot 예제](./spring-boot-examples.md) - Spring Boot 환경 통합
- [설정 옵션 레퍼런스](../reference/configuration.md) - 상세 설정
- [API 레퍼런스](../reference/api-reference.md) - API 상세
