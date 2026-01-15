# 하드 삭제 → 소프트 삭제 마이그레이션 가이드

## 문서 간 교차 링크
- [사용자 가이드](./soft-delete-user-guide.md)
- [레퍼런스](./soft-delete-reference.md)
- [예제/레시피](./soft-delete-examples.md)

## 목차(TOC)
1. 스키마 변경
2. 엔티티 주석
3. 리포지토리 활성화/설정
4. 서비스 계층 변경
5. 데이터 이행(SQL)
6. 유니크 제약/인덱스 전략
7. 검증/롤백/테스트 매핑
8. 에지 케이스/주의
9. Appendix

---

## 1. 스키마 변경
권장 DeleteMark 패턴에 맞춰 컬럼과 인덱스를 추가합니다.
- 컬럼 추가(MySQL/MariaDB 예시):
```sql
ALTER TABLE users
  ADD COLUMN deleted_at DATETIME(6) NOT NULL DEFAULT '1000-01-01 00:00:00';
```
- 인덱스 추가:
```sql
CREATE INDEX idx_users_deleted_at ON users (deleted_at);
```
- 외래키/관계 테이블에도 동일한 패턴을 적용하는 것을 권장합니다(자식 테이블의 DeleteMark 일관성 유지).
- 타임존/정밀도 일치: 컬럼 정밀도(DATETIME(6))와 NOW(6) 등 함수 정밀도를 일치시켜 비교 오류를 방지합니다.

## 2. 엔티티 주석
엔티티의 삭제 마크 필드에 `@DeleteMark`를 부여합니다.
- Kotlin 예시(권장 패턴):
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
- 관계 엔티티에도 동일하게 `@DeleteMark`를 부여하여 RECURSIVE 전략 사용 시 일관성 있게 마킹되도록 합니다.

## 3. 리포지토리 활성화/설정
프로젝트에서 SoftDelete 확장을 활성화합니다.
- 자동설정(Starter 사용 시 자동 적용): `SoftDeleteJpaRepositoryAutoConfiguration`
- 베이스 클래스 명시(필요 시):
```kotlin
@EnableJpaRepositories(repositoryBaseClass = SoftDeleteJpaRepositoryImpl::class)
class JpaConfig
```
- 설정 키(예):
```yaml
softdelete:
  delete:
    strategy: RECURSIVE
  upsert-all:
    flush-interval: 50
  alive-predicate:
    enabled: true
  query:
    strict: true
```

## 4. 서비스 계층 변경
하드 삭제 호출을 소프트 삭제로 대체하고 복구 플로우를 마련합니다.
- 기존:
```kotlin
fun deleteUser(id: Long) {
  userRepository.deleteById(id) // 하드 삭제
}
```
- 변경:
```kotlin
fun deleteUser(id: Long) {
  userRepository.softDeleteById(id) // 소프트 삭제
}

fun restoreUser(id: Long) {
  // 권장 패턴: 최소 DateTime으로 복구
  userRepository.updateById(id, mapOf("deleted_at" to LocalDateTime.parse("1000-01-01T00:00:00")))
}
```
- 관계 삭제 정책: RECURSIVE 전략 사용 시 부모 삭제 시 자식도 함께 마킹. BULK 전략 사용 시 자식은 별도 처리가 필요하므로 서비스 레이어에서 추가 작업을 수행합니다.

## 5. 데이터 이행(SQL)
기존에 하드 삭제되어 별도 테이블/로그에 기록된 데이터가 있다면 마이그레이션 정책을 수립합니다.
- 초기화: 기존 모든 Alive 행을 최소 DateTime으로 설정(필요 시)
```sql
UPDATE users SET deleted_at = '1000-01-01 00:00:00' WHERE deleted_at <> '1000-01-01 00:00:00';
```
- 삭제 기록 반영: 삭제된 것으로 간주해야 하는 행을 현재 시각으로 마킹
```sql
UPDATE users SET deleted_at = NOW(6)
WHERE id IN (SELECT user_id FROM deleted_user_log);
```
- 관계 테이블: 부모/자식 모두 동일 규칙으로 마이그레이션 수행
```sql
UPDATE orders SET deleted_at = NOW(6)
WHERE user_id IN (SELECT user_id FROM deleted_user_log);
```
- 대량 처리 시 배치 크기/트랜잭션 크기를 조정하고, 작업 후 인덱스 재구성/통계를 갱신합니다.

## 6. 유니크 제약/인덱스 전략
소프트 삭제 보존 시 유니크 제약과 충돌할 수 있습니다.
- 전략 1: 부분 인덱스/조건부 유니크(벤더 지원 시)
  - 예: `UNIQUE (email) WHERE deleted_at = '1000-01-01 00:00:00'`
- 전략 2: 논리 유니크 정책(애플리케이션 레벨)
  - Alive 상태에서만 유니크 검증 수행, Deleted 상태는 중복 허용
- 전략 3: 복합 인덱스
  - `(email, deleted_at)` 복합 인덱스로 조회/검증 비용 최적화

## 7. 검증/롤백/테스트 매핑
마이그레이션 완료 후 다음 체크리스트로 검증합니다.
- 체크리스트
  - [ ] 모든 테이블에 `deleted_at` 컬럼과 인덱스가 추가됨
  - [ ] Alive 초기 상태가 최소 DateTime으로 일관되게 설정됨
  - [ ] 삭제 대상이 NOW로 올바르게 마킹됨
  - [ ] RECURSIVE/BULK 설정에 맞춰 관계 엔티티의 삭제 마킹 일관성 보장
  - [ ] 유니크 제약 정책(부분 인덱스/논리 유니크)이 충돌 없이 동작함
  - [ ] 서비스 계층의 deleteById → softDeleteById 대체가 완료됨
  - [ ] 복구 플로우(최소 DateTime 복구 또는 null 복구)가 정상 동작
  - [ ] @Query 혼용 시 Alive 조건 중복/누락이 없음
  - [ ] 락/타임아웃/데드락에 대한 재시도 정책이 도입됨
- 롤백 포인트
  - 스키마 변경 이전 스냅샷 또는 DDL 리버스 스크립트 준비
  - 데이터 마이그레이션 시 단계별 커밋과 기록 테이블 보관(되돌릴 수 있도록)
- 테스트 매핑(프로젝트 테스트에 상응)
  - Basic: 단일 엔티티 삭제/복구 시나리오
  - Advanced: 조건 기반 삭제/업서트/락 시나리오
  - MergePolicy: IGNORE vs OVERWRITE 병합 정책 동작 확인
  - Migration: 삭제 마크 적용 및 관계 일관성 검증
  - SequentialCode: 캐시/코드 생성 기능과의 간섭 여부 확인
  - ThreeLevelHierarchy: 부모-자식-손자 관계에서 RECURSIVE/BULK 비교 검증

## 8. 에지 케이스/주의
- 타임존/정밀도 불일치로 인한 비교 오류
- @SQLRestriction와 Alive 필터 중복으로 인한 성능 저하/결과 왜곡
- BULK 전략 사용 시 영속성 컨텍스트 동기화 누락
- 유니크 제약 충돌(Deleted 상태 보존 시)

## 9. Appendix
- 벤더별 최소 DateTime 값과 설정 방법
- 운영 모니터링(삭제 시각 기반 리포트 생성) 팁
