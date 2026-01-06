# SoftDeleteJpaRepository 레퍼런스

## 최신 버전 정보
<!-- version-info:start -->
```
Last updated: 2026-01-05 18:00:39 KST
jpa-repository-extension: 1.2.0-SNAPSHOT
```
<!-- version-info:end -->

## 문서 간 교차 링크
- [사용자 가이드](./soft-delete-user-guide.md)
- [마이그레이션](./soft-delete-migration.md)
- [예제 / 레시피](./soft-delete-examples.md)

## 목차(TOC)
1. 타입 / 주요 컴포넌트
2. 공개 API 요약 표
3. 설정 키 요약 표
4. 동작 규칙 / 예외 / 트랜잭션 / 락
5. 전략 / 관계 / 일괄 처리
6. JpaRepository 교차 사용 대체 API 표
7. Appendix

---

## 1. 타입 / 주요 컴포넌트
- SoftDeleteJpaRepository / SoftDeleteJpaRepositoryImpl
- SoftDeleteJpaRepositoryAutoConfiguration
- SoftDeleteProperties
- DeleteMark / DeleteMarkValue

## 2. 공개 API 상세 표
- 범주별로 목적/시그니처/트랜잭션/Alive 필터/예외/주의를 요약합니다.

### 2.1 조회 / 카운트(Alive 자동 필터)
| 메서드         | 시그니처(개략)             | 반환           | TX | Alive 필터 | 예외(대표)              | 주의 / 비고                                          |
|-------------|----------------------|--------------|----|----------|---------------------|--------------------------------------------------|
| findAllBy   | findAllBy(조건 / page) | List / Page  | 조회 | 자동 적용    | DataAccessException | `@Query` 혼용 시 Alive 누락 위험 → where 포함/strict=true |
| findFirstBy | findFirstBy(조건)      | Entity / DTO | 조회 | 자동 적용    | DataAccessException | 정렬/인덱스 설계 권장                                     |
| countBy     | countBy(조건)          | Long         | 조회 | 자동 적용    | DataAccessException | 통계 왜곡 방지를 위해 Alive 일관성 유지                        |

### 2.2 소프트 삭제(단건/조건)
| 메서드                   | 시그니처(개략)                     | 반환         | TX | Alive 필터 | 예외(대표)                            | 주의 / 비고                  |
|-----------------------|------------------------------|------------|----|----------|-----------------------------------|--------------------------|
| softDelete            | softDelete(entity)           | Int / Void | 필수 | N/A      | DataAccessException               | 권장 패턴: DATETIME(MIN→NOW) |
| softDeleteById        | softDeleteById(id)           | Int / Void | 필수 | N/A      | EmptyResult / DataAccessException | 존재하지 않는 ID 처리 정책 명시      |
| softDeleteByField     | softDeleteByField(name, val) | Int        | 필수 | N/A      | DataAccessException               | 단일 인덱스 필드 권장             |
| softDeleteByFields    | softDeleteByFields(map)      | Int        | 필수 | N/A      | DataAccessException               | 복합 인덱스 설계 권장             |
| softDeleteByCondition | softDeleteByCondition(조건)    | Int        | 필수 | N/A      | DataAccessException               | Alive 제외 로직 중복 금지        |

### 2.3 업데이트 / 업서트 / 복구(업데이트 기반)
| 메서드        | 시그니처(개략)               | 반환           | TX | Alive 필터 | 예외(대표)                          | 주의 / 비고                                 |
|------------|------------------------|--------------|----|----------|---------------------------------|-----------------------------------------|
| updateById | updateById(id, fields) | Int          | 필수 | N/A      | DataAccessException             | 복구 시 deleted_at ← MIN_DATETIME / NULL   |
| upsert     | upsert(entity)         | Entity / Int | 필수 | 자동 적용    | DataIntegrityViolationException | NullMergePolicy = IGNORE / OVERWRITE 적용 |
| upsertAll  | upsertAll(list)        | Int          | 필수 | 자동 적용    | DataIntegrityViolationException | flush-interval 성능 / 메모리 트레이드 오프         |

### 2.4 락 / 리프레시 / 기타
| 메서드                      | 시그니처(개략)                   | 반환       | TX  | Alive 필터 | 예외(대표)                                           | 주의 / 비고                     |
|--------------------------|----------------------------|----------|-----|----------|--------------------------------------------------|-----------------------------|
| rowLockById              | rowLockById(id)            | Entity   | 필수  | 자동 적용    | PessimisticLockException / QueryTimeoutException | 접근 순서 통일, 타임아웃 / 재시도 전략     |
| refresh                  | refresh(entity)            | Entity   | 선택  | 자동 적용    | IllegalArgumentException                         | 영속 상태 필요                    |
| getEntityClass           | getEntityClass()           | Class<T> | 불필요 | N/A      | -                                                | 제네릭 타입 확인                   |
| sequentialCodeCacheStats | sequentialCodeCacheStats() | Stats    | 조회  | N/A      | -                                                | 시퀀셜 코드 캐시 적중 / 미스 / 크기 등 통계 |

## 3. 설정 키 상세 표
- 경로/타입/기본값/효과/권장/반영 시점(런타임 적용 여부)을 포함합니다.

| 키(경로)                                | 타입                      | 기본값       | 효과  / 설명                               | 권장값 / 비고                     | 반영 시점          |
|--------------------------------------|-------------------------|-----------|----------------------------------------|------------------------------|----------------|
| softdelete.upsert-all.flush-interval | Int                     | 50        | upsertAll 배치 플러시 간격                    | 데이터 양에 따라 50~200 튜닝          | 즉시(다음 호출부터)    |
| softdelete.upsert.null-merge         | Enum[IGNORE, OVERWRITE] | IGNORE    | null 병합 정책(IGNORE: 보존, OVERWRITE: 초기화) | 보존 우선이면 `IGNORE`             | 즉시             |
| softdelete.delete.strategy           | Enum[RECURSIVE, BULK]   | RECURSIVE | 삭제 전략 선택                               | 강한 무결성: RECURSIVE, 처리량: Bulk | 즉시(트랜잭션 경계 고려) |
| softdelete.alive-predicate.enabled   | Boolean                 | true      | Alive 자동 필터 on/off                     | 안전을 위해 `true` 유지 권장          | 즉시(쿼리 경로)      |
| softdelete.query.strict              | Boolean                 | true      | `@Query` 혼용 시 일관성 강화 / 검증              | 팀 규약에 맞게 유지                  | 즉시             |

참고: 각 키는 환경별(YAML/Properties)로 설정하며, 벤더 / 버전 차이에 따라 적용 범위가 달라질 수 있습니다.

## 4. 동작 규칙 / 예외 / 트랜잭션 / 락
- Alive 조건: 자동 주입. 비활성화 시 쿼리 자체에 조건 포함 필요.
- 트랜잭션: softDelete* / updateBy* / upsert*는 `@Transactional` 권장.
- 락: rowLock*는 `PESSIMISTIC_WRITE`, 타임아웃 시 예외 전파.
- 예외: 벤더 / 설정에 따라 Deadlock / Timeout 등 발생 가능. 재시도 / 순서 보장 등 운영 가이드 참고.

### 4.1 경고 / 주의 상자
- `@SQLRestriction`과 Alive 필터 중복
  - 증상: 동일 조건 중복으로 불필요한 스캔 / 정렬, 예상 외 결과 필터링
  - 대응: 하나의 경로로만 Alive 보장(자동 주입 켜고, 커스텀 제한은 중복되지 않게 조정)
- BULK 삭제 후 동기화 누락
  - 증상: 영속성 컨텍스트와 DB 상태 불일치 → 이후 조회 / 업서트 오동작
  - 대응: BULK 수행 후 `flush` / `clear`로 동기화, 필요한 경우 재조회
- 트랜잭션 경계 오용
  - 증상: 부분 커밋 / 롤백으로 계층 일관성 붕괴(특히 `RECURSIVE`)
  - 대응: 부모 / 자식 일괄 트랜잭션 유지, 단계적 커밋 지양
- 락 타임아웃 / 데드락
  - 증상: 예외 전파, 재시도 필요
  - 대응: 접근 순서 통일, 잠금 범위 최소화, 재시도 정책 도입

## 5. 전략 / 관계 / 일괄 처리
- `RECURSIVE` vs `BULK` 차이, 관계 탐색 정책, 배치 플러시 간격의 성능 영향은 사용자 가이드 고급 섹션 참고.

## 6. JpaRepository 교차 사용 대체 API 표

| JpaRepository 메서드      | 기본 의미     | 소프트 삭제 관점 리스크 / 한계         | 권장 대체 API(SoftDelete)                           | Alive 자동 | 비고                         |
|------------------------|-----------|----------------------------|-------------------------------------------------|----------|----------------------------|
| deleteById(id)         | 실삭제       | 데이터 보존 불가, 관계 무결성 붕괴 위험    | softDeleteById(id)                              | N/A      | `RECURSIVE` / `BULK` 전략 적용 |
| delete(entity)         | 실삭제       | 영속성 컨텍스트와 상태 불일치 가능        | softDelete(entity)                              | N/A      | 삭제 마크 업데이트                 |
| deleteAll(entities)    | 일괄 실삭제    | 대량 실삭제로 회복 불가              | forEach → softDelete / 조건 기반 softDeleteByFields | N/A      | `RECURSIVE` / `BULK` 정책 고려 |
| findById(id)           | 단건 조회     | 삭제된 행 포함 가능                | findOneById(id)                                 | 예        | ID 기반 단건 조회 대체 권장          |
| existsById(id)         | 존재 확인     | 삭제된 행을 존재로 간주 가능           | existsAliveById(id)                             | 예        | 필드 기반 countByField 대체보다 선호 |
| findAll(spec / page)   | 목록/페이징    | 커스텀 `@Query` 혼용 시 Alive 누락 | findAllBy(조건 / 페이지)                             | 예        | where에 Alive 조건 포함 권장      |
| count(spec)            | 개수        | Alive 누락 시 통계 왜곡           | countBy(조건)                                     | 예        | 지표 일관성 보장                  |
| @Query (JPQL / Native) | 커스텀 쿼리    | Alive 중복/누락                | where Alive 조건 명시 + strict=true                 | -        | 성능/일관성 주의                  |
| save(entity)           | upsert 유사 | null 병합 / 일관성 불명확          | upsert(entity)                                  | 예        | NullMergePolicy로 명확화       |

> 참고: <br> 
> 필드 기반 대체 메서드(findFirstByField 등)와 전용 존재 확인(existsAliveById)은 의도를 명확히 하고 Alive 일관성 확보에 유리합니다.<br> 
> "Alive 자동"은 SoftDelete 리포지토리 경로로 호출할 때 Alive 필터 자동 주입 여부를 의미합니다.

## 6.1 JpaRepository 교차 사용 요약
- 대체 API 매핑
  - `deleteById` / `delete` → `softDeleteById(id)` / `softDelete(entity)`
  - `findById` → `findOneById(id)`
  - `existsById` → `existsAliveById(id)`
- 위험/회피
  - 하드 삭제 호출 → softDelete*로 치환
  - Alive 누락(`@Query`) → where 포함 / strict=true
  - exists / find 정책 혼선 → alive 전용 / 필드 기반 메서드로 일관성
  - 락 / 배치 혼용 → 접근 순서 / 범위 최소화 / 재시도

자세한 설명은 사용자 가이드의 “7. JpaRepository 교차 사용”을 참고하세요.

## 7. Appendix
- 벤더별 최소 DATETIME 값, 타임존/정밀도 일치 권고, 운영 모니터링(삭제 시각 분석) 팁
