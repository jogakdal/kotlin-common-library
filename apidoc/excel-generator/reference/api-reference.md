# Excel Generator API 레퍼런스

## 목차
1. [ExcelGenerator](#1-excelgenerator)
2. [ExcelDataProvider](#2-exceldataprovider)
3. [SimpleDataProvider](#3-simpledataprovider)
4. [DocumentMetadata](#4-documentmetadata)
5. [비동기 API](#5-비동기-api)
6. [예외 클래스](#6-예외-클래스)

---

## 1. ExcelGenerator

템플릿 기반 Excel 생성기의 메인 클래스입니다.

### 패키지
```kotlin
com.hunet.common.excel.ExcelGenerator
```

### 생성자

```kotlin
ExcelGenerator()
ExcelGenerator(config: ExcelGeneratorConfig)
```

| 파라미터 | 타입 | 설명 |
|----------|------|------|
| config | `ExcelGeneratorConfig` | 생성기 설정 (기본값: `ExcelGeneratorConfig()`) |

### 동기 메서드

#### generate(template, data)

Map 데이터로 Excel을 생성합니다.

```kotlin
fun generate(
    template: InputStream,
    data: Map<String, Any>,
    password: String? = null
): ByteArray
```

| 파라미터 | 타입 | 설명 |
|----------|------|------|
| template | `InputStream` | 템플릿 입력 스트림 |
| data | `Map<String, Any>` | 바인딩할 데이터 맵 |
| password | `String?` | 파일 열기 암호 (선택) |

**반환값**: 생성된 Excel 파일의 바이트 배열

**예시 (Kotlin)**:
```kotlin
val bytes = generator.generate(
    template = File("template.xlsx").inputStream(),
    data = mapOf("title" to "보고서", "items" to listOf(...))
)
```

**예시 (Java)**:
```java
byte[] bytes = generator.generate(
    new FileInputStream("template.xlsx"),
    Map.of("title", "보고서", "items", List.of(...))
);
```

---

#### generate(template, dataProvider)

DataProvider로 Excel을 생성합니다.

```kotlin
fun generate(
    template: InputStream,
    dataProvider: ExcelDataProvider,
    password: String? = null
): ByteArray
```

| 파라미터 | 타입 | 설명 |
|----------|------|------|
| template | `InputStream` | 템플릿 입력 스트림 |
| dataProvider | `ExcelDataProvider` | 데이터 제공자 |
| password | `String?` | 파일 열기 암호 (선택) |

**반환값**: 생성된 Excel 파일의 바이트 배열

---

#### generate(template: File, dataProvider)

File 객체로 템플릿을 지정하여 Excel을 생성합니다.

```kotlin
fun generate(
    template: File,
    dataProvider: ExcelDataProvider,
    password: String? = null
): ByteArray
```

---

#### generateToFile(template, dataProvider, outputDir, baseFileName)

Excel을 생성하여 파일로 저장합니다.

```kotlin
fun generateToFile(
    template: InputStream,
    dataProvider: ExcelDataProvider,
    outputDir: Path,
    baseFileName: String,
    password: String? = null
): Path
```

| 파라미터 | 타입 | 설명 |
|----------|------|------|
| template | `InputStream` | 템플릿 입력 스트림 |
| dataProvider | `ExcelDataProvider` | 데이터 제공자 |
| outputDir | `Path` | 출력 디렉토리 경로 |
| baseFileName | `String` | 기본 파일명 (확장자 제외) |
| password | `String?` | 파일 열기 암호 (선택) |

**반환값**: 생성된 파일의 경로

**파일명 생성 규칙**:
- `FileNamingMode.TIMESTAMP` (기본): `{baseFileName}_{yyyyMMdd_HHmmss}.xlsx`
- `FileNamingMode.NONE`: `{baseFileName}.xlsx`

**예시**:
```kotlin
val path = generator.generateToFile(
    template = template,
    dataProvider = provider,
    outputDir = Path.of("/var/reports"),
    baseFileName = "monthly_report"
)
// 결과: /var/reports/monthly_report_20260115_143052.xlsx
```

---

### 비동기 메서드

#### generateAsync (Kotlin Coroutines)

```kotlin
suspend fun generateAsync(
    template: InputStream,
    dataProvider: ExcelDataProvider,
    password: String? = null
): ByteArray
```

#### generateToFileAsync (Kotlin Coroutines)

```kotlin
suspend fun generateToFileAsync(
    template: InputStream,
    dataProvider: ExcelDataProvider,
    outputDir: Path,
    baseFileName: String,
    password: String? = null
): Path
```

#### generateFuture (Java CompletableFuture)

```kotlin
fun generateFuture(
    template: InputStream,
    dataProvider: ExcelDataProvider,
    password: String? = null
): CompletableFuture<ByteArray>
```

#### generateToFileFuture (Java CompletableFuture)

```kotlin
fun generateToFileFuture(
    template: InputStream,
    dataProvider: ExcelDataProvider,
    outputDir: Path,
    baseFileName: String,
    password: String? = null
): CompletableFuture<Path>
```

#### submit (백그라운드 작업)

비동기 작업을 제출하고 작업 핸들을 반환합니다.

```kotlin
fun submit(
    template: InputStream,
    dataProvider: ExcelDataProvider,
    outputDir: Path,
    baseFileName: String,
    password: String? = null,
    listener: ExcelGenerationListener? = null
): GenerationJob
```

| 파라미터 | 타입 | 설명 |
|----------|------|------|
| template | `InputStream` | 템플릿 입력 스트림 |
| dataProvider | `ExcelDataProvider` | 데이터 제공자 |
| outputDir | `Path` | 출력 디렉토리 경로 |
| baseFileName | `String` | 기본 파일명 (확장자 제외) |
| password | `String?` | 파일 열기 암호 (선택) |
| listener | `ExcelGenerationListener?` | 작업 상태 리스너 (선택) |

**반환값**: `GenerationJob` - 작업 핸들

---

### 리소스 관리

#### close()

```kotlin
override fun close()
```

리소스를 해제합니다. `Closeable` 인터페이스를 구현하므로 `use` 블록이나 try-with-resources를 사용할 수 있습니다.

**Kotlin**:
```kotlin
ExcelGenerator().use { generator ->
    // 사용
}
```

**Java**:
```java
try (ExcelGenerator generator = new ExcelGenerator()) {
    // 사용
}
```

---

## 2. ExcelDataProvider

데이터 제공자 인터페이스입니다.

### 패키지
```kotlin
com.hunet.common.excel.ExcelDataProvider
```

### 메서드

```kotlin
interface ExcelDataProvider {
    fun getValue(name: String): Any?
    fun getItems(name: String): Iterator<Any>?
    fun getImage(name: String): ByteArray?
    fun getAvailableNames(): Set<String>
    fun getMetadata(): DocumentMetadata?
}
```

| 메서드 | 설명 |
|--------|------|
| `getValue(name)` | 단일 값 반환 |
| `getItems(name)` | 컬렉션 Iterator 반환 |
| `getImage(name)` | 이미지 바이트 배열 반환 |
| `getAvailableNames()` | 사용 가능한 모든 이름 반환 |
| `getMetadata()` | 문서 메타데이터 반환 |

---

## 3. SimpleDataProvider

`ExcelDataProvider`의 기본 구현체입니다.

### 패키지
```kotlin
com.hunet.common.excel.SimpleDataProvider
```

### 정적 메서드

#### of(data)

Map으로부터 SimpleDataProvider를 생성합니다.

```kotlin
@JvmStatic
fun of(data: Map<String, Any>): SimpleDataProvider
```

**예시**:
```kotlin
val provider = SimpleDataProvider.of(mapOf(
    "title" to "보고서",
    "items" to listOf(item1, item2)
))
```

#### empty()

빈 SimpleDataProvider를 반환합니다.

```kotlin
@JvmStatic
fun empty(): SimpleDataProvider
```

#### builder()

Builder를 반환합니다.

```kotlin
@JvmStatic
fun builder(): Builder
```

---

### Builder 클래스

#### value(name, value)

단일 값을 추가합니다.

```kotlin
fun value(name: String, value: Any): Builder
```

#### items(name, items)

컬렉션을 추가합니다 (즉시 로딩).

```kotlin
fun items(name: String, items: Iterable<Any>): Builder
```

#### items(name, itemsSupplier)

컬렉션을 추가합니다 (지연 로딩 - Kotlin).

```kotlin
fun items(name: String, itemsSupplier: () -> Iterator<Any>): Builder
```

#### itemsFromSupplier(name, itemsSupplier)

컬렉션을 추가합니다 (지연 로딩 - Java).

```kotlin
fun itemsFromSupplier(name: String, itemsSupplier: Supplier<Iterator<Any>>): Builder
```

#### image(name, imageData)

이미지를 추가합니다.

```kotlin
fun image(name: String, imageData: ByteArray): Builder
```

#### metadata(block)

문서 메타데이터를 설정합니다 (Kotlin DSL).

```kotlin
fun metadata(block: DocumentMetadataBuilder.() -> Unit): Builder
```

#### metadata(configurer)

문서 메타데이터를 설정합니다 (Java Consumer).

```kotlin
fun metadata(configurer: Consumer<DocumentMetadata.Builder>): Builder
```

#### build()

SimpleDataProvider를 빌드합니다.

```kotlin
fun build(): SimpleDataProvider
```

---

### DSL 함수

#### simpleDataProvider

DSL 방식으로 SimpleDataProvider를 생성합니다.

```kotlin
fun simpleDataProvider(block: SimpleDataProvider.Builder.() -> Unit): SimpleDataProvider
```

**예시**:
```kotlin
val provider = simpleDataProvider {
    value("title", "보고서")
    items("employees") { repository.streamAll().iterator() }
    image("logo", logoBytes)
    metadata {
        title = "월간 보고서"
        author = "작성자"
    }
}
```

---

## 4. DocumentMetadata

문서 메타데이터를 나타내는 데이터 클래스입니다.

### 패키지
```kotlin
com.hunet.common.excel.DocumentMetadata
```

### 속성

| 속성 | 타입 | 설명 |
|------|------|------|
| title | `String?` | 문서 제목 |
| author | `String?` | 작성자 |
| subject | `String?` | 주제 |
| keywords | `List<String>?` | 키워드 목록 |
| description | `String?` | 설명 |
| category | `String?` | 범주 |
| company | `String?` | 회사 |
| manager | `String?` | 관리자 |
| created | `LocalDateTime?` | 작성 일시 |

### DSL Builder

```kotlin
metadata {
    title = "보고서 제목"
    author = "작성자"
    subject = "주제"
    keywords("키워드1", "키워드2", "키워드3")
    description = "문서 설명"
    category = "범주"
    company = "회사명"
    manager = "관리자"
    created = LocalDateTime.now()
}
```

### Java Builder

```java
DocumentMetadata.Builder builder = new DocumentMetadata.Builder();
builder.title("보고서 제목")
       .author("작성자")
       .company("회사명");
DocumentMetadata metadata = builder.build();
```

---

## 5. 비동기 API

### GenerationJob

비동기 작업 핸들 인터페이스입니다.

```kotlin
interface GenerationJob {
    val jobId: String
    val status: JobStatus
    fun cancel(): Boolean
    fun await(): GenerationResult
    fun await(timeout: Long, unit: TimeUnit): GenerationResult?
}
```

| 속성/메서드 | 설명 |
|------------|------|
| `jobId` | 작업 고유 ID |
| `status` | 현재 작업 상태 |
| `cancel()` | 작업 취소 |
| `await()` | 완료까지 대기 |
| `await(timeout, unit)` | 제한 시간 내 대기 |

### JobStatus

```kotlin
enum class JobStatus {
    PENDING,    // 대기 중
    RUNNING,    // 실행 중
    COMPLETED,  // 완료
    FAILED,     // 실패
    CANCELLED   // 취소됨
}
```

### GenerationResult

생성 결과를 나타내는 데이터 클래스입니다.

```kotlin
data class GenerationResult(
    val jobId: String,
    val filePath: Path,
    val rowsProcessed: Int,
    val durationMs: Long
)
```

| 속성 | 타입 | 설명 |
|------|------|------|
| jobId | `String` | 작업 ID |
| filePath | `Path` | 생성된 파일 경로 |
| rowsProcessed | `Int` | 처리된 행 수 |
| durationMs | `Long` | 소요 시간 (밀리초) |

### ExcelGenerationListener

작업 상태 리스너 인터페이스입니다.

```kotlin
interface ExcelGenerationListener {
    fun onStarted(jobId: String) {}
    fun onProgress(jobId: String, progress: ProgressInfo) {}
    fun onCompleted(jobId: String, result: GenerationResult) {}
    fun onFailed(jobId: String, error: Exception) {}
    fun onCancelled(jobId: String) {}
}
```

| 메서드 | 호출 시점 |
|--------|----------|
| `onStarted` | 작업 시작 시 |
| `onProgress` | 진행률 변경 시 |
| `onCompleted` | 작업 완료 시 |
| `onFailed` | 작업 실패 시 |
| `onCancelled` | 작업 취소 시 |

### ProgressInfo

진행률 정보입니다.

```kotlin
data class ProgressInfo(
    val processedRows: Int,
    val percentage: Int
)
```

---

## 6. 예외 클래스

### TemplateProcessingException

템플릿 처리 중 발생하는 예외입니다.

```kotlin
class TemplateProcessingException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)
```

**발생 상황**:
- repeat 마커 문법 오류
- 필수 파라미터 누락
- 잘못된 셀 범위
- 존재하지 않는 시트 참조

### FormulaExpansionException

수식 확장 중 발생하는 예외입니다.

```kotlin
class FormulaExpansionException(
    val sheetName: String,
    val cellRef: String,
    val formula: String
) : RuntimeException(...)
```

| 속성 | 설명 |
|------|------|
| sheetName | 오류가 발생한 시트 이름 |
| cellRef | 오류가 발생한 셀 참조 |
| formula | 문제가 된 수식 |

**발생 상황**:
- 수식의 참조 범위가 Excel 제한을 초과
- 대량의 행이 생성되어 수식 참조가 너무 커짐

---

## 다음 단계

- [설정 옵션 레퍼런스](./configuration.md) - ExcelGeneratorConfig 상세
- [Spring Boot 예제](../examples/spring-boot-examples.md) - 실전 예제
- [사용자 가이드](../user-guide.md) - 전체 가이드
