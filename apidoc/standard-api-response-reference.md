# Standard API Response Reference

`standard-api-response` 모듈이 제공하는 **표준 응답 구조, 핵심 타입, 어노테이션, 빌더/헬퍼, 역직렬화 규칙, 구성 프로퍼티 및 확장 포인트**를 체계적으로 정리한 공식 레퍼런스입니다. 

이 문서는 *사용 방법* 중심의 가이드가 아니라 **정확한 규칙과 타입 명세(source of truth)** 를 제공합니다. 처음 사용하는 경우 아래 교차 문서를 먼저 읽은 뒤 본 레퍼런스를 참고하세요.

## 교차 문서 (Cross References)
| 문서 | 목적 / 차이점                                                           |
|------|--------------------------------------------------------------------|
| [standard-api-specification.md](standard-api-specification.md) | **표준 API 규격**: request 규칙, response 필드 정의, 상태/에러 규칙, 리스트 처리 방식 정의  |
| [standard-api-response-library-guide.md](standard-api-response-library-guide.md) | 라이브러리 **사용자 가이드**: 표준 응답 생성, 역직렬화, 케이스 변환, 사용 패턴 심화 설명 |
| [standard-api-response-examples.md](standard-api-response-examples.md) | 실전 예시 모음: payload 구성, 페이지/커서 처리, 역직렬화, 케이스 변환, Alias/Canonical 등   |

---
## 1. 개요
표준 응답은 최상위에 다음 필드를 갖습니다:
`status / version / datetime / duration / payload`

`payload`는 **단일 도메인 DTO(BasePayload)** 또는 **표준 리스트 컨테이너(PageableList, IncrementalList)** 혹은 **이들을 조합한 사용자 정의 Payload**가 될 수 있습니다.

추가 기능:
- 케이스(필드명) 변환 (Camel ↔ Snake 등) + 필드 단위 예외(`@NoCaseTransform`)
- Jackson Alias(`@JsonProperty`, `@JsonAlias`) 수집 및 Canonical Key 기반 느슨한 역직렬화
- Duration 자동 측정/주입(`@InjectDuration` + Filter)
- 페이지/커서(Incremental) 기반 목록 포맷 및 헬퍼

---
## 2. 핵심 개념 요약 (Concept Map)
| 개념 | 요약 |
|------|------|
| BasePayload | 모든 payload의 Marker 인터페이스. 케이스/별칭/역직렬화 스캔 대상 루트 |
| StandardResponse | 통합 응답 래퍼 (status, version, datetime, duration, payload) |
| PageableList / IncrementalList | 페이지 / 커서(더보기) 기반 리스트 표준 구조 |
| PageListPayload / IncrementalListPayload | 복합 응답 구조를 위한 래퍼 (도메인 전용 래퍼로 대체 가능) |
| StandardCallbackResult | 콜백 빌더에서 payload / (선택적) status/version 반환용 구조체 |
| CaseConvention & @ResponseCase | 출력 JSON 필드명 케이스 정책 결정 |
| Alias 수집 | @JsonProperty/@JsonAlias + canonical 변환을 통한 느슨한 역직렬화 지원 |
| Duration 주입 | Filter + Advice 로 처리시간 측정 후 @InjectDuration 대상 필드 채움 |
| NullResponse | 의미적 placeholder 기본값 상수 모음 |

---
## 3. 코어 타입 레퍼런스
### 3.1 상태 및 공통 구조
```text
StandardStatus (enum): NONE, SUCCESS, FAILURE
OrderDirection (enum): ASC, DESC
OrderBy(field: String, direction: OrderDirection)
OrderInfo(sorted: Boolean?, by: List<OrderBy>)
PageInfo(size: Long, current: Long, total: Long)
Items<T>(total: Long?, current: Long?, list: List<T>)
BasePayload (interface)
BasePayloadImpl : BasePayload
```
<details><summary>구현상 세부 규칙</summary>

- StandardStatus 직렬화 값: SUCCESS -> "SUCCESS", FAILURE -> "FAILURE", NONE -> ""(빈 문자열). fromString 미매칭/빈 문자열 → SUCCESS.
- OrderDirection 직렬화 값(JSON): ASC -> "asc", DESC -> "desc" (소문자).
- StandardStatus.fromString(text): 매칭 실패 시 SUCCESS.
</details>

### 3.2 리스트 & 페이지 / 커서 구조
```text
PageableList<T>(page: PageInfo, order: OrderInfo?, items: Items<T>) : BasePayload
  companion:
    build(items, totalItems, pageSize, currentPage, orderInfo?)
    fromPage(page: Page<E>, mapper: (E) -> P)
    fromPageJava(page: Page<E>, mapper: Function<E,P>)
  itemsAsList: List<T> (@JsonIgnore)

CursorInfo<P>(field: String, start: P?, end: P?, expandable: Boolean?)
  companion:
    buildFromTotal(startIndex, howMany, totalItems, field, convertIndex?)
    buildFromTotalGeneric(startIndex, howMany, totalItems, field, convertIndex?)

IncrementalList<T,P>(cursor: CursorInfo<P>?, order: OrderInfo?, items: Items<T>) : BasePayload
  companion:
    build(items, startIndex, endIndex, totalItems, cursorField, expandable, orderInfo?)
    buildFromTotal(items, startIndex, howMany, totalItems, cursorField, orderInfo?, convertIndex?)
    buildFromTotalJava(items, startIndex, howMany, totalItems, cursorField, orderInfo?, convertIndex?)
  itemsAsList: List<T> (@JsonIgnore)
```

### 3.3 합성 Payload 헬퍼
```kotlin
open class PageListPayload<P: BasePayload>(var pageable: PageableList<P>) : BasePayload {
    companion object { inline fun <P: BasePayload, reified E> fromPage(page: Page<E>, noinline mapper: (E) -> P) }
}
open class IncrementalListPayload<P,I>(var incremental: IncrementalList<P,I>) : BasePayload
```

### 3.4 에러 구조
```kotlin
open class ErrorDetail(val code: String, val message: String)
open class ErrorPayload(
    val errors: MutableList<ErrorDetail> = mutableListOf(),
    val appendix: MutableMap<String, Any> = mutableMapOf()
) : BasePayload
```

### 3.5 Response / Callback / 직렬화 유틸
```text
StandardCallbackResult(payload: BasePayload, status?: StandardStatus, version?: String)
StandardResponse<T: BasePayload>(status?: StandardStatus = SUCCESS, version: String, datetime: Instant, duration?: Long, payload: T)
  getRealPayload<T>() : T?
  companion:
    build(payload)
    build(payload, status, version)
    build(payload, status, version, duration?)
    build(callback: () -> StandardCallbackResult) / buildWithCallback(Supplier)
    deserialize<T>(json: String)
    deserialize(json: String, payloadClass: Class<T>)
    deserialize(json: String, typeRef: TypeReference<T>)
Extension: StandardResponse<T>.toJson(case?, pretty?) : String
Utility: clearAliasCaches()
```

타입 별칭 (편의):
```kotlin
typealias PageablePayload<P> = PageableList<P>
typealias IncrementalPayload<P,I> = IncrementalList<P,I>
typealias DefaultResponse = StandardResponse<BasePayload>
```

---
## 4. 어노테이션 (Annotations)
| 어노테이션 | 대상 | 목적 | 주요 파라미터 / 기본값 |
|-----------|------|------|------------------------|
| `@InjectDuration` | FIELD / PROPERTY | 요청 처리 소요시간 주입 | `unit = MILLISECONDS` |
| `@NoCaseTransform` | PROPERTY | 케이스 변환 제외 | - |
| `@ResponseCase` | CLASS (Payload) | 기본 CaseConvention 지정 | `value = CaseConvention` |

---
## 5. Case Convention 적용 규칙
1. 적용 위치: `StandardApiResponseAdvice.beforeBodyWrite` (JSON 직렬화 직전)
2. 결정 우선순위 (높음→낮음):
   1) Query Parameter (`?case=...`) – `stdapi.response.case.query-override=true`
   2) Header (`X-Response-Case`) – `stdapi.response.case.header-override=true`
   3) Payload 클래스 `@ResponseCase`
   4) 설정 기본값 `stdapi.response.case.default`
   5) Fallback: `IDENTITY`
3. 변환 제외: `@NoCaseTransform` 필드 + 해당 alias/variant
4. 지원 값: `IDENTITY, SNAKE_CASE, SCREAMING_SNAKE_CASE, KEBAB_CASE, CAMEL_CASE, PASCAL_CASE`
5. 구현 요약: 토큰 분해 → 캐시 재조합(ConcurrentHashMap) → 변환
6. 최상위(`StandardResponse`의 `status`/`version`/`datetime`/`duration`) 필드는 case 변환/alias 치환 이후에도 canonical 기반 탐색만 사용되며, payload 내부 재귀(alias+canonical) 처리와 구분됨.
7. `toJson(case = ...)` 호출 시 케이스 결정 우선순위: 전달된 `case` 인자 > payload 클래스 `@ResponseCase` > `IDENTITY`. 이 메서드는 Spring Advice 경로를 거치지 않고 직접 직렬화된 문자열을 얻을 때 사용하며, 글로벌 기본 케이스(`stdapi.response.case.default`)는 적용되지 않습니다.
8. 캐시 구조 상세:
   - 내부 캐시: `Map<CaseConvention, ConcurrentHashMap<String,String>>`
   - 키: 원본 property/field 명(변환 대상) → 변환 후 문자열
   - 토큰화 정규식: `[A-Z]+(?=[A-Z][a-z0-9])|[A-Z]?[a-z0-9]+|[A-Z]+|[0-9]+`
     - 예: `UserID2Value` → tokens: user, id, 2, value → SNAKE_CASE: `user_id_2_value`
   - 무효화: 현재 라이브러리에서 case 캐시는 명시적 무효화 기능 없음 (runtime 영속). `clearAliasCaches()`는 alias 캐시만 초기화하며 case 캐시에 영향 없음.

---
## 6. Alias & Canonical Key 매칭 규칙
| 요소 | 설명 |
|------|------|
| `@JsonProperty` | 직렬화 시 대체 필드명 |
| `@JsonAlias` | 역직렬화 허용 추가 키 |
| Canonical Key | 영문/숫자 제외 제거 + 소문자화 (`user-id`, `user_id` → 동일) |
| Skip Case Keys | `@NoCaseTransform` 대상 및 모든 alias variant 집합 |

수집 흐름 (`collectGlobalAliasMaps`): 클래스 트리 재귀 순회 → (serializationMap / canonicalAliasToProp / skipCaseKeys) 구성

직렬화(`toJson`):
1. Jackson → JsonNode
2. Alias 적용 (propertyName→alias)
3. Case 변환 (skip-case 제외)
4. Pretty 옵션 시 pretty printer

역직렬화(`StandardResponse.deserialize`):
1. Kotlinx Json 1차 파싱 & canonical 키 매칭 (상위 필드: `status`/`version`/`datetime`/`duration`은 alias 재귀 없음)
2. payload JsonObject 에 alias + canonical 재귀 적용 후 Jackson 변환
3. 실패 → `ErrorPayload(code="E_DESERIALIZE_FAIL")`로 FAILURE 응답

### 6.1 Canonical 키 생성 규칙
| 입력 | filter(isLetterOrDigit) | lowercase | canonical 결과 |
|------|------------------------|-----------|----------------|
| `User-ID` | `UserID` | `userid` | `userid` |
| `user_id` | `user_id` → `userid` | `userid` | `userid` |
| `USERID` | `USERID` | `userid` | `userid` |
| `user-id_Extra` | `useridExtra` | `useridextra` | `useridextra` |

충돌 예시: `user-id`, `user_id`, `UserID` 모두 동일 canonical(`userid`) → 최초 발견 property 우선, 충돌 후보(`conflictCandidates`)에 기록.

### 6.2 SkipCaseKeys 처리 예시
`@NoCaseTransform`이 붙은 property `user_id`에 `@JsonAlias("user-id")`가 추가된 경우:
| 원본 요소 | 추가되는 skipCaseKeys 항목 |
|-----------|---------------------------|
| 기본명 `user_id` | `user_id`, `user-id` (underscore ↔ hyphen variant) |
| Alias `user-id` | `user-id`, `user_id` (역변환) |
| Canonical 비교 | 모두 동일 canonical(`userid`) → case 변환 제외 |

Before (SNAKE_CASE 지정):
```json
{ "user_id": 1, "user-id": 1 }
```
After (변환 기대):
```json
{ "user_id": 1, "user-id": 1 }
```
> SkipCaseKeys 는 변환 대상에서 제외되므로 alias/variant 형태 그대로 유지.

### 6.3 BEST_MATCH 간단 의사 결정
- 충돌 canonical 키 집합 내 후보 property들에 대해 실제 입력 JSON 키 소문자 값이 `propertyAliasLower[property]` 셋에 포함되는 첫 번째 property 선택.
- 예: 입력 JSON 키 `user-id-extra` / 후보: `userIdExtra`, `userId` → `userIdExtra`가 더 긴 alias 집합 포함하면 해당 property 우선.

---
## 7. 역직렬화 (Deserialization) 동작
| 메서드 | 용도 | 특징 |
|--------|------|------|
| `deserialize<T>(json)` | Kotlin reified | Canonical + alias 적용, 실패 시 ErrorPayload fallback |
| `deserialize(json, payloadClass)` | Java Class | 단일 클래스 타입 |
| `deserialize(json, typeRef)` | Java TypeReference | 제네릭 보존 (예: PageListPayload<UserPayload>) |
| `JsonObject.deserializePayload<P>()` | Payload 단독 | 상위 Response 없이 payload 만 역직렬화 |
| `BasePayload.deserializePayload(typeRef)` | Payload 단독 + 제네릭 | 내부 유틸 |

Kotlin 예시:
```kotlin
val r1 = StandardResponse.deserialize<MyPayload>(json)
val pageResp = StandardResponse.deserialize<PageableList<ItemPayload>>(jsonPage)
val incResp = StandardResponse.deserialize<IncrementalList<LogEntryPayload, Long>>(jsonInc)
```

Java 예시:
```java
StandardResponse<MyPayload> r1 = StandardResponse.deserialize(json, MyPayload.class);
StandardResponse<PageableList<ItemPayload>> r2 =
  StandardResponse.deserialize(jsonPage, new TypeReference<PageableList<ItemPayload>>(){ });
```

Edge Case 처리:
- payload 누락 → ErrorPayload 래핑
- datetime 파싱 실패 → `Instant.now()` 대입
- status 미인식 → `SUCCESS` fallback
- 제네릭 역직렬화 필요 → `TypeReference` 사용

### 7.1 Jackson ObjectMapper 설정
| 설정 항목 | 값 / 상태 | 의미                              |
|-----------|-----------|---------------------------------|
| Module | KotlinModule, JavaTimeModule | Kotlin data class/시간 타입 지원      |
| SerializationFeature.WRITE_DATES_AS_TIMESTAMPS | Disabled | 날짜를 timestamp 숫자 대신 ISO 문자열로 출력 |
| DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES | Disabled | 추가 필드 무시(전방 호환)                 |
| MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES | Enabled | 필드명 대소문자 무시 매칭                  |
| MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS | Enabled | Enum 값 대소문자 무시                  |
| Custom Any serializer (kotlinx) | AnyValueSerializer | appendix 등 맵/List 안전 역직렬화  |
| Unknown alias 충돌 처리 | WARN 또는 ERROR (모드 설정) | 충돌 시 로그 또는 즉시 예외                |

> Jackson은 상위 `StandardResponse` 직렬화/역직렬화(문자열 변환) 및 payload 실제 객체 매핑에 사용되며, 최상위 JSON 문자열 파싱은 `kotlinx.serialization(JsonConfig)`로 수행 후 payload 부분만 Jackson 적용.

---
## 8. Duration 자동 주입 메커니즘
| 구성 | 설명 |
|------|------|
| Filter | `RequestTimingFilter` – `System.nanoTime()` 시작값 Request Attribute 저장 |
| Advice | `StandardApiResponseAdvice` – `StandardResponse` 탐지 후 `@InjectDuration` 필드 주입 |
| 지원 타입 | Long / Int / Double / String / java.time.Duration / kotlin.time.Duration |
| 단위 지정 | `@InjectDuration(unit=TimeUnit.X)` |
| 활성화 | `stdapi.response.auto-duration-calculation.active=true` |
| 필터 순서 | `stdapi.response.auto-duration-calculation.filter-order` (기본 `Int.MIN_VALUE`) |
| 빌더 측정 | `StandardResponse.build()` 내부에서도 시작/종료 시각 측정 후 duration 계산 (필터 미사용 시 이 값이 최종) |
| 최종 결정 | 필터 활성 + Advice 동작 시 Advice 주입 값이 최종 duration으로 override될 수 있음 |

---
## 9. 빌더 & 팩토리 패턴 요약
| 목적 | API | 특징 |
|------|-----|------|
| 단순 성공 응답 | `StandardResponse.build(payload)` | status=SUCCESS, version 기본, duration 자동 측정 |
| 커스텀 상태/버전 | `build(payload, status, version[, duration])` | duration null → 자동 측정 |
| 콜백 빌드 | `buildWithCallback(Supplier<StandardCallbackResult>)` | payload + (선택) status/version 동시 제공 |
| 페이지 변환 | `PageableList.fromPage`, `PageListPayload.fromPage` | Spring `Page` → 표준 구조 |
| 커서 리스트 | `IncrementalList.buildFromTotal` | `start/howMany/total` 기반 커서 산출 |
| Java 호환 | `fromPageJava`, `buildFromTotalJava` | `Function` 기반 매퍼 |

`StandardCallbackResult` 를 통해 Service 경계에서 하나의 반환 타입을 유지하고 Controller 는 단일 빌더 패턴을 사용해 일관성을 확보합니다.

---
## 10. 페이지 / 커서 계산 규칙 (Algorithm)
| 항목 | 규칙 |
|------|------|
| 총 페이지 | `(totalItems + pageSize - 1) / pageSize` (`pageSize <= 0` → `totalItems` 1페이지 취급) |
| Cursor end | `start + (min(howMany, total - start) - 1)` (음수/0 보정) |
| expandable | `start + howMany < total` 일 때 true |
| Generic 커서 | `CursorInfo.buildFromTotalGeneric` – 커스텀 인덱스 타입 변환 람다 |

### 10.1 Cursor 계산 상세
| 단계 | 설명 | 코드 대응 |
|------|------|----------|
| 입력 보정 | `safeStart = if(startIndex >= 0) startIndex else 0` | `buildFromTotalGeneric` 내부 |
| 갯수 보정 | `safeHowMany = if(howMany >= 1) howMany else 1` | 동일 |
| total <= 0 또는 start >= total | start/end 모두 totalItems 로 반환, expandable=false | 조건 분기 |
| endIndex 계산 | `safeStart + (min(safeHowMany, totalItems - safeStart) - 1)` (결과 < safeStart 시 0 보정) | min & minus 로직 |
| expandable 판단 | `safeStart + safeHowMany < totalItems` | 마지막 줄 |

Edge Case 표:
| 케이스 | 입력(start, howMany, total) | 결과 start | 결과 end | expandable |
|--------|---------------------------|------------|----------|------------|
| 정상 | (10, 5, 100) | 10 | 14 | true |
| howMany == 0 | (10, 0, 100) | 10 | 10 | true (0→1 보정 후) |
| start < 0 | (-3, 5, 50) | 0 | 4 | true |
| start > total | (60, 5, 50) | 50 | 50 | false |
| total == 0 | (0, 5, 0) | 0 | 0 | false |
| near end | (48, 10, 50) | 48 | 49 | false |

### 10.2 PageInfo 총 페이지 계산 상세
- 공식: `(totalItems + pageSize - 1) / pageSize` 단, `pageSize <= 0` → `pageSize = 1`로 간주.
- 예: totalItems = 101, pageSize = 10 → 11 페이지.
- page.number (0-base) + 1 → current 페이지.

---
## 11. 캐시 & 성능 (Case / Alias)
| 캐시 대상 | 구현 | 무효화 |
|----------|------|--------|
| Case 변환 결과 | 내부 캐시(`Map<CaseConvention, ConcurrentHashMap<String,String>>`) | 런타임 유지 |
| Alias 메타 | `AliasCache` (KClass → GlobalAliasMaps) | `clearAliasCaches()` 호출 |

### 11.1 Case 캐시 상세
| 항목 | 값 |
|------|----|
| 자료구조 | `Map<CaseConvention, ConcurrentHashMap<String,String>>` |
| 저장 키 | 원본 key (property 명 또는 이미 alias 적용된 key) |
| 저장 값 | 변환된 target key 문자열 |
| 토큰화 | 정규식 기반 split 후 케이스 규칙 재조합 |
| 무효화 | 없음 (JVM 생명주기 동안 유지) |
| 영향 범위 | 직렬화 시 반복 key 변환 비용 절감 |
| Alias 캐시 연계 | Case 캐시는 alias 적용 결과를 입력으로 받을 뿐 clearAliasCaches() 호출로 초기화되지 않음 |

> 대량 다양한 key 사용 시 최초 변환 비용 발생, 이후 메모리 상주. 메모리 누수 방지 위해 key 폭발 상황(동적 생성 필드명) 지양.

GlobalAliasMaps 구성:
- `serializationMap`: propertyName → 직렬화 alias
- `canonicalAliasToProp`: canonical(alias) → propertyName
- `skipCaseKeys`: 변환 제외 키 집합
- `conflictCandidates`: canonical 충돌 발생 시 후보 property 집합 (BEST_MATCH 전략 선택 참고)
- `propertyAliasLower`: property 별 alias(소문자) 전체 세트 – BEST_MATCH 실제 key 비교에 활용

---
## 12. 구성 프로퍼티 Reference
| Property | 기본값 | 설명 |
|----------|--------|------|
| `stdapi.response.case.enabled` | true | 케이스 변환 on/off |
| `stdapi.response.case.default` | IDENTITY | 글로벌 기본 케이스 |
| `stdapi.response.case.query-override` | true | Query Param 우선 허용 |
| `stdapi.response.case.header-override` | true | Header 우선 허용 |
| `stdapi.response.case.query-param` | case | 케이스 지정 query 파라미터명 |
| `stdapi.response.case.header-name` | X-Response-Case | 케이스 지정 헤더명 |
| `stdapi.response.auto-duration-calculation.active` | (없음/false) | true 시 Filter 자동 등록 |
| `stdapi.response.auto-duration-calculation.filter-order` | Int.MIN_VALUE | Filter 우선순위 |

### 12.1 Alias 충돌 제어
| 설정 | 환경변수 / 시스템속성 | 값 | 설명 |
|------|----------------------|----|------|
| 모드 | `STDAPI_RESPONSE_ALIAS_CONFLICT_MODE` / `stdapi.response.alias-conflict-mode` | WARN, ERROR | ERROR: 충돌 시 즉시 예외 발생 |
| 해결전략 | `STDAPI_RESPONSE_ALIAS_CONFLICT_RESOLUTION` / `stdapi.response.alias-conflict-resolution` | FIRST_WIN, BEST_MATCH | BEST_MATCH: 입력 실제 key와 alias 후보를 소문자 집합 비교하여 최적 후보 선택 |

- FIRST_WIN: 최초 매핑 property 유지
- BEST_MATCH: 충돌 시 `propertyAliasLower` 활용하여 실제 JSON key와 가장 일치(포함)하는 property 선택
- WARN 모드: 충돌 로그 경고 후 최초 정의 유지 / ERROR 모드: `IllegalStateException` throw

---
## 13. 확장 & 커스터마이징 포인트
| 영역 | 방법 | 비고 |
|------|------|------|
| 응답 구조 확장 | BasePayload 구현 DTO 사용 | 케이스/alias 자동 적용 |
| 필드명 보호 | `@NoCaseTransform` | alias variant 포함 보호 |
| 기본 케이스 강제 | `@ResponseCase(SNAKE_CASE)` | query/header override 가 우선 |
| Duration 비활성 | 설정 비활성 또는 수동 duration 지정 | `build(..., duration=값)` |
| 커서 인덱스 커스터마이징 | `buildFromTotalGeneric` | UUID/날짜 등 변환 |
| Alias 캐시 초기화 | `clearAliasCaches()` | 구조 변경 후 재스캔 |
| 강제 직렬화 케이스 | `response.toJson(case = ...)` | 클래스 @ResponseCase 무시하고 인자 우선 |

---
## 14. NullResponse 상수
| 항목 | 값 | 용도 |
|------|----|------|
| STRING | "null" | 빈 문자열 대신 placeholder |
| INT / LONG / DOUBLE / FLOAT | 0 / 0L / 0.0 / 0F | 숫자 기본 sentinel |
| DATE | LocalDate.MIN | 날짜 sentinel |
| YEAR | 9999 | 특수 연도 placeholder |
| YN | YnFlag.Y | 도메인 YnFlag 기본 |
| DURATION | Duration.ZERO | 기간 기본 |
| BIG_DECIMAL | BigDecimal.ZERO | 금액/정밀 수치 기본 |

---
## 15. 에러 처리 시나리오 (Fallback 규칙)
| 상황 | 결과 |
|------|------|
| 역직렬화 payload 구조 오류 | FAILURE + ErrorPayload(E_DESERIALIZE_FAIL) |
| status 필드 미인식 | SUCCESS fallback |
| datetime 형식 오류 | Instant.now() 대입 |
| duration 필드 누락 | 0L 기본 |

---
## [Appendix] 현업 적용 패턴 & 샘플
> 실 프로젝트 관찰 기반 패턴. 의사결정 참고용 (가이드 문서의 "실 서비스 적용 패턴" 섹션과 상호 보완).

### 계층 패턴 개요
| 구분 | 패턴 | 비고 |
|------|------|------|
| Controller → Service → Response | Controller: `StandardResponse.build(callback={ service.method() })` / Service: `StandardCallbackResult` 반환 | status/version 대부분 기본값(SUCCESS/1.0 가정) |
| 비동기 즉시 응답 | 긴 작업 비동기 실행 후 즉시 `CudResultPayload` 성공 응답 | JobId 등 후속 추적 |
| Error 처리 | 전역 `@ControllerAdvice` → 예외를 `ErrorPayload` 로 포장 | 성공 흐름/실패 흐름 분리 |
| 페이지 응답 | Service Page 결과 → DTO 매핑 → `PageListPayload` (또는 도메인 전용 래퍼) | 일관 인터페이스 |
| CallbackResult 통일 | Service 경계: 성공 시 payload, 실패 시 예외 throw | Controller 단순화 |
| Duration 자동 주입 | `auto-duration-calculation.active=true` + callback 빌드 | 측정 로직 분리 |
| 빌더 선택 | 기본: 콜백 빌드 / 예외 Advice 경로: 직접 `build(payload=ErrorPayload)` | 일관 패턴 |

### 16.2 Controller 샘플
```kotlin
@GetMapping("/manager/companyCode/{companyCode}/{pageNo}")
fun getManagerSessionByCompanyCode(
    @PathVariable companyCode: String,
    @PathVariable pageNo: Long,
    @RequestParam pageSize: Long = 20
): StandardResponse<PagedManagerSessionPayload> =
    StandardResponse.build(callback = { managerService.getManagerSessionList(companyCode, pageNo, pageSize) })
```

Service:
```kotlin
fun getManagerSessionList(companyCode: String, pageNo: Long, pageSize: Long): StandardCallbackResult {
    val page = repository.findManagerSessionListByCompanyCode(companyCode, pageNo, pageSize)
    val mapped = page.content.map { /* DTO 매핑 */ }
    return StandardCallbackResult(
        payload = PagedManagerSessionPayload(
            managerSessionList = mapped,
            totalItems = page.totalElements,
            pageSize = pageSize,
            currentPage = pageNo,
            orderInfo = OrderInfo(sorted = true, by = listOf(OrderBy("id", OrderDirection.DESC)))
        )
    )
}
```

### 16.3 비동기 + 즉시 응답
```kotlin
@PostMapping("/report/generateReport")
fun generateReport(@RequestBody req: ForceGenerateReportRequest): StandardResponse<CudResultPayload> {
    taskExecutor.execute { reportService.generateReport(req.questionnaireSessionId, req.subjectIds) }
    return StandardResponse.build(callback = {
        StandardCallbackResult(
            payload = CudResultPayload(
                operation = CudOperation.INSERT,
                status = "SUCCESS",
                message = "실행 되었습니다."
            )
        )
    })
}
```

### 16.4 공통 예외 처리
```kotlin
@ExceptionHandler(NoDataException::class)
fun handle(e: NoDataException) = ResponseEntity(
    StandardResponse.build(
        payload = ErrorPayload(code = e.code, message = e.message ?: "No data.", appendix = e.appendix),
        status = StandardStatus.FAILURE
    ), HttpStatus.BAD_REQUEST
)
```
> 성공 로직과 실패 로직을 분리하여 Controller는 일관된 성공 경로만 표현.
