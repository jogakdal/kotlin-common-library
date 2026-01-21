# SoftDeleteJpaRepository 사용자 가이드 (스켈레톤)

## 최신 버전 정보
<!-- version-info:start -->
```
Last updated: 2026-01-21 15:04:13 KST
jpa-repository-extension: 1.2.0-SNAPSHOT
```
<!-- version-info:end -->

## 1. 개요
- 목적: JPA 소프트 삭제 확장(SoftDeleteJpaRepository) 사용 안내

## 문서 간 교차 링크
- [레퍼런스](./soft-delete-reference.md)
- [마이그레이션](./soft-delete-migration.md)
- [예제/레시피](./soft-delete-examples.md)

## 목차(TOC)
1. 개요
2. 환경/의존성
3. 핵심 개념
4. 설정/프로퍼티
5. Quick Start
6. 고급 사용법
7. JpaRepository 교차 사용 주의사항·장단점·한계
8. FAQ
9. Appendix

---

## 2. 환경/의존성
- Spring Data JPA 기반, 프로젝트 자동설정: `SoftDeleteJpaRepositoryAutoConfiguration`
- 리포지토리 베이스 클래스 활성화(필요 시): `@EnableJpaRepositories(repositoryBaseClass = SoftDeleteJpaRepositoryImpl::class)`
- 권장 버전(예): Java 21, Kotlin 2.1.x, Spring Boot 3.4.x

## 3. 핵심 개념
- DeleteMark(삭제 마크)
  - 엔티티에 삭제 여부를 표시하는 필드에 `@DeleteMark` 주석을 부여합니다.
  - alive / deleted 기준은 필드 타입 및 `@DeleteMark`의 설정값에 따라 판정됩니다.
  - 예시 기준값: BOOLEAN(Alive=true / Deleted=false), STRING(Alive="ALIVE" / Deleted="DELETED"), DATETIME(Alive=NULL / Deleted=NOW) 등.
  - 권장 패턴(DATETIME 컬럼을 NULL 허용하지 않고 최소 DateTime을 기본값으로 설정):
    - `@DeleteMark(aliveMark = DeleteMarkValue.DATE_TIME_MIN, deletedMark = DeleteMarkValue.NOW)`
    - 의미: Alive는 DB가 허용하는 최소 DateTime 값(예: `1000-01-01 00:00:00`), Deleted는 삭제 시점의 현재 시각.
    - 장점: 
      - `IS NULL` 비교를 피하고 동등 비교(`=`) 또는 범위 비교로 인덱스 활용도가 높음, 기본값(DEFAULT)로 Alive 상태를 보장하기 쉬움.
      - 삭제한 시점 파악이 용이함
    - 참고: 벤더별 최소 DateTime은 상이할 수 있으므로 스키마에서 해당 최소값을 DEFAULT로 설정합니다.
- Alive / Deleted Predicate
  - 조회/카운트 시 자동으로 Alive 조건이 주입되어 삭제된 행을 제외합니다.
  - 커스텀 쿼리(`@Query`) 혼용 시 Alive 조건 누락 주의.
- 삭제 전략 개요
  - `RECURSIVE`: 관계를 따라 자식까지 순회하며 삭제 마크 업데이트.
  - `BULK`: 대상만 일괄 업데이트(자식 처리 미포함 또는 별도 수행).
- 병합/업서트 개요
  - NullMergePolicy: `IGNORE`(입력 null은 덮어쓰지 않음) / `OVERWRITE`(입력 null도 덮어씀).

## 4. 설정/프로퍼티
애플리케이션 설정으로 리포지토리 동작을 제어할 수 있습니다. 키 / 기본값은 레퍼런스 표를 참고하세요.
- `softdelete.upsert-all.flush-interval`: 대량 upsert 시 배치 플러시 간격(예: 50)
- `softdelete.upsert.null-merge`: IGNORE 또는 OVERWRITE
- `softdelete.delete.strategy`: RECURSIVE 또는 BULK
- `softdelete.alive-predicate.enabled`: Alive 필터 자동 주입 활성/비활성
- `softdelete.query.strict`: 스펙/커스텀 쿼리와 Alive 필터 일관성 강화 모드

예시(application.yml):
```yaml
softdelete:
  upsert-all:
    flush-interval: 50
  upsert:
    null-merge: IGNORE
  delete:
    strategy: RECURSIVE
  alive-predicate:
    enabled: true
  query:
    strict: true
```

## 5. Quick Start
아래 단계로 최소 설정 후 바로 사용할 수 있습니다.
1) 엔티티에 삭제 마크 필드 추가 및 주석 부여
- 권장 패턴(DATETIME 컬럼을 NULL 허용하지 않고 최소 DateTime을 기본값으로 설정):
```kotlin
@Entity
@Table(name = "users")
data class User(
  @Id val id: Long,
  @Column(name = "deleted_at", nullable = false)
  @DeleteMark(aliveMark = DeleteMarkValue.DATE_TIME_MIN, deletedMark = DeleteMarkValue.NOW)
  val deletedAt: LocalDateTime
)
```
- 대체 패턴(DATETIME Nullable):
```kotlin
@Entity
@Table(name = "users")
data class User(
  @Id val id: Long,
  @Column(name = "deleted_at")
  @DeleteMark
  val deletedAt: LocalDateTime?
)
```
- 규칙 요약: 권장 패턴은 `deleted_at = MIN_DATETIME` → Alive, 삭제 시 `deleted_at = NOW()`; 대체 패턴은 `deleted_at == null` → Alive.
- 스키마 예(MySQL/MariaDB 예시):
```sql
ALTER TABLE users
  ADD COLUMN deleted_at DATETIME(6) NOT NULL DEFAULT '1000-01-01 00:00:00',
  ADD INDEX idx_users_deleted_at (deleted_at);
```

2) 리포지토리 활성화 및 사용
- 베이스 클래스 활성화(필요 시): `@EnableJpaRepositories(repositoryBaseClass = SoftDeleteJpaRepositoryImpl::class)`
- 기본 조회/카운트는 Alive 필터가 적용됩니다.

3) 삭제(Soft Delete)
- 단건: `softDelete(entity)` 또는 `softDeleteById(id)`
- 조건/필드 기반: `softDeleteByField(field, value)`, `softDeleteByFields(map)`, `softDeleteByCondition(condition)`

4) 복구(Undelete)
- 권장 패턴: 삭제 마크 필드를 최소 DateTime 값으로 업데이트(예: `updateById(id, mapOf("deleted_at" to MIN_DATETIME))`).
- 대체 패턴: 삭제 마크 필드를 `null` 로 업데이트(예: `updateById(id, mapOf("deleted_at" to null))`).

5) 업서트/병합
- `upsert(entity)` / `upsertAll(list)` + NullMergePolicy 선택(`IGNORE` / `OVERWRITE`)

6) 주의사항(요약)
- `@Query` 혼용 시 Alive 조건 누락 방지 필요.
- `RECURSIVE` / `BULK` 전략의 관계 처리 차이를 이해하고 선택하세요.
- 트랜잭션 및 배치 플러시 간격 설정은 성능 / 일관성에 영향.

## 6. 고급 사용법
### 6.1 삭제 전략 상세(Recursive vs Bulk)
- `RECURSIVE`
  - 특징: 부모 → 자식(→ 손자)로 관계를 따라가며 삭제 마크를 갱신.
  - 장점: 관계 일관성 보장, 자식도 함께 소프트 삭제 처리.
  - 단점: 쿼리 수 증가, 성능 부담. 깊은 관계에서 트랜잭션 시간이 길어질 수 있음.
  - 사용 권장: 강한 참조 무결성과 함께 논리 삭제가 계층 전체에 적용되어야 할 때.
- `BULK`
  - 특징: 대상 엔티티만 일괄 업데이트(자식은 별도 처리 또는 비처리).
  - 장점: 빠른 처리(쿼리 적음), 대량 작업에 유리.
  - 단점: 자식 무결성은 별도 작업 필요. 쿼리 후 영속성 컨텍스트와 DB 상태 동기화 주의.
  - 사용 권장: 관계가 느슨하거나 자식 처리 정책이 별도 파이프라인으로 운영될 때.

### 6.2 관계 / 연관 처리
- `@OneToMany(mappedBy)` 등 연관에서 자식 엔티티에 DeleteMark가 있으면 자식도 소프트 삭제 대상.
- 자식에 DeleteMark가 없으면 실제 삭제가 필요할 수 있으므로 정책적으로 금지하거나 별도 하드 삭제 경로를 문서화(권장: DeleteMark 부여로 일관성 유지).
- 순회 시 컬렉션 페치 전략은 성능에 큰 영향: 지연 로딩 + 배치 크기 조정, 필요 시 조인 페치.

### 6.3 락 / 동시성
- 행 잠금(rowLock*)은 `PESSIMISTIC_WRITE` 기반.
- 타임아웃 / 예외: DB / 벤더 설정에 따라 타임아웃 발생 시 예외 전파. 재시도 전략(트랜잭션 재시도)을 서비스 계층에서 구성.
- 데드락 회피: 같은 순서로 리소스 접근, BULK 작업과 병행 시 락 범위 최소화.

### 6.4 일괄 작업(배치)
- upsertAll: `softdelete.upsert-all.flush-interval`로 플러시 간격 조정.
- 대량 삭제(BULK): 영속성 컨텍스트 동기화 필요 시 clear / flush를 명시적으로 수행.
- 메모리 / 성능 트레이드오프: 간격이 작을수록 메모리 사용은 낮아지나 I/O가 증가.

### 6.5 트랜잭션 / 일관성
- `@Transactional` 경계 내에서 softDelete* / updateBy* / upsert* 호출.
- `RECURSIVE`는 다수의 업데이트가 하나의 트랜잭션에 포함되므로 롤백 포인트 설계(부모 단위 커밋은 권장하지 않음).
- NullMergePolicy 선택은 병합 일관성에 직접 영향: `IGNORE`는 안전(누락 보호), `OVERWRITE`는 명확성(의도적 초기화)에 유리.

### 6.6 성능 / 인덱싱
- Alive 컬럼(예: `deleted_at = MIN_DATETIME`)에 대한 동등 비교 인덱스 또는 부분 인덱스(벤더 지원 시) 권장.
- 조회/카운트 경로에서 Alive 조건을 최우선으로 배치하여 불필요한 스캔 최소화.
- RECURSIVE 시 N+1 예방: 컬렉션 배치 크기 / 페치 전략 튜닝.

### 6.7 권장 DeleteMark 패턴 상세
- 스키마 가이드(예: MySQL / MariaDB)
  - 컬럼: `deleted_at DATETIME(6) NOT NULL DEFAULT '1000-01-01 00:00:00'`
  - 인덱스: `(deleted_at)` 단일 또는 복합 인덱스의 선두 키로 배치
  - 삭제 시: `UPDATE ... SET deleted_at = NOW(6)`
  - 복구 시: `UPDATE ... SET deleted_at = '1000-01-01 00:00:00'`
- 장점
  - DEFAULT로 Alive 상태 보장(입력 누락 시에도 Alive)
  - 동등 비교(`=`) 인덱스 사용 용이, 일부 엔진에서 `IS NULL` 보다 최적화 유리
  - 삭제 시각이 기록되어 운영/분석 용이
- 주의사항
  - 최소 DateTime 값은 벤더 / 모드에 따라 다름(`0000-00-00` 금지 모드 등). 환경에 맞는 최소 유효값을 선택.
  - 타임존 / 정밀도(소수점) 차이로 비교 실패를 막기 위해 컬럼과 NOW 함수 정밀도를 일치(DATETIME(6) ↔ NOW(6)).
  - 애플리케이션 / DB 모두 UTC 사용을 권장.

## 7. JpaRepository 교차 사용 주의사항·장단점·한계
교차 사용 시 다음 규칙을 따르십시오.

- 대체 API 매핑(요약)
  - deleteById / delete → softDeleteById / softDelete(entity)
  - findById → findOneById
  - existsById → existsAliveById 
  - findAll / count → Alive 자동 필터가 적용되는 리포지토리 메서드로 사용
  - `@Query` 혼용 시 where 절에 Alive 조건 명시

- Do & Don't 체크리스트
  - [Do] 삭제는 항상 softDelete* 계열을 사용한다
  - [Do] 커스텀 쿼리에서 Alive 조건을 명시하고, strict 모드를 고려한다
  - [Do] 관계 삭제 정책(`RECURSIVE` / `BULK`)을 팀 규약으로 고정한다
  - [Do] 복구는 업데이트 기반으로 처리한다(권장 패턴: 최소 DateTime)
  - [Don't] 하드 삭제 delete*를 직접 호출하지 않는다
  - [Don't] Alive 필터를 비활성화한 채 기본 메서드를 사용하지 않는다
  - [Don't] BULK 후 영속성 컨텍스트 동기화를 누락하지 않는다

- 위험 시나리오와 회피책
  - 하드 삭제 호출: 데이터 보존·관계 일관성 붕괴 → softDelete*로 치환
  - Alive 조건 누락(`@Query`): 삭제된 행 노출 / 통계 왜곡 → where에 Alive 조건 포함, strict=true 설정
  - `existsById()` / `findById()` 정책 혼선: 삭제 마크 행 처리 정의 부재 → `existsAliveById()` / `findOneById()`로 일관성 확보
  - 락 / 배치 혼용: 데드락/타임아웃 → 접근 순서 통일 / 잠금 범위 최소화 / 재시도 도입

- 장단점
  - 장점: 기존 API 학습 자산 활용, 단순 조회 / 카운트 생산성 향상
  - 단점 / 한계: 복구 전용 API 부재(업데이트 기반 필요), 스펙 / 커스텀 쿼리 Alive 누락 가능성, 관계 / 성능 트레이드오프

### 존재 확인
```kotlin
val exists = repo.existsAliveById(id)
```
- 삭제 마크된 행은 제외하고 Alive만 대상으로 존재 여부를 확인합니다.

## 8. FAQ
Q1. 왜 별도의 복구(undelete) API가 없나요?
- 삭제 마크 필드를 Alive 기준값으로 업데이트하는 것이 더 유연하고, 다양한 DeleteMark 패턴에 공통 적용되기 때문입니다. 레퍼런스의 DeleteMarkValue 매핑 표와 권장 패턴을 참고하세요.

Q2. Alive 필터를 비활성화하거나 커스터마이즈할 수 있나요?
- 가능합니다. `softdelete.alive-predicate.enabled=false`로 비활성화하거나, 쿼리 / 스펙에 Alive 조건을 직접 포함하세요. 단, 일관성 보장을 위해 비활성화는 신중히 사용하세요.

Q3. `@Query`를 사용할 때 주의할 점은?
- Alive 조건이 자동 주입되지 않을 수 있습니다. where 절에 Alive 조건을 명시하거나 `softdelete.query.strict=true`로 일관성 강화를 권장합니다.

Q4. NullMergePolicy(`IGNORE` vs `OVERWRITE`)는 어떻게 선택하나요?
- `IGNORE`는 데이터 보존 관점에서 안전하고, `OVERWRITE`는 명시적 초기화가 필요한 경우 유용합니다. 팀 규약에 맞춰 설정하세요.

Q5. 락/타임아웃/데드락은 어떻게 처리하나요?
- rowLock*는 PESSIMISTIC_WRITE입니다. 타임아웃 / 데드락 예외에 대비해 재시도 정책과 접근 순서 통일, 잠금 범위 최소화를 권장합니다.

## 9. Appendix(스켈레톤)
- DB 벤더별 차이 정리 예정
