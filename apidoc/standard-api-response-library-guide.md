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
9. [FAQ](#9-faq)
10. [Appendix](#appendix)
---
## 1. 라이브러리 개요
### 1.1 목적
다양한 REST API들에서 **일관된 상위 응답 스키마**를 표준화하여 클라이언트(웹/앱/3rd-party)가 단일 파싱 로직으로 응답을 소비할 수 있게 합니다.<br>
또한 외부 시스템이 동일 규격으로 응답한 JSON을 역직렬화(consume)할 수 있게 해 주고, 역직렬화 시 케이스/표기 방식이 달라도 안정적으로 DTO에 매핑할 수 있도록 돕습니다.

### 1.2 주요 피쳐(Feature)
| Feature    | 설명                                        | 대표 기능 |
|------------|-------------------------------------------|-----------|
| 표준 응답 생성   | 서버에서 모든 응답을 `StandardResponse<T>` 포맷으로 생성 | 빌더/콜백, duration 자동 측정, 페이지·커서 리스트 지원, 상태/오류 표준화 |
| 표준 응답 역직렬화 | 외부/내부 표준 JSON 문자열을 DTO로 역직렬화 | Canonical Key, Alias/Case 변형 허용, 중첩·컬렉션 지원 |
| 보조 기능      | 출력 케이스 변환 & Java 상호 운용, 성능 최적화 | CaseConvention 변환, `@NoCaseTransform`, 캐시, Java 헬퍼 |

### 1.3 모듈 구성
| 모듈 | 역할 |
|-------|------|
| `common-core` | 공통 유틸/기초 타입 (시간, 공통 예외 등) |
| `standard-api-response` | 표준 응답/리스트/에러/역직렬화/케이스 변환 핵심 구현 + `@InjectDuration`, `@ResponseCase`, `@NoCaseTransform` 제공 |

### 1.4 지원 환경 & 런타임
| 항목 | 권장/테스트된 버전 |
|------|--------------------------------------|
| Java Toolchain | 21 |
| Kotlin | 2.1.20 |
| Spring Boot | 3.4.4 |
| Jackson | 2.18.2 |
| kotlinx.serialization (Wrapper 파서) | 1.8.0 |

> Spring Boot는 선택 사항이며 자동 duration 주입 및 요청 기반 케이스 오버라이드 기능을 사용할 때 필요합니다.

### 1.5 기능 요약
- 단일 래퍼 구조: `status` / `version` / `datetime` / `duration` / `traceid`, `payload`
- 성공/실패 상태 & 표준 상태/오류 페이로드(`StatusPayload`, `ErrorPayload` + `ErrorDetail` 리스트)
- Page / Incremental 리스트 공통 구조
- 콜백 빌더로 실행 시간 측정 후 duration 자동 주입
- `@InjectDuration` 필드 주입(재귀)
- Canonical + Alias 매핑 기반 강건한 역직렬화 (케이스/구분자 차이 허용)
- 응답 키 케이스 변환 (전역/DTO/요청 단위)
- Java 전용 정적 헬퍼 (`fromPageJava`, `buildFromTotalJava`, `buildWithCallback`)
- 자바 직렬화 브릿지: `StandardResponseJsonBridge.toJson(resp, case)` 오버로드 제공
- Java 제네릭 유지 역직렬화를 위한 `TypeReference` 오버로드 (`deserialize(json, typeRef)`) 제공
- 리플렉션 메타 캐싱으로 성능 최적화 (`clearAliasCaches()`)

### 1.6 표준 Payload 종류 Quick Overview
| 분류        | Payload 타입 | 용도 | 필드 개요 / 특징 | 선택 기준 |
|-----------|--------------|------|------------------|-----------|
| 상태        | `StatusPayload` | 단순 상태/메시지/부가 appendix 전달 (성공/정보성 상태) | `code`, `message`, `appendix(Map)` | 성공/헬스체크/Ping/단순 완료 알림 등 오류 세부 목록이 필요 없을 때 |
| 오류        | `ErrorPayload` | 오류/실패 상세 전달 | `errors(List<ErrorDetail(code,message))`, `appendix(Map)` | 실패/부분 실패/검증 오류/역직렬화 오류 등 다수 세부 오류 필요할 때 |
| 페이지 포함 래퍼 | `PageListPayload<T>` | 페이지네이션 확장 래퍼 | 내부에 `pageable: PageableList<T>` 포함 (Page/Order/Items 집약) | 향후 추가 메타(추가 필드) 확장 가능성이 높을 때 |
| 페이지로만 구성  | `PageableList<T>` | 페이지 리스트 자체 | `page`, `order`, `items(list+총계)` | 단순 목록 + 페이지 메타만 필요, 별도 확장 가능성 낮음 |
| 커서 포함 래퍼  | `IncrementalListPayload<T,P>` | 커서 기반(더보기) 리스트 확장 래퍼 | 내부에 `incremental: IncrementalList<T,P>` (cursor+items) 포함 | 커서 메타 외 부가 도메인 필드 확장 여지 필요할 때 |
| 커서로만 구성   | `IncrementalList<T,P>` | 커서 리스트 자체(경량형) | `cursor`, `order`, `items(list+총계)` | 경량 커서 + 아이템만 필요, 응답 크기 최소화 |

> 구성 관계 요약
> - `PageListPayload<T>` 는 내부에 `PageableList<T>` 를 `pageable` 멤버로 포함하여 확장 필드를 추가하기 위한 래퍼입니다.
> - `IncrementalListPayload<T,P>` 는 내부에 `IncrementalList<T,P>` 를 `incremental` 멤버로 포함하여 확장 필드를 추가하기 위한 래퍼입니다.
> - `PageableList<T>` 와 `IncrementalList<T,P>` 는 자체로 `BasePayload` 이므로 Controller에서 직접 `StandardResponse.build(pageableList)` 형태로 반환 가능합니다.
>
> 기본 규칙: 단순 성공/정보 전달은 `StatusPayload`, 실패/검증/부분 실패는 `ErrorPayload`.

#### 1.6.1 StatusPayload vs ErrorPayload JSON 형태
StatusPayload 예시:
```json
{
  "status": "SUCCESS",
  "version": "1.0",
  "datetime": "...",
  "duration": 3,
  "traceid": "7f7c9e2b-5d3b-4e9e-8f11-0b2d2d7c9a01",
  "payload": { "code": "pong", "message": "성공" }
}
```
ErrorPayload 예시 (최상위 code/message 필드 없음 – 첫 오류를 대표로 사용):
```json
{
  "status": "FAILURE",
  "version": "1.0",
  "datetime": "...",
  "duration": 5,
  "traceid": "7f7c9e2b-5d3b-4e9e-8f11-0b2d2d7c9a01",
  "payload": {
    "errors": [
      { "code": "E400", "message": "잘못된 요청" },
      { "code": "E_FIELD", "message": "필드 오류" }
    ],
    "appendix": { }
  }
}
```
> ErrorPayload 의 대표 코드/메시지가 필요하면 `payload.errors.firstOrNull()` 결과를 이용하세요.

---
## 2. 사용 준비 (환경 & 의존성 & Quick Start)
### 2.1 Gradle 의존성
```groovy
dependencies {
    implementation("com.hunet.common:common-core:<version>")
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
        <artifactId>standard-api-response</artifactId>
        <version>버전</version>
    </dependency>
</dependencies>
```
### 2.3 최신 버전 정보
<!-- version-info:start -->
```
Last updated: 2026-01-15 12:51:26 KST
common-core: 1.1.0-SNAPSHOT
apidoc-core: 1.1.0-SNAPSHOT
apidoc-annotations: 1.1.0-SNAPSHOT
standard-api-response: 1.3.1-SNAPSHOT
```
<!-- version-info:end -->

### 2.4 최소 설정 (application.yml 예)
```yaml
stdapi:
  response:
    auto-duration-calculation:
      active: true              # duration 자동 주입 (필터 + Advice)
      filter-order: -2147483648 # 필터 순서 지정(기본 Int.MIN_VALUE)
    case:
      enabled: true             # 응답 키 케이스 변환 활성화
      default: IDENTITY         # 기본 케이스
```

### 2.5 Query/헤더 오버라이드 옵션
```yaml
stdapi:
  response:
    case:
      query-override: true
      header-override: true
      query-param: case
      header-name: X-Response-Case
```

### 2.6 환경변수 / 시스템 속성 키 (Alias 충돌 제어)
| 구분 | 키 | 기본 |
|------|----|------|
| 모드 | `STDAPI_RESPONSE_ALIAS_CONFLICT_MODE` / `stdapi.response.alias-conflict-mode` | WARN |
| 해결전략 | `STDAPI_RESPONSE_ALIAS_CONFLICT_RESOLUTION` / `stdapi.response.alias-conflict-resolution` | FIRST_WIN |
> 전략 값: `FIRST_WIN` (최초 매핑 유지), `BEST_MATCH` (입력 key 와 가장 유사 alias 선택)

### 2.7 응답 생성 Quick Start (Kotlin Controller)
본 라이브러리는 손쉽게 표준 응답을 생성할 수 있는 빌더 API를 제공합니다.
```kotlin
@RestController
class PingController {
  @GetMapping("/api/ping")
  fun ping() = StandardResponse.build(StatusPayload("pong"))
}
```
응답 예 (실제 직렬화 결과):
```json
{
  "status": "SUCCESS",
  "version": "1.0",
  "datetime": "...",
  "duration": 3,
  "traceid": "7f7c9e2b-5d3b-4e9e-8f11-0b2d2d7c9a01",
  "payload": { "code": "pong", "message": "성공", "appendix": {} }
}
```
> StatusPayload의 필드의 기본값은 `code = "OK"`, `messsage = "성공"`, `appendix = mutableMapOf()`입니다.
> 
> 빈 맵은 직렬화 시 기본적으로 `{}`로 표현됩니다.
> 
> Java 권고: 자바 코드에서는 `StatusPayload.of("OK", "성공", null)` 와 `StandardCallbackResult.of(payload[, status, version])` 팩토리 사용을 권장합니다. appendix에 null을 전달해도 `of(...)`가 빈 맵으로 안전 변환합니다.

### 2.8 응답 JSON 매핑(역직렬화) Quick Start 및 개요

#### 2.8.1 가장 단순한 사용 (Kotlin)
```kotlin
val json: String = httpClient.get("/external/api")
val resp = StandardResponse.deserialize<StatusPayload>(json)
if (resp.status == StandardStatus.SUCCESS) {
    val statusInfo: StatusPayload = resp.payload
} else {
    val err: ErrorPayload? = resp.getRealPayload<ErrorPayload>()
    val firstError = err?.errors?.firstOrNull()
}
```

#### 2.8.2 Java Class 기반
```java
class DeserializeExample {
    private final HttpClient httpClient; // 가상의 HTTP 클라이언트
    DeserializeExample(HttpClient httpClient) { this.httpClient = httpClient; }
    void consume() {
        String json = httpClient.get("/external/api");
        StandardResponse<StatusPayload> resp = StandardResponse.deserialize(json, StatusPayload.class);
        if (resp.getStatus() == StandardStatus.SUCCESS) {
            StatusPayload status = resp.getPayload();
        } else {
            ErrorPayload ep = resp.getRealPayload(ErrorPayload.class); // 실패 시 캐스팅 시도
        }
    }
}
```

#### 2.8.3 제네릭/중첩 타입 (Kotlin reified)
```kotlin
val pageResp = StandardResponse.deserialize<PageListPayload<UserDto>>(jsonPage)
val users = pageResp.payload.pageable.items.list
```

#### 2.8.4 제네릭/중첩 타입 (Java TypeReference)
```java
StandardResponse<PageListPayload<UserDto>> pageResp = StandardResponse.deserialize(
    jsonPage,
    new com.fasterxml.jackson.core.type.TypeReference<PageListPayload<UserDto>>() {}
);
List<UserDto> users = pageResp.getPayload().getPageable().getItems().getList();
```

#### 2.8.5 부분 안전 캐스팅 헬퍼
```kotlin
val anyResp: StandardResponse<BasePayload> = StandardResponse.deserialize(json)
val user: UserDto? = anyResp.getRealPayload<UserDto>() // 타입 불일치 시 null
```

#### 2.8.6 안전성 
본 라이브러리는 역직렬화 시점에서 발생할 수 있는 다양한 문제 상황에 대해 안전 메커니즘을 내장하고 있습니다.

| 영역 | 안전 메커니즘 | 이점 |
|------|---------------|------|
| 실패 처리 | 역직렬화 예외 → `status=FAILURE` + `ErrorPayload` (첫 `errors[0]` = `E_DESERIALIZE_FAIL`) | 호출측 try/catch 최소화, 표준 오류 코드 일관성 |
| Canonical/Alias | 케이스/구분자 변형 허용 | 외부 시스템 표기 편차 허용 → 호환성 ↑ |
| BEST_MATCH 전략 | (WARN+BEST_MATCH) 충돌 시 실제 입력 키와 가장 유사 alias 선택 | 다수 alias 혼재 상황에서도 자동 분기 |
| 부분 다운캐스팅 | `getRealPayload<T>()` | 잘못된 캐스팅 예외 회피 |
| Wrapper 분리 | 상위 메타 파싱 실패 시에도 payload 영향 국한 | 손상 JSON 일부라도 최대 정보 확보 |

---
## 3. 표준 응답 생성
### 3.1 핵심 타입 및 개념
| 타입 | 설명 |
|------|------|
| `StandardResponse<T>` | 최상위 래퍼 (T : `BasePayload`) |
| `StandardStatus` | `SUCCESS` / `FAILURE` / `NONE`(내부용, 일반 응답에 사용 지양) |
| `BasePayload` / `BasePayloadImpl` | Payload 마커 / 빈 구현 |
| `StatusPayload` | 성공/정보성 상태 전달 (`code`,`message`,`appendix`) |
| `ErrorPayload` / `ErrorDetail` | 오류 응답 / 상세 오류 리스트 (`errors[ {code,message} ]`) |
| `PageableList<T>` / `PageListPayload<T>` | 페이지 기반 리스트 |
| `IncrementalList<T,P>` / `IncrementalListPayload<T,P>` | 커서 기반 리스트 |
| `Items<T>` | `total` / `current` / `list` 메타 |
| `PageInfo`, `OrderInfo`, `OrderBy` | 페이지·정렬 메타 |
| `CursorInfo<P>` | 커서(start/end/expandable) 메타 |
| `@InjectDuration` | duration 자동 주입 표시 |
| `StandardCallbackResult` | 콜백 빌더 반환 컨테이너 |

#### 3.1.1 ErrorPayload 구조
- 최상위에 `code` / `message` 필드가 따로 존재하지 않습니다.
- 주 오류 식별: `payload.errors.firstOrNull()`
- 다중 오류 누적: `addError(code, message)` 사용
- 부가 정보: `appendix` 맵 활용

### 3.2 JSON 구조
```json
{
  "status": "SUCCESS",
  "version": "1.0",
  "datetime": "2025-01-01T00:00:00Z",
  "duration": 12,
  "traceid": "7f7c9e2b-5d3b-4e9e-8f11-0b2d2d7c9a01",
  "payload": {}
}
```
> version 기본값은 `1.0`, datetime은 ISO-8601 UTC(Z) 포맷, duration은 밀리초 단위 Long, traceid는 UUIDv4 문자열.

### 3.3 기본 빌드
```kotlin
val ok = StandardResponse.build(StatusPayload("OK", "정상"))
val fail = StandardResponse.build(
    payload = ErrorPayload("E400", "잘못된 요청"),
    status = StandardStatus.FAILURE,
    version = "1.1"
)
val timed = StandardResponse.build<StatusPayload>(payload = null) {
  StandardCallbackResult(StatusPayload("OK", "완료"), StandardStatus.SUCCESS, "2.0")
}
```
Java:
```java
class BuildExample {
    void buildSamples() {
        StandardResponse<StatusPayload> okResp = StandardResponse.build(new StatusPayload("OK", "정상", null));
        StandardResponse<ErrorPayload> failResp = StandardResponse.build(
            new ErrorPayload("E500", "서버 오류", null), StandardStatus.FAILURE, "1.2", ""
        );
        StandardResponse<StatusPayload> timedResp = StandardResponse.<StatusPayload>buildWithCallback(
            () -> new StandardCallbackResult<>(new StatusPayload("OK", "완료", null), StandardStatus.SUCCESS, "2.0")
        );
    }
}
```

### 3.4 자동 Duration 주입 (`@InjectDuration`)
- 활성화: `stdapi.response.auto-duration-calculation.active=true`
- 주입 범위: 최상위 `StandardResponse` 및 payload 내부(`BasePayload` 구현체) 재귀적으로 주입
- 단위 변경: `@InjectDuration(TimeUnit.SECONDS)` 등
- 최종 응답에 표시되는 `duration`(StandardResponse 필드)은 빌더 콜백 실행 시간 또는 요청 전체(elapsed) 중 자동 계산된 값(필터 + Advice)으로 주입되며, 요청 시간 필터 활성 시 필터 기준 elapsed 값이 override 됩니다.
- 지원 주입 타입: Long, Int, Double, String, `java.time.Duration`, `kotlin.time.Duration` (필드 타입에 따라 변환)
- 재귀 범위: 최상위 `StandardResponse` → `payload` → 중첩 `BasePayload` → 컬렉션(List/Set 등) 요소 중 `BasePayload` → Map 값 중 `BasePayload`.

### 3.5 오류 응답 패턴
```kotlin
val ep = ErrorPayload("E400", "잘못된 요청")
ep.addError("E_DETAIL", "세부 오류")

val resp = StandardResponse.build(ep, StandardStatus.FAILURE, version = "1.0")
val primary = resp.payload.errors.firstOrNull() // 대표 코드/메시지
```

### 3.6 리스트 응답
Kotlin Page:
```kotlin
fun <E> toPagePayload(page: Page<E>, map: (E) -> MyDto) = PageListPayload.fromPage(page, map)
```
Java Page:
```java
PageableList<MyDto> payload = PageableList.fromPageJava(page, e -> new MyDto(e.getId()));
```
Kotlin Incremental:
```kotlin
val inc = IncrementalList.buildFromTotal<String, Long>(listOf("A", "B"),  0, 2, 10, "id")
```
Java Incremental (인덱스 변환 BiFunction):
```java
IncrementalList<String, Long> inc = IncrementalList.buildFromTotalJava(
  List.of("A", "B"), 0L, 2L, 10L, "id", null, (field, index) -> index
);
```

### 3.6.x 편의 프로퍼티
`PageableList` / `IncrementalList` 에는 `itemsAsList` (items.list 단축) 가 있어 바로 아이템 리스트에 접근 가능합니다.

### 3.6.1 공통 구성 요소 (Items<T> / OrderInfo / OrderBy)
`PageableList<T>` 와 `IncrementalList<T,P>` (및 이를 감싸는 래퍼 `PageListPayload<T>`, `IncrementalListPayload<T,P>`) 는 **정렬 메타**와 **아이템 메타**를 일관된 형태로 제공하기 위해 `OrderInfo` 와 `Items<T>` 구조를 정의하여 공통으로 사용합니다.

#### 3.6.1.1 Items<T>
목적: 컬렉션 본문(list)과 그에 대한 총계 메타를 표준화하여 클라이언트가 동일한 파싱 로직을 재사용하게 함.

필드 의미:
- `total`: 전체 아이템 수 (페이지 전체 또는 커서 전체 추정). 일부 환경에서 아직 모를 경우 라이브러리 설계에 따라 0 또는 null(도메인 설계)로 둘 수 있으며, 통상 `current`보다 크거나 같아야 합니다.
- `current`: 현재 응답에 포함된 아이템 수. 일반적으로 `list.size` 와 동일합니다.
- `list`: 실제 아이템 컬렉션. 제네릭 `T` 는 반드시 `BasePayload` 구현체 또는 단순 값 타입이어야 합니다.

불변 규칙(권장):
1. `current == list.size`
2. `total >= current` (단, `total`을 미확정 상태로 0 처리하는 초기 커서 응답 등 예외 케이스 문서화 필요)
3. 빈 결과: `current=0`, `list=[]`, `total` 은 0 또는 전체 총계(정책에 따라 선택)

활용 패턴:
- 페이지 응답: 페이징 소스(`Page<E>`)에서 `totalElements` → `total`, `content.size` → `current`, `mappedList` → `list`.
- 커서 응답: 커서 범위 내 fetch된 레코드 수 → `current`; 전체 총계 계산 가능 시 → `total`, 아니면 추정값 사용 또는 별도 appendix로 예측치 전달.

성능 고려:
- 클라이언트가 `list.size` 반복 계산하는 대신 즉시 `current` 로 액세스 가능.
- 부분 응답/스트리밍 시 `current` 와 `total` 비교로 추가 페이지/커서 요청 필요 여부 신속 결정.

#### 3.6.1.2 OrderInfo / OrderBy
목적: 다중 정렬 기준을 안정적으로 표현하고, "정렬 적용 여부"를 명시하여 클라이언트가 후처리 판단을 단순화할 수 있게 합니다.

필드 의미:
- `sorted`: 하나 이상의 `OrderBy` 가 적용되었다면 true. (무작위/미정렬/기본 순서라면 false)
- `by`: 적용된 순서 있는 정렬 기준 리스트. 빈 리스트면 `sorted=false` 권장.

`OrderBy` 필드:
- `field`: 정렬 대상 필드명(DTO 필드인 경우 직렬화 시 케이스 변환 규칙/alias 적용 이전의 canonical 이름을 권장)
- `direction`: `ASC` 또는 `DESC` (DTO 필드인 경우 `OrderDirection` Enum class 사용 권장)

활용 패턴:
- JPA/Page 요청에서 Sort 정보 매핑 → `OrderInfo.sorted = sort.isSorted`, `OrderInfo.by = sort.orders.map { OrderBy(it.property, it.direction.name) }`
- 다중 필드 정렬: `by` 리스트 순서대로 우선순위 적용.

JSON 예시:
```json
{
  "pageable": {
    "page": { "size": 20, "current": 1, "total": 10 },
    "order": { "sorted": true, "by": [ { "field": "createdAt", "direction": "DESC" }, { "field": "id", "direction": "ASC" } ] },
    "items": { "total": 200, "current": 20, "list": [ { "userId": 10, "name": "황용호" } ] }
  }
}
```

#### 3.6.1.3 권장 구현 예 (Kotlin 직접 구성)
```kotlin
val items = Items(total = 200, current = userDtos.size, list = userDtos)
val orderInfo = OrderInfo(sorted = true, by = listOf(OrderBy("createdAt", "DESC"), OrderBy("id", "ASC")))
val pageableList = PageableList(
  page = PageInfo(size = 20, current = 1, total = 10),
  order = orderInfo,
  items = items
)
val resp = StandardResponse.build(pageableList)
```

#### 3.6.1.4 커서 리스트에서의 차이점
- `IncrementalList<T,P>` 는 `cursor`(start/end/expandable) 메타를 추가로 포함.
- `Items<T>` 사용 규칙은 동일 (current/total/list).
- 커서 기반에서 `total` 계산 비용이 높을 경우: 즉시 계산 가능한 범위만 확정하고 미확정 총계는 0 또는 별도 appendix 로 전달 (`StatusPayload`나 `ErrorPayload` appendix, 혹은 추가 필드 확장).

#### 3.6.1.5 Edge Case & 품질 체크
| 상황 | 점검 포인트 | 해결 |
|------|-------------|------|
| total < current | 잘못된 총계 계산 | 총계 재계산 또는 total=0 정책 → 문서화 필요 |
| sorted=true 이지만 by 빈 리스트 | 정렬 플래그 불일치 | sorted=false 로 교정 또는 by 채우기 |
| current != list.size | 누락/중복 매핑 | 리스트 변환(map) 후 size 재동기화 |
| direction 오타 | 클라이언트 파싱 실패 | Enum 사용 강제 / 검증 테스트 추가 |

> `Items<T>` 는 독립 응답으로 거의 사용하지 않으므로 단독 반환보다 `PageableList` / `IncrementalList` 내부에서만 사용하도록 유지하는 것이 표준 해석 일관성에 유리합니다.

### 3.7 리스트 구조 선택 기준 (Page vs Incremental / Wrapper vs Direct)
| 구조 | 직접 타입 사용 | Payload 래퍼 사용 | 선택 기준 |
|------|---------------|------------------|-----------|
| 페이지 | `PageableList<T>` | `PageListPayload<T>` | 래퍼 사용 시 추가 메타 필드 확장 용이(`pageable` 아래로 묶임). 단순 페이지 정보만 필요하면 `PageableList` 직접을 payload 로 사용 가능. |
| 커서(더보기) | `IncrementalList<T>` | `IncrementalListPayload<T>` | 래퍼 사용 시 향후 부가 필드(예: 도메인별 통계 등) 추가 용이. 경량 리스트만 필요하면 직접 타입 사용. |
(권장) 복잡한 도메인에서 다형성/추가 appendix 필요 가능성이 있으면 Payload 래퍼 사용, 단순 조회 API는 직접 리스트 타입 반환으로 직렬화 크기/필드 깊이 최소화.

### 3.8 traceid 필드
traceid 필드는 분산 시스템에서 요청-응답 간 호출 흐름을 추적하기 위한 고유 식별자입니다. <br>
API 개발자가 직접 생성/전파할 수도 있지만, 일반적으로 API Gateway 또는 Edge Server에서 최초 요청 시 생성하여 내부 서비스 호출 시 동일 값을 전달하는 패턴이 권장됩니다. <br>
일반적으로 openTelemetry, zipkin, jaeger 등 분산 추적 시스템과 연동하여 로그 상관 분석 및 성능 모니터링에 활용됩니다.<br>
대부분 라이브러리 차원에서 자동 생성 및 주입되므로 개발자가 직접 신경 쓸 필요는 없으나, 필요 시 아래 가이드에 따라 구현할 수 있습니다.<br>
이 라이브러리는 `traceid` 필드를 자동으로 생성하거나 전파, 주입 등의 기능은 제공하지 않으며, openTelemetry 등 외부 라이브러리와 연동하여 사용해야 합니다.

| 항목 | 내용                                                                             |
|------|--------------------------------------------------------------------------------|
| 목적 | 요청-응답, 내부 마이크로서비스 호출, 비동기 처리(메시지 큐, 이벤트) 간 **단일 호출 흐름(Correlation)** 추적 용도     |
| 타입 | `String` (UUID v4 권장). 하이픈 포함 36자 형태: `xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx`   |
| 필수 여부 | Optional (미포함 시 클라이언트 또는 게이트웨이/서버에서 새 UUID 생성 가능)                              |
| 값 생성 권장 위치 | 최초 진입 지점(API Gateway, Edge Server, 혹은 첫 REST Controller)                       |
| 전파 방식 | 동일 값을 하위 서비스 호출 시 **HTTP Header (예: `X-Trace-Id`)** 또는 메시지 메타로 전달 후 응답에 그대로 반영 |
| 변경 금지 | 동일 호출 체인 내에서는 값 변경/재생성 금지 (단, 없는 경우만 생성)                                       |
| 보안 | PII/민감정보 포함 금지 (순수 랜덤 UUID). 로그 마스킹 불필요하지만 접근 제어된 저장소에만 보관                     |
| 사용 범위 | 로그 상관 분석, 디버깅, 성능 측정(여러 구간 Duration 합산), 장애 시 빠른 경로 추적                         |
| 하위 호환 | 기존 클라이언트는 traceid 부재에도 정상 동작 (Optional 유지)                                     |

##### 생성/전파 예시 흐름
1. 클라이언트가 요청 시 `X-Trace-Id` 헤더가 없으면 Gateway가 UUID 생성 → 요청 헤더 및 응답 body `traceid` 모두 설정.
2. 내부 서비스 A가 B를 호출할 때 기존 헤더의 값을 그대로 전달.
3. 각 서비스는 로깅 프레임워크 MDC 등에 `traceid` 반영 후 처리.
4. 최종 응답은 원래 값을 `traceid` 필드에 그대로 포함.

---

## 4. 역직렬화
### 4.1 개요
두 단계: Wrapper(kotlinx) → payload(Jackson). <br>
실패 시 예외를 그대로 Throw 하지 않고 **FAILURE 상태 + ErrorPayload(code="E_DESERIALIZE_FAIL")** 로 감싼 응답 반환.

### 4.2 기본 역직렬화 API
```kotlin
val resp = StandardResponse.deserialize<StatusPayload>(jsonString)
```
Java:
```java
StandardResponse<StatusPayload> resp = StandardResponse.deserialize(jsonString, StatusPayload.class);
```
실패 시(`StatusPayload` 기대했으나 역직렬화 실패 또는 실제 오류 응답):
```json
{
  "status": "FAILURE",
  "version": "1.0",
  "payload": {
    "errors": [ { "code": "E_DESERIALIZE_FAIL", "message": "..." } ]
  }
}
```

### 4.3 제네릭 & 리스트/커서
```kotlin
val pageResp = StandardResponse.deserialize<PageListPayload<MyItemPayload>>(json)
val incResp  = StandardResponse.deserialize<IncrementalList<LogEntryPayload, Long>>(json)
```
Java TypeReference:
```java
StandardResponse<PageListPayload<MyItemPayload>> resp =
  StandardResponse.deserialize(json, new TypeReference<PageListPayload<MyItemPayload>>() {});
```

### 4.4 오류 응답 역직렬화
```kotlin
val errorResp = StandardResponse.deserialize<ErrorPayload>(json)
if (errorResp.status == StandardStatus.FAILURE) {
    val first = errorResp.payload.errors.firstOrNull()
}
```

### 4.5 Canonical / Alias
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
- 변형 처리: `_` ↔ `-` 교차 변형도 canonical 후보로 등록하여 충돌 후보 집합(`conflictCandidates`) 구축.
- 충돌 해소 전략: `AliasConflictMode=ERROR` 시 즉시 예외; `WARN` + `AliasConflictResolution=BEST_MATCH` 시 입력 실제 key와 alias 후보 유사도 기반 선택.

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

### 4.6 재귀/중첩 구조 & 성능
- BasePayload/Collection/Map 내부 재귀
- 캐시: 최초 방문 후 전역 저장 → `clearAliasCaches()` 호출로 초기화 가능

---
## 5. 케이스 컨벤션 변환
### 5.1 개요
응답 JSON 직렬화(DTO → JSON) 시 필드 키를 `snake_case`, `kebab-case`, `SCREAMING_SNAKE_CASE` 등으로 변환.
(역직렬화는 Canonical/Alias 매핑 로직이 별도 처리됨 – 케이스 변환 설정 비영향)

### 5.2 동작 순서
기존 순서 교정 및 alias 단계 명시:
1. Jackson 기본 직렬화 (원본 DTO → JSON Tree)
2. Alias serializationMap 적용(필드별 최종 출력 alias로 치환) + `@NoCaseTransform` 대상 skip 키 수집
3. 선택된 CaseConvention에 따라 변환(IDENTITY 이외) – skip 키는 원본 유지
4. Pretty 옵션 여부에 따라 최종 문자열 생성
> (주의) <br>
> `StandardResponse.toJson(case=...)`는 전역 default 케이스 설정을 사용하지 않습니다.<br>
> ControllerAdvice 경로에서만 application.yml의 default 우선순위(쿼리 > 헤더 > DTO > default)가 적용됩니다.

### 5.3 사용 예
```kotlin
@ResponseCase(CaseConvention.SNAKE_CASE)
data class UserPayload(val userId: Long, val firstName: String, val emailAddress: String): BasePayload
val snakeJson = StandardResponse.build(UserPayload(1, "용호", "mail@test.com")).toJson()
val kebabJson = StandardResponse.build(UserPayload(1, "용호", "mail@test.com")).toJson(case = CaseConvention.KEBAB_CASE)
```
Java 브릿지:
```java
StandardResponse<UserPayload> resp = StandardResponse.build(new UserPayload(1, "용호", "jogakdal@gmail.com"));
String snake = StandardResponseJsonBridge.toJson(resp, CaseConvention.SNAKE_CASE);
String kebab = StandardResponseJsonBridge.toJson(resp, CaseConvention.KEBAB_CASE);
```

우선순위 정리:
- 직접 호출(`resp.toJson()`): 명시적 `case` 인자 > DTO `@ResponseCase` > 기본값(IDENTITY)
- ControllerAdvice 자동 적용: 쿼리 파라미터(`case`) > 헤더(`X-Response-Case`) > DTO `@ResponseCase` > 전역 default 설정값
(참고: `enabled=false`이면 위 우선순위와 상관없이 변환을 수행하지 않고 원래 키를 유지합니다.)

### 5.4 지원 케이스 컨벤션
IDENTITY(원 타입 그대로) / SNAKE_CASE / SCREAMING_SNAKE_CASE / KEBAB_CASE / CAMEL_CASE / PASCAL_CASE

### 5.5 특정 필드 제외
```kotlin
data class Sample(
    @JsonProperty("api_key") 
    @NoCaseTransform 
    val apiKey: String, 
    val normalField: String
): BasePayload
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

### 6.1 Kotlin MockMvc
```kotlin
val mvcResult = mockMvc.perform(get("/api/ping"))
  .andExpect(status().isOk)
  .andExpect(jsonPath("$.status").value("SUCCESS"))
  .andReturn()
val resp = StandardResponse.deserialize<StatusPayload>(mvcResult.response.contentAsString)
assertEquals(StandardStatus.SUCCESS, resp.status)
```

### 6.2 Java MockMvc
```java
class MockMvcExample {
    void test(MockMvc mockMvc) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/ping"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUCCESS"))
            .andReturn();
        StandardResponse<StatusPayload> resp = StandardResponse.deserialize(
            result.getResponse().getContentAsString(), StatusPayload.class
        );
    }
}
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
| 항목 | 설명 |
|------|------|
| 기본 파라미터 | Kotlin 기본값 → Java 사용 시 오버로드 메서드로 모두 명시 |
| reified 한계 | `fromPage()` 등 reified 함수 → `fromPageJava()`, `buildFromTotalJava()` 사용 |
| 제네릭 안전성 | `StandardResponse<Payload>` 변수 형태로 명확히 선언하여 캐스팅 방지 |
| 복합 제네릭 역직렬화 | 내장 `deserialize(json, TypeReference<T>)` 사용 (payload 타입 T 지정) |

권고 사항:
- 생성 편의: `StatusPayload.Companion.of(code, message, appendixNullable)` 사용 → appendix null-safe.
- 콜백 결과: `StandardCallbackResult.of(payload)` 또는 `of(payload, status, version)` 사용 → Kotlin 기본 인자 차이와 NPE 방지.

### 7.2 Callback 빌더 패턴
`StandardResponse.build(callback = { ... })`(Kotlin) / `StandardResponse.buildWithCallback(() -> { ... })`(Java)는 응답 생성 직전 도메인 로직을 람다/함수 블록으로 감싸 **payload + status + version**을 한 번에 선언하도록 하는 패턴입니다.<br>
- `StandardCallbackResult` 는 제네릭(`StandardCallbackResult<T : BasePayload>`)으로 변경되어 타입 안전성이 강화되었습니다. Java에서는 `Supplier<StandardCallbackResult<T>>` 형태로 사용합니다.

Callback 내부에서 `StandardCallbackResult`를 반환해야 하며, `status` / `version`을 생략하면 기본값(`SUCCESS` / `1.0`)이 적용됩니다.

#### 도입 배경 & 장점
| 항목 | 설명 | 효과 |
|------|------|------|
| Lazy 계산 | 응답 직전까지 도메인 작업 지연 | 불필요 연산 최소화, duration 측정 정확도 ↑ |
| 단일 선언 지점 | payload + status + version 한 곳에서 확정 | 가독성/코드 리뷰 용이 |
| 실패/성공 분기 단순화 | if/else 내부에서 `StandardCallbackResult`만 교체 | 예외 남발/중첩 축소 |
| 일관된 duration 측정 | 빌더가 callback 전/후 시각 측정 | 코드 중복 제거, 실측치 반영 |
| 테스트 용이 | callback 단위 mocking/stub 가능 | 단위 테스트에서 외부 리소스 분리 |
| Java/Kotlin 일관 인터페이스 | `buildWithCallback` 제공 | 다국어 코드베이스 통일 |

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
class UserServiceControllerExample {
    private final Repo repo;
    UserServiceControllerExample(Repo repo) { this.repo = repo; }
    public StandardResponse<UserPayload> getUser(long id) {
        return StandardResponse.<UserPayload>buildWithCallback(() -> {
            UserEntity e = repo.findById(id);
            if (e == null) {
                return new StandardCallbackResult<>(
                    new ErrorPayload("E404", "user not found", null),
                    StandardStatus.FAILURE,
                    "1.0"
                );
            }
            return new StandardCallbackResult<>(
                new UserPayload(e.getId(), e.getName()), StandardStatus.SUCCESS, "1.0"
            );
        });
    }
}
```

#### Controller ↔ Service 연계 패턴
Service가 `StandardCallbackResult`를 직접 반환하도록 하여 Controller가 그대로 감싸는 패턴.
```kotlin
@Service
class UserService {
    fun loadUser(id: Long): StandardCallbackResult<BasePayload> = run {
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
| 전략 | 설명 |
|------|------|
| Callback 내부 ErrorPayload 반환 | 예측 가능한 비즈니스 실패 |
| 예외 throw + 글로벌 Advice 변환 | 시스템/인프라 오류 |
| 역직렬화 실패 자동 FAILURE 래핑 | 라이브러리 내부 처리 |

### 7.3 Duration 측정 주의 사항
- 콜백 빌더 내부 시간: callback 블록 실행 전후 차이 (업무 로직 측정)
- 요청 전체 시간: 필터(System.nanoTime 시작) → 응답 직전 Advice에서 계산, `@InjectDuration` 표기된 모든 재귀 필드에 주입
- 최종 출력 `StandardResponse.duration`: 필터 기반 값 우선, 필터 비활성 시 빌더 측정값 사용.

### 7.4 Batch 실패 개선 예시
```kotlin
fun batch(items: List<String>): StandardResponse<BasePayload> = StandardResponse.build(callback = {
    val rowErrors = mutableListOf<ErrorDetail>()
    items.forEach { v ->
        runCatching { insert(v) }.onFailure { t ->
            rowErrors += ErrorDetail("ROW_FAIL", t.message ?: v)
        }
    }
    if (rowErrors.isEmpty()) {
        StandardCallbackResult(StatusPayload("OK", "모두 성공"))
    } else {
        val payload = ErrorPayload(
            code = if (rowErrors.size < items.size) "PART_FAIL" else "FAIL",
            message = if (rowErrors.size < items.size) "일부 실패 발생" else "전체 실패",
            errors = rowErrors
        )
        StandardCallbackResult(payload, StandardStatus.FAILURE, "1.0")
    }
})
```
정책 포인트:
- 성공 시 `StatusPayload` 사용 (오류 리스트 불필요)
- 실패/부분 실패 시 `ErrorPayload` 사용 → 클라이언트는 payload 타입 확인 또는 `getRealPayload<ErrorPayload>()` 사용

---
## 8. 실 서비스 적용 패턴

### 8.1 Controller ↔ Service ↔ Response 흐름
| 레이어 | 역할 | 패턴 |
|--------|------|------|
| Controller | 엔드포인트 정의, 컨텍스트 추출 | `StandardResponse.build { service.method(...) }` |
| Service | 비즈니스 + DTO 매핑 | 성공: `StandardCallbackResult(payload=...)` / 실패: 예외 throw |
| Advice | 전역 예외 처리 | 예외 → `ErrorPayload` + `FAILURE` 응답 |
| 비동기 트리거 | 오래 걸리는 작업 위임 | 즉시 CUD 결과 응답 + 비동기 실행 |

### 8.2 StandardCallbackResult 활용 (페이징 서비스 실전)
**도메인 페이징 조회**를 StandardCallbackResult로 감싸는 실전 흐름에 대한 예시입니다.

#### 8.2.1 Service 레이어 (도메인 로직 + DTO 변환)
```kotlin
@Service
class ManagerSessionService(
    private val repo: ManagerSessionRepository
) {
    fun getManagerSessionList(companyCode: String, pageNo: Int, pageSize: Int): StandardCallbackResult {
        val page = repo.findByCompanyCode(companyCode, PageRequest.of(pageNo - 1, pageSize))
        // 도메인 정책 예: 데이터 없으면 실패로 간주 (또는 빈 리스트 성공 정책 선택 가능)
        if (page.content.isEmpty()) {
            return StandardCallbackResult(
                payload = ErrorPayload(
                    code = "E_EMPTY",
                    message = "세션이 없습니다",
                    appendix = mapOf("companyCode" to companyCode)
                ),
                status = StandardStatus.FAILURE
            )
        }
        val payload = PageListPayload.fromPage(page) { e -> ManagerSessionPayload(e.id, e.userName) }
        return StandardCallbackResult(payload) // SUCCESS 기본 적용
    }
}

data class ManagerSessionPayload(val sessionId: Long, val userName: String): BasePayload
```

#### 8.2.2 Controller 레이어 (표준 응답 래핑)
```kotlin
@RestController
@RequestMapping("/manager")
class ManagerSessionController(
    private val managerSessionService: ManagerSessionService
) {
    @GetMapping("/{companyCode}/{pageNo}")
    fun list(
        @PathVariable companyCode: String,
        @PathVariable pageNo: Int,
        @RequestParam(defaultValue = "20") pageSize: Int
    ): StandardResponse<BasePayload> = StandardResponse.build {
        managerSessionService.getManagerSessionList(companyCode, pageNo, pageSize)
    }
}
```

#### 8.2.3 응답 예시 (성공)
```json
{
  "status": "SUCCESS",
  "version": "1.0",
  "datetime": "...",
  "duration": 5,
  "traceid": "7f7c9e2b-5d3b-4e9e-8f11-0b2d2d7c9a01",
  "payload": {
    "pageable": {
      "page": { "size": 20, "current": 1, "total": 3 },
      "order": { "sorted": false, "by": [] },
      "items": { "total": 42, "current": 20, "list": [ { "sessionId": 10, "userName": "홍길동" }, { "sessionId": 11, "userName": "김철수" } ] }
    }
  }
}
```

#### 8.2.4 응답 예시 (실패)
```json
{
  "status": "FAILURE",
  "version": "1.0",
  "datetime": "...",
  "duration": 3,
  "traceid": "7f7c9e2b-5d3b-4e9e-8f11-0b2d2d7c9a01",
  "payload": {
    "errors": [ { "code": "E_EMPTY", "message": "세션이 없습니다" } ],
    "appendix": { "companyCode": "ACME" }
  }
}
```

#### 8.2.5 설계 선택 포인트 (빈 페이지 처리 정책)
| 정책 | 조건 | 응답 형태 | 장점 | trade-off |
|------|------|----------|------|-----------|
| 성공(빈 리스트) | 데이터 없음 | SUCCESS + items.current=0 | 클라이언트 로직 단순 | 빈/존재 구분을 클라이언트가 추가 판단 필요 |
| 실패(ErrorPayload) | 데이터 없음 | FAILURE + 에러 코드(E_EMPTY) | 상태 분기 명확 | 일부 클라이언트에서 빈 결과를 오류로 볼 필요 없을 수 있음 |

#### 8.2.6 Service에서 StandardCallbackResult를 반환해야 하는 이유
| 포인트 | 이유 | 효과 |
|--------|------|------|
| 응답 표준화 분리 | Controller는 포맷팅만 담당 | 레이어 책임 명확화, 테스트 단순화 |
| 에러/성공 동시 처리 | 하나의 return 으로 상태/페이로드 결정 | 가독성/코드 리뷰 용이 |
| 확장 용이성 | appendix 등 메타 추가 위치를 Service로 집중 | Controller 변경 최소화 |
| 측정 정밀도 | build(callback) 로직 범위만 duration 측정 | 전체 요청 시간과 로직 시간 분리 가능 |

---
## 9. FAQ
| 질문 | 답변 |
|------|------|
| `duration`이 항상 0이다 | 필터 비활성 / 측정 구간이 매우 짧음 / `@InjectDuration` 누락 가능성. auto-duration 설정과 필터 등록 확인. |
| 역직렬화 실패 처리 | FAILURE + `ErrorPayload` (`errors[0].code == E_DESERIALIZE_FAIL`) 자동 래핑 |
| 대표 오류 코드/메시지 어디? | `ErrorPayload.errors.firstOrNull()` (최상위 code/message 없음) |
| Java에서 reified 대안 | `fromPageJava`, `buildFromTotalJava`, `deserialize(json, typeRef)` |
| 다양한 케이스 입력 매핑? | Canonical + Alias 매핑으로 `user_id` / `userId` / `USER-ID` 허용 |
| 특정 필드 케이스 변환 제외 | `@NoCaseTransform` |
| Alias 충돌 강제 검사 | 모드 `ERROR` 또는 WARN+로그, 필요 시 BEST_MATCH 전략 |
| 페이지 번호 기준 | `PageInfo.current` 는 1부터 시작 (Spring Page.number + 1) |
| 오류/성공 선택 기준 | 단순 상태 → `StatusPayload`, 오류/부분실패/검증 → `ErrorPayload` |

---
## Appendix

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
> enabled=false 상태에서 부분 변환 필요 시 외부 레이어(Controller advice 등)에서 선택적 케이스 변환 검토

### A.4 실패 정책(부분 실패 Batch) 권고
```kotlin
fun batch(items: List<String>): StandardResponse<BasePayload> = StandardResponse.build(callback = {
    val errors = mutableListOf<ErrorDetail>()
    items.forEach { v ->
        runCatching { insert(v) }.onFailure { t -> errors += ErrorDetail("ROW_FAIL", t.message ?: v) }
    }
    if (errors.isEmpty()) {
        StandardCallbackResult(StatusPayload("OK", "모두 성공"))
    } else {
        val code = if (errors.size < items.size) "PART_FAIL" else "FAIL"
        val msg = if (errors.size < items.size) "일부 실패" else "전체 실패"
        StandardCallbackResult(ErrorPayload(code, msg, errors), StandardStatus.FAILURE)
    }
})
```
> (변경) 성공만을 위한 `ErrorPayload(OK,...)` 사용 대신 `StatusPayload` 활용.

### A.5 참고 메모
- Callback 패턴 사용 시 확장 포인트(duration 수동 주입, 로깅, metrics hook) 주입 용이
- 단순 build(payload)는 직렬화 최소 & 순수 전달 목적에 적합
- 대규모 응답 성능 이슈 예상되면 먼저 케이스 변환 OFF 후 경계 레이어 선택 변환 검토
