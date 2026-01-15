# AbstractControllerTest Reference Manual

`AbstractControllerTest`는 REST / MVC 통합 테스트 편의를 위해 제공되는 추상 베이스 클래스입니다. 본 문서는 사용자 가이드에서 다루지 않은 세부 시그니처와 확장 포인트를 기술합니다.

## 1. 선언부
```kotlin
@ActiveProfiles("local")
@SpringBootTest
@ExtendWith(RestDocumentationExtension::class, SpringExtension::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@AutoConfigureMockMvc(addFilters = true)
@AutoConfigureRestDocs
abstract class AbstractControllerTest {
    @Autowired lateinit var mockMvc: MockMvc

    val objectMapper: ObjectMapper
    val prettyPrinter: ObjectWriter

    fun ResultActions.checkData(key: String, data: Any): ResultActions
    fun ResultActions.checkSize(key: String, size: Int): ResultActions
    fun prettyPrint(json: String)
    open fun dtoToParam(value: Any): String
    open fun dtoToQueryParams(obj: Any): MultiValueMap<String, String>
    val ResultActions.respJson: String
}
```

## 2. 어노테이션 구성 의미
| 어노테이션 | 목적 |
|------------|------|
| `@SpringBootTest` | 전체 컨텍스트 로딩 (빈 자동 구성 포함) |
| `@AutoConfigureMockMvc` | MockMvc 자동 주입; 필터 활성화(addFilters=true) |
| `@AutoConfigureRestDocs` | Spring REST Docs 스니펫 생성 지원 |
| `@ExtendWith(RestDocumentationExtension, SpringExtension)` | JUnit5 확장 – RestDocs + Spring TestContext |
| `@ActiveProfiles("local")` | `application-local.*` 프로파일 활성 |
| `@TestMethodOrder(OrderAnnotation::class)` | 메서드 순서 지정 필요 시 지원 |

## 3. 필드 / 프로퍼티 설명
| 이름 | 타입 | 설명 |
|------|------|------|
| `mockMvc` | MockMvc | HTTP 요청/응답 시뮬레이션 핵심 객체 |
| `objectMapper` | ObjectMapper | Jackson (Kotlin module) 기본 mapper |
| `prettyPrinter` | ObjectWriter | `objectMapper.writerWithDefaultPrettyPrinter()` 결과 |
| `respJson` | String | 마지막 ResultActions 의 JSON 본문 |

## 4. JSON 검증 헬퍼
```kotlin
fun ResultActions.checkData(key: String, data: Any)
```
- `payload` 루트 기준 JSONPath 생성.
- key 가 빈 문자열이면 `$.payload` 그대로 사용.
- key 가 `[` 로 시작하면 배열 인덱스 패턴으로 간주하여 가공 생략.

```kotlin
fun ResultActions.checkSize(key: String, size: Int)
```
- `hasSize()` matcher 사용.

## 5. DTO 변환 유틸
```kotlin
open fun dtoToParam(value: Any): String = objectMapper.writeValueAsString(value)
```
오버라이드하여 YAML / XML 직렬화로 교체 가능.

```kotlin
open fun dtoToQueryParams(obj: Any): MultiValueMap<String, String>
```
리플렉션으로 public Kotlin 프로퍼티 순회:
- Iterable/Array → 동일 key 반복 추가
- null 무시
- 단일 값 `toString()` 변환

## 6. 확장 포인트
| 대상 | 방법 | 목적 |
|------|------|------|
| 직렬화 포맷 | override `dtoToParam` | JSON 외 포맷 전송 |
| QueryParam 규칙 | override `dtoToQueryParams` | 커스텀 포맷, 날짜 변환 |
| 공통 헤더 주입 | 클래스를 상속하여 `@BeforeEach` 에서 MockMvc request post-processor 설정 | 인증 토큰 삽입 |

## 7. 사용 패턴 예시 (Type-safe QueryParams)
```kotlin
data class FindCond(val status: List<String>, val page: Int)

class FindApiTest : AbstractControllerTest() {
    @Test
    fun search() {
        val params = dtoToQueryParams(FindCond(listOf("ACTIVE","HOLD"), 1))
        mockMvc.get("/api/resources") {
            params(params)
        }.andExpect(status().isOk)
            .checkSize("items", 2)
    }
}
```

## 9. Troubleshooting
| 증상 | 원인 | 해결 |
|------|------|------|
| `Unable to find a @SpringBootConfiguration` | 패키지 경로에 @SpringBootApplication 없음 | 테스트 루트에 TestApplication 정의 |
| JSON Path 매칭 실패 | payload 구조 변경 | 응답 본문 로그 + key 재확인 |
| 한글 깨짐 | MockMvc 인코딩 필터 설정 누락 | `CharacterEncodingFilter("UTF-8", true)` 추가 |

## 10. 베스트 프랙티스
1. 테스트간 격리: 적절한 데이터 정리 전략 사용
2. 실패 재현 쉬운 시딩: SQL 파일 commit → 리뷰 diff 용이
3. 공통 header/인증: 상속한 베이스 재정의 or RequestPostProcessor 추상화
4. REST Docs: 모든 성공 경로 최소 1 스니펫 생성 후 CI 에서 스니펫 검증

## 11. 향후 개선 아이디어
- DSL 기반 JSON Path 헬퍼 (타입 세이프)
- 자동 롤백 모드 옵션
- Multi-module test slicing (부분 컨텍스트 로딩)

## 12. 요약
`AbstractControllerTest` 는 테스트 부트스트랩/헬퍼를 표준화해 반복 코드를 제거하고, "준비된 데이터 → 호출 → 검증" 흐름을 단순화합니다.
