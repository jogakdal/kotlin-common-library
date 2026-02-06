# Standard API Response 활용 예제

이 문서는 standard-api-response 라이브러리에 대해 다양한 페이로드/목록/케이스/alias 시나리오를 **빠르게 복사해 사용할 수 있는 순수 예제 모음**입니다.

### 관련 문서 (Cross References)
| 문서 | 목적 / 차이점                                                          |
|------|-------------------------------------------------------------------|
| [standard-api-specification.md](standard-api-specification.md) | **표준 API 규약**: request 규칙, response 필드 정의, 상태/에러 규칙, 리스트 처리 방식 정의 |
| [standard-api-response-library-guide.md](standard-api-response-library-guide.md) | 라이브러리 **사용자 가이드**: 표준 응답 생성, 역직렬화, 케이스 변환, 사용 패턴 심화 설명 |
| [standard-api-response-reference.md](standard-api-response-reference.md) | **레퍼런스 매뉴얼**: 모듈 / 내부 타입 세부 설명                                    |

## 0. 공통 준비
### Gradle(Kotlin DSL)
```groovy
dependencies {
    implementation("com.hunet.common:common-core:<version>")
    implementation("com.hunet.common:standard-api-response:<version>")
}
```
(Java 프로젝트도 동일)

### application.yml (선택)
Duration 자동 주입과 케이스 변환 설정:
```yaml
stdapi:
  response:
    auto-duration-calculation:
      active: true
    case:
      default: SNAKE_CASE
```
(설정 없으면 DTO 원본 케이스 사용)

---
## 1. 리스트가 없는 기본 페이로드
### 1.1 Kotlin
```kotlin
data class StatusPayload(
    val code: String,
    val message: String
) : BasePayload

fun basicSuccess(): StandardResponse<StatusPayload> = StandardResponse.build(callback = {
    StandardCallbackResult(StatusPayload("OK", "성공"))
})

fun basicFailure(): StandardResponse<ErrorPayload> = StandardResponse.build(callback = {
    StandardCallbackResult(
        payload = ErrorPayload(code = "E400", message = "잘못된 요청"),
        status = StandardStatus.FAILURE,
        version = "1.0"
    )
})

fun successWithTrace(): StandardResponse<StatusPayload> =
    StandardResponse.build(StatusPayload("OK", "성공"), StandardStatus.SUCCESS, "1.0", 5L, java.util.UUID.randomUUID().toString())
```
응답 예 (traceid 포함):
```json
{
  "status": "SUCCESS",
  "version": "1.0",
  "datetime": "2025-12-03T09:40:00Z",
  "duration": 5,
  "traceid": "7f7c9e2b-5d3b-4e9e-8f11-0b2d2d7c9a01",
  "payload": { "code": "OK", "message": "성공" }
}
```
### 1.2 Java
```java
public StandardResponse<StatusPayload> basicSuccess() {
    return StandardResponse.buildWithCallback(
        () -> StandardCallbackResult.of(StatusPayload.Companion.of("OK", "성공", null))
    );
}

public StandardResponse<ErrorPayload> basicFailure() {
    return StandardResponse.buildWithCallback(
            () -> StandardCallbackResult.of(
                    new ErrorPayload("E400", "잘못된 요청", null), StandardStatus.FAILURE, "1.0"
            )
    );
}

public StandardResponse<StatusPayload> successWithTrace() {
    String tid = java.util.UUID.randomUUID().toString();
    return StandardResponse.build(
        StatusPayload.Companion.of("OK", "성공", null), StandardStatus.SUCCESS, "1.0", 5L, tid
    );
}
```
응답 예 (traceid 포함):
```json
{
  "status": "SUCCESS",
  "version": "1.0",
  "datetime": "2025-12-03T09:40:00Z",
  "duration": 5,
  "traceid": "b2c62f84-4f5a-4b0a-a2d5-1c6a4fc7b9ef",
  "payload": { "code": "OK", "message": "성공" }
}
```

---
## 2. 단일 리스트 포함 페이로드 (pageable / incremental)
### 2.1 Pageable (Kotlin)
```kotlin
data class ItemPayload(val id: Long, val name: String) : BasePayload

data class ItemsPageContainer(
    val pageable: PageableList<ItemPayload>
) : BasePayload

fun pageableContainer(): StandardResponse<ItemsPageContainer> = StandardResponse.build(callback = {
    val items = listOf(ItemPayload(1, "황용호"), ItemPayload(2, "홍용호"))
    val page = PageableList.build(
        items = items,
        totalItems = 10,
        pageSize = 2,
        currentPage = 1,
        orderInfo = OrderInfo(sorted = true, by = listOf(OrderBy("id", OrderDirection.ASC)))
    )
    StandardCallbackResult(ItemsPageContainer(page))
})
```
### 2.1 Pageable (Java)
```java
public class ItemPayload implements BasePayload {
    private final long id; private final String name;
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
    return StandardResponse.buildWithCallback(
        () -> {
            List<ItemPayload> list = List.of(new ItemPayload(1, "황용호"), new ItemPayload(2, "홍용호"));
            PageableList<ItemPayload> page = PageableList.build(
                list, 10, 2, 1,
                new OrderInfo(true, List.of(new OrderBy("id", OrderDirection.ASC)))
            );
            return StandardCallbackResult.of(new ItemsPageContainer(page), StandardStatus.SUCCESS, "1.0");
        }
    );
}
```
### 2.2 Incremental (Kotlin)
```kotlin
data class LogEntryPayload(val idx: Long, val text: String) : BasePayload

data class LogsIncrementalContainer(
    val incremental: IncrementalList<LogEntryPayload, Long>
) : BasePayload

fun incrementalContainer(start: Long, size: Long): StandardResponse<LogsIncrementalContainer> = StandardResponse.build(callback = {
    val logs = (start until (start + size)).map { LogEntryPayload(it, "log-$it") }
    val inc = IncrementalList.buildFromTotal(
        items = logs,
        startIndex = start,
        howMany = size,
        totalItems = 100,
        cursorField = "idx"
    )
    StandardCallbackResult(LogsIncrementalContainer(inc))
})
```
### 2.2 Incremental (Java)
```java
public class LogEntryPayload implements BasePayload {
    private final long idx; private final String text;
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
    return StandardResponse.buildWithCallback( () -> {
        List<LogEntryPayload> logs = java.util.stream.LongStream
            .range(start, start + size)
            .mapToObj(i -> new LogEntryPayload(i, "log-" + i))
            .toList();
        IncrementalList<LogEntryPayload, Long> inc = IncrementalList.buildFromTotalJava(
            logs, start, size, 100, "idx", null, (f, i) -> i
        );
        return StandardCallbackResult.of(new LogsIncrementalContainer(inc), StandardStatus.SUCCESS, "1.0");
    });
}
```
### 2.3 PageListPayload / IncrementalListPayload (래퍼형)
```kotlin
// PageListPayload: 내부에 pageable: PageableList<T> 포함
val userPagePayload = PageListPayload(
    items = listOf(UserPayload(10, "황용호")),
    totalItems = 25,
    pageSize = 10,
    currentPage = 1
)
val pageResp = StandardResponse.build(userPagePayload)

// IncrementalListPayload: 내부에 incremental: IncrementalList<T,I> 포함
val logIncPayload = IncrementalListPayload(
    items = listOf(LogEntryPayload(100, "boot"), LogEntryPayload(101, "ready")),
    startIndex = 100L,
    endIndex = 101L,
    totalItems = 500,
    cursorField = "idx",
    expandable = true
)
val incResp = StandardResponse.build(logIncPayload)
```

### 2.4 fromPage / fromPageJava 활용
```kotlin
// Spring Data Page<UserEntity> 가 있다고 가정
val page: org.springframework.data.domain.Page<UserEntity> = loadUserPage()
val mappedPageList = PageableList.fromPage(page) { e -> UserPayload(e.id, e.name) }
val mappedPagePayload = PageListPayload.fromPage(page) { e -> UserPayload(e.id, e.name) }
val respMapped = StandardResponse.build(mappedPagePayload)
```
```java
// Java: PageableList.fromPageJava
Page<UserEntity> page = loadUserPage();
PageableList<UserPayload> mapped = PageableList.fromPageJava(page, e -> new UserPayload(e.getId(), e.getName()));
StandardResponse<PageableList<UserPayload>> resp = StandardResponse.build(mapped);
```

### 2.5 buildFromTotalJava (커서 인덱스 변환 BiFunction)
```java
List<LogEntryPayload> logs = List.of(new LogEntryPayload(200, "start"), new LogEntryPayload(201, "next"));
IncrementalList<LogEntryPayload, Long> incList = IncrementalList.buildFromTotalJava(
    logs, 200L, 2L, 1000L, "idx", null, (field, index) -> index
);
StandardResponse<IncrementalList<LogEntryPayload, Long>> incResp2 = StandardResponse.build(incList);
```

### 2.6 itemsAsList 편의 접근
```kotlin
val listOnly: List<UserPayload> = pageResp.payload.pageable.items.list // 표준 접근
val listShortcut: List<UserPayload> = pageResp.payload.pageable.itemsAsList // 동일 결과 (단축 프로퍼티)
```

---
## 3. 리스트 자체가 페이로드
### 3.1 Kotlin (PageableList 직접)
```kotlin
fun directPageable(): StandardResponse<PageableList<ItemPayload>> = StandardResponse.build(callback = {
    val items = listOf(ItemPayload(1, "황용호"))
    val page = PageableList.build(items, totalItems = 1, pageSize = 1, currentPage = 1, orderInfo = null)
    StandardCallbackResult(page)
})
```
traceid 전달 (PageableList 직접)
```kotlin
val page = PageableList.build(listOf(ItemPayload(1, "황용호")), 1, 1, 1, null)
val tid = java.util.UUID.randomUUID().toString()
val respWithTrace = StandardResponse.build(page, StandardStatus.SUCCESS, "1.0", 2L, tid)
```

### 3.2 Java (IncrementalList 직접)
```java
public StandardResponse<IncrementalList<LogEntryPayload, Long>> directIncremental() {
    return StandardResponse.buildWithCallback( () -> {
        List<LogEntryPayload> list = List.of(new LogEntryPayload(0, "first"));
        IncrementalList<LogEntryPayload, Long> inc = IncrementalList.buildFromTotalJava(
            list, 0, 1, 10, "idx", null, (f, i) -> i
        );
        return StandardCallbackResult.of(inc, StandardStatus.SUCCESS, "1.0");
    });
}
```

---
## 4. 여러 리스트 동시 페이로드
### 4.1 Kotlin
```kotlin
data class MultiListsPayload(
    val users: PageableList<UserPayload>,
    val logs: IncrementalList<LogEntryPayload, Long>
) : BasePayload

data class UserPayload(val id: Long, val name: String) : BasePayload

fun multiLists(): StandardResponse<MultiListsPayload> = StandardResponse.build(callback = {
    val users = PageableList.build(
        items = listOf(UserPayload(1, "황용호"), UserPayload(2, "홍용호")),
        totalItems = 2, pageSize = 2, currentPage = 1, orderInfo = null
    )
    val logs = IncrementalList.buildFromTotal(
        items = listOf(LogEntryPayload(10, "boot"), LogEntryPayload(11, "ready")),
        startIndex = 10, howMany = 2, totalItems = 200, cursorField = "idx"
    )
    StandardCallbackResult(MultiListsPayload(users, logs))
})
```
```
// Sample Result (요약) 예시:
// {
//   "status":"SUCCESS","version":"1.0","datetime":"2025-09-16T12:04:00Z","duration":4,
//   "payload":{
//     "users":{ "page":{...}, "order":null, "items":{...} },
//     "logs":{ "cursor":{...}, "order":null, "items":{...} }
//   }
// }
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
        this.users = users; 
        this.logs = logs; 
    }
    public PageableList<UserPayload> getUsers() { return users; }
    public IncrementalList<LogEntryPayload, Long> getLogs() { return logs; }
}

public StandardResponse<MultiListsPayload> multiLists() {
    return StandardResponse.buildWithCallback( () -> {
        PageableList<UserPayload> users = PageableList.build(
            List.of(new UserPayload(1, "황용호"), new UserPayload(2, "홍용호")), 2, 2, 1, null
        );
        IncrementalList<LogEntryPayload, Long> logs = IncrementalList.buildFromTotalJava(
            List.of(new LogEntryPayload(10, "boot"), new LogEntryPayload(11, "ready")),
            10, 2, 200, "idx", null, (f, i) -> i
        );
        return StandardCallbackResult.of(new MultiListsPayload(users, logs), StandardStatus.SUCCESS, "1.0");
    });
}
```
```
// Sample Result (Kotlin과 동일 구조)
// {
//   "status":"SUCCESS","version":"1.0","payload":{"users":{...},"logs":{...}}
// }
```

---
## 5. 중첩(Composite) 페이로드
### 5.1 Kotlin
```kotlin
data class BasicInfoPayload(val system: String, val versionText: String): BasePayload

data class CompositePayload(
    val info: BasicInfoPayload,
    val status: StatusPayload,
    val users: PageableList<UserPayload>
) : BasePayload

fun composite(): StandardResponse<CompositePayload> = StandardResponse.build(callback = {
    val info = BasicInfoPayload("core-service", "v2")
    val status = StatusPayload("OK", "정상")
    val users = PageableList.build(
        items = listOf(UserPayload(100, "황용호")),
        totalItems = 1, pageSize = 1, currentPage = 1, orderInfo = null
    )
    StandardCallbackResult(CompositePayload(info, status, users))
})
```
```
// Sample Result (전체):
// {
//   "status":"SUCCESS","version":"1.0","datetime":"2025-09-16T12:05:00Z","duration":5,
//   "payload":{
//     "info":{"system":"core-service","version_text":"v2"},
//     "status":{"code":"OK","message":"정상"},
//     "users":{ "page":{...}, "order":null, "items":{"total":1,"current":1,"list":[{"id":100,"name":"황용호"}]}}
//   }
// }
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
    private final BasicInfoPayload info; private final StatusPayload status; private final PageableList<UserPayload> users;
    public CompositePayload(BasicInfoPayload info, StatusPayload status, PageableList<UserPayload> users) { 
        this.info = info; this.status = status; this.users = users; 
    }
    public BasicInfoPayload getInfo() { return info; }
    public StatusPayload getStatus() { return status; }
    public PageableList<UserPayload> getUsers() { return users; }
}

public StandardResponse<CompositePayload> composite() {
    return StandardResponse.buildWithCallback( () -> {
        BasicInfoPayload info = new BasicInfoPayload("core-service", "v2");
        StatusPayload status = StatusPayload.Companion.of("OK", "정상", null);
        PageableList<UserPayload> users = PageableList.build(
            List.of(new UserPayload(100, "황용호")), 1, 1, 1, null
        );
        return StandardCallbackResult.of(new CompositePayload(info, status, users), StandardStatus.SUCCESS, "1.0");
    });
}
```
```
// Sample Result 요약:
// {"status":"SUCCESS","payload":{"info":{...},"status":{...},"users":{...}}}
```

---
## 6. 역직렬화 & TypeReference
### 6.1 Kotlin 단일 페이로드
```kotlin
val json: String = obtainJson()
val parsed = StandardResponse.deserialize<StatusPayload>(json)
val statusPayload: StatusPayload? = parsed.getRealPayload<StatusPayload>()
```
### 6.2 Kotlin 실패 -> FAILURE 변환
```kotlin
fun safeParse(raw: String): StandardResponse<BasePayload> = try {
    StandardResponse.deserialize<StatusPayload>(raw)
} catch (e: Exception) {
    StandardResponse.build(
        ErrorPayload("E_DESERIALIZE_FAIL", e.message ?: "fail"),
        status = StandardStatus.FAILURE,
        version = "1.0"
    )
}
```
### 6.3 Java 단일 Class 역직렬화
```java
StandardResponse<StatusPayload> parsed = StandardResponse.deserialize(json, StatusPayload.class);
StatusPayload payload = parsed.getPayload();
```
### 6.4 Java 런타임 타입 결정
```java
@SuppressWarnings("unchecked")
Class<? extends BasePayload> dynamicType = decidePayloadType(); // 동적 타입은 화이트리스트(허용 목록) 검증 권장
StandardResponse<? extends BasePayload> parsedAny = (StandardResponse<? extends BasePayload>) StandardResponse.deserialize(
    json, 
    (Class<? extends BasePayload>) dynamicType
);
```
### 6.5 Kotlin 부분 Payload 추출
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
        // 단순 실패 변환: callback 대신 직접 build
        return StandardResponse.build(
            new ErrorPayload("FAIL", "bad json", null),
            StandardStatus.FAILURE,
            "1.0"
        );
    }
}
```
### 6.7 Kotlin 실제 JSON 예
```kotlin
val jsonBasic = """
{
    "status": "SUCCESS",
    "version": "1.0",
    "datetime": "2025-12-03T09:45:00Z",
    "duration": 7,
    "traceid": "9c7f1c1a-6c1b-43e8-9c25-5bb6c8e0f111",
    "payload": { "code": "OK", "message": "성공" }
}
""".trimIndent()
val basicResp = StandardResponse.deserialize<StatusPayload>(jsonBasic)
println(basicResp.traceid) // UUID 문자열
println(basicResp.getRealPayload<StatusPayload>()?.message) // "성공"
```
### 6.8 Java TypeReference (PageableList)
```java
String jsonPageable = "...";
StandardResponse<PageableList<ItemPayload>> typedResp =
  StandardResponse.deserialize(jsonPageable, new TypeReference<PageableList<ItemPayload>>(){});
```
> 안전성 참고: raw Class 사용 시 제네릭 손실 위험. 반드시 `new TypeReference<PageableList<ItemPayload>>() {}` 형태 사용 권장.

### 6.9 Java IncrementalList TypeReference
```java
StandardResponse<IncrementalList<LogEntryPayload, Long>> incResp =
  StandardResponse.deserialize(jsonIncremental, new TypeReference<IncrementalList<LogEntryPayload, Long>>() {});
```
> `new TypeReference<IncrementalList<LogEntryPayload, Long>>()`로 커서/아이템 제네릭 유지.
### 6.10 Java Composite TypeReference
```java
StandardResponse<CompositePayload> comp = StandardResponse.deserialize(jsonComposite, new TypeReference<CompositePayload>() {});
```
> Composite도 TypeReference 권장(중첩 내부 제네릭 포함 시 안전).

### 6.11 Dynamic Type Hint
```kotlin
// 외부 입력 typeHint -> 허용 목록 기반 동적 역직렬화 (whitelist 적용)
val jsonDyn = """
{
  "status":"SUCCESS",
  "version":"1.0",
  "datetime":"2025-09-16T12:15:00Z",
  "duration":3,
  "traceid": "9c7f1c1a-6c1b-43e8-9c25-5bb6c8e0f111",
  "typeHint":"STATUS",  // 표준 상위 응답 스키마(StandardResponse) 외 추가 필드
  "payload":{"code":"OK","message":"성공"}
}
""".trimIndent()
val allowed = setOf("STATUS", "COMPOSITE", "LIST")
val rootObj = kotlinx.serialization.json.Json.parseToJsonElement(jsonDyn).jsonObject
val rawHint = rootObj["typeHint"]?.jsonPrimitive?.content
val dynResp: StandardResponse<out BasePayload> = if (rawHint != null && rawHint in allowed) {
    when(rawHint) {
        "STATUS" -> StandardResponse.deserialize<StatusPayload>(jsonDyn)
        "COMPOSITE" -> StandardResponse.deserialize<CompositePayload>(jsonDyn)
        "LIST" -> StandardResponse.deserialize<PageableList<ItemPayload>>(jsonDyn)
        else -> StandardResponse.deserialize<BasePayloadImpl>(jsonDyn)
    }
} else {
    StandardResponse.build(
        ErrorPayload("INVALID_HINT", rawHint ?: "missing"),
        status = StandardStatus.FAILURE,
        version = "1.0"
    )
}
println("DYN_STATUS=${dynResp.status} class=${dynResp.payload::class.simpleName}")
```
> 보안 권고: typeHint -> enum/whitelist 검증 후 역직렬화. 미허용 값이면 FAILURE + ErrorPayload.

### 6.12 케이스별 역직렬화 예제
섹션 1~5에서 이미 정의한 DTO(StatusPayload, ErrorPayload, ItemPayload, ItemsPageContainer, LogEntryPayload, LogsIncrementalContainer, MultiListsPayload, CompositePayload 등)로 만들어진 JSON을 다시 역직렬화하는 예제입니다.

#### 6.12.1 단일 성공(StatusPayload)
JSON:
```json
{
  "status": "SUCCESS", 
  "version": "1.0", 
  "datetime": "2023-08-01T12:00:00Z", 
  "duration": 45,
  "traceid": "9c7f1c1a-6c1b-43e8-9c25-5bb6c8e0f111",
  "payload": { "code": "OK", "message": "성공" }
}
```
Kotlin:
```kotlin
val respStatus = StandardResponse.deserialize<StatusPayload>(jsonStatus)
println(respStatus.payload.code)
```
Java:
```java
StandardResponse<StatusPayload> respStatus = StandardResponse.deserialize(jsonStatus, StatusPayload.class);
System.out.println(respStatus.getPayload().getCode());
```

#### 6.12.2 단일 실패(ErrorPayload)
JSON:
```json
{
  "status" :"FAILURE", 
  "version": "1.0", 
  "datetime": "2025-10-21T13:01:45Z", 
  "duration": 20,
  "traceid": "9c7f1c1a-6c1b-43e8-9c25-5bb6c8e0f111",
  "payload": {
    "errors": [{"code": "E400", "message": "잘못된 요청"}]
  }
}
```
Kotlin:
```kotlin
val errorResp = StandardResponse.deserialize<ErrorPayload>(jsonError)
require(errorResp.status == StandardStatus.FAILURE)
```
Java:
```java
StandardResponse<ErrorPayload> errorResp = StandardResponse.deserialize(jsonError, ErrorPayload.class);
assert errorResp.getStatus() == StandardStatus.FAILURE;
```

#### 6.12.3 단일 페이지 리스트 컨테이너(ItemsPageContainer)
JSON:
```json
{
  "status": "SUCCESS", 
  "version": "1.0", 
  "datetime": "2025-10-21T13:01:45Z", 
  "duration": 20,
  "traceid": "9c7f1c1a-6c1b-43e8-9c25-5bb6c8e0f111",
  "payload": {
    "pageable": {
      "page": { "size": 2, "current": 1, "total": 5 },
      "order": { "sorted": true, "by": [ { "field": "id", "direction": "asc" } ] },
      "items": {"total": 10, "current": 2, "list": [ { "id": 1, "name": "황용호" }, { "id": 2, "name": "홍용호" } ] }
    }
  }
}
```
Kotlin:
```kotlin
val pageContainer = StandardResponse.deserialize<ItemsPageContainer>(jsonItems)
println(pageContainer.payload.pageable.items.list.size) // 2
```
Java:
```java
StandardResponse<ItemsPageContainer> pageContainer = StandardResponse.deserialize(jsonItems, ItemsPageContainer.class);
System.out.println(pageContainer.getPayload().getPageable().getItems().getList().size());
```

#### 6.12.4 단일 증분 리스트 컨테이너(LogsIncrementalContainer)
JSON:
```json
{
  "status": "SUCCESS", 
  "version": "1.0", 
  "datetime": "2025-10-21T13:01:45Z", 
  "duration": 20,
  "traceid": "9c7f1c1a-6c1b-43e8-9c25-5bb6c8e0f111",
  "payload": {
    "incremental": {
      "cursor": { "field": "idx", "start": 0, "end": 1, "expandable": true },
      "items": { "total": 100, "current": 2, "list": [ { "idx": 0, "text": "log-0" }, { "idx": 1, "text": "log-1" } ] }
    }
  }
}
```
Kotlin:
```kotlin
val incContainer = StandardResponse.deserialize<LogsIncrementalContainer>(jsonInc)
println(incContainer.payload.incremental.cursor.start) // 0
```
Java:
```java
StandardResponse<LogsIncrementalContainer> incContainer = StandardResponse.deserialize(jsonInc, LogsIncrementalContainer.class);
System.out.println(incContainer.getPayload().getIncremental().getCursor().getStart());
```

#### 6.12.5 직접 페이지 리스트(PageableList<ItemPayload>)
JSON:
```json
{
  "status": "SUCCESS", 
  "version": "1.0", 
  "datetime": "2025-10-21T13:01:45Z", 
  "duration": 20,
  "traceid": "9c7f1c1a-6c1b-43e8-9c25-5bb6c8e0f111",
  "payload": {
    "page": { "size": 1, "current": 1, "total": 1 },
    "order": null,
    "items": { "total": 1, "current": 1, "list": [ { "id": 1, "name": "황용호" } ] }
  }
}
```
Kotlin:
```kotlin
val directPage = StandardResponse.deserialize<PageableList<ItemPayload>>(jsonDirectPage)
println(directPage.payload.items.list.first().name)
```
Java (TypeReference):
```java
StandardResponse<PageableList<ItemPayload>> directPage = StandardResponse.deserialize(
    jsonDirectPage, new TypeReference<PageableList<ItemPayload>>() {}
);
System.out.println(directPage.getPayload().getItems().getList().get(0).getName());
```

#### 6.12.6 직접 증분 리스트(IncrementalList<LogEntryPayload, Long>)
JSON:
```json
{
  "status": "SUCCESS", 
  "version": "1.0", 
  "datetime": "2025-10-21T13:01:45Z", 
  "duration": 20,
  "traceid": "9c7f1c1a-6c1b-43e8-9c25-5bb6c8e0f111",
  "payload": {
    "cursor": { "field": "idx", "start": 10, "end": 10, "expandable": false },
    "order": null,
    "items": { "total": 10, "current": 1, "list": [ { "idx": 10, "text": "first" } ] }
  }
}
```
Kotlin:
```kotlin
val directInc = StandardResponse.deserialize<IncrementalList<LogEntryPayload, Long>>(jsonDirectInc)
println(directInc.payload.items.list.first().text)
```
Java (TypeReference):
```java
StandardResponse<IncrementalList<LogEntryPayload, Long>> directInc = StandardResponse.deserialize(
    jsonDirectInc, new TypeReference<IncrementalList<LogEntryPayload, Long>>() {}
);
System.out.println(directInc.getPayload().getItems().getList().get(0).getText());
```

#### 6.12.7 다중 리스트 컨테이너(MultiListsPayload)
JSON:
```json
{
  "status": "SUCCESS", 
  "version": "1.0", 
  "datetime": "2025-10-21T13:01:45Z", 
  "duration": 20,
  "traceid": "9c7f1c1a-6c1b-43e8-9c25-5bb6c8e0f111",
  "payload": {
    "users": { 
      "page": { 
        "size": 2, "current": 1, "total": 1
      }, 
      "order": null, 
      "items": { 
        "total": 2, 
        "current": 2, 
        "list": [ { "id": 1, "name": "황용호" }, { "id": 2, "name": "홍용호" } ]
      }
    },
    "logs": { 
      "cursor": { 
        "field": "idx", 
        "start": 10, 
        "end": 11, 
        "expandable": true
      }, 
      "order": null, 
      "items": { 
        "total": 200, 
        "current": 2, 
        "list": [ { "idx": 10, "text": "boot" }, { "idx": 11, "text": "ready" } ]
      }
    }
  }
}
```
Kotlin:
```kotlin
val multiListsResp = StandardResponse.deserialize<MultiListsPayload>(jsonMulti)
println(multiListsResp.payload.users.items.list.size to multiListsResp.payload.logs.items.list.size)
```
Java:
```java
StandardResponse<MultiListsPayload> multiListsResp = StandardResponse.deserialize(jsonMulti, MultiListsPayload.class);
System.out.println(multiListsResp.getPayload().getUsers().getItems().getList().size());
```

#### 6.12.8 중첩 Composite(CompositePayload)
JSON:
```json
{
  "status": "SUCCESS", 
  "version": "1.0", 
  "datetime": "2025-10-21T13:01:45Z", 
  "duration": 20,
  "traceid": "9c7f1c1a-6c1b-43e8-9c25-5bb6c8e0f111",
  "payload": {
    "info": { "system": "core-service", "version_text": "v2" },
    "status": { "code": "OK", "message": "정상" },
    "users": { 
      "page": { "size": 1, "current": 1, "total": 1 }, 
      "order": null, 
      "items": { "total": 1, "current": 1, "list": [ { "id": 100, "name": "황용호" } ] }
    }
  }
}
```
Kotlin:
```kotlin
val compositeResp = StandardResponse.deserialize<CompositePayload>(jsonComposite)
println(compositeResp.payload.info.versionText)
```
Java (TypeReference 권장):
```java
StandardResponse<CompositePayload> compositeResp = StandardResponse.deserialize(
    jsonComposite, new TypeReference<CompositePayload>() {}
);
System.out.println(compositeResp.getPayload().getInfo().getVersionText());
```

---
## 7. 케이스 컨벤션 (CaseConvention) 확장 예
### 7.1 기본 변환 매트릭스
| 원본(camel) | SNAKE_CASE | SCREAMING_SNAKE_CASE | KEBAB_CASE | CAMEL_CASE | PASCAL_CASE |
|-------------|------------|----------------------|-----------|-----------|-------------|
| userId      | user_id    | USER_ID              | user-id   | userId    | UserId      |
| firstName   | first_name | FIRST_NAME           | first-name| firstName | FirstName   |
| APIURLVersion2 | api_url_version2 | API_URL_VERSION2 | api-url-version2 | apiUrlVersion2 | ApiUrlVersion2 |
> 변환 순서: 토큰 분해 -> 캐시 조합 -> 케이스 변환. 한 번 계산된 결과 재사용.

### 7.2 @ResponseCase vs toJson(case=...) 우선순위
```kotlin
@ResponseCase(CaseConvention.SNAKE_CASE)
data class CaseUser(val userId: Long, val firstName: String) : BasePayload
val base = StandardResponse.build(callback = { StandardCallbackResult(CaseUser(1, "용호")) })
val snake = base.toJson() // annotation: snake_case
val kebab = base.toJson(case = CaseConvention.KEBAB_CASE) // override: kebab-case
```
### 7.3 @NoCaseTransform + @JsonProperty
```kotlin
data class ApiKeyPayload(
    @JsonProperty("api_key") @NoCaseTransform val apiKey: String,
    val requestCount: Long
) : BasePayload
StandardResponse.build(callback = { StandardCallbackResult(ApiKeyPayload("K-1", 5)) })
  .toJson(case = CaseConvention.KEBAB_CASE)
// 결과 키: api_key, request-count (NoCaseTransform: 출력 케이스 변환에서 제외됨)
```
### 7.4 SCREAMING_SNAKE_CASE / PASCAL_CASE 예
```kotlin
@ResponseCase(CaseConvention.SCREAMING_SNAKE_CASE)
data class UpperPayload(
    val userId: Long,
    val firstName: String,
    val lastLoginAt: Instant
) : BasePayload
val upper = StandardResponse.build(callback = {
    StandardCallbackResult(UpperPayload(10, "용호", Instant.parse("2025-09-16T00:00:00Z")))
}).toJson() // USER_ID, FIRST_NAME, LAST_LOGIN_AT
val pascal = StandardResponse.build(callback = {
    StandardCallbackResult(UpperPayload(10, "용호", Instant.parse("2025-09-16T00:00:00Z")))
}).toJson(case = CaseConvention.PASCAL_CASE) // UserId, FirstName, LastLoginAt
```

---
## 8. Alias & Canonical 매핑 심화
### 8.1 단일 Payload alias
```kotlin
data class AliasUser(
    @JsonProperty("user_id") @JsonAlias("USER-ID", "UserId") val userId: Long,
    @JsonProperty("first_name") @JsonAlias("FIRST-NAME", "firstName") val firstName: String,
    val emailAddress: String
) : BasePayload
val input = """
{
    "status": "SUCCESS",
    "version": "1.0",
    "datetime": "2025-09-16T00:00:00Z",
    "duration": 1,
    "traceid": "9c7f1c1a-6c1b-43e8-9c25-5bb6c8e0f111",
    "payload": { "USER-ID": 10, "FIRST-NAME": "용호", "email-address": "jogakdal@gmail.com" }
}
""".trimIndent()
val parsed = StandardResponse.deserialize<AliasUser>(input)
println(parsed.payload.userId) // 10
```
### 8.2 Map & 중첩 구조
```kotlin
data class Child(@JsonProperty("child_name") @JsonAlias("childName", "child-name") val childName: String): BasePayload
data class Wrapper(@JsonProperty("items_map") val items: Map<String, Child>): BasePayload
val json = """
{
    "status": "SUCCESS",
    "version": "1.0",
    "datetime": "2025-09-16T00:00:00Z",
    "duration": 1,
    "traceid": "9c7f1c1a-6c1b-43e8-9c25-5bb6c8e0f111",
    "payload": { "items-map": { "a": { "child-name": "황용호" } } }
}
"""
val wrapper = StandardResponse.deserialize<Wrapper>(json)
println(wrapper.payload.items["a"]!!.childName)
```
### 8.3 복합 List<Map<String, Payload>>
```kotlin
data class NestedChild(
    @JsonProperty("child_id") @JsonAlias("CHILD-ID", "childId") val childId: Long,
    @JsonProperty("label_text") @JsonAlias("label-text", "labelText") val label: String
): BasePayload
data class Complex(@JsonProperty("entries") val entries: List<Map<String, NestedChild>>) : BasePayload
val raw = """
{
    "status":"SUCCESS",
    "version":"1.0",
    "datetime":"2025-09-16T00:00:00Z",
    "duration":2,
    "traceid": "9c7f1c1a-6c1b-43e8-9c25-5bb6c8e0f111",
    "payload":{
        "entries": [ { "k1": { "CHILD-ID": 1, "label-text": "A" } } ]
    }
}
""".trimIndent()
val complex = StandardResponse.deserialize<Complex>(raw)
println(complex.payload.entries[0]["k1"]!!.childId) // 1
```
### 8.4 Canonical 규칙 요약
입력 키에서 비영숫자 제거 후 소문자화 -> canonical. underscore/dash 변형 모두 후보로 등록. 예: `USER-ID`, `user_id`, `UserId`, `userid` -> canonical `userid`.
### 8.5 충돌 처리(WARN)
| 상황 | 처리 |
|------|------|
| 서로 다른 property가 동일 canonical | 최초 등록 유지, WARN 로그 |
| 동일 property에 서로 다른 `@JsonProperty` | 최초 alias 유지, WARN 로그 |
> WARN 예: `[AliasRegistry] canonical conflict: 'userid' already mapped to field 'userId' (ignored alias 'UserId')` (예시 로그)

---
## 9. 캐시 & 성능
```kotlin
clearAliasCaches() // alias/케이스/리플렉션 캐시 초기화 (동적 클래스 재로드 시)
```
- Alias/Canonical/Case 변환 정보는 최초 분석 후 클래스 단위 캐싱.
- CaseKeyCache 내부적으로 케이스 변환 결과(토큰 분해 후)를 재사용.
- 대량 DTO 반복 역직렬화 시 1회 비용 이후 O(1) 근사.
- 자세한 동작/구성: 사용자 가이드 참조.

---
## 참고 링크
복잡한 개념 설명(케이스 변환 우선순위, Canonical 규칙 상세, Duration 주입 재귀 범위 등)은 사용자 가이드 문서에 상세히 기술되어 있습니다.
- 자세한 규칙 및 내부 동작: `standard-api-response-library-guide.md` 참고
