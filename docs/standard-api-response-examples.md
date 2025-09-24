# Standard API Response 활용 예제

> 다양한 페이로드/목록/케이스/alias 시나리오를 빠르게 참고하기 위한 **예제 중심** 문서입니다.
> 본 문서는 스펙/가이드에 정의된 규칙을 실제 코드 형태로 빠르게 찾아 붙여 넣을 수 있도록 하는 '카탈로그' 역할을 합니다.
>
> ### 관련 문서 (Cross References)
> | 문서                                                                               | 목적 / 차이점 |
> |----------------------------------------------------------------------------------|----------|
> | [standard-api-response-library-guide.md](standard-api-response-library-guide.md) | 라이브러리 **사용자 가이드**: spec 을 준수하여 응답 생성/역직렬화 하는 헬퍼 API 설명. |
> | [standard-api-specification.md](standard-api-specification.md)                   | 표준 API 규격: request 규칙, response의 필드 정의, 상태/에러 규칙, 리스트 처리 방식 정의 |
> | [README.md](../README.md)                                                        | 루트 개요 & 지원 CaseConvention 요약 표. |
>
> 아래 예제들은 세(spec) 준수 상태를 가정하며, 불일치 시 spec 문서를 우선합니다.

이 문서는 `standard-api-response` 라이브러리에 대한 활용 가능한 예제를 제공합니다.

포함되는 케이스 분류:
1. 리스트가 없는 기본 페이로드 (Basic Scalar Payload)
2. 단일 리스트를 `pageable` 또는 `incremental` 필드로 포함하는 페이로드
3. 리스트 자체가 곧 페이로드 (`PageableList` / `IncrementalList` 직접 응답)
4. 여러 리스트(`Pageable` + `Incremental` 등)를 동시에 가지는 페이로드
5. 이미 정의된(다른) 페이로드를 하위 필드로 중첩 보유하는 페이로드 (Composite/Nested)

각 케이스별로 Kotlin / Java 두 가지 예제를 모두 제시합니다.

---
## 0. 공통 준비
Gradle(Kotlin DSL)
```kotlin
dependencies { implementation("com.hunet.common_library:standard-api-response:<version>") }
```
(Java 프로젝트도 동일한 좌표 사용)

application.yml (선택: duration 자동 주입)
```yaml
standard-api-response:
  auto-duration-calculation:
    active: true
```

---
## 1. 리스트가 없는 기본 페이로드
### 1.1 Kotlin
```kotlin
data class StatusPayload(
  val code: String,
  val message: String
) : BasePayload

fun basicSuccess(): StandardResponse<StatusPayload> =
  StandardResponse.build(StatusPayload("OK", "성공"))

fun basicFailure(): StandardResponse<ErrorPayload> =
  StandardResponse.build(
    ErrorPayload(code = "E400", message = "잘못된 요청"),
    status = StandardStatus.FAILURE,
    version = "1.0"
  )

// Sample Result (basicSuccess)
// {
//   "status":"SUCCESS",
//   "version":"1.0",
//   "datetime":"2025-09-16T12:00:00Z",
//   "duration":5,
//   "payload":{"code":"OK","message":"성공"}
// }
```
### 1.2 Java
```java
public class StatusPayload implements BasePayload {
  private final String code;
  private final String message;
  public StatusPayload(String code, String message) { this.code = code; this.message = message; }
  public String getCode() { return code; }
  public String getMessage() { return message; }
}

public StandardResponse<StatusPayload> basicSuccess() {
  return StandardResponse.build(new StatusPayload("OK", "성공"));
}

public StandardResponse<ErrorPayload> basicFailure() {
  return StandardResponse.build(
      new ErrorPayload("E400", "잘못된 요청", null),
      StandardStatus.FAILURE,
      "1.0"
  );
}

// Sample Result (basicFailure)
// {
//   "status":"FAILURE",
//   "version":"1.0",
//   "datetime":"2025-09-16T12:00:02Z",
//   "duration":3,
//   "payload":{"errors":[{"code":"E400","message":"잘못된 요청"}],"appendix":{}}
// }
```

---
## 2. 단일 리스트를 포함하는 페이로드 (pageable / incremental 필드명)
### 2.1 Pageable 포함
#### Kotlin
```kotlin
data class ItemPayload(val id: Long, val name: String) : BasePayload

data class ItemsPageContainer(
  val pageable: PageableList<ItemPayload>
) : BasePayload

fun pageableContainer(): StandardResponse<ItemsPageContainer> {
  val items = listOf(ItemPayload(1, "황용호"), ItemPayload(2, "홍용호"))
  val page = PageableList.build(
    items = items,
    totalItems = 10,
    pageSize = 2,
    currentPage = 1,
    orderInfo = OrderInfo(sorted = true, by = listOf(OrderBy("id", OrderDirection.ASC)))
  )
  return StandardResponse.build(ItemsPageContainer(page))
}

// Sample Result
// {
//   "status":"SUCCESS","version":"1.0","datetime":"2025-09-16T12:01:00Z","duration":4,
//   "payload":{
//     "pageable":{
//       "page":{"size":2,"current":1,"total":5},
//       "order":{"sorted":true,"by":[{"field":"id","direction":"asc"}]},
//       "items":{"total":10,"current":2,"list":[{"id":1,"name":"황용호"},{"id":2,"name":"홍용호"}]}
//     }
//   }
// }
```
#### Java
```java
public class ItemPayload implements BasePayload {
  private final long id;
  private final String name;
  public ItemPayload(long id, String name) { this.id = id; this.name = name; }
  public long getId() { return id; }
  public String getName() { return name; }
}

public class ItemsPageContainer implements BasePayload {
  private final PageableList<ItemPayload> pageable;
  public ItemsPageContainer(PageableList<ItemPayload> pageable) { this.pageable = pageable; }
  public PageableList<ItemPayload> getPageable() { return pageable; }
}

public StandardResponse<ItemsPageContainer> pageableContainer() {
  List<ItemPayload> list = List.of(new ItemPayload(1, "황용호"), new ItemPayload(2, "홍용호"));
  PageableList<ItemPayload> page = PageableList.build(
      list,
      10,
      2,
      1,
      new OrderInfo(true, List.of(new OrderBy("id", OrderDirection.ASC)))
  );
  return StandardResponse.build(new ItemsPageContainer(page));
}

// Sample Result 동일 (Kotlin 예시 참고)
```

### 2.2 Incremental 포함
#### Kotlin
```kotlin
data class LogEntryPayload(val idx: Long, val text: String) : BasePayload

data class LogsIncrementalContainer(
  val incremental: IncrementalList<LogEntryPayload, Long>
) : BasePayload

fun incrementalContainer(start: Long, size: Long): StandardResponse<LogsIncrementalContainer> {
  val logs = (start until (start + size)).map { LogEntryPayload(it, "log-$it") }
  val inc = IncrementalList.buildFromTotal(
    items = logs,
    startIndex = start,
    howMany = size,
    totalItems = 100,
    cursorField = "idx"
  )
  return StandardResponse.build(LogsIncrementalContainer(inc))
}

// Sample Result (start=0,size=3)
// {
//  "status":"SUCCESS","version":"1.0","datetime":"2025-09-16T12:02:00Z","duration":5,
//  "payload":{"incremental":{
//     "cursor":{"field":"idx","start":0,"end":2,"expandable":true},
//     "order":null,
//     "items":{"total":100,"current":3,
//       "list":[{"idx":0,"text":"log-0"},{"idx":1,"text":"log-1"},{"idx":2,"text":"log-2"}]}
//  }}
// }
```
#### Java
```java
public class LogEntryPayload implements BasePayload {
  private final long idx;
  private final String text;
  public LogEntryPayload(long idx, String text) { this.idx = idx; this.text = text; }
  public long getIdx() { return idx; }
  public String getText() { return text; }
}

public class LogsIncrementalContainer implements BasePayload {
  private final IncrementalList<LogEntryPayload, Long> incremental;
  public LogsIncrementalContainer(IncrementalList<LogEntryPayload, Long> incremental) { this.incremental = incremental; }
  public IncrementalList<LogEntryPayload, Long> getIncremental() { return incremental; }
}

public StandardResponse<LogsIncrementalContainer> incrementalContainer(long start, long size) {
  List<LogEntryPayload> logs = java.util.stream.LongStream
      .range(start, start + size)
      .mapToObj(i -> new LogEntryPayload(i, "log-" + i))
      .toList();
  IncrementalList<LogEntryPayload, Long> inc = IncrementalList.buildFromTotalJava(
      logs, start, size, 100, "idx", null, (f, i) -> i
  );
  return StandardResponse.build(new LogsIncrementalContainer(inc));
}

// Sample Result 동일 (Kotlin 예시 참고)
```

---
## 3. 리스트 자체가 페이로드
### 3.1 Kotlin (PageableList 직접)
```kotlin
fun directPageable(): StandardResponse<PageableList<ItemPayload>> {
  val items = listOf(ItemPayload(1, "황용호"))
  val page = PageableList.build(items, totalItems = 1, pageSize = 1, currentPage = 1, orderInfo = null)
  return StandardResponse.build(page)
}

// Sample Result
// {"status":"SUCCESS","version":"1.0","datetime":"2025-09-16T12:03:00Z","duration":2,
//  "payload":{
//    "page":{"size":1,"current":1,"total":1},
//    "order":null,
//    "items":{"total":1,"current":1,"list":[{"id":1,"name":"황용호"}]}
//  }
// }
```
### 3.2 Java (IncrementalList 직접)
```java
public StandardResponse<IncrementalList<LogEntryPayload, Long>> directIncremental() {
  List<LogEntryPayload> list = List.of(new LogEntryPayload(0, "first"));
  IncrementalList<LogEntryPayload, Long> inc = IncrementalList.buildFromTotalJava(
      list, 0, 1, 10, "idx", null, (f, i) -> i
  );
  return StandardResponse.build(inc);
}

// Sample Result
// {"status":"SUCCESS","version":"1.0","datetime":"2025-09-16T12:03:05Z","duration":1,
//  "payload":{
//     "cursor":{"field":"idx","start":0,"end":0,"expandable":true},
//     "order":null,
//     "items":{"total":10,"current":1,"list":[{"idx":0,"text":"first"}]}
//  }
// }
```

---
## 4. 여러 리스트를 동시에 가지는 페이로드
### 4.1 Kotlin
```kotlin
data class MultiListsPayload(
  val users: PageableList<UserPayload>,
  val logs: IncrementalList<LogEntryPayload, Long>
) : BasePayload

data class UserPayload(val id: Long, val name: String) : BasePayload

fun multiLists(): StandardResponse<MultiListsPayload> {
  val users = PageableList.build(
    items = listOf(UserPayload(1, "황용호"), UserPayload(2, "홍용호")),
    totalItems = 2, pageSize = 2, currentPage = 1, orderInfo = null
  )
  val logs = IncrementalList.buildFromTotal(
    items = listOf(LogEntryPayload(10, "boot"), LogEntryPayload(11, "ready")),
    startIndex = 10, howMany = 2, totalItems = 200, cursorField = "idx"
  )
  return StandardResponse.build(MultiListsPayload(users, logs))
}

// Sample Result (요약)
// {"status":"SUCCESS","version":"1.0","payload":{"users":{...},"logs":{...}}}
```
### 4.2 Java
```java
public class UserPayload implements BasePayload {
  private final long id; private final String name;
  public UserPayload(long id, String name) { this.id = id; this.name = name; }
  public long getId() { return id; }
  public String getName() { return name; }
}

public class MultiListsPayload implements BasePayload {
  private final PageableList<UserPayload> users;
  private final IncrementalList<LogEntryPayload, Long> logs;
  public MultiListsPayload(PageableList<UserPayload> users, IncrementalList<LogEntryPayload, Long> logs) {
    this.users = users; this.logs = logs;
  }
  public PageableList<UserPayload> getUsers() { return users; }
  public IncrementalList<LogEntryPayload, Long> getLogs() { return logs; }
}

public StandardResponse<MultiListsPayload> multiLists() {
  PageableList<UserPayload> users = PageableList.build(
      List.of(new UserPayload(1, "황용호"), new UserPayload(2, "홍용호")),
      2, 2, 1, null
  );
  IncrementalList<LogEntryPayload, Long> logs = IncrementalList.buildFromTotalJava(
      List.of(new LogEntryPayload(10, "boot"), new LogEntryPayload(11, "ready")),
      10, 2, 200, "idx", null, (f, i) -> i
  );
  return StandardResponse.build(new MultiListsPayload(users, logs));
}

// Sample Result (요약)
// {"status":"SUCCESS","version":"1.0","payload":{"users":{...},"logs":{...}}}
```

---
## 5. 중첩 페이로드 (Composite)
### 5.1 Kotlin
```kotlin
data class BasicInfoPayload(val system: String, val versionText: String): BasePayload

data class CompositePayload(
  val info: BasicInfoPayload,
  val status: StatusPayload,
  val users: PageableList<UserPayload>
) : BasePayload

fun composite(): StandardResponse<CompositePayload> {
  val info = BasicInfoPayload("core-service", "v2")
  val status = StatusPayload("OK", "정상")
  val users = PageableList.build(
    items = listOf(UserPayload(100, "황용호")),
    totalItems = 1, pageSize = 1, currentPage = 1, orderInfo = null
  )
  return StandardResponse.build(CompositePayload(info, status, users))
}

// Sample Result (요약)
// {"status":"SUCCESS","version":"1.0","payload":{"info":{"system":"core-service"...},"status":{"code":"OK"...},"users":{...}}}
```
### 5.2 Java
```java
public class BasicInfoPayload implements BasePayload {
  private final String system; private final String versionText;
  public BasicInfoPayload(String system, String versionText) { this.system = system; this.versionText = versionText; }
  public String getSystem() { return system; }
  public String getVersionText() { return versionText; }
}

public class CompositePayload implements BasePayload {
  private final BasicInfoPayload info;
  private final StatusPayload status;
  private final PageableList<UserPayload> users;
  public CompositePayload(BasicInfoPayload info, StatusPayload status, PageableList<UserPayload> users) {
    this.info = info; this.status = status; this.users = users;
  }
  public BasicInfoPayload getInfo() { return info; }
  public StatusPayload getStatus() { return status; }
  public PageableList<UserPayload> getUsers() { return users; }
}

public StandardResponse<CompositePayload> composite() {
  BasicInfoPayload info = new BasicInfoPayload("core-service", "v2");
  StatusPayload status = new StatusPayload("OK", "정상");
  PageableList<UserPayload> users = PageableList.build(
      List.of(new UserPayload(100, "황용호")), 1, 1, 1, null
  );
  return StandardResponse.build(new CompositePayload(info, status, users));
}

// Sample Result (요약) - Kotlin 참고
```

---
## 6. 역직렬화 (Deserialize)
### 6.1 Kotlin 기본
```kotlin
val json: String = obtainJson()
val parsed = StandardResponse.deserialize<StatusPayload>(json)
val statusPayload: StatusPayload? = parsed.getRealPayload<StatusPayload>()

// 예상 json 구조: {"status":"SUCCESS","payload":{"code":"OK","message":"성공"}...}
```
### 6.2 Kotlin 안전 래핑
```kotlin
fun safeParse(raw: String): StandardResponse<StatusPayload> = try {
  StandardResponse.deserialize<StatusPayload>(raw)
} catch (e: Exception) {
  StandardResponse.build(
    ErrorPayload("DESERIALIZE_FAIL", e.message ?: "fail"),
    status = StandardStatus.FAILURE,
    version = "1.0"
  )
}
```
### 6.3 Java 타입 지정
```java
StandardResponse<StatusPayload> parsed = StandardResponse.deserialize(json, StatusPayload.class);
StatusPayload payload = parsed.getPayload();

// payload.getCode(), payload.getMessage()
```
### 6.4 Java 런타임 타입 결정
```java
Class<? extends BasePayload> dynamicType = decide();
StandardResponse<? extends BasePayload> parsedAny = StandardResponse.deserialize(json, (Class) dynamicType);
```
### 6.5 Kotlin 부분 추출
```kotlin
val root = kotlinx.serialization.json.Json.parseToJsonElement(json).jsonObject
val onlyStatus = BasePayload.deserializePayload(root, StatusPayload::class.java)
```
### 6.6 Java 실패 Fallback
```java
public StandardResponse<StatusPayload> safe(String raw) {
  try {
    return StandardResponse.deserialize(raw, StatusPayload.class);
  } catch (Exception e) {
    return StandardResponse.build(
        new ErrorPayload("FAIL", "bad json", null),
        StandardStatus.FAILURE,
        "1.0"
    );
  }
}
```
### 6.7 실제 JSON 문자열 예제 (기본 단일 Payload)
#### Kotlin
```kotlin
val jsonBasic = """
{
  "status":"SUCCESS",
  "version":"1.0",
  "datetime":"2025-09-16T12:10:00Z",
  "duration":7,
  "payload":{"code":"OK","message":"성공"}
}
""".trimIndent()

val basicResp = StandardResponse.deserialize<StatusPayload>(jsonBasic)
val msgUpper = basicResp.getRealPayload<StatusPayload>()?.message?.uppercase()
println("MSG_UPPER=$msgUpper")
```
#### Java
```java
String jsonBasic = """
{
  \"status\":\"SUCCESS\",
  \"version\":\"1.0\",
  \"datetime\":\"2025-09-16T12:10:00Z\",
  \"duration\":7,
  \"payload\":{\"code\":\"OK\",\"message\":\"성공\"}
}
""";
StandardResponse<StatusPayload> basicResp = StandardResponse.deserialize(jsonBasic, StatusPayload.class);
String upper = basicResp.getPayload().getMessage().toUpperCase();
System.out.println("MSG_UPPER=" + upper);
```
### 6.8 PageableList Payload JSON (payload 자체가 PageableList)
#### Kotlin
```kotlin
val jsonPageable = """
{
  "status":"SUCCESS","version":"1.0","datetime":"2025-09-16T12:11:00Z","duration":4,
  "payload":{
    "page":{"size":2,"current":1,"total":5},
    "order":{"sorted":true,"by":[{"field":"id","direction":"asc"}]},
    "items":{"total":10,"current":2,"list":[{"id":1,"name":"황용호"},{"id":2,"name":"홍용호"}]}
  }
}
""".trimIndent()
val pageResp = StandardResponse.deserialize<PageableList<ItemPayload>>(jsonPageable)
val ids = pageResp.payload.items.list.map { it.id }
println("IDS=$ids total=${pageResp.payload.items.total}")
```
#### Java
```java
String jsonPageable = """
{
  \"status\":\"SUCCESS\",\"version\":\"1.0\",\"datetime\":\"2025-09-16T12:11:00Z\",\"duration\":4,
  \"payload\":{
    \"page\":{\"size\":2,\"current\":1,\"total\":5},
    \"order\":{\"sorted\":true,\"by\":[{\"field\":\"id\",\"direction\":\"asc\"}]},
    \"items\":{\"total\":10,\"current\":2,\"list\":[{\"id\":1,\"name\":\"황용호\"},{\"id\":2,\"name\":\"홍용호\"}]}
  }
}
""";
StandardResponse<PageableList<ItemPayload>> pageResp = StandardResponse.deserialize(jsonPageable, (Class) PageableList.class); // raw cast 주의
// 안전하게 사용하려면 별도 wrapper 필요. 여기서는 list 크기만 출력
System.out.println("CURRENT_ITEMS=" + pageResp.getPayload().getItems().getList().size());
```
### 6.9 IncrementalList Payload JSON
#### Kotlin
```kotlin
val jsonIncremental = """
{
  "status":"SUCCESS","version":"1.0","datetime":"2025-09-16T12:12:00Z","duration":6,
  "payload":{
    "cursor":{"field":"idx","start":0,"end":2,"expandable":true},
    "order":null,
    "items":{"total":100,"current":3,"list":[{"idx":0,"text":"log-0"},{"idx":1,"text":"log-1"},{"idx":2,"text":"log-2"}]}
  }
}
""".trimIndent()
val incResp = StandardResponse.deserialize<IncrementalList<LogEntryPayload, Long>>(jsonIncremental)
val expandable = incResp.payload.cursor?.expandable == true
println("EXPANDABLE=$expandable nextStart=${incResp.payload.cursor?.end?.plus(1)}")
```
#### Java
```java
String jsonIncremental = """
{
  \"status\":\"SUCCESS\",\"version\":\"1.0\",\"datetime\":\"2025-09-16T12:12:00Z\",\"duration\":6,
  \"payload\":{
    \"cursor\":{\"field\":\"idx\",\"start\":0,\"end\":2,\"expandable\":true},
    \"order\":null,
    \"items\":{\"total\":100,\"current\":3,\"list\":[{\"idx\":0,\"text\":\"log-0\"},{\"idx\":1,\"text\":\"log-1\"},{\"idx\":2,\"text\":\"log-2\"}]}
  }
}
""";
StandardResponse<IncrementalList> incResp = StandardResponse.deserialize(jsonIncremental, IncrementalList.class); // raw type 사용 (예시)
// 실제 제네릭 안전성을 위해 별도 직렬화 전략 권장
System.out.println("EXPANDABLE=" + ((Map) ((Map) incResp.getPayload()).get("cursor")).get("expandable"));
```
### 6.10 Composite Payload
#### Kotlin
```kotlin
val jsonComposite = """
{
  "status":"SUCCESS","version":"1.0","datetime":"2025-09-16T12:13:00Z","duration":8,
  "payload":{
    "info":{"system":"core-service","versionText":"v2"},
    "status":{"code":"OK","message":"정상"},
    "users":{
       "page":{"size":1,"current":1,"total":1},
       "order":null,
       "items":{"total":1,"current":1,"list":[{"id":100,"name":"황용호"}]}
    }
  }
}
""".trimIndent()
val compResp = StandardResponse.deserialize<CompositePayload>(jsonComposite)
val sys = compResp.payload.info.system
val firstUser = compResp.payload.users.items.list.firstOrNull()?.name
println("SYSTEM=$sys FIRST_USER=$firstUser")
```
#### Java
```java
String jsonComposite = """
{
  \"status\":\"SUCCESS\",\"version\":\"1.0\",\"datetime\":\"2025-09-16T12:13:00Z\",\"duration\":8,
  \"payload\":{
    \"info\":{\"system\":\"core-service\",\"versionText\":\"v2\"},
    \"status\":{\"code\":\"OK\",\"message\":\"정상\"},
    \"users\":{\"page\":{\"size\":1,\"current\":1,\"total\":1},\"order\":null,\"items\":{\"total\":1,\"current\":1,\"list\":[{\"id\":100,\"name\":\"황용호\"}]}}
  }
}
""";
StandardResponse<CompositePayload> compResp = StandardResponse.deserialize(jsonComposite, CompositePayload.class);
System.out.println("SYSTEM=" + compResp.getPayload().getInfo().getSystem());
```
### 6.11 실패 응답 JSON 처리
#### Kotlin
```kotlin
val jsonFail = """
{
  "status":"FAILURE","version":"1.0","datetime":"2025-09-16T12:14:00Z","duration":2,
  "payload":{"errors":[{"code":"E401","message":"인증 실패"}],"appendix":{}}
}
""".trimIndent()
val failResp = StandardResponse.deserialize<ErrorPayload>(jsonFail)
if (failResp.status == StandardStatus.FAILURE) {
  val firstErr = failResp.payload.errors.firstOrNull()
  println("ERROR_CODE=${firstErr?.code}")
}
```
#### Java
```java
String jsonFail = """
{
  \"status\":\"FAILURE\",\"version\":\"1.0\",\"datetime\":\"2025-09-16T12:14:00Z\",\"duration\":2,
  \"payload\":{\"errors\":[{\"code\":\"E401\",\"message\":\"인증 실패\"}],\"appendix\":{}}
}
""";
StandardResponse<ErrorPayload> failResp = StandardResponse.deserialize(jsonFail, ErrorPayload.class);
if (failResp.getStatus() == StandardStatus.FAILURE) {
  String code = failResp.getPayload().getErrors().isEmpty() ? null : failResp.getPayload().getErrors().get(0).getCode();
  System.out.println("ERROR_CODE=" + code);
}
```
### 6.12 Dynamic Type Hint 예시
(간단한 typeHint 필드 사용 – 라이브러리 기본 기능은 아니며 사용자 정의 로직 예)
#### Kotlin
```kotlin
val jsonDyn = """
{
  "status":"SUCCESS","version":"1.0","datetime":"2025-09-16T12:15:00Z","duration":3,
  "typeHint":"STATUS",
  "payload":{"code":"OK","message":"성공"}
}
""".trimIndent()
val dynRoot = kotlinx.serialization.json.Json.parseToJsonElement(jsonDyn).jsonObject
val hint = dynRoot["typeHint"]?.jsonPrimitive?.content
val dynResp = when (hint) {
  "STATUS" -> StandardResponse.deserialize<StatusPayload>(jsonDyn)
  else -> StandardResponse.deserialize<BasePayloadImpl>(jsonDyn)
}
println("DYN_STATUS=${dynResp.status} class=${dynResp.payload::class.simpleName}")
```
#### Java
```java
String jsonDyn = """
{
  \"status\":\"SUCCESS\",\"version\":\"1.0\",\"datetime\":\"2025-09-16T12:15:00Z\",\"duration\":3,
  \"typeHint\":\"STATUS\",
  \"payload\":{\"code\":\"OK\",\"message\":\"성공\"}
}
""";
boolean isStatus = jsonDyn.contains("\"typeHint\":\"STATUS\""); // 단순 검사 예
if (isStatus) {
  StandardResponse<StatusPayload> r = StandardResponse.deserialize(jsonDyn, StatusPayload.class);
  System.out.println("DYN_STATUS=" + r.getStatus());
}
```

---
## 7. 케이스 컨벤션 (CaseConvention) 확장 예
### 7.1 기본 변환 매트릭스
| 원본(camel) | SNAKE_CASE | SCREAMING_SNAKE_CASE | KEBAB_CASE | CAMEL_CASE | PASCAL_CASE |
|-------------|------------|----------------------|------------|------------|-------------|
| userId | user_id | USER_ID | user-id | userId | UserId |
| firstName | first_name | FIRST_NAME | first-name | firstName | FirstName |
| APIURLVersion2 | api_url_version2 | API_URL_VERSION2 | api-url-version2 | apiURLVersion2 | ApiUrlVersion2 |

### 7.2 @ResponseCase vs toJson 파라미터
```kotlin
@ResponseCase(CaseConvention.SNAKE_CASE)
data class CaseUser(val userId: Long, val firstName: String): BasePayload

val base = StandardResponse.build(CaseUser(1,"용호"))
base.toJson()                              // snake_case (annotation)
base.toJson(case = CaseConvention.KEBAB_CASE) // kebab-case (파라미터 우선)
```

### 7.3 @NoCaseTransform 예
```kotlin
data class ApiKeyPayload(
  @JsonProperty("api_key") @NoCaseTransform val apiKey: String,
  val requestCount: Long
): BasePayload

val json = StandardResponse.build(ApiKeyPayload("K-1", 5)).toJson(case = CaseConvention.KEBAB_CASE)
// 결과 키: api_key, request-count
```
@NoCaseTransform 은 alias 및 언더스코어/대시 변형을 모두 케이스 변환 제외 목록에 포함합니다.

### 7.4 SCREAMING_SNAKE_CASE 예
```kotlin
@ResponseCase(CaseConvention.SCREAMING_SNAKE_CASE)
data class UpperPayload(
  val userId: Long,
  val firstName: String,
  val lastLoginAt: Instant
): BasePayload

val upperJson = StandardResponse.build(
  UpperPayload(10, "용호", Instant.parse("2025-09-16T00:00:00Z"))
).toJson()
// 포함 키 예: "USER_ID","FIRST_NAME","LAST_LOGIN_AT"

val overrideJson = StandardResponse.build(
  UpperPayload(10, "용호", Instant.parse("2025-09-16T00:00:00Z"))
).toJson(case = CaseConvention.PASCAL_CASE)
// 오버라이드: UserId, FirstName, LastLoginAt
```

---
## 8. Alias / Canonical 매핑 심화
### 8.1 단일 Payload 예
```kotlin
data class AliasUser(
  @JsonProperty("user_id") @JsonAlias("USER-ID","UserId") val userId: Long,
  @JsonProperty("first_name") @JsonAlias("FIRST-NAME","firstName") val firstName: String,
  val emailAddress: String
): BasePayload

val input = """
{
  "status":"SUCCESS","version":"1.0","datetime":"2025-09-16T00:00:00Z",
  "payload":{
    "USER-ID":10,
    "FIRST-NAME":"용호",
    "email-address":"x@y.com"
  }
}
""".trimIndent()
val parsed = StandardResponse.deserialize<AliasUser>(input)
parsed.payload.userId == 10 // true
```

### 8.2 Map & 중첩 구조
```kotlin
data class Child(@JsonProperty("child_name") @JsonAlias("childName","child-name") val childName: String): BasePayload
data class Wrapper(@JsonProperty("items_map") val items: Map<String, Child>): BasePayload

val json = """
{"status":"SUCCESS","version":"1.0","datetime":"2025-09-16T00:00:00Z","payload":{
  "items-map": { "a": { "child-name":"황용호" } }
}}
"""
val wrapper = StandardResponse.deserialize<Wrapper>(json)
wrapper.payload.items["a"]!!.childName // "황용호"
```

### 8.3 List<Map<String, Payload>> (복합)
```kotlin
data class NestedChild(
  @JsonProperty("child_id") @JsonAlias("CHILD-ID","childId") val childId: Long,
  @JsonProperty("label_text") @JsonAlias("label-text","labelText") val label: String
) : BasePayload

data class Complex(@JsonProperty("entries") val entries: List<Map<String, NestedChild>>): BasePayload

val raw = """
{"status":"SUCCESS","version":"1.0","datetime":"2025-09-16T00:00:00Z","payload":{
  "entries":[{"k1":{"CHILD-ID":1,"label-text":"A"}}]
}}
"""
val complex = StandardResponse.deserialize<Complex>(raw)
complex.payload.entries[0]["k1"]!!.childId // 1
```

### 8.4 Canonical 규칙
- 입력 키: 비영숫자 제거 후 소문자 → canonical
- 교차 변형: underscore ↔ dash 를 추가 canonical 후보로 자동 등록
- 결과: `USER-ID`, `user_id`, `UserId`, `userid` → 모두 canonical `userid`

### 8.5 충돌 / 우선순위
| 상황 | 처리 |
|------|------|
| 서로 다른 property 가 동일 canonical | 최초 등록 유지, WARN 로그 |
| 동일 property 에 서로 다른 @JsonProperty | 최초 alias 유지, WARN 로그 |

---
## 9. 캐시 & 성능
최초 역직렬화 시 리플렉션을 통해:
- serializationMap (propertyName→@JsonProperty)
- canonicalAliasToProp (canonical→propertyName)
- skipCaseKeys (@NoCaseTransform 관련)
을 생성 후 KClass 단위 캐싱합니다.

```kotlin
clearAliasCaches() // 필요 시(핫 리로드, 리플렉션 테스트) 캐시 초기화
```
수천 DTO 반복 역직렬화 시 초기 1회 비용 이후 상수 시간 근사.

---
## 10. 트러블슈팅 빠른 점검표
| 증상 | 원인 | 조치 |
|------|------|------|
| 특정 필드 null | 키 철자/alias 미등록 | @JsonProperty/@JsonAlias 추가 |
| 케이스 변환 제외 안 됨 | @NoCaseTransform 누락 | 어노테이션 추가 |
| alias 충돌 WARN | 중복 canonical | 명확한 고유 alias 재설계 |
| 성능 저하(초기만) | 최초 캐시 빌드 | 정상 (두 번째 호출부터 완화) |
| 예상치 못한 키 대문자 | PASCAL_CASE 선택 | 다른 CaseConvention 지정 |

---
## 11. 종합 시나리오
```kotlin
@ResponseCase(CaseConvention.KEBAB_CASE)
data class AllInOne(
  @JsonProperty("user_id") @JsonAlias("USER-ID","UserId") val userId: Long,
  @JsonProperty("api_key") @NoCaseTransform val apiKey: String,
  val nested: Wrapper
): BasePayload

val src = """
{"status":"SUCCESS","version":"1.0","datetime":"2025-09-16T00:00:00Z","payload":{
  "USER-ID":77,
  "api_key":"K-9",
  "nested":{"items-map":{"x":{"child-name":"황용호"}}}
}}
"""
val parsed = StandardResponse.deserialize<AllInOne>(src)
val out = parsed.toJson() // kebab-case 출력, api_key 그대로 유지
```

---
## 12. Callback 빌더 확장 예제
> 성능 측정, 동적 status/version, 예외 매핑 등을 한 번에 처리할 수 있는 실전 패턴 모음입니다. (가이드 문서 13.x 심화 예와 중복 최소화, 복사-붙여넣기 용)

### 12.1 Kotlin 패턴
#### (1) 기본 측정 + 동적 status
```kotlin
data class HealthPayload(val ok: Boolean, val failed: List<String>): BasePayload

fun healthCheck(): StandardResponse<HealthPayload> =
  StandardResponse.build<HealthPayload>(payload = null) {
    val targets = listOf("db", "cache")
    val failed = targets.filterNot { t -> runCatching { ping(t) }.getOrElse { false } }
    if (failed.isEmpty()) StandardCallbackResult(HealthPayload(true, emptyList()), StandardStatus.SUCCESS, "2.0")
    else StandardCallbackResult(ErrorPayload("HEALTH_FAIL", failed.joinToString()), StandardStatus.FAILURE, "2.0")
  }
```
#### (2) 예외 매핑 통합
```kotlin
data class ReportPayload(val size: Int): BasePayload

fun generateReport(): StandardResponse<BasePayload> = StandardResponse.build(payload = null) {
  try {
    val raw = heavyLoad()
    StandardCallbackResult(ReportPayload(raw.size), StandardStatus.SUCCESS, "1.1")
  } catch (e: ValidationException) {
    StandardCallbackResult(ErrorPayload("VALID", e.message ?: "validation"), StandardStatus.FAILURE, "1.1")
  } catch (e: Exception) {
    StandardCallbackResult(ErrorPayload("UNEXPECTED", e.message ?: "err"), StandardStatus.FAILURE, "1.1")
  }
}
```
#### (3) 수동 duration (외부 준비 포함 X)
```kotlin
fun manualDuration(): StandardResponse<ErrorPayload> {
  val start = System.nanoTime()
  // 미리 준비(측정 제외): warmUp()
  val manual = (System.nanoTime() - start) / 1_000_000
  return StandardResponse.build(
    payload = null,
    duration = manual
  ) { StandardCallbackResult(ErrorPayload("OK", "ready"), StandardStatus.SUCCESS, "1.0") }
}
```
#### (4) Generic Helper
```kotlin
inline fun <reified P: BasePayload> timed(version: String = "1.0", block: () -> P) =
  StandardResponse.build<P>(payload = null) { StandardCallbackResult(block(), StandardStatus.SUCCESS, version) }

val userResp = timed("2.2") { object: BasePayload {} } // 예시
```
#### (5) Batch 처리 + 누적 오류
```kotlin
fun batch(items: List<String>): StandardResponse<ErrorPayload> = StandardResponse.build(payload = null) {
  val ep = ErrorPayload("OK","모두 성공")
  items.forEach { v ->
    runCatching { insert(v) }.onFailure {
      if (ep.errors.isEmpty()) { 
        ep.code = "PART_FAIL" 
        ep.message = "일부 실패" 
      }
      ep.addError("ROW_FAIL", it.message ?: v)
    }
  }
  val st = if (ep.errors.size > 1) StandardStatus.FAILURE else StandardStatus.SUCCESS
  StandardCallbackResult(ep, st, "1.0")
}
```

### 12.2 Java 패턴
#### (1) status/version override
```java
StandardResponse<ErrorPayload> overridden = StandardResponse.buildWithCallback(
    () -> new StandardCallbackResult(new ErrorPayload("OK", "fine", null), StandardStatus.SUCCESS, "2.5"),
    StandardStatus.FAILURE, // 초기값 (무시)
    "0.0",                 // 초기 version (무시)
    null
);
```
#### (2) 예외 매핑
```java
public StandardResponse<ErrorPayload> runTask() {
  return StandardResponse.buildWithCallback(() -> {
    try {
      Result r = svc.execute();
      return new StandardCallbackResult(r.toPayload(), StandardStatus.SUCCESS, "1.0");
    } catch (KnownException e) {
      return new StandardCallbackResult(new ErrorPayload("KNOWN", e.getMessage(), null), StandardStatus.FAILURE, "1.0");
    } catch (Exception e) {
      return new StandardCallbackResult(new ErrorPayload("UNEXPECTED", e.getMessage(), null), StandardStatus.FAILURE, "1.0");
    }
  });
}
```
#### (3) 수동 duration
```java
public StandardResponse<ErrorPayload> manualDur() {
  long t0 = System.currentTimeMillis();
  warm();
  long ms = System.currentTimeMillis() - t0; // 준비 시간만 측정
  return StandardResponse.buildWithCallback(
      () -> new StandardCallbackResult(new ErrorPayload("OK", "manual", null), StandardStatus.SUCCESS, "1.0"),
      StandardStatus.SUCCESS,
      "1.0",
      ms
  );
}
```
#### (4) Helper
```java
public static <P extends BasePayload> StandardResponse<P> time(String ver, Supplier<P> sup) {
  return StandardResponse.buildWithCallback(() -> new StandardCallbackResult(sup.get(), StandardStatus.SUCCESS, ver));
}
```
#### (5) Batch 누적 오류
```java
public StandardResponse<ErrorPayload> batch(List<String> rows) {
  return StandardResponse.buildWithCallback(() -> {
    ErrorPayload ep = new ErrorPayload("OK", "모두 성공", null);
    for (String r : rows) {
      try { save(r); } catch (Exception ex) {
        if (ep.getErrors().isEmpty()) {
          ep.setCode("PART_FAIL");
          ep.setMessage("일부 실패"); 
        }
        ep.addError("ROW_FAIL", ex.getMessage());
      }
    }
    StandardStatus st = ep.getErrors().size() > 1 ? StandardStatus.FAILURE : StandardStatus.SUCCESS;
    return new StandardCallbackResult(ep, st, "1.0");
  });
}
```

> 핵심: 측정하고 싶은 연산은 모두 callback 람다 내부에 배치. 외부에서 선 호출하면 duration 범위 제외.
