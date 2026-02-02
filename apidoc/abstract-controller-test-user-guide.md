# AbstractControllerTest User Guide

본 문서는 `test-support` 모듈이 제공하는 `AbstractControllerTest`를 소비 프로젝트(또는 라이브러리 내부 하위 모듈) 테스트 코드에서 즉시 활용하기 위한 실용 가이드입니다.

## 1. 목적
- Spring MVC / REST Docs / MockMvc 기반 컨트롤러 테스트의 공통 부트스트랩 간소화
- 일관된 JSON 응답 검증 헬퍼 제공

## 2. 주요 기능 요약
| 기능 | 설명 |
|------|------|
| MockMvc 자동 주입 | `@AutoConfigureMockMvc` + SpringBootTest 환경 구성 |
| RestDocs 설정 | `@AutoConfigureRestDocs` + `RestDocumentationExtension` 등록 |
| JSON Path 검증 헬퍼 | `ResultActions.checkData()`, `checkSize()` |
| Pretty Print 헬퍼 | 테스트 중 가독성 높은 JSON 출력 |
| DTO QueryParam 변환 | `dtoToQueryParams()` – 컬렉션/배열 처리 지원 |

## 3. 의존성 추가
소비 프로젝트(예: Gradle Kotlin DSL):
```kotlin
dependencies {
    testImplementation("com.hunet.common:test-support:<version>")
}
```
(스냅샷 사용 시 `<version>-SNAPSHOT` 형식으로 조정)

## 4. 기본 사용 예
```kotlin
class UserApiTest : AbstractControllerTest() {
    @Test
    fun createUser() {
        // given
        val payload = mapOf("name" to "황용호")

        // when
        val result = mockMvc.post("/api/users") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(payload)
        }

        // then
        result.andExpect(status().isCreated)
            .checkData("name", "황용호")
    }
}
```
> 참고: Kotlin DSL MockMvc 확장 사용 시 `org.springframework.test.web.servlet.post` import 필요.

## 5. Spring Boot 구성 클래스(@SpringBootApplication) 필요성
`@SpringBootTest` 는 패키지 상위 경로에서 `@SpringBootApplication` 을 탐색합니다.
테스트 루트 패키지에 애플리케이션 클래스가 없다면 아래와 같이 하나 추가하세요.
```kotlin
@SpringBootApplication
class TestApplication
```
> 라이브러리 자체 테스트에서 이미 `TestSupportTestApplication` 과 유사한 부트스트랩 클래스를 제공하면 중복 생성 불필요.

## 6. JSON 검증 헬퍼 상세
```kotlin
result.checkData("user.id", 10)
result.checkSize("users", 3)
```
- `checkData(key, value)`: `$.payload.<key>` 또는 배열 인덱스 지원 (`[0].id` 형태 전달 시 가공 안 함)
- `checkSize(key, size)`: 컬렉션 길이 검증

## 7. DTO -> Query Param 변환 규칙
| 타입 | 처리 방식 |
|------|-----------|
| Iterable / Array | 각 요소를 같은 key 로 add |
| null | 무시 |
| 단일 값 | `toString()` 변환 |

예시:
```kotlin
data class SearchCond(val status: List<String>, val page: Int)
val params = dtoToQueryParams(SearchCond(listOf("ACTIVE", "HOLD"), 1))
// 결과: status=ACTIVE&status=HOLD&page=1
```

## 8. 확장/오버라이드 포인트
| 메서드 | 목적 | 예시 |
|--------|------|------|
| `dtoToParam` | 본문 직렬화 전략 교체 | YAML 직렬화 등 |
| `dtoToQueryParams` | DTO -> MultiValueMap 매핑 커스터마이즈 | 날짜 포맷 변환 |

## 9. Troubleshooting
| 증상 | 원인 | 해결 |
|------|------|------|
| SpringBootConfiguration 에러 | @SpringBootApplication 미탐색 | 테스트 패키지 루트에 정의 |
| JSON Path 실패 | payload 구조 상이 | 응답 본문 로그 후 key 재확인 |

## 10. 베스트 프랙티스 요약
- 각 테스트 독립성 확보: 데이터 준비 -> 검증 -> 정리 패턴
- 테스트 간 데이터 격리를 위한 적절한 설정 활용

## See also
- AbstractControllerTest Reference: [abstract-controller-test-reference.md](./abstract-controller-test-reference.md)
- 전체 문서 인덱스: [index.md](./index.md)

---
이 문서는 사용자 가이드(User Guide)이며, 클래스/메서드 세부 시그니처는 Reference 문서를 참고하세요.
