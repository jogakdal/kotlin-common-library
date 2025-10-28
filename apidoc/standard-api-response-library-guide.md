# **Standard API Response 라이브러리 사용 가이드**

본 문서는 **standard-api-response** 모듈을 적용(사용)하는 서비스/애플리케이션 **개발자** 관점에서의 활용 방법을 설명합니다.

### 관련 문서 (Cross References)
| 문서 | 목적 / 차이점                                                          |
|------|-------------------------------------------------------------------|
| [standard-api-specification.md](standard-api-specification.md) | **표준 API 규격**: request 규칙, response 필드 정의, 상태/에러 규칙, 리스트 처리 방식 정의 |
| [standard-api-response-reference.md](standard-api-response-reference.md) | **레퍼런스 매뉴얼**: 모듈 / 내부 타입 세부 설명                                    |
| [standard-api-response-examples.md](standard-api-response-examples.md) | 실전 예시 모음: payload 구성, 페이지/커서 처리, 역직렬화, 케이스 변환, Alias/Canonical 등  |

> 실무 적용 가이드이며 강제 규칙/스펙 우선순위는 standard-api-specification.md 를 따릅니다.

---
## 빠른 목차 (High Level Structure)
1. [라이브러리 개요](#1-라이브러리-개요)
2. [사용 준비 (환경 & 의존성 & Quick Start)](#2-사용-준비-환경--의존성--quick-start)
3. [표준 응답 생성](#3-표준-응답-생성)
4. [역직렬화(Json to Object)](#4-역직렬화)
5. [케이스 컨벤션 변환](#5-케이스-컨벤션-변환)
6. [테스트 및 검증 예시](#6-테스트-및-검증-예시)
7. [심화 가이드](#7-심화-가이드)
8. [실 서비스 적용 패턴](#8-실-서비스-적용-패턴)
9. [FAQ](#9-faq-자주-묻는-질문)

---
## 1. 라이브러리 개요
### 1.1 목적
다양한 REST API들에서 **일관된 상위 응답 스키마**를 표준화하여 클라이언트(웹/앱/3rd-party)가 단일 파싱 로직으로 응답을 소비할 수 있게 합니다.<br>
또한 외부 시스템이 동일 규격으로 응답한 JSON을 역직렬화(consume)할 수 있게 해 주고, 역직렬화 시 케이스/표기 방식이 달라도 안정적으로 DTO에 매핑할 수 있도록 돕습니다.

### 1.2 주요 피쳐(Feature)
| Feature    | 설명                                        | 대표 기능 |
|------------|-------------------------------------------|-----------|
| 표준 응답 생성   | 서버에서 모든 응답을 `StandardResponse<T>` 포맷으로 생성 | 빌더/콜백, duration 자동 측정, 페이지·커서 리스트 지원, 오류 표준화 |
| 표준 응답 역직렬화 | 외부/내부 표준 JSON 문자열을 DTO로 역직렬화              | Canonical Key, Alias/Case 변형 허용, 중첩·컬렉션 지원 |
| 보조 기능      | 출력 케이스 변환 & Java 상호운용, 성능 최적화             | CaseConvention 변환, `@NoCaseTransform`, 캐시, Java 헬퍼 |

### 1.3 모듈 구성
| 모듈 | 역할 |
|-------|------|
| `common-core` | 공통 유틸/기초 타입 (시간, 공통 예외 등) |
| `apidoc-annotations` | `@InjectDuration`, `@ResponseCase`, `@NoCaseTransform` 등 어노테이션 제공 |
| `standard-api-response` | 표준 응답/리스트/에러/역직렬화/케이스 변환 핵심 구현 |

### 1.4 지원 환경 & 런타임
| 항목 | 권장/테스트된 버전 |
|------|--------------------------------------|
| Java Toolchain | 21 |
| Kotlin | 2.1.20 |
| Spring Boot | 3.4.4 |
| Jackson | 2.18.2 |
| kotlinx.serialization (선택) | 1.8.0 |

> 버전 상이 시: 최소 Java 17 이상, Jackson 2.15+ 권장. Spring Boot 없이도(Plain Kotlin/JVM) 직렬화/역직렬화 코어는 사용 가능하나 자동 duration 주입/요청 기반 케이스 오버라이드는 Spring 기반 컴포넌트 빈에 의존합니다.

### 1.5 기능 요약
- 단일 래퍼 구조: `status/version/datetime/duration/payload`
- 성공/실패 상태 & 표준 오류 페이로드(`ErrorPayload`)
- Page / Incremental 리스트 공통 구조 (pageable vs incremental)
- 콜백 빌더로 실행 시간 측정 후 duration 자동 주입
- `@InjectDuration` 필드 주입
- Canonical + Alias 매핑 기반 강건한 역직렬화 (케이스/구분자 차이 허용)
- 응답 키 케이스 변환 (전역/DTO/요청 단위)
- Java 전용 정적 헬퍼 (`fromPageJava`, `buildFromTotalJava`, `buildWithCallback`)
- Java 제네릭 유지 역직렬화를 위한 `TypeReference` 오버로드 (`StandardResponse.deserialize(json, typeRef)`) 제공
- 리플렉션 메타 캐싱으로 성능 최적화

---
## 2. 사용 준비 (환경 & 의존성 & Quick Start)
### 2.1 Gradle 의존성
```groovy
dependencies {
    implementation("com.hunet.common:common-core:<version>")
    implementation("com.hunet.common:apidoc-annotations:<version>")
    implementation("com.hunet.common:standard-api-response:<version>")
}
```
### 2.2 Maven 의존성

```xml

<dependencies>
    <dependency>
        <groupId>com.hunet.common</groupId>
        <artifactId>common-core</artifactId>
        <version>버전</version>
    </dependency>
    <dependency>
        <groupId>com.hunet.common</groupId>
        <artifactId>apidoc-annotations</artifactId>
        <version>버전</version>
    </dependency>
    <dependency>
        <groupId>com.hunet.common</groupId>
        <artifactId>standard-api-response</artifactId>
        <version>버전</version>
    </dependency>
</dependencies>
```
### 2.3 최신 버전 정보
<!-- version-info:start -->
```
Last updated: 2025-10-28 16:12:23 KST
common-core: 1.1.0-SNAPSHOT
apidoc-core: 1.1.0-SNAPSHOT
apidoc-annotations: 1.1.0-SNAPSHOT
standard-api-response: 1.2.0-SNAPSHOT
```
<!-- version-info:end -->

### 2.4 최소 설정 (application.yml 예)
```yaml
stdapi:
  response:
    auto-duration-calculation:
      active: true          # duration 자동 주입
    case:
      enabled: true         # 응답 키 케이스 변환 활성화
      default: IDENTITY     # 기본 케이스 (필요 시 SNAKE_CASE 등)
```
> 케이스 쿼리 / 헤더 오버라이드가 필요하면 `query-override: true`, `header-override: true` 및 파라미터/헤더 이름을 추가 설정.

### 2.5 Quick Start (Kotlin Controller)
```kotlin
@RestController
class PingController {
  @GetMapping("/api/ping")
  fun ping() = StandardResponse.build(
    ErrorPayload("OK", "pong")
  )
}
```
결과 JSON 예:
```json
{"status":"SUCCESS","version":"1.0","datetime":"...","duration":0,"payload":{"errors":[{"code":"OK","message":"pong"}]}}
```

### 2.6 Quick Start (역직렬화)
```kotlin
val json = /* 위 응답 문자열 */
val resp = StandardResponse.deserialize<ErrorPayload>(json)
println(resp.payload.errors.first().code) // OK
```

---
## 3. 표준 응답 생성
### 3.1 핵심 타입 및 개념
| 타입 | 설명 |
|------|------|
| `StandardResponse<T>` | 최상위 래퍼. 제네릭 T = `BasePayload` 구현체 |
| `StandardStatus` | `SUCCESS` / `FAILURE` (그 외 `NONE` 내부용) |
| `BasePayload` / `BasePayloadImpl` | Payload 마커 / 빈 Payload 기본 구현 |
| `ErrorPayload` / `ErrorDetail` | 오류 응답 / 상세 오류 (`code`, `message` 등) |
| `PageableList<T>` / `PageListPayload<T>` | 페이지 기반 리스트 구조 (`payload.pageable.*`) |
| `IncrementalList<T, P>` / `IncrementalListPayload<T, P>` | 커서 기반(더보기) 리스트 (`payload.incremental.*`) |
| `Items<T>` | 리스트 아이템/총계/현개수 메타 |
| `PageInfo`, `OrderInfo`, `OrderBy` | 페이지·정렬 메타데이터 |
| `CursorInfo<P>` | 커서 범위(start/end), expandable 여부 |
| `@InjectDuration` | duration 자동 주입 필드 표시 |
| `StandardCallbackResult` | 콜백 빌더 반환 컨테이너 (payload/status/version) |

### 3.2 표준 응답 JSON 구조
```json5
{
  "status": "SUCCESS",
  "version": "1.0",
  "datetime": "2025-09-16T12:34:56.789Z",
  "duration": 12,
  "payload": { /* 실제 데이터 */ }
}
```
필드 의미: status / version / datetime / duration / payload

### 3.3 기본 빌드 (StandardResponse 생성)
Kotlin:
```kotlin
val ok = StandardResponse.build(ErrorPayload("OK", "정상"))
val fail = StandardResponse.build(
  ErrorPayload("E400", "잘못된 요청"), status = StandardStatus.FAILURE, version = "1.1"
)
val timed = StandardResponse.build<ErrorPayload>(payload = null) {
  StandardCallbackResult(ErrorPayload("OK", "완료"), StandardStatus.SUCCESS, "2.0")
}
```
Java:
```java
ErrorPayload okPayload = new ErrorPayload("OK", "정상", null);
StandardResponse<ErrorPayload> okResp = StandardResponse.build(okPayload);
StandardResponse<ErrorPayload> failResp = StandardResponse.build(
  new ErrorPayload("E500", "서버 오류", null), StandardStatus.FAILURE, "1.2"
);
StandardResponse<ErrorPayload> timedResp = StandardResponse.buildWithCallback(
  () -> new StandardCallbackResult(new ErrorPayload("OK", "완료", null), StandardStatus.SUCCESS, "2.0")
);
```

### 3.4 자동 Duration 주입 (`@InjectDuration`)
application.yml:
```yaml
stdapi:
  response:
    auto-duration-calculation:
      active: true
```
DTO:
```kotlin
data class ApiResult(@InjectDuration val duration: Long? = null, val data: Any): BasePayload
```

### 3.5 오류 응답 처리 패턴
- 실패 시 `status=FAILURE` + `ErrorPayload(code, message)`
- 다중 오류: `ErrorPayload.addError(code, message)`
- 부가정보: `appendix["traceId"] = ...` 등

### 3.6 리스트 응답 (페이지 & 커서)
| 요구 | 사용 구조 |
|------|-----------|
| 페이지 번호 기반 | `PageListPayload<T>` / `PageableList<T>` |
| 커서/더보기 | `IncrementalListPayload<T,P>` / `IncrementalList<T,P>` |
| 총 개수 기반 expandable 계산 | `IncrementalList.buildFromTotal(...)` |
| 직접 커서 지정 | `IncrementalList.build(...)` |

Kotlin Page:
```kotlin
fun <E> toPagePayload(page: Page<E>, map: (E)->MyDto) = PageListPayload.fromPage(page, map)
```
Java Page:
```java
Page<MyEntity> page = ...;
PageableList<MyDto> payload = PageableList.fromPageJava(page, e -> new MyDto(/*...*/));
```
Kotlin Incremental:
```kotlin
val inc = IncrementalList.buildFromTotal<String, Long>(listOf("A","B"), 0, 2, 10, "id")
```
Java Incremental:
```java
IncrementalList<String, Long> inc = IncrementalList.buildFromTotalJava(
  List.of("A","B"), 0L, 2L, 10L, "id", null, (f, item) -> item
);
```

---
## 4. 역직렬화
### 4.1 개요
표준 응답 역직렬화는 두 층위로 이루어집니다:
1. 최상위 `StandardResponse<T>` 구조 파싱 (`status`, `version`, `datetime`, `duration`, `payload`)
2. `payload` 내부 DTO (단일, 페이지, 커서, 중첩, 컬렉션/맵) & 다양한 입력 키(alias/케이스 변형) 매핑

이 라이브러리는 **Canonical Key Normalization** + **Alias 처리**를 통해 `snake_case`, `camelCase`, `kebab-case`, 대/소문자 혼용, 구분자(`_`, `-`) 차이를 모두 허용합니다.

### 4.2 기본 역직렬화 API
Kotlin (reified):
```kotlin
val resp = StandardResponse.deserialize<ErrorPayload>(jsonString)
```
Java:
```java
StandardResponse<ErrorPayload> resp = StandardResponse.deserialize(jsonString, ErrorPayload.class);
```
실패/예외 발생 시 라이브러리 내부에서 Jackson 예외가 throw 되며, 필요하면 상위에서 캐치 후 ErrorPayload 변환.

### 4.3 제네릭 & 리스트/페이지/커서 역직렬화
Kotlin:
```kotlin
val pageResp = StandardResponse.deserialize<PageListPayload<MyItemPayload>>(json)
val incResp  = StandardResponse.deserialize<IncrementalList<LogEntryPayload, Long>>(json)
```
Java (단순 Class 시그니처):
```java
StandardResponse<IncrementalList> inc = StandardResponse.deserialize(json, IncrementalList.class); // 내부 아이템 제네릭 정보 소실
```
Java (제네릭 유지 – 내장 TypeReference 오버로드):
```java
StandardResponse<PageListPayload<MyItemPayload>> resp =
  StandardResponse.deserialize(json, new TypeReference<PageListPayload<MyItemPayload>>() {});
```
> TypeReference 오버로드는 `payload` 제네릭 타입(T)을 유지합니다. 전체 `StandardResponse<...>`를 감싸는 TypeReference 가 아니라 **payload 타입만** 지정하면 됩니다.

### 4.4 오류 응답 역직렬화
```kotlin
val errorResp = StandardResponse.deserialize<ErrorPayload>(json)
if (errorResp.status == StandardStatus.FAILURE) {
  val first = errorResp.payload.errors.firstOrNull()
}
```

### 4.5 Canonical / Alias 매핑 (다양한 입력 키 수용)
#### 4.5.1 Alias 선언
```kotlin
data class AliasSample(
  @JsonProperty("user_id") val userId: Long,
  @JsonProperty("given_name") @JsonAlias("givenName") val firstName: String,
  @JsonProperty("surname") @JsonAlias("familyName", "lastName") val lastName: String,
  val emailAddress: String
): BasePayload
```
역직렬화 허용 예 (모두 동일 프로퍼티 매핑):
```
user_id / user-id / userId / USER_ID / USERID → userId
surname / familyName / lastName → lastName
```

#### 4.5.2 Canonical 키 규칙
입력 키에서 영문/숫자만 추출 → 소문자화 → 연결한 문자열을 Canonical. 구분자(`_`, `-`)·대소문자 차이 제거됨.
예: `FIRST-NAME`, `First_Name`, `firstName` → canonical: `firstname` (충돌 시 경고 로깅 후 매핑 우선순위 규칙 적용)

#### 4.5.3 중첩/컬렉션/맵
- 중첩 DTO (`BasePayload` 필드) 재귀 처리
- `List<BasePayload>` 요소별 처리
- `Map<K, BasePayload>` 값 객체 처리
- 복합 구조 (List<Map<String, ChildPayload>> 등) 전체 순회

맵 예시:
```kotlin
data class Child(@JsonProperty("child_name") @JsonAlias("childName","child-name") val childName: String): BasePayload
data class Wrapper(@JsonProperty("items_map") val items: Map<String, Child>): BasePayload
```
JSON: `items-map` / `child-name` → 각각 `items` / `childName` 매핑

#### 4.5.4 성능 & 캐싱
- 클래스 단위 리플렉션 메타는 1회 분석 후 글로벌 캐시
- 대량 DTO 초기 러닝 시 첫 호출만 비용, 이후 O(1) 조회
- 필요 시 `clearAliasCaches()` (특수 동적 로딩 시)

#### 4.5.5 디버깅 체크리스트
| 증상 | 원인 | 해결 |
|------|------|------|
| 필드 null | alias 누락/철자 불일치 | `@JsonProperty`/`@JsonAlias` 재확인 (canonical 충돌 여부 로그) |
| 엉뚱한 필드 매핑 | canonical 충돌 | 서로 다른 필드 alias 정리 → 유일화 |
| 출력 변환 제외 불가 | `@NoCaseTransform` 누락 | DTO 대상 필드에 어노테이션 추가 |

#### 4.5.6 `@NoCaseTransform` 관계
- 출력(직렬화) 케이스 변환만 비활성
- 입력(역직렬화) alias/canonical 매핑에는 영향 없음

### 4.6 역직렬화 모범 사례
| 상황 | 권장 패턴 |
|------|----------|
| Kotlin 도메인 DTO | `StandardResponse.deserialize<DomainPayload>(body)` |
| Java 단순 타입 | `StandardResponse.deserialize(body, PayloadClass.class)` |
| Java 복합(중첩 제네릭) | `StandardResponse.deserialize(body, new TypeReference<ComplexPayload<Inner>>() {})` |
| 다국적 클라이언트(다양한 케이스 전송) | DTO에 대표 `@JsonProperty` 1개 + 가능한 변형을 `@JsonAlias`로 추가 |

---
## 5. 케이스 컨벤션 변환
### 5.1 개요
응답 JSON 직렬화(DTO → JSON) 시 필드 키를 `snake_case`, `kebab-case`, `SCREAMING_SNAKE_CASE` 등으로 변환.
(역직렬화는 Canonical/Alias 매핑 로직이 별도 처리됨 – 케이스 변환 설정 비영향)

### 5.2 동작 순서
1. Jackson 기본 직렬화 (`@JsonProperty` alias 적용)
2. DTO 필드 `@NoCaseTransform` 제외 처리
3. 최종 CaseConvention 변환

### 5.3 사용 예
```kotlin
@ResponseCase(CaseConvention.SNAKE_CASE)
data class UserPayload(val userId: Long, val firstName: String, val emailAddress: String): BasePayload
val snakeJson = StandardResponse.build(UserPayload(1,"용호","mail@test.com")).toJson()
val kebabJson = StandardResponse.build(UserPayload(1,"용호","mail@test.com")).toJson(case = CaseConvention.KEBAB_CASE)
```
우선순위: `toJson(case=...)` > DTO `@ResponseCase` > 전역 default (IDENTITY 기본)

### 5.4 지원 케이스 컨벤션 (요약)
IDENTITY(원 타입 그대로) / SNAKE_CASE / SCREAMING_SNAKE_CASE / KEBAB_CASE / CAMEL_CASE / PASCAL_CASE

### 5.5 특정 필드 제외
```kotlin
data class Sample(@JsonProperty("api_key") @NoCaseTransform val apiKey: String, val normalField: String): BasePayload
```
`apiKey`는 전역 변환 영향 제외 – alias 그대로 출력.

### 5.6 전역 설정 (application.yml)
```yaml
stdapi:
  response:
    case:
      enabled: true
      default: IDENTITY
      query-override: true
      header-override: true
      query-param: case
      header-name: X-Response-Case
```
우선순위: 쿼리 파라미터 > 헤더 > DTO `@ResponseCase` > default. `enabled=false` 시 변환 비활성.

---
## 6. 테스트 및 검증 예시
(역직렬화 관련 상세 설명은 [4. 역직렬화](#4-역직렬화) 참조)

### 6.1 Kotlin MockMvc
```kotlin
val mvcResult = mockMvc.perform(get("/api/ping"))
  .andExpect(status().isOk)
  .andExpect(jsonPath("$.status").value("SUCCESS"))
  .andReturn()
val resp = StandardResponse.deserialize<ErrorPayload>(mvcResult.response.contentAsString)
assertEquals(StandardStatus.SUCCESS, resp.status)
```

### 6.2 Java MockMvc
```java
MvcResult result = mockMvc.perform(get("/api/ping"))
  .andExpect(status().isOk())
  .andExpect(jsonPath("$.status").value("SUCCESS"))
  .andReturn();
StandardResponse<ErrorPayload> resp = StandardResponse.deserialize(result.getResponse().getContentAsString(), ErrorPayload.class);
```

### 6.3 페이지/커서 역직렬화 검증
```kotlin
val pageResp = StandardResponse.deserialize<PageListPayload<MyItemPayload>>(json)
assertTrue(pageResp.payload.pageable.items.list.isNotEmpty())

val incResp = StandardResponse.deserialize<IncrementalList<LogEntryPayload, Long>>(incJson)
assertTrue(incResp.payload.cursor?.expandable == true)
```

---
## 7. 심화 가이드
### 7.1 Java 상호 운용성 주의사항
| 항목 | 설명                                                                      |
|------|-------------------------------------------------------------------------|
| 기본 파라미터 | Kotlin 기본값 → Java 사용 시 오버로드 메서드로 모두 명시                                  |
| reified 한계 | `fromPage()` 등 reified 함수 → `fromPageJava()`, `buildFromTotalJava()` 사용 |
| 제네릭 안전성 | `StandardResponse<Payload>` 변수 형태로 명확히 선언하여 캐스팅 방지                      |
| 복합 제네릭 역직렬화 | 내장 `deserialize(json, TypeReference<T>)` 사용 (payload 타입 T 지정)           |

### 7.2 Callback 빌더 패턴
`StandardResponse.build(callback = { ... })`(Kotlin) / `StandardResponse.buildWithCallback(() -> { ... })`(Java)는 응답 생성 직전 도메인 로직을 람다/함수 블록으로 감싸 **payload + status + version**을 한 번에 선언하도록 하는 패턴입니다.<br>
Callback 내부에서 `StandardCallbackResult`를 반환해야 하며, `status` / `version`을 생략하면 기본값(`SUCCESS` / `1.0`)이 적용됩니다.

#### 도입 배경 & 장점
| 항목 | 설명                                        | 효과 |
|------|-------------------------------------------|------|
| Lazy 계산 | 응답 직전까지 도메인 작업 지연                         | 불필요 연산 최소화, duration 측정 정확도 ↑ |
| 단일 선언 지점 | payload + status + version 한 곳에서 확정       | 가독성/코드 리뷰 용이 |
| 실패/성공 분기 단순화 | if/else 내부에서 `StandardCallbackResult`만 교체 | 예외 남발/중첩 축소 |
| 일관된 duration 측정 | 빌더가 callback 전/후 시각 측정                    | 코드 중복 제거, 실측치 반영 |
| 테스트 용이 | callback 단위 mocking/stub 가능               | 단위 테스트에서 외부 리소스 분리 |
| Java/Kotlin 일관 인터페이스 | `buildWithCallback` 제공                    | 다국어 코드베이스 통일 |

#### 기본 사용 문법
Kotlin:
```kotlin
fun getUser(id: Long): StandardResponse<UserPayload> = StandardResponse.build(callback = {
  val entity = repo.findById(id)
  if (entity == null) {
    StandardCallbackResult(
      payload = ErrorPayload("E404", "user not found"),
      status = StandardStatus.FAILURE
    )
  } else {
    StandardCallbackResult(UserPayload(entity.id, entity.name)) // SUCCESS 기본
  }
})
```
Java:
```java
public StandardResponse<UserPayload> getUser(long id) {
  return StandardResponse.buildWithCallback(() -> {
    UserEntity e = repo.findById(id);
    if (e == null) {
      return new StandardCallbackResult(
          new ErrorPayload("E404", "user not found", null),
          StandardStatus.FAILURE,
          "1.0"
      );
    }
    return new StandardCallbackResult(new UserPayload(e.getId(), e.getName()), StandardStatus.SUCCESS, "1.0");
  });
}
```

#### Controller ↔ Service 연계 패턴
Service가 `StandardCallbackResult`를 직접 반환하도록 하여 Controller가 그대로 감싸는 패턴.
```kotlin
@Service
class UserService {
  fun loadUser(id: Long): StandardCallbackResult = run {
    val e = repo.findById(id)
    if (e == null) StandardCallbackResult(ErrorPayload("E404", "user not found"), StandardStatus.FAILURE)
    else StandardCallbackResult(UserPayload(e.id, e.name))
  }
}

@RestController
class UserController(private val userService: UserService) {
  @GetMapping("/api/users/{id}")
  fun get(@PathVariable id: Long) = StandardResponse.build { userService.loadUser(id) }
}
```
> 장점: Controller는 포맷팅만 담당, 비즈니스 분기(Service)와 응답 표준화 분리.

#### 실패 처리 전략 비교
| 전략 | 설명 | 권장도 |
|------|------|------|
| Callback 내부에서 ErrorPayload 반환 | 단순 검증/조건 실패 | ✅ 기본 |
| 예외 throw + ControllerAdvice 변환 | 예측 어려운 시스템/인프라 오류 | ✅ (표준화 필요) |
| 혼합(조건 실패 = Callback, 치명적 = 예외) | 구분 명확 | ✅ |
| 모든 실패를 예외화 | 과도한 스택 트레이스, 비용 증가 | ⚠️ 지양 |

#### 안티 패턴 (지양할 점)
| 패턴 | 문제점 | 대안 |
|------|--------|------|
| Callback 내부에서 블로킹 I/O 다중 중복 호출 | duration 증가/가독성 저하 | I/O 묶음 or Service 분리 |
| Callback 내 try-catch 로 모든 예외 흡수 후 SUCCESS로 래핑 | 오류 숨김, 관측 불가 | FAILURE 명시 or throw |
| payload를 먼저 만들고 builder 외부에서 반환 | duration 측정 구간 손실 | 도메인 로직을 callback 내부로 이동 |
| 중첩 callback(callback 안에서 또 build 호출) | 가독성 저하, duration 이중 계산 | 계층 평탄화 (Service -> Callback 1단) |

#### 테스트 시 Mocking (Java)
```java
when(service.find(id)).thenReturn(new StandardCallbackResult(new UserPayload(1,"A"), StandardStatus.SUCCESS, "1.0"));
StandardResponse<UserPayload> resp = StandardResponse.buildWithCallback(() -> service.find(id));
```
### 7.3 페이지 vs 커서 선택 기준
| 조건 | 추천 구조 |
|------|----------|
| 총 페이지 수/번호 탐색, 랜덤 접근 | PageListPayload |
| 무한 스크롤, 최근 데이터 스트림 | IncrementalList |
| 전체 개수 알 수 있음 + 성능 영향 낮음 | `buildFromTotal` (expandable 계산 용이) |
| 커스텀 커서 규칙 필요 | `build(...)` 직접 지정 |

### 7.4 Duration 측정 세분화
부분 구간 측정 필요 시: `@InjectDuration` 외 별도 필드 두고 수동 측정 → callback 안에서 계산 후 payload에 세팅.

### 7.5 케이스 변환 오버라이드 전략
- 외부 파트너 별도 요구 또는 동적 변환: 리퀘스트에 쿼리 파라미터 `?case=snake_case` 지정 또는 헤더 `X-Response-Case: snake_case` 지정
- 위험: 캐시 정책(프론트/Edge)이 키 변환에 따라 다른 JSON shape 저장 → 필요 시 canonical 캐시 키 분리

### 7.6 Alias 충돌 예방 및 해결
Alias는 `@JsonProperty`(대표 키) + `@JsonAlias`(허용 추가 키) 조합으로 선언하며, 라이브러리는 입력 키를 Canonical(영숫자만 소문자)로 정규화하여 매핑합니다.<br>세부 내부 로직/알고리즘은 Reference 문서를 참고하세요.

#### 핵심 규칙
- Canonical 정규화: `user_id / user-id / userId / USERID` → 모두 동일 그룹.
- 충돌 기본 정책: 최초 등록(First-win). 충돌 시 WARN 로그.
- 옵션 모드
    - `stdapi.response.alias-conflict-mode`: `WARN`(기본) | `ERROR`
        - `WARN`: 로그만 출력하고 first-win 유지
        - `ERROR`: 충돌 즉시 예외(애플리케이션 부팅/요청 실패) → CI 강제 차단 용도
    - `stdapi.response.alias-conflict-resolution`: `FIRST_WIN`(기본) | `BEST_MATCH` (WARN 모드에서만 의미)
        - `FIRST_WIN`: 충돌 시 최초 등록 필드 우선 선택
        - `BEST_MATCH`: 실제 입력 JSON 키와 alias 집합이 가장 직접적으로 일치하는 property 우선 선택 (점진 마이그레이션에 유용)

#### 권장 패턴 (Check List)
1. 대표 키 1개(`@JsonProperty`) + 꼭 필요한 소수 alias 만 사용 (케이스/하이픈/언더스코어 변형은 대부분 자동 처리).
2. snake / camel 두 스타일을 같은 의미로 중복 선언하지 말 것 (둘 중 하나만 대표 지정 후 나머지는 alias 최소화).
3. PR 시 Canonical 중복(WARN 로그) 여부 확인: 로컬 실행 또는 테스트 로그 grep `Alias canonical conflict`.
4. 새 필드 추가 시: 기존과 충돌 예상되는지 빠르게 단위 테스트(직렬화→역직렬화 왕복) 작성.
5. 필요 시 CI 에 `alias-conflict-mode=ERROR` 잠시 적용해 구조 정리 진행.

#### 문제 발생 시 빠른 대응 순서
1. 로그에서 충돌 키 확인 (`userid` 등).
2. 실제 공존 필요 여부 판별 → 의미 다르면 이름 재설계(접두/접미 추가), 같다면 중복 필드 제거.
3. alias 과다(대소문/구분자 반복) 제거.
4. 테스트 (왕복 + 다양한 케이스/하이픈/언더스코어 변형 입력) 통과 확인.
5. 필요 시 `BEST_MATCH`로 전환하여 레거시→신규 키 이행 기간 단축.

#### 언제 `BEST_MATCH`를 쓰나?
- 기존 `user_id` 필드에서 점진적으로 `userId` 로 전환할 때, 클라이언트별 전송 키가 섞여있어도 자연스럽게 “가장 닮은” 필드로 매핑되길 원할 때.

#### 요약 표

| 상황 | 설정 권장 | 비고 |
|------|-----------|------|
| 일반 운영(안정) | `WARN` + `FIRST_WIN` | 최소 오버헤드, 기본 안전 |
| 강제 정합성/초기 정리 | `ERROR` | 충돌 즉시 실패로 누락 방지 |
| 마이그레이션(키 변경) | `WARN` + `BEST_MATCH` | 구·신 혼재 입력 수용 |

### 7.7 에러 Payload 확장
추가 코드 체계 필요 시 `appendix["subCode"]` / `appendix["i18nKey"]` 등 확장 → 클라이언트 로직 단순화

### 7.8 응답 DTO 모듈러 설계
- 작은 재사용 단위(Atomic) DTO를 정의하고 조합형(Aggregate) Payload로 구성
- 리스트는 pageable / incremental 표준 구조 사용
- 과도한 중첩·중복 필드·과잉 필드 지양, 필요할 때 버전 분리(V2 등)
- 빈 리스트는 항상 []

> 자세한 용어 정의, 원칙 표, 예시 JSON, 마이그레이션 전략은 `standard-api-specification.md`의 "응답 Payload 모듈러 설계" 절(6장) 참조.

---
## 8. 실 서비스 적용 패턴

### 8.1 Controller ↔ Service ↔ Response 흐름
| 레이어 | 역할 | 패턴 |
|--------|------|------|
| Controller | 엔드포인트 정의, 컨텍스트 추출 | `StandardResponse.build { service.method(...) }` |
| Service | 비즈니스 + DTO 매핑 | 성공: `StandardCallbackResult(payload=...)` / 실패: 예외 throw |
| Advice | 전역 예외 처리 | 예외 → `ErrorPayload` + `FAILURE` 응답 |
| 비동기 트리거 | 오래 걸리는 작업 위임 | 즉시 CUD 결과 응답 + 비동기 실행 |

### 8.2 StandardCallbackResult 활용
```kotlin
@GetMapping("/manager/companyCode/{companyCode}/{pageNo}")
fun list(...): StandardResponse<PagedManagerSessionPayload> =
  StandardResponse.build { managerService.getManagerSessionList(companyCode, pageNo, pageSize) }
```

### 8.3 비동기 즉시 수락
```kotlin
@PostMapping("/report/generateReport")
fun generateReport(req: ForceGenerateReportRequest) = StandardResponse.build {
  StandardCallbackResult(
    payload = CudResultPayload(
      operation = CudOperation.INSERT,
      status = "SUCCESS",
      message = "실행 되었습니다."
    )
  )
}
```

### 8.4 Error 처리 전략
Service 내부는 실패 시 예외 → Advice 단일 경로에서 표준화 → Controller if/else 감소, 응답 구조 일관.

---
## 9. FAQ (자주 묻는 질문)
| 질문                        | 답변 |
|---------------------------|------|
| `duration` 항상 0           | 자동 주입 비활성 또는 DTO `@InjectDuration` 누락. 설정/어노테이션 확인 |
| payload 역직렬화 실패           | JSON 구조/타입 불일치, Java 제네릭 손실. 필요 시 `deserialize(json, new TypeReference<Payload<Inner>>() {})` 사용 |
| Java에서 reified 호출 불가      | Java 전용 `fromPageJava`, `buildFromTotalJava` 사용 |
| 커스텀 상태 값 추가하고 싶다          | `StandardStatus` 확장 대신 `ErrorPayload.code`/`appendix`로 세분화 |
| 다양한 케이스 입력 매핑?            | Canonical + Alias 매핑으로 지원. DTO에 대표 `@JsonProperty` 1개 + 변형 `@JsonAlias` 기입 |
| 특정 필드 케이스 변환 제외           | `@NoCaseTransform` 사용 (역직렬화엔 영향 없음) |
| Alias 충돌 알아내는 방법? | 구동 로그 / 테스트 시 충돌 경고. DTO alias 중복 정리 |

---
## Appendix: 패턴 일관성 / 권고

### A.1 권장 패턴 표
| 용도 | 권장 패턴 | 비고 |
|------|-----------|------|
| 단순 성공 응답(상태/버전 기본) | `StandardResponse.build(payload)` | side-effect 없음, duration auto 옵션만 연동 |
| 상태/버전/에러 분기/동적 처리 | `StandardResponse.build(callback = { StandardCallbackResult(...) })` | 한 곳에서 status/version/payload 결정, 확장 용이 |
| Java 동등 기능 | `StandardResponse.buildWithCallback(() -> new StandardCallbackResult(...))` | Kotlin 패턴과 동일 의미 |
| 대규모 응답에서 케이스 변환 OFF | `stdapi.response.case.enabled=false` | 케이스 변환/토큰화 비용 제거 |
| 부분 실패 가능(배치) | callback 내부 누적 후 최종 StandardCallbackResult | 실패 정책 문서화 권장 |

### A.2 build vs callback 선택 기준
- 단순 DTO 전달: build(payload)
- 성공/실패/버전/측정/예외 매핑 통합: build(callback)
- 향후 확장(duration 수동 주입, 로깅, metrics hook) 필요: callback 패턴 통일 권장

### A.3 케이스 변환 비활성화 전체 옵션 예시
```yaml
stdapi:
  response:
    case:
      enabled: false   # 필드 원본 케이스 유지
    auto-duration-calculation:
      active: true
```
> enabled=false 상태에서 부분 변환 필요 시 외부 레이어(Controller advice 등)에서 선택적 케이스 변환.

### A.4 실패 정책(부분 실패 Batch) 권고
```kotlin
fun batch(items: List<String>): StandardResponse<ErrorPayload> = StandardResponse.build(callback = {
    var ep = ErrorPayload("OK", "모두 성공", emptyList())
    items.forEach { v ->
        runCatching { insert(v) }.onFailure {
            ep = if (ep.errors.isEmpty()) {
                ep.copy(code = "PART_FAIL", message = "일부 실패", errors = ep.errors + ErrorEntry("ROW_FAIL", it.message ?: v))
            } else {
                ep.copy(errors = ep.errors + ErrorEntry("ROW_FAIL", it.message ?: v))
            }
        }
    }
    val status = if (ep.errors.isNotEmpty()) StandardStatus.FAILURE else StandardStatus.SUCCESS
    StandardCallbackResult(ep, status, "1.0")
})
```
정책 포인트:
- 첫 실패 시 code/message → PART_FAIL/"일부 실패" 변경
- 오류 존재하면 status = FAILURE (재시도 판단 필요 시 명확)
- 세분화 필요 시: `errors.size > 0 && errors.size < total` → PARTIAL, `errors.size == total` → FAILURE (enum 추가 고려)

### A.5 참고 메모
- Callback 패턴 사용 시 확장 포인트(duration 수동 주입, 로깅, metrics hook) 주입 용이
- 단순 build(payload)는 직렬화 최소 & 순수 전달 목적에 적합
- 대규모 응답 성능 이슈 예상되면 먼저 케이스 변환 OFF 후 경계 레이어 선택 변환 검토
