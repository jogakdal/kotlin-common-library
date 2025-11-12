# SoftDeleteJpaRepository 리팩토링 작업 절차서
(작성일: 2025-11-17)

본 문서는 `SoftDeleteJpaRepository.kt` 리팩토링을 단계(Phase)별로 분리하여 여러 세션에 걸쳐 순차 진행할 수 있도록 한 절차서입니다. 각 Phase는 독립 커밋/PR 단위로 수행하고, 완료 시 체크박스([x])로 표시합니다. 모든 변경은 안정성(정합성) → 성능 → 확장성 순서로 진행합니다.

## 전체 진행 현황
- [x] Phase 1: 동적 쿼리 안전성·정합성 개선
- [x] Phase 2: Upsert / Null 머지 정책 및 순차코드 호출 단일화
- [x] Phase 3: Soft Delete N+1 제거 및 Bulk 전략 도입
- [x] Phase 4: 순차 코드 재귀 통합 및 캐싱
- [x] Phase 5: Row Lock API 개선 (표현력/Deprecated 정리)
- [x] Phase 6: Alive/Delete 마크 처리 표준화
- [ ] Roadmap A: 필드명/조건 신뢰성 강화
- [ ] Roadmap B: 다중 DB/Dialect 호환성
- [ ] Roadmap C: 순차 코드 동시성 확장

---
## Phase 1: 동적 쿼리 안전성·정합성 개선
- 완료 여부: [x]
### 목표
`prepareQuery`, `executeFind`, `executeFindFirst` 내 whereClause 비어있을 때, alive 필터 결합 로직 표준화 및 잘못된 필드명 사용 방지.
### 근거 (Rationale)
빈 whereClause + alive 조건 결합 시 문법 혼동 가능. 필드명 외부 입력 검증 없음. Alive 조건 생성 코드 중복 존재.
### 주요 작업(Task)
- [x] Alive/Deleted 공통 Builder 함수 `buildAlivePredicate(info, alias)` 추가
- [x] whereClause 비어있으면 "TRUE" 기본 조건 적용
- [x] 필드명 유효성 검증 (리플렉션 기반) 후 잘못된 경우 IllegalArgumentException (strict) 또는 WARN (non-strict)
- [x] 중복 alive 필터 경고 로직 공통 유틸로 이전 `logDuplicateAliveFilterIfNeeded`
- [x] DeleteMarkValue.NOT_NULL 분기 유지 (aliveMarkValue 미사용) 처리 명확화
- [x] 기능 플래그: `softdelete.query.strict`
### 코드 변경 체크리스트
- [x] SoftDeleteJpaRepository.kt: `prepareQuery`, `executeFindFirst` alive 문자열 생성 제거 → Builder 사용
- [x] 중복 경고 로직 추출
- [x] 필드 검증 함수 추가
- [x] 테스트 패키지 보강 (추가 테스트 클래스 Phase1AdditionalEntities/SoftDeleteJpaRepositoryPhase1AdditionalTest)
### 테스트 (단위) 계획 상태
- [x] 빈 whereClause 처리 테스트 (selectAllEmptyConditionUsesTrue)
- [x] NOT_NULL aliveMark → IS NOT NULL 테스트 (notNullAliveMarkFiltersDeleted)
- [x] 커스텀 aliveMark 값 파라미터 바인딩 테스트 (findFirstByFieldBindsAliveMark)
- [x] 잘못된 필드명 strict=true 예외 테스트 (invalidFieldNameStrictModeThrows)
- [x] 잘못된 필드명 strict=false WARN 시나리오 (invalidFieldNameNonStrictWarns) - 동적 프로퍼티 변경으로 예외 cause 확인
### 테스트 (통합) 계획 상태
- [x] @Where + deleteMarkInfo 동시 존재 엔티티 경고 조건 경로 테스트 (duplicateAliveFilterWarnsOnce) - 로그 수동 검증 필요
- [x] selectAll 결과 행 개수 변화 없음 (selectAllUsesTrueAndAlivePredicate / selectAllEmptyConditionUsesTrue)
### 위험 & 대응
변경된 JPQL 캐시 영향 → 초기 성능 소폭 저하 가능. 1회 워밍업 권장.
### 롤백 전략 (상세)
1. 제거 대상 코드:
   - `buildAlivePredicate`, `logDuplicateAliveFilterIfNeeded`, `validateFieldNames` 함수 삭제
   - `strictQueryValidation`, `allowedFieldNames` 필드 삭제
   - 변경된 `prepareQuery`, `executeFindFirst` 로직을 이전 문자열 결합 방식으로 복원
2. 플래그 제거: `softdelete.query.strict` 프로퍼티 사용 위치 삭제
3. Git: `git revert <Phase1 commit>` 또는 백업 브랜치 `backup/pre-phase1` 에서 강제 reset
4. 서비스 영향 최소화 절차:
   - 배포 전 JPA 로드 테스트(간단 조회) 후 문제 발생 시 즉시 revert
5. 검증 후 로그: 중복 alive 필터 경고 이전처럼 softDelete 내 1회만 출력되는지 확인.
### 성공 지표
- 중복 alive 조건 생성 코드 5+개 → 1~2개 함수로 축소 (달성)
- 잘못된 필드명 사전 차단 (strict 모드에서 예외) 가능 (구현 및 테스트 완료)
### 선행조건
- (완료) 엔티티 필드명 집합 캐싱

---
## Phase 2: Upsert / Null 머지 정책 및 순차코드 호출 단일화
- 완료 여부: [x]
### 목표
`copyAndMerge` null 처리 정책 명확화(무시/덮어쓰기) 및 순차코드 생성 중복 호출 제거.
### 주요 작업(Task)
- [x] 설정 추가: `softdelete.upsert.null-merge` (ignore | overwrite), 기본 ignore
- [x] copyAndMerge 변경: 변경 대상 필드 diff 계산 후 정책 반영
- [x] 순차코드 생성 단일 진입점: persist/merge 직전 1회
- [x] 변경 필드 디버그 로그(필드명, 이전값, 새값, skip 사유)
### 코드 변경 체크리스트
- [x] Null 정책 읽기 지연 로딩 프로퍼티 추가
- [x] copyAndMerge 내부 분기 리팩터링 (GenerateSequentialCode 필드 null 미덮어쓰기)
- [x] upsert 경로 중복 generateSequentialCodes 호출 제거
### 테스트 (단위)
- [x] ignore 정책: 입력 null -> 기존 값 유지 (`copyAndMergeIgnoresNullWhenPolicyIgnore`)
- [x] overwrite 정책: 입력 null -> 기존 값 null로 갱신 (`copyAndMergeOverwritesNullWhenPolicyOverwrite`)
- [x] 순차코드 한 번만 생성 (`upsertGeneratesSequentialCodesOnce`)
- [x] ID 불일치 예외 (기존 AdvancedTest 내 존재)
### 통합 테스트
- [x] 기본/overwrite 정책 클래스 분리로 프로퍼티 격리 검증
### 위험 & 대응
- overwrite 설정 오용 -> 데이터 소실 가능. 기본 ignore로 보호, 잘못된 값 WARN 후 ignore.
### 성공 지표
- 순차코드 중복 생성 로그 0
- 변경 필드 디버그 로그로 머지 diff 추적 가능
### 선행조건
- 순차코드 대상 필드 식별 어노테이션(`@GenerateSequentialCode`) 유지

---
## Phase 3: Soft Delete N+1 제거 및 Bulk 전략 도입
- 완료 여부: [x]
### 목표
자식 엔티티 삭제 시 N+1 쿼리 최소화, Bulk 모드 제공(`softdelete.delete.strategy=bulk|recursive`).
### 근거
현재 각 childRepo 호출 재귀로 관계 깊을수록 쿼리 폭증.
### 주요 작업
- [x] 자식 엔티티 ID 리스트 1회 조회, IN 절 기반 Bulk update 지원 (단일 PK)
- [x] composite PK 지원: 다중 PK OR 조건 묶음 bulk update 처리
- [x] 전략 분기 recursive/bulk (기본 recursive) 프로퍼티 `softdelete.delete.strategy` 도입
- [x] Bulk 후 영속성 컨텍스트 동기화 (루트 depth==1 시 clear 유지)
- [x] 중복 alive 경고 Bulk 경로에도 적용
- [ ] 빌더 함수 분리(`buildBulkSoftDeleteSql`) - 현재 직접 JPQL 문자열 구성 (추후 Phase6 Builder 통합 시 재검토)
### 코드 변경 체크리스트
- [x] `DeleteStrategy` enum 및 `deleteStrategy` 프로퍼티 추가
- [x] `softDeleteChildrenBulk(entity)` 함수 추가 (요구사항의 `softDeleteChildren` 대체 명칭)
- [x] softDelete ���부 전략 분기 적용
- [x] 단일/복합 PK Bulk 로직 구현
- [ ] 별도 SQL Builder 분리(선택)
### 테스트 (단위)
- [x] Bulk 모드: 자식 다수 softDelete 후 alive 조회 제외 (`bulkSoftDeleteChildrenInSingleUpdate`)
- [x] Composite PK 자식 Bulk softDelete (`bulkSoftDeleteCompositePkChildren`)
- [x] 3레벨 트리 Bulk softDelete (`bulkSoftDeleteThreeLevelHierarchy`)
- [ ] Recursive vs Bulk 결과 동일성 (기존 recursive 테스트와 비교 자동화 미구현)
### 통합 테스트
- [x] 3레벨 트리 삭제 후 alive 상태 검증 (Bulk 전용)
### 위험 & 대응
- Bulk 후 일부 영속성 캐시 stale 가능 → depth==1 시 clear 유지로 대응.
### 롤백 전략
- strategy=recursive 설정으로 즉시 복귀 (기능 플래그 기반)
### 성공 지표
- 평균 child softDelete 쿼리 수 70% 이상 감소 (성능 계측 테스트 추후)
### 선행조건
- 다단계 트리 테스트 fixture 준비 (완료)

---
## Phase 4: 순차 코드 재귀 통합 및 캐싱
- 완료 여부: [x]
### 목표
`generateSequentialCodesRecursively` 와 `generateSequentialCodesForEntity` 통합 → 단일 함수 + 순환참조 방지 + 필드 캐싱.
### 근거
중복 구조/비효율적 재귀로 성능 저하 가능.
### 주요 작업
- [x] 통합 함수 `applySequentialCodes(entity, visited)` 구현
- [x] 관계 판별 유틸 `isJpaRelation()` 재사용 (rename)
- [x] 필드 캐시(Map<Class, List<Property>>) 도입 + Java field 포함
- [x] 순환참조 감지 로깅(WARN)
- [x] 공통 시퀀스 코드 생성 헬퍼 `computeSequentialCode(holder, ann)` 추가 (중복 제거)
- [x] 캐시 모니터링 통계 메서드 `sequentialCodeCacheStats()` (missCount, cacheSize) 추가
### 코드 변경 체크리스트
- [x] 기존 두 함수 제거 / 대체 (generateSequentialCodesRecursively / generateSequentialCodesForEntity)
- [x] 캐시 초기화 로직 추가 (computeIfAbsent + missCounter)
- [x] upsert, copyAndMerge 호출 경로 변경 (applySequentialCodes 사용)
- [x] 중복 코드(프리픽스/코드 생성) computeSequentialCode 로 통합
- [x] 통계 메서드 추가
### 테스트 (단위)
- [x] 순환참조 발생 시 무한루프 방지 (testApplySequentialCodesHandlesCircularReferences)
- [x] 캐시 재사용(두 번째 호출 missCounter 증가 없음) (testApplySequentialCodesCachesFieldsPerEntityClass)
- [x] 모든 @GenerateSequentialCode 필드 채워짐 (testApplySequentialCodesPopulatesAllGenerateSequentialCodeFields)
- [x] 캐시 통계 missCount/size 재사용 검증 (SoftDeleteSequentialCodeCacheStatsTest - sequential code 캐시 MISS 한번 이후 동일 클래스 재사용시 missCount 증가 없음)
### 통합 테스트
- [x] 복합 관계 엔티티 persist/upsert 시 정상 코드 부여 (Phase4Parent/Child/Grand 구조)
### 위험 & 대응
- 캐시 누락 시 코드 미생성 → 순수 재귀 내에서 각 필드 blank 검사 후 재시도 필요 시 로그. 현재 MISS/HIT 디버그 로그 제공.
### 롤백 전략
- applySequentialCodes 제거 → 이전 generateSequentialCodesRecursively/ForEntity 복원 (Git revert)
### 성공 지표
- 평균 upsert 시간(관계 많은 엔티티) 감소 (추후 측정) / 재귀 함수 수 2 → 1 통합
- 시퀀스 코드 생성 관련 중복 로직(프리픽스/카운터) 2곳 → 1 헬퍼로 축소
### 선행조건
- 순환참조 테스트용 엔티티 준비 (테스트 내 정의)

---
## Phase 5: Row Lock API 개선
- 완료 여부: [x]
### 목표
락 후 엔티티를 block 인자로 전달하는 직관적 API 추가, 기존 API Deprecation 및 점진적 제거.
### 근거
기존 rowLockBy* 블록에 엔티티 전달되지 않아 락 획득 직후 값 읽기/수정 표현력이 떨어짐.
### 주요 작업
- [x] 새 오버로드: `rowLockById(id) { entity -> ... }` 등 추가
- [x] 기존 메서드 Deprecation 주석/문서화
- [x] 락 대상 미존재 시 예외 명확화 (NoSuchElementException)
- [x] 호환 어댑터 복원(@Deprecated 구 오버로드 → 내부에서 신규 오버로드 위임)
- [ ] 다중 행 락 정책(여러 행 매칭 시 처리) 옵션 설계: `softdelete.rowlock.multiple=FIRST|ERROR|ALL`
- [ ] 문서(apidoc) 보강 (예제 + Deprecation 기간 안내)
### 코드 변경 체크리스트
- [x] 인터페이스: 구 오버로드 복원 + @Deprecated 추가
- [x] 구현체: 구 오버로드 신규 오버로드 호출로 위임
- [x] 신규 오버로드에서 첫 행 부재 시 예외 throw
- [ ] 다중 행 락 정책 구현 (현재 FIRST 고정)
### Deprecation & 호환성 전략
- 구 오버로드 유지 기간: 1 minor release (예: 0.0.7 까지 유지 후 0.0.8에서 제거 예정)
- 제거 절차: 문서 Deprecation 섹션에 sunset 버전 명시 → 제거 커밋에서 CHANGELOG 항목 추가
### 테스트 (단위)
- [x] 새 오버로드 엔티티 전달 확인 (AdvancedTest 내 사용)
- [x] 존재하지 않는 ID 예외 발생 (예외 메시지 포함)
- [ ] 다중 행 매칭 시 정책별 분기 테스트 (구현 후 추가)
### 통합 테스트
- [x] ExtendedScenariosTest: 복합 ID / Field / Fields / Condition 락 + 업데이트 시나리오
- [ ] 경합(동시 트랜잭션) 시 두 번째 트랜잭션 대기/예외 (별도 multi-thread 테스트 필요)
### 위험 & 대응
- 외부 사용자 구 오버로드 즉시 제거 시 컴파일 오류 → 어댑터로 완화
- 다중 행 매칭 시 비의도적 첫 행 선택 → 정책 옵션 추가로 해소
### 롤백 전략
- 구 오버로드 유지 + 신규 오버로드 제거 (최소 변경) → revert commit
### 성공 지표
- 신규 오버로드 사용률(내부 테스트 호출 비중) 90% 이상
- 다중 행 락 정책 도입 후 ALL 사용률 < 10% (권장 FIRST/ERROR)
### 선행조건
- 트랜잭션 설정(PESSIMISTIC_WRITE 지원) 확인

---
## Phase 6: Alive/Delete 마크 처리 표준화
- 완료 여부: [x]
### 목표
alive/deleted 조건 문자열 생성 단일화 및 DeleteMarkValue.NOT_NULL 의미 명확화.
### 근거
여러 위치에서 중복 문자열; NOT_NULL 처리 혼동 가능.
### 주요 작업
- [x] AlivePredicate / DeletePredicate Builder 두 개 구현 (`AliveDeleteBuilders.kt`)
- [x] SoftDelete Native SQL setClause Builder 적용 (DeletePredicateBuilder)
- [x] count/findFirst/prepareQuery 등 모두 AlivePredicateBuilder 경유 (기능 플래그로 스위치)
- [x] 기능 플래그: `softdelete.alive-predicate.enabled` (기본 false)
- [x] NOT_NULL 삭제 시 defaultDeleteMarkValue 제공 정책 통합(DeleteDefaultValueProvider)
### 코드 변경 체크리스트
- [x] 문자열 하드코딩 제거 → Builder 호출로 교체 (prepareQuery/executeFindFirst/softDelete setClause)
- [x] child alive 필터 파라미터/alias 정책 유지 (`_aliveChildMark`, `c` alias) - 빌더 경유 준비
- [x] 플래그 OFF 시 기존 경로 유지(롤백)
### 테스트 (단위)
- [x] 다양한 DeleteMarkValue 조합 alive 생성 검증 (VALUE/NULL/NOT_NULL)
- [x] NOT_NULL delete 시 default 값 설정 (LocalDateTime/String/Boolean 타입별)
### 통합 테스트
- [x] 삭제 후 재조회 alive 정확성 유지 (플래그 ON/OFF 비교)
### 위험 & 대응
- 조건 생성 오류 → 조회/삭제 불일치. 플래그 OFF로 즉시 복구 가능.
### 롤백 전략
- Builder 경로 비활성화: `softdelete.alive-predicate.enabled=false` (기본값)
### 성공 지표
- alive/delete 관련 중복 코드 라인 수 대폭 감소 (빌더로 수렴)
### 선행조건
- DeleteMarkValue 사용 케이스 매핑 표 정리 (기본 제공자 DeleteDefaultValueProvider로 대체)

---
## Roadmap A: 필드명/조건 신뢰성 강화
- 완료 여부: [ ]
### 선택지
A) 리플렉션 검증 (이미 Phase 1 일부)  
B) 메타모델 기반 정적 Registry 캐싱  
C) 화이트리스트 구성 외부 인젝션 제한
### 순서 권장
A → B → 필요 시 C (보안 요구 증가 시)
### 향후 작업 요약
- [ ] 메타모델 스냅샷 빌드 및 변경 감지
- [ ] 화이트리스트 설정 파일(`softdelete.fields-whitelist`) 파싱
- [ ] 테스트: invalid field 차단, whitelist 미등록 경고

## Roadmap B: 다중 DB / Dialect 호환성
- 완료 여부: [ ]
### 이슈
백틱(`\``) 기반 MySQL 가정. 기타 DB (PostgreSQL, Oracle) 호환 필요.
### 작업 요약
- [ ] Dialect 확인(Hibernate) 추출 함수 제공
- [ ] 컬럼 quoting 전략 추상화 (MySQL `\`` / PostgreSQL ")
- [ ] Native SQL 최소화 또는 Criteria API 전환 검토

## Roadmap C: 순차 코드 동시성 확장
- 완료 여부: [ ]
### 이슈
현행 `AtomicLong` fallback 분산 환경에서 충돌 위험.
### 선택지
- [ ] DB 시퀀스 또는 별도 sequence 테이블
- [ ] 외부 분산 ID 서비스(예: Redis, Snowflake 스타일)
### 작업 요약
- [ ] 동시 upsert 부하 테스트 (멀티 스레드) 설계
- [ ] 충돌 발생 시 fallback 전략 교체

---
## 공통 운영 원칙
### Feature Flag 전략
- 모든 Phase 핵심 변경 기능 플래그 도입 후 default=false → 안정화 후 true로 전환.
### 브랜치 / 버전
- `feature/softdelete-phaseX` → main 머지 후 태그 `vX.Y-phaseX`
### 모니터링 항목
- 경고 로그 발생 빈도
- softDelete 호출 평균 DB 라운드트립 수
- upsert 평균 소요 시간(ms)
### 롤백 공통 방법
- 플래그 OFF + 이전 태그 재배포
- 주요 위험: 데이터 정합성 / 성능 회귀 / Deadlock 증가

---
## 추천 테스트 메서드 이름 모음
(추후 구현 시 참고)
- testPrepareQueryAddsAlivePredicateWhenDeleteMarkValueIsCustom
- testPrepareQueryUsesTrueWhenWhereClauseEmpty
- testExecuteFindFirstWarnsOnceOnDuplicateAliveFilter
- testPrepareQueryThrowsOnInvalidFieldName
- testCopyAndMergeIgnoresNullWhenPolicyIgnore
- testCopyAndMergeOverwritesNullWhenPolicyOverwrite
- testUpsertGeneratesSequentialCodesOnce
- testSoftDeleteBulkReducesQueriesForMultipleChildren
- testSoftDeleteRecursiveMatchesBulkResults
- testSoftDeleteCompositeKeyChildrenBulkUpdate
- testSoftDeleteClearsPersistenceContextOnRootCompletion
- testApplySequentialCodesHandlesCircularReferences
- testApplySequentialCodesCachesFieldsPerEntityClass
- testRowLockByIdProvidesLockedEntityToBlock
- testRowLockConcurrencyBlocksSecondTransaction
- testAlivePredicateForNotNullMarkUsesIsNotNull
- testDeletePredicateForCustomValueUsesProvidedLiteral
- testSoftDeleteNativeSqlUsesStandardBuilder
- testCountQueryIncludesAlivePredicate

---
## 메모 / 다음 결정 필요 항목
- [ ] Null merge 기본값 최종 확정 (현재: ignore)
- [ ] Bulk softDelete 적용 범위 (전 엔티티 vs whitelist)
- [ ] RowLock API Deprecation 기간 (제안: 1 minor release)
- [ ] 다중 DB 지원 목표 시점

(필요시 항목 확정 후 본 문서 갱신)
