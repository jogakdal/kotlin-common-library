# DataFeed Reference Manual

본 문서는 DataFeed의 구조/시그니처/파서 동작/로그 포맷/확장 가능 지점을 상세 기술합니다. 사용자 가이드는 `datafeed-user-guide.md` 참고.

## 1. 클래스 개요
```kotlin
open class DataFeed {
    lateinit var entityManagerFactory: EntityManagerFactory
    fun executeScriptFromFile(scriptFilePath: String)
    fun executeUpsertSql(query: String)
    protected open fun executeStatement(sql: String)
}
```

## 2. 라이프사이클 & Bean 생성
- AutoConfiguration: `CommonCoreAutoConfiguration`
  - 조건: EntityManagerFactory 존재 시 `DataFeed` 빈 등록
  - no-op: JPA 부재 시 test-support의 fallback (별도 구현)
- Scope: 싱글톤 (stateless, per-statement EM 전략)

## 3. Per-statement EntityManager 전략
| 단계 | 동작 |
|------|------|
| 1 | `entityManagerFactory.createEntityManager()` 생성 |
| 2 | 트랜잭션 `begin()` |
| 3 | `createNativeQuery(sql).executeUpdate()` |
| 4 | `commit()` (예외 시 rollback) |
| 5 | `EntityManager#close()` |

이 전략은 1차 캐시/영속성 컨텍스트 공유를 제거하여 테스트 격리성 향상.

## 4. 파서(FSM) 상세
입력: `List<String>` (파일 라인)
출력: `(sql:String, line:Int)` 리스트

상태 플래그

| 플래그 | 의미 |
|--------|------|
| inSingle | 단일따옴표 문자열 내부 |
| inDouble | 이중따옴표 문자열 내부 |
| inBlockComment | 블록 주석 내부 |
| currentStartLine | 현재 구문 시작 라인 (1-base) |
| semicolonCount | 세미콜론 기반 구문 파싱 여부 판단 |

규칙
1. 문자열 내부 세미콜론 무시
2. 라인 주석 시작 토큰 만나는 즉시 라인 파싱 종료 (`--`, `//`, `#`)
3. 블록 주석 `/* ... */` 전체 스킵 (중첩 미지원)
4. 세미콜론 하나도 없으면 레거시 라인 모드
5. 공백/빈 구문 제거

레거시 라인 모드
- 조건: 전체 스크립트 파싱 중 `semicolonCount == 0`
- 비어있지 않은 라인별 1 구문
- 동일한 라인 주석/블록 주석 제거 로직 적용

## 5. 실패 로깅 포맷
```
[DataFeed] failed statement at line {line}: {exceptionMessage}
----- SQL START -----
{originalSql}
----- SQL END -----
```

## 6. 예외 전파 정책
| 메서드 | 성공/실패 처리 |
|--------|---------------|
| executeScriptFromFile | 구문별 try-catch, 실패 구문만 로그, 다음 구문 계속 |
| executeUpsertSql | 실패 시 예외 전파 (호출부에서 처리) |
| executeStatement | 보호 범위 (script 내부 호출) – 실패 시 예외 throw → 상위에서 로그 |

## 7. 확장 포인트
| 대상 | 방법 | 예시 |
|------|------|------|
| 실행 전후 후킹 | subclass override `executeStatement` | 타이밍 측정 로깅 추가 |
| 파서 커스터마이즈 | 별도 유틸로 전체 재정의 (private 파서 직접 확장 불가) | 커스텀 DSL 지원 |
| 에러 수집 | override `executeScriptFromFile` 후 실패 구문 리스트 저장 | 테스트용 검증 |

## 8. 스레드 안전성
- Stateless (필드 없음) + per-statement EM → 동시성 안전 (단, DB 락은 별개)
- 하나의 DataFeed 빈 다수 테스트에서 병렬 사용 가능 (DB 레벨 충돌 고려 필요)

## 9. 퍼포먼스 참고
| 측면 | 영향 | 대응 |
|------|------|------|
| 다량 구문 (>> 500) | 빈번한 EM 생성 비용 | 스크립트 분할 / 대규모 마이그레이션 도구 전환 |
| 긴 텍스트 SQL | 로그 난독 | abbreviate(120)로 요약 |
| 실패 다수 | 예외 객체 생성 비용 | 필요 시 구문 전 validate/분리 |

## 10. 권장 패턴
```kotlin
fun seed(vararg sql: String) = sql.forEach { dataFeed.executeUpsertSql(it) }
```

## 11. 제약 및 비권장
- 트랜잭션 경계를 구문별로 강제 → 원자성 보장 안 됨 (여러 구문 원자성 필요 시 직접 EntityManager 사용)
- 대규모 이관 / 운영 배치용 사용 금지

## 12. 향후 개선 후보
- 파서 중첩 블록 주석 지원
- 옵션 기반 (continue-on-error=false) fail-fast 모드
- script 실행 결과(성공/실패 카운터) 반환 타입 추가

## 13. 요약
DataFeed 는 "테스트 시딩" 특화, 안전한 per-statement 실행 모델을 제공하며, 파서/실패 로깅은 단순하고 예측 가능하도록 설계되었습니다.

## See also
- DataFeed User Guide: [datafeed-user-guide.md](./datafeed-user-guide.md)
- AbstractControllerTest User Guide: [abstract-controller-test-user-guide.md](./abstract-controller-test-user-guide.md)
- AbstractControllerTest Reference: [abstract-controller-test-reference.md](./abstract-controller-test-reference.md)
- 문서 인덱스: [index.md](./index.md)
