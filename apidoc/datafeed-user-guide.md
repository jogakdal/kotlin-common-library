# DataFeed User Guide

DataFeed는 테스트 / 시딩(Seeding) 환경에서 SQL 스크립트나 단일 구문을 빠르게 적용하기 위한 유틸리티입니다. (프로덕션 대량 마이그레이션/배치 목적이 아닌, 테스트 독립성 확보/예비 데이터 삽입 용도)

## 1. 핵심 개념
| 항목 | 내용 |
|------|------|
| 전략 | Per-statement EntityManager (구문마다 새로운 EntityManager 생성) |
| 트랜잭션 | 각 구문 단위 `begin → execute → commit` (실패 시 rollback 후 다음 구문 진행) |
| 주 용도 | 테스트 데이터 시딩 / 테스트 격리 / 빠른 정리(DELETE) |
| 비 JPA 환경 | no-op (메서드 호출 시 아무 것도 수행하지 않음) |

## 2. 의존성
```kotlin
dependencies {
    testImplementation("com.hunet.common:common-core:<version>")
    testImplementation("com.hunet.common:test-support:<version>") // (선택) AbstractControllerTest 등
}
```
SNAPSHOT 사용 시 `<version>-SNAPSHOT`.

## 3. 자동 구성 동작
- JPA (Hibernate) 및 EntityManagerFactory 존재 시: `CommonCoreAutoConfiguration` 이 DataFeed 등록
- JPA 미존재: `test-support` 모듈의 fallback no-op DataFeed 자동 등록
- 추가적인 `@ComponentScan` / `scanBasePackages` 필요 없음

## 4. 주요 기능
| 메서드 | 설명 | 트랜잭션 경계 |
|--------|------|---------------|
| `executeScriptFromFile(path)` | Classpath SQL 스크립트 파싱 & 순차 실행 | 구문별 독립 |
| `executeUpsertSql(sql)` | 단일 SQL (INSERT/UPDATE/DELETE/MERGE 등) 실행 | 1 구문 |

## 5. 스크립트 파서 규칙
- 세미콜론(`;`) 단위 구분 (문자열 리터럴 내부 세미콜론은 무시)
- 세미콜론이 하나도 없다면 비어있지 않은 한 줄 = 하나의 구문 (레거시 모드)
- 지원 주석:
  - 라인: `--`, `//`, `#`
  - 블록: `/* ... */` (중첩 미지원)
- 문자열 리터럴: `'...'`, `"..."` (단일 따옴표 내부 이스케이프 `''` 처리)

## 6. 실패 처리
- 스크립트 실행 중 실패한 구문만 로그 (시작 라인, SQL 원문 블록)
- 실패해도 나머지 구문 계속 시도 (내부 try-catch)
- 단일 메서드 `executeUpsertSql` 는 예외 전파 (호출부에서 필요 시 assertThrows 등으로 검증)

## 7. 사용 예 (시딩 + 검증)
```kotlin
@Autowired lateinit var dataFeed: DataFeed

@BeforeEach
fun seed() {
    dataFeed.executeUpsertSql("DELETE FROM users")
    dataFeed.executeScriptFromFile("db/seed/users.sql")
}

@Test
fun verify() {
    val count = dataFeed.entityManagerFactory.createEntityManager().use { em ->
        (em.createNativeQuery("SELECT COUNT(*) FROM users").singleResult as Number).toLong()
    }
    assertThat(count).isEqualTo(3)
}
```

## 8. 단일 구문 실행 예
```kotlin
dataFeed.executeUpsertSql("INSERT INTO feature_flag(id, enabled) VALUES (1, true)")
```

## 9. 트러블슈팅
| 증상 | 원인 | 해결 |
|------|------|------|
| DataFeed = null | JPA 미사용 | JPA 설정 & common-core 추가 |
| 아무 실행 없음 | fallback no-op | JPA 설정 확인 |
| 일부 구문 누락 | 세미콜론 미배치/문자열 따옴표 깨짐 | SQL 문법 확인 |
| 성능 저하 | 매우 많은 구문 | 스크립트 구조 재설계 / 통합 구문 사용 |

## 10. 베스트 프랙티스
1. 테스트 독립성: 매 테스트 전 정리(DELETE / TRUNCATE) + 필요한 최소 데이터 삽입
2. 대량/장문 SQL: 별도 `.sql` 파일로 관리 후 `executeScriptFromFile`
3. 민감 트랜잭션 케이스(원자성 필요): 여러 구문 하나로 합치거나 직접 EM/트랜잭션 제어

## 11. 한계 & 비권장 사례
- 대규모 마이그레이션/DDL 배포 목적 X → Flyway/Liquibase 사용 권장
- 프로덕션 경합 높은 구문 대량 실행 X (테스트 세션 전용)

---
Reference 문서(메서드 시그니처, 예외, 내부 동작 세부)는 `datafeed-reference.md` 참고.
