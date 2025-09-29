# Standard API Response Reference Manual

`standard-api-response` 모듈이 제공하는 모든 핵심 타입, 어노테이션, 빌더/헬퍼, 구성 속성(property) 및 동작 규칙을 체계적으로 정리한 레퍼런스 문서입니다. <br>
사용 시나리오는 사용자 가이드(`standard-api-response-library-guide.md`)와 예제 카탈로그(`standard-api-response-examples.md`), 전체 규격은 `standard-api-specification.md` 를 참고하세요.

## 0. 개요
표준 응답 구조는 최상위에 `status / version / datetime / duration / payload` 필드를 두고, `payload` 는 임의의 비즈니스 DTO(`BasePayload`) 또는 표준 리스트 컨테이너(`PageableList`, `IncrementalList`) 혹은 그것들을 조합한 사용자 정의 Payload 로 구성됩니다.

케이스(필드명 표기) 변환, Alias(@JsonProperty/@JsonAlias), Duration 자동 주입, Canonical Key 기반 느슨한 역직렬화, Cursor/페이지 기반 목록 헬퍼를 제공합니다.

---
## 1. 주요 공개 타입 선언 요약
```kotlin
// 상태 / 정렬
enum class StandardStatus { NONE, SUCCESS, FAILURE }
enum class OrderDirection { ASC, DESC }

// 정렬 & 페이지/커서 메타
class OrderBy(val field: String, val direction: OrderDirection)
class OrderInfo(val sorted: Boolean?, val by: List<OrderBy>)
class PageInfo(val size: Long, val current: Long, val total: Long) {
    companion object { fun calcTotalPages(totalItems: Long, pageSize: Long): Long }
}

// 공통 리스트 아이템 래퍼
data class Items<T>(val total: Long?, val current: Long?, val list: List<T>)

// BasePayload Marker & 구현체
interface BasePayload { /* + companion deserialize helpers */ }
open class BasePayloadImpl : BasePayload

// 에러 구조
open class ErrorDetail(val code: String, val message: String)
open class ErrorPayload(
    val errors: MutableList<ErrorDetail> = mutableListOf(),
    val appendix: MutableMap<String, Any> = mutableMapOf()
) : BasePayload

// 페이지 리스트
open class PageableList<T>(val page: PageInfo, val order: OrderInfo?, val items: Items<T>) : BasePayload {
    companion object {
        fun <T> build(items: List<T>, totalItems: Long, pageSize: Long, currentPage: Long, orderInfo: OrderInfo?): PageableList<T>
        inline fun <P: BasePayload, reified E> fromPage(page: Page<E>, mapper: (E) -> P): PageableList<P>
        fun <P: BasePayload, E> fromPageJava(page: Page<E>, mapper: Function<E,P>): PageableList<P>
    }
    val itemsAsList: List<T> // @JsonIgnore
}

// 커서 메타
class CursorInfo<P>(val field: String, val start: P?, val end: P?, val expandable: Boolean?) {
    companion object {
        inline fun <reified P> buildFromTotal(...): CursorInfo<P>
        fun <P> buildFromTotalGeneric(...): CursorInfo<P>
    }
}

// Incremental(더보기) 리스트
class IncrementalList<T,P>(val cursor: CursorInfo<P>?, val order: OrderInfo?, val items: Items<T>) : BasePayload {
    companion object {
        fun <T,P> build(...): IncrementalList<T,P>
        inline fun <T, reified P> buildFromTotal(...): IncrementalList<T,P>
        fun <T,P> buildFromTotalJava(...): IncrementalList<T,P>
    }
    val itemsAsList: List<T> // @JsonIgnore
}

// 합성 Payload Helper 타입 (PageListPayload / IncrementalListPayload)
open class PageListPayload<P: BasePayload>(var pageable: PageableList<P>) : BasePayload {
    companion object { inline fun <P: BasePayload, reified E> fromPage(page: Page<E>, noinline mapper: (E) -> P) }
}
open class IncrementalListPayload<P,I>(var incremental: IncrementalList<P,I>) : BasePayload

data class StandardCallbackResult(val payload: BasePayload, val status: StandardStatus? = null, val version: String? = null)

data class StandardResponse<T: BasePayload>(
    val status: StandardStatus? = StandardStatus.SUCCESS,
    val version: String,
    val datetime: Instant,
    @InjectDuration val duration: Long? = 0L,
    val payload: T
) {
    inline fun <reified T: BasePayload> getRealPayload(): T?
    companion object {
        fun <T: BasePayload> build(...): StandardResponse<T>
        fun <T: BasePayload> build(payload: T): StandardResponse<T>
        fun <T: BasePayload> build(payload: T, status: StandardStatus, version: String): StandardResponse<T>
        fun <T: BasePayload> build(payload: T, status: StandardStatus, version: String, duration: Long?): StandardResponse<T>
        fun <T: BasePayload> buildWithCallback(callback: Supplier<StandardCallbackResult>): StandardResponse<T>
        fun <T: BasePayload> buildWithCallback(callback: Supplier<StandardCallbackResult>, status: StandardStatus, version: String, duration: Long?): StandardResponse<T>
        inline fun <reified T: BasePayload> deserialize(json: String): StandardResponse<T>
        fun <T: BasePayload> deserialize(json: String, payloadClass: Class<T>): StandardResponse<T>
    }
}

// 확장 & 유틸
fun <T: BasePayload> StandardResponse<T>.toJson(case: CaseConvention? = null, pretty: Boolean = false): String
fun clearAliasCaches()

// Case & 직렬화 관련
enum class CaseConvention { IDENTITY, SNAKE_CASE, SCREAMING_SNAKE_CASE, KEBAB_CASE, CAMEL_CASE, PASCAL_CASE }
@Target(AnnotationTarget.PROPERTY) annotation class NoCaseTransform
@Target(AnnotationTarget.CLASS) annotation class ResponseCase(val value: CaseConvention)

// Duration 주입 & Filter
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY)
annotation class InjectDuration(val unit: TimeUnit = TimeUnit.MILLISECONDS)
class RequestTimingFilter : OncePerRequestFilter { /* 요청 시작 nano 기록 */ }

// NullResponse (기본값 헬퍼)
object NullResponse { STRING, INT, LONG, DOUBLE, FLOAT, DATE, YEAR, YN, DURATION, BIG_DECIMAL }

// 타입 별칭
typealias PageablePayload<P> = PageableList<P>
typealias IncrementalPayload<P,I> = IncrementalList<P,I>
typealias DefaultResponse = StandardResponse<BasePayload>
```

---
## 2. 어노테이션 상세
| 어노테이션                                           | 적용 대상 | 목적 | 주요 파라미터 / 기본값 |
|-------------------------------------------------|-----------|------|------------------------|
| `@InjectDuration`                               | FIELD / PROPERTY | 요청 처리 소요시간 주입 대상 표시 | `unit = MILLISECONDS` (TimeUnit) |
| `@NoCaseTransform`                              | PROPERTY | 케이스 변환 시 이 필드명 그대로 유지 | - |
| `@ResponseCase`                                 | CLASS (Payload) | 해당 Payload 직렬화 시 기본 CaseConvention 명시 | `value = CaseConvention` |
| `@EnumConstant`*                                | ENUM | 문서화/스키마(enum 설명) 목적 (다른 모듈) | - |
| `@Sequence`*                                    | FIELD | 문서/스키마 출력순 지정 (다른 모듈) | 숫자 |
| `@SwaggerDescription`* , `@SwaggerDescribable`* | CLASS/FIELD | OpenAPI Schema 커스텀 설명 (문서 모듈) | - |

(*) `std-api-documentation` 모듈에서 제공 – 레퍼런스 편의상 목록화.

---
## 3. Case Convention 적용 규칙
1. 처리 위치: `StandardApiResponseAdvice.beforeBodyWrite` 에서 JSON 변환 직전 전역 적용
2. 결정 우선순위 (상위 → 하위):
   1) Query Parameter (`?case=...`) – `standard-api-response.case.query-override=true` 일 때
   2) Header (`X-Response-Case`) – `standard-api-response.case.header-override=true` 일 때
   3) Payload 클래스의 `@ResponseCase`
   4) 설정 기본값 `standard-api-response.case.default`
   5) Fallback: `IDENTITY`
3. 변환 제외 키: `@NoCaseTransform` 붙은 필드(및 그 별칭 alias) + alias variant(언더스코어/하이픈 상호 치환)
4. 지원 값: `IDENTITY / SNAKE_CASE / SCREAMING_SNAKE_CASE / KEBAB_CASE / CAMEL_CASE / PASCAL_CASE`
5. 내부 구현: 정규화된 토큰 분해 → 캐시(`ConcurrentHashMap`) → 재조합 (성능 최적화)

---
## 4. Alias & Canonical Key 매칭
| 요소 | 설명 |
|------|------|
| `@JsonProperty` | 직렬화(출력) 시 실제 필드명 교체 (serialization map) |
| `@JsonAlias` | 역직렬화(입력) 시 허용되는 추가 키 |
| Canonical Key | 영문/숫자 이외 제거 + 소문자화하여 충돌 완화 (`user-id`, `user_id` → 동일) |
| Skip Case Keys | `@NoCaseTransform` 대상 + 해당 alias 및 variant 를 집합으로 캐시 |

수집 과정(`collectGlobalAliasMaps`):
- 클래스 트리(필드/생성자 파라미터/중첩 Payload) 재귀 순회
- propertyName → serialization alias 맵, canonical(alias variants) → propertyName 맵, skip-case 키 집합 구성

직렬화 흐름(`toJson`):
1. Jackson 으로 객체 → JsonNode 변환
2. Alias map 적용 (propertyName => alias)
3. Case 변환 (skip-case 제외)
4. Pretty 옵션 시 `writerWithDefaultPrettyPrinter`

역직렬화 흐름(`StandardResponse.deserialize`):
1. Kotlinx Json 파싱 → 최상위 `status/version/datetime/duration/payload` canonical 탐색
2. `payload` 영역을 Jackson + alias 재귀 적용 후 대상 Payload 변환
3. 실패 시 `ErrorPayload(code="E_DESERIALIZE_FAIL", message=...)` 를 payload 로 감싼 FAILURE 응답 반환

---
## 5. Duration 자동 주입
| 구성 | 설명 |
|------|------|
| 필터 | `RequestTimingFilter` – `System.nanoTime()` 시작 값을 Request Attribute 저장 |
| Advice | `StandardApiResponseAdvice` – body 가 `StandardResponse` 이고, 필드(혹은 data class copy) 중 `@InjectDuration` 발견 시 경과시간 주입 |
| 변환 단위 | `@InjectDuration(unit=TimeUnit.X)` – Long/Int/Double/String/`java.time.Duration`/`kotlin.time.Duration` 지원 |
| 활성화 | (필터 등록) `standard-api-response.auto-duration-calculation.active=true` + AutoConfiguration 조건 만족 시 |
| 필터 순서 | `standard-api-response.auto-duration-calculation.filter-order` (기본 `Int.MIN_VALUE`) |

주의: `StandardResponse` 의 `duration` 필드는 기본 `@InjectDuration` 이며, 별도 사용자 Payload 의 필드도 동일 어노테이션 부여 가능 (mutable 이거나 data class copy 경로 필요).

---
## 6. 표준 빌더 & 팩토리 패턴
| 목적 | API | 특징 |
|------|-----|------|
| 단순 성공 응답 | `StandardResponse.build(payload)` | status=SUCCESS, version=1.0, duration=실행 측정 |
| 커스텀 상태/버전 | `StandardResponse.build(payload, status, version[, duration])` | duration null 시 내부 측정 |
| 콜백 기반 (지연/트랜잭션 내 로직) | `StandardResponse.buildWithCallback(Supplier<StandardCallbackResult>)` | 콜백에서 payload/status/version 동시 제공 |
| 페이지 변환 | `PageableList.fromPage(page)`, `PageListPayload.fromPage(page, mapper)` | Spring `Page` → 표준 구조 |
| 커서 리스트 | `IncrementalList.buildFromTotal(...)` | start/howMany/total 로 커서 범위 산출 |
| Java 친화 | `fromPageJava`, `buildFromTotalJava` | `Function` / `BiFunction` 사용 |

`StandardCallbackResult` 는 빌더가 payload 미리 알 수 없을 때 하나의 래퍼로 결과를 반환하기 위한 구조체입니다.

---
## 7. 역직렬화 (Deserialization)
| 메서드 | 용도 | 동작 |
|--------|------|------|
| `StandardResponse.deserialize<T>(json)` | Kotlin reified | Canonical 키 매칭 + Payload 역직렬화 실패 시 ErrorPayload 래핑 |
| `StandardResponse.deserialize(json, payloadClass)` | Java 용 | 위와 동일 |
| `JsonObject.deserializePayload<P>()` | Payload 만 별도 추출 | 내부 canonical + alias 적용 후 Jackson 역직렬화 |

Edge Cases:
- payload 누락 → 예외 → ErrorPayload 래핑
- datetime 포맷 불일치 → 현재시각 대체
- status 알 수 없음 → SUCCESS fallback

---
## 8. Page / Incremental 계산 로직
| 항목 | 규칙 |
|------|------|
| Page 수 계산 | `(totalItems + pageSize - 1) / pageSize` (pageSize ≤0 인 경우 totalItems 사용) |
| Cursor end 계산 | `start + (min(howMany, total-start) - 1)` (음수/0 보정 후) |
| expandable | `start + howMany < total` 일 때 true |
| BuildFromTotal Generic | `CursorInfo.buildFromTotalGeneric` – 커스텀 인덱스 타입 변환 람다 허용 |

---
## 9. 케이스/별칭 캐시 & 성능
| 캐시 대상 | 구현 | 무효화 |
|-----------|------|--------|
| Case 변환 키 | `ConcurrentHashMap<CaseConvention, String>` | 런타임 지속 |
| Alias 수집 | `AliasCache` (KClass -> GlobalAliasMaps) | `clearAliasCaches()` 호출 시 리셋 |

GlobalAliasMaps 구성 요소:
- `serializationMap`: propertyName → 직렬화 alias
- `canonicalAliasToProp`: canonical(alias) → propertyName
- `skipCaseKeys`: 케이스 변환 제외 키 세트

---
## 10. 구성(설정) 프로퍼티 Reference
| Property | 기본값 | 설명 |
|----------|--------|------|
| `standard-api-response.case.enabled` | true | 케이스 변환 기능 전체 on/off |
| `standard-api-response.case.default` | IDENTITY | 글로벌 기본 CaseConvention |
| `standard-api-response.case.query-override` | true | Query Param 우선 적용 허용 |
| `standard-api-response.case.header-override` | true | Header 우선 적용 허용 |
| `standard-api-response.case.query-param` | case | 케이스 지정 query 파라미터명 |
| `standard-api-response.case.header-name` | X-Response-Case | 케이스 지정 헤더명 |
| `standard-api-response.auto-duration-calculation.active` | (없음/false) | true 시 RequestTimingFilter 자동 등록 |
| `standard-api-response.auto-duration-calculation.filter-order` | Int.MIN_VALUE | 필터 체인 우선순위 |

주의: 예제 문서에 `standard-api-response.default-case-convention` 식 표현이 있을 수 있으나 실제 코드 기준 키는 `standard-api-response.case.default` 입니다 (동기화 필요).

---
## 11. 확장 & 커스터마이징 포인트
| 영역 | 방법 | 비고 |
|------|------|------|
| 기본 응답 구조 확장 | `payload` 로 자체 DTO 구현 (BasePayload 구현/상속) | 케이스/alias 규칙 자동 적용 |
| 필드명 보호 | 민감/외부 계약 필드에 `@NoCaseTransform` | alias variant 포함 보존 |
| 기본 케이스 강제 | DTO 클래스에 `@ResponseCase(CaseConvention.SNAKE_CASE)` | query/header override 가 우선 순위 높음 |
| Duration 측정 비활성 | 프로퍼티 제거 또는 `auto-duration-calculation.active=false` | 수동 측정 시 `build(..., duration=값)` |
| 커서 인덱스 타입 커스터마이징 | `buildFromTotalGeneric` / `buildFromTotal` 의 `convertIndex` 람다 | UUID/날짜 변환 등 |
| Alias 캐시 초기화 | 장애/리플렉션 구조 변경 시 `clearAliasCaches()` 호출 | 재스캔 비용 발생 |
| 직렬화 시 강제 케이스 | `response.toJson(case = CaseConvention.SNAKE_CASE)` | payload @ResponseCase 무시하고 파라미터 우선 |

---
## 12. NullResponse 상수
| 항목 | 값 | 용도 |
|------|----|------|
| STRING | "null" | 의미상 빈 문자열 대신 명시적 placeholder |
| INT / LONG / DOUBLE / FLOAT | 0 / 0L / 0.0 / 0F | 숫자 기본값 표현 |
| DATE | LocalDate.MIN | 날짜 기본 sentinel |
| YEAR | 9999 | 특수 연도 값 placeholder |
| YN | YnFlag.Y | 도메인 YnFlag 기본 |
| DURATION | Duration.ZERO | 기간 기본 |
| BIG_DECIMAL | BigDecimal.ZERO | 금액/정밀 수치 기본 |

---
## 13. 에러 처리 시나리오
| 상황 | 결과 |
|------|------|
| 역직렬화 중 payload 구조 오류 | FAILURE + ErrorPayload(E_DESERIALIZE_FAIL) |
| status 필드 인식 불가 | SUCCESS 로 fallback |
| datetime 형식 오류 | 현재시각(Instant.now) 대입 |
| duration 필드 누락 | 0L 기본 |
| `build()` 호출 시 payload null & callback null | 예외 throw |

---
## 14. 예제 빠른 참조
| 사용 목적 | 예제 문서 섹션 |
|-----------|----------------|
| 단일 DTO 성공 | examples 1.1 / 1.2 |
| pageable 포함 | examples 2.1 |
| incremental 포함 | examples 2.2 |
| 다중 리스트 복합 | examples 4 |
| 콤포지트/중첩 | examples 5 |
| 직접 리스트 payload | examples 3 |

---
## 15. 성능 & 주의 사항
1. Alias/Case 변환은 캐시 기반 – 런타임 클래스 구조 변동(리플렉션 기반 코드 생성) 시 `clearAliasCaches()` 고려
2. `@InjectDuration` 은 mutable property setter 또는 data class copy 경로를 사용 – 불변(non-data) + private setter 인 경우 무시
3. Case 변환은 JSON 트리 순회(O(n)) – 대규모 응답에서 필요시 `case.enabled=false` 로 비활성화 후 API 게이트웨이층에서 변환 고려
4. Canonical 매칭은 필드 수 만큼 선형 검색(ObjectNode iteration) – 상위 필드 제한적이므로 실무 영향 적음

---
## 16. 사용 체크리스트
- [ ] `BasePayload` 구현 완료
- [ ] 필요 시 `@ResponseCase` 지정
- [ ] API Module `application.yml` 에 case/duration 속성 설정
- [ ] Pageable/Incremental 응답: 빌더(`build`, `buildFromTotal`) 사용해 계산 오류 제거
- [ ] 역직렬화 시 Generic Type 주의 (Kotlin reified or Class<T> 제공)
- [ ] 다중 alias 필요 시 @JsonAlias + @NoCaseTransform (필요시) 조합 고려

---
## 17. Cross References
| 문서 | 설명 |
|------|------|
| [standard-api-response-library-guide.md](./standard-api-response-library-guide.md) | 실사용 가이드 (설정, 빌더 활용 흐름) |
| [standard-api-response-examples.md](./standard-api-response-examples.md) | 다형/목록/케이스/alias 예제 카탈로그 |
| [standard-api-specification.md](./standard-api-specification.md) | 표준 응답 & 요청 규격 정의 |
| [abstract-controller-test-user-guide.md](./abstract-controller-test-user-guide.md) | 컨트롤러 테스트 User Guide (표준 응답 검증 헬퍼) |
| [abstract-controller-test-reference.md](./abstract-controller-test-reference.md) | 테스트 베이스 클래스 상세 Reference |

---
## 18. 요약
`standard-api-response` 모듈은 "표준화된 상위 메타 + 유연한 payload" 구조를 제공하면서, 케이스/alias/리스트/커서/시간 측정/역직렬화 관용성을 한 번에 해결합니다.<br> 
본 Reference 는 시그니처와 규칙을 빠르게 회고하기 위한 용도이며, 구체 사용 흐름은 가이드 & 예제 문서를 병행 참고하세요.

