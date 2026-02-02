# TBEG 고급 예제

## 목차
1. [지연 로딩 (DataProvider)](#1-지연-로딩-dataprovider)
2. [비동기 처리](#2-비동기-처리)
3. [수식에서 변수 사용](#3-수식에서-변수-사용)
4. [하이퍼링크](#4-하이퍼링크)
5. [다중 시트](#5-다중-시트)
6. [대용량 데이터 처리](#6-대용량-데이터-처리)
7. [다중 반복 영역](#7-다중-반복-영역)
8. [오른쪽 방향 반복](#8-오른쪽-방향-반복)

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

### count 제공으로 성능 최적화

```kotlin
val employeeCount = 10000  // DB에서 COUNT 쿼리로 조회

val provider = simpleDataProvider {
    value("title", "직원 현황 보고서")

    // count와 함께 지연 로딩 제공 (최적 성능)
    items("employees", employeeCount) {
        fetchEmployeesFromDatabase()
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

## 3. 수식에서 변수 사용

### 템플릿 (formula_template.xlsx)

|   | A      | B                             |
|---|--------|-------------------------------|
| 1 | 시작 행   | ${startRow}                   |
| 2 | 종료 행   | ${endRow}                     |
| 3 |        |                               |
| 4 | 데이터1   | 100                           |
| 5 | 데이터2   | 200                           |
| 6 | 데이터3   | 300                           |
| 7 |        |                               |
| 8 | 합계     | =SUM(B${startRow}:B${endRow}) |

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
| 8 | 합계     | =SUM(B4:B6) -> 600 |

---

## 4. 하이퍼링크

### 템플릿 (link_template.xlsx)

셀 A1에 HYPERLINK 수식 설정:
```
=HYPERLINK("${url}", "${text}")
```

### Kotlin 코드

```kotlin
import com.hunet.common.tbeg.ExcelGenerator
import java.io.File

fun main() {
    val data = mapOf(
        "text" to "휴넷 홈페이지 바로가기",
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

## 5. 다중 시트

### 템플릿 (multi_sheet_template.xlsx)

**Summary 시트**:

|   | A       | B              |
|---|---------|----------------|
| 1 | 제목      | ${title}       |
| 2 | 총 직원 수  | ${size(employees)} |

**Employees 시트**:

|   | A                                  | B               | C             |
|---|------------------------------------|-----------------|---------------|
| 1 | ${repeat(employees, A2:C2, emp)}   |                 |               |
| 2 | ${emp.name}                        | ${emp.position} | ${emp.salary} |

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

## 6. 대용량 데이터 처리

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
        streamingMode = StreamingMode.ENABLED,  // 스트리밍 모드 활성화
        progressReportInterval = 1000           // 1000행마다 진행률 보고
    )

    // 데이터 개수 (DB COUNT 쿼리로 조회)
    val dataCount = 1_000_000

    // 지연 로딩으로 데이터 제공
    val provider = simpleDataProvider {
        value("title", "대용량 보고서")

        // count와 함께 지연 로딩 제공 (최적 성능)
        items("data", dataCount) {
            // 100만 건 데이터 시뮬레이션
            (1..dataCount).asSequence().map {
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

---

## 7. 다중 반복 영역

한 시트에 여러 개의 반복 영역을 사용할 수 있습니다.

### 템플릿 (multi_repeat.xlsx)

|   | A                                     | B             | C | D                                      | E             |
|---|---------------------------------------|---------------|---|----------------------------------------|---------------|
| 1 | ${repeat(employees, A2:B2, emp)}      |               |   | ${repeat(departments, D2:E2, dept)}    |               |
| 2 | ${emp.name}                           | ${emp.salary} |   | ${dept.name}                           | ${dept.budget}|

### Kotlin 코드

```kotlin
import com.hunet.common.tbeg.ExcelGenerator
import java.io.File

data class Employee(val name: String, val salary: Int)
data class Department(val name: String, val budget: Int)

fun main() {
    val data = mapOf(
        "employees" to listOf(
            Employee("황용호", 8000),
            Employee("한용호", 6500),
            Employee("홍용호", 4500)
        ),
        "departments" to listOf(
            Department("개발팀", 50000),
            Department("기획팀", 30000)
        )
    )

    ExcelGenerator().use { generator ->
        val template = File("multi_repeat.xlsx").inputStream()
        val bytes = generator.generate(template, data)
        File("output.xlsx").writeBytes(bytes)
    }
}
```

### 결과

|   | A    | B     | C | D     | E      |
|---|------|-------|---|-------|--------|
| 1 |      |       |   |       |        |
| 2 | 황용호 | 8,000 |   | 개발팀  | 50,000 |
| 3 | 한용호 | 6,500 |   | 기획팀  | 30,000 |
| 4 | 홍용호 | 4,500 |   |       |        |

> **주의**: 반복 영역은 2D 공간에서 겹치면 안 됩니다.

---

## 8. 오른쪽 방향 반복

### 템플릿 (right_repeat.xlsx)

|   | A                                       | B             |
|---|-----------------------------------------|---------------|
| 1 | ${repeat(months, B1:B2, m, RIGHT)}      | ${m.month}월   |
| 2 |                                         | ${m.sales}    |

### Kotlin 코드

```kotlin
import com.hunet.common.tbeg.ExcelGenerator
import java.io.File

data class MonthData(val month: Int, val sales: Int)

fun main() {
    val data = mapOf(
        "months" to listOf(
            MonthData(1, 1000),
            MonthData(2, 1500),
            MonthData(3, 2000),
            MonthData(4, 1800)
        )
    )

    ExcelGenerator().use { generator ->
        val template = File("right_repeat.xlsx").inputStream()
        val bytes = generator.generate(template, data)
        File("output.xlsx").writeBytes(bytes)
    }
}
```

### 결과

|   | A  | B      | C      | D      | E      |
|---|----|--------|--------|--------|--------|
| 1 |    | 1월     | 2월     | 3월     | 4월     |
| 2 |    | 1,000  | 1,500  | 2,000  | 1,800  |

---

## 다음 단계

- [Spring Boot 예제](./spring-boot-examples.md) - Spring Boot 환경 통합
- [설정 옵션 레퍼런스](../reference/configuration.md) - 상세 설정
- [API 레퍼런스](../reference/api-reference.md) - API 상세
