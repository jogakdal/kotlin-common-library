# AbstractControllerTest User Guide

본 문서는 `test-support` 모듈이 제공하는 `AbstractControllerTest`를 소비 프로젝트(또는 라이브러리 내부 하위 모듈) 테스트 코드에서 즉시 활용하기 위한 실용 가이드입니다.

## 1. 목적
- Spring MVC / REST Docs / MockMvc 기반 컨트롤러 테스트의 공통 부트스트랩 간소화
- 선택적으로 DataFeed(Data 시딩 도구) 주입
- 일관된 JSON 응답 검증 헬퍼 제공

## 2. 주요 기능 요약
| 기능 | 설명 |
|------|------|
| MockMvc 자동 주입 | `@AutoConfigureMockMvc` + SpringBootTest 환경 구성 |
| RestDocs 설정 | `@AutoConfigureRestDocs` + `RestDocumentationExtension` 등록 |
| JSON Path 검증 헬퍼 | `ResultActions.checkData()`, `checkSize()` |
| Pretty Print 헬퍼 | 테스트 중 가독성 높은 JSON 출력 |
| DTO QueryParam 변환 | `dtoToQueryParams()` – 컬렉션/배열 처리 지원 |
| 선택 DataFeed 주입 | JPA 활성 시 DataFeed 자동 구성 (비활성 시 null 허용) |

## 3. 의존성 추가
소비 프로젝트(예: Gradle Kotlin DSL):
```kotlin
dependencies {
    testImplementation("com.hunet.common_library:test-support:<version>")
    testImplementation("com.hunet.common_library:common-core:<version>") // DataFeed 포함 필요 시
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

## 5. DataFeed 연계 (선택)
JPA 설정 + `common-core` 의존성이 존재하면 `DataFeed`가 주입됩니다.
```kotlin
class DataSeedingApiTest : AbstractControllerTest() {
    @BeforeEach
    fun seed() {
        dataFeed?.executeUpsertSql("DELETE FROM users")
        dataFeed?.executeUpsertSql("INSERT INTO users(id, name) VALUES (100, 'SeedUser')")
    }

    @Test
    fun readSeed() {
        val cnt = dataFeed!!.entityManagerFactory.createEntityManager().use { em ->
            (em.createNativeQuery("SELECT COUNT(*) FROM users WHERE id=100").singleResult as Number).toLong()
        }
        assertThat(cnt).isEqualTo(1)
    }
}
```
### DataFeed 주입이 null 인 경우
- 원인: JPA 미사용, 혹은 DataFeed 자동 구성 제외
- 대처: 테스트에서 조건부(`dataFeed?....`) 호출 또는 명시적 스킵

## 6. Spring Boot 구성 클래스(@SpringBootApplication) 필요성
`@SpringBootTest` 는 패키지 상위 경로에서 `@SpringBootApplication` 을 탐색합니다.
테스트 루트 패키지에 애플리케이션 클래스가 없다면 아래와 같이 하나 추가하세요.
```kotlin
@SpringBootApplication
class TestApplication
```
> 라이브러리 자체 테스트에서 이미 `TestSupportTestApplication` 과 유사한 부트스트랩 클래스를 제공하면 중복 생성 불필요.

## 7. JSON 검증 헬퍼 상세
```kotlin
result.checkData("user.id", 10)
result.checkSize("users", 3)
```
- `checkData(key, value)`: `$.payload.<key>` 또는 배열 인덱스 지원 (`[0].id` 형태 전달 시 가공 안 함)
- `checkSize(key, size)`: 컬렉션 길이 검증

## 8. DTO → Query Param 변환 규칙
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

## 9. 확장/오버라이드 포인트
| 메서드 | 목적 | 예시 |
|--------|------|------|
| `dtoToParam` | 본문 직렬화 전략 교체 | YAML 직렬화 등 |
| `dtoToQueryParams` | DTO → MultiValueMap 매핑 커스터마이즈 | 날짜 포맷 변환 |

## 10. Troubleshooting
| 증상 | 원인 | 해결 |
|------|------|------|
| `dataFeed` null | JPA 미사용 | common-core + JPA 설정 추가 |
| SpringBootConfiguration 에러 | @SpringBootApplication 미탐색 | 테스트 패키지 루트에 정의 |
| JSON Path 실패 | payload 구조 상이 | 응답 본문 로그 후 key 재확인 |
| 2회 이상 동일 시딩 레코드 | 테스트 독립성 부족 | `DELETE` 선반영 후 INSERT |

## 11. 베스트 프랙티스 요약
- 각 테스트 독립성 확보: 시딩 → 검증 → 정리 패턴
- DataFeed 사용 시 항상 where 절 있는 정리 쿼리/혹은 전체 truncate
- 복잡한 대량 시딩: SQL 파일(`executeScriptFromFile`)로 관리, Git diff 가독성 확보

## See also
- DataFeed User Guide: [datafeed-user-guide.md](./datafeed-user-guide.md)
- DataFeed Reference: [datafeed-reference.md](./datafeed-reference.md)
- AbstractControllerTest Reference: [abstract-controller-test-reference.md](./abstract-controller-test-reference.md)
- 전체 문서 인덱스: [index.md](./index.md)

---
이 문서는 사용자 가이드(User Guide)이며, 클래스/메서드 세부 시그니처는 Reference 문서를 참고하세요.
