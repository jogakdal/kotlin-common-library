# SoftDeleteJpaRepository 문서화 단계별 계획서 (jpa-repository-extension)

> 목적: jpa-repository-extension 모듈의 소프트 삭제 확장(SoftDeleteJpaRepository)을 대상으로, 기존 apidoc 문서 톤/깊이(예: standard-api-response-library-guide.md, standard-api-specification.md)와 유사한 수준의 사용자 가이드/레퍼런스/마이그레이션/예제 문서를 다단계로 작성한다.  
> 범위: SoftDeleteJpaRepository 중심. 미완성인 PersistJpaRepository는 명시적으로 제외한다.

마지막 갱신: 계획 수립일 기준. 실제 집필 완료 시 각 문서 상단에 버전/환경/갱신 일시 블록을 표기한다.

---

## 문서 묶음 구성(파일 구조 제안)
- apidoc/soft-delete-user-guide.md  
  사용자 가이드(개념/설정/Quick Start/고급 사용법/FAQ)
- apidoc/soft-delete-reference.md  
  레퍼런스(공개 API/설정 키/동작 규칙/예외/트랜잭션/락/정책)
- apidoc/soft-delete-migration.md  
  하드 삭제 → 소프트 삭제 마이그레이션 가이드(스키마/코드/데이터 이행/검증/롤백)
- apidoc/soft-delete-examples.md (선택)  
  예제/레시피 모음(테스트 케이스와의 교차 매핑 포함)

추가: apidoc/index.md에 위 문서 링크를 추가한다(집필 완료 단계에서 반영).

---

## 범위와 제외(중요)
- 포함 대상(예시 경로):
  - modules/jpa/jpa-repository-extension/src/main/kotlin/com/hunet/common/data/jpa/softdelete/SoftDeleteJpaRepository.kt
  - modules/jpa/jpa-repository-extension/src/main/kotlin/com/hunet/common/data/jpa/softdelete/SoftDeleteJpaRepositoryAutoConfiguration.kt
  - 연관 내부 컴포넌트(예: DeleteMark 주석/값/빌더, Alive/Deleted Predicate 생성기, Properties 등)
  - 테스트 시나리오(예: Basic/Advanced/MergePolicy/Migration/SequentialCode/ThreeLevelHierarchy 등)
- 제외 대상:
  - modules/jpa/jpa-repository-extension/src/main/kotlin/com/hunet/common/lib/repository/PersistJpaRepository.kt (미완성: 본 계획과 문서에서 제외)

---

## 교차 참조(문서/코드)
- 문서: 
  - apidoc/standard-api-response-library-guide.md  
  - apidoc/standard-api-specification.md
- 코드: 
  - modules/jpa/jpa-repository-extension/src/main/kotlin/com/hunet/common/data/jpa/softdelete/**/*
- 테스트: 
  - modules/jpa/jpa-repository-extension/src/test/kotlin/com/hunet/common/data/jpa/softdelete/test/**/*  
    (예: SoftDeleteJpaRepositoryBasicTest, AdvancedTest, MergePolicyTest, MigrationTest, SequentialCodeTest, ThreeLevelHierarchyTest)

---

## 단계별 집필 계획(Phased Plan)

### 1) 기초 구조·범위 확정 및 스켈레톤 구성
- 작업 내용
  - 사용자 가이드/레퍼런스/마이그레이션/(선택)예제 파일 생성 및 1·2레벨 헤더 목차(TOC) 초안 추가
  - 범위/제외 항목 명시(특히 PersistJpaRepository 제외 경고)
  - 코드/테스트 경로 교차참조 표 삽입
  - 기존 apidoc 톤(개요 → 환경/의존성 → 핵심 → 고급 → FAQ → Appendix) 반영한 스켈레톤 유지
- 산출물(Deliverables)
  - apidoc/soft-delete-user-guide.md: 개요/목적/문서 간 교차 링크/목차
  - apidoc/soft-delete-reference.md: 공개 API/설정 키 섹션의 비어있는 표 구조
  - apidoc/soft-delete-migration.md: 단계 아웃라인(스키마 → 주석 → 리포지토리/설정 → 서비스 계층 → 데이터 이행 → 검증/롤백)
  - apidoc/soft-delete-examples.md(선택): 예제 카테고리/시나리오 목록 스켈레톤
- 수용 기준(Acceptance Criteria)
  - [x] 각 문서에 1·2레벨 헤더와 목차(TOC)가 존재한다
  - [x] 코드·테스트 파일 경로가 정확히 기재된다
  - [x] 기존 apidoc 톤과 유사한 섹션 흐름을 갖춘다
  - [x] apidoc/index.md에 SoftDelete 문서 링크가 추가되었다

---

### 2) 핵심 개념·설정·Quick Start 작성
- 작업 내용
  - 개념/모델: DeleteMark 주석과 값 스펙(NULL/NOT_NULL/NOW/YES/NO/TRUE/FALSE/ZERO 등), alive/deleted 판정 규칙
  - Alive/Deleted Predicate 및 SetClause 빌더 개요(AlivePredicateBuilder, DeletePredicateBuilder 등)
  - 리포지토리 핵심 API 범주화 및 요약: 
    - 조회/카운트(find*/count*): Alive 필터 자동 적용 경로
    - 삭제(softDelete*): 단건/조건/필드 기반, 전략/트랜잭션/영속성 컨텍스트 동기화 주의점
    - 복구(undelete) 가이드: 전용 API 부재 시 업데이트 기반 복구 패턴 안내
    - 갱신/업서트(updateBy*/upsert/upsertAll): NullMergePolicy(IGNORE/OVERWRITE) 동작 차이
    - 행 잠금(rowLock*): PESSIMISTIC_WRITE, 타임아웃/예외 메시지 정책 요약
    - 기타(refresh/getEntityClass/sequentialCodeCacheStats)
  - 자동설정/프로퍼티
    - SoftDeleteJpaRepositoryAutoConfiguration, @EnableJpaRepositories(repositoryBaseClass=...)
    - 설정 키 예: 
      - softdelete.upsert-all.flush-interval(기본 50)
      - softdelete.upsert.null-merge=[ignore|overwrite]
      - softdelete.delete.strategy=[recursive|bulk]
      - softdelete.alive-predicate.enabled=[true|false]
      - softdelete.query.strict=[true|false]
  - 엔티티 요건/예시: @DeleteMark 필드 타입/컬럼, @CreatedDate/@LastModifiedDate 보존/갱신, copyAndMerge 규칙
  - Quick Start: 최소 설정 → 엔티티/리포지토리 준비 → 기본 흐름(조회/삭제/복구/업서트) 
- 산출물(Deliverables)
  - apidoc/soft-delete-user-guide.md: 개념/설정/Quick Start 섹션 내용 채움
  - 레퍼런스: 공개 API/설정 키 표(목적/입력/출력/예외/트랜잭션/기본값/효과)
  - 복구 모범사례 문단(업데이트 기반)
- 수용 기준(Acceptance Criteria)
  - [x] 사용자 가이드에 핵심 개념/설정/Quick Start가 작성되었다
  - [x] 모든 공개 메서드가 목적/입력/출력/예외/트랜잭션 여부로 문서화된다(레퍼런스 표: 1차 요약 표 작성 완료)
  - [x] 모든 설정 키가 경로/타입/기본값/런타임 반영 지점으로 표기된다(레퍼런스 표: 1차 요약 표 작성 완료)
  - [x] Quick Start만 읽어도 사용 흐름을 파악할 수 있다
  - [x] DeleteMarkValue별 동작 차이가 표/서술로 명확하다(레퍼런스에 상세 표 추가 완료)

---

### 3) 고급 사용법(전략/관계/락/성능/트랜잭션) 정리
- 작업 내용
  - 삭제 전략: RECURSIVE vs BULK – 자식/손자 처리 범위, 쿼리 수/성능, 트랜잭션 영향, 감사/트리거 상호작용
  - 관계 처리: @OneToMany(mappedBy) 기반 탐색/Alive 필터/자식 DeleteMark 유무에 따른 실제 삭제 vs 소프트 마킹
  - 필터/제약: @SQLRestriction와 Alive 필터 중복 경고(쿼리 일관성/성능 영향/권장 대응)
  - 쿼리/락/일괄: Alive 필터 자동 주입 경로, 비관적 잠금 힌트/타임아웃, 일괄 업서트/삭제 플러시 간격과 메모리 트레이드오프
  - 트랜잭션/일관성: 메서드별 @Transactional/@Modifying 유무, 커밋/flush/clear 시점, NullMergePolicy가 병합 일관성에 미치는 영향
  - 성능/인덱싱: Alive 컬럼 인덱스 권장, 부분 인덱스(벤더별) 제안, 대량 삭제 시 BULK의 장단점
- 산출물(Deliverables)
  - 사용자 가이드 고급 섹션(전략/관계/락/일괄/트랜잭션/성능)
  - 레퍼런스 경고/주의 상자(중복 필터, 일괄 업데이트 동기화 등)
- 수용 기준(Acceptance Criteria)
  - [x] RECURSIVE/BULK 차이가 예시 시나리오로 명료하게 설명된다(가이드 6.1 반영)
  - [x] 관계형 삭제 흐름과 Alive 필터 결합 규칙이 오해 없이 서술된다(가이드 6.2 반영)
  - [x] 트랜잭션/락 동작이 메서드 단위로 명확히 표기된다(가이드 6.3/6.5 + 레퍼런스 4/4.1 반영)
  - [x] 성능 권장사항이 체크리스트 형태로 정리된다(가이드 6.6/6.7 요약 반영)

---

### 4) 마이그레이션 가이드(하드 → 소프트) 작성
- 작업 내용
  - 스키마 변경: 삭제 마크 컬럼 추가/타입 결정(BOOLEAN/STRING/DATETIME 등), 기본값/널 정책
  - 엔티티 주석: @DeleteMark(aliveMark/deletedMark), @Column 이름 정합성
  - 리포지토리 교체/활성화: 베이스 클래스/자동설정 확인
  - 서비스 계층: 기존 deleteById 대체 플로우, 복구 플로우 마련
  - 데이터 이행: 과거 삭제 데이터 마킹 변환(배치 SQL), 유니크 제약 충돌 시 전략(조건부 유니크/부분 인덱스 등)
  - 회귀/테스트: Basic/Advanced/MergePolicy/Migration/SequentialCode/ThreeLevelHierarchy 상응 시나리오로 점검
- 산출물(Deliverables)
  - 마이그레이션 문서(절차, 체크리스트, 롤백 포인트, SQL 힌트)
  - 테스트 항목 표(시나리오 → 해당 테스트/예제 링크)
- 수용 기준(Acceptance Criteria)
  - [x] 스키마/주석/코드/데이터 이행 단계가 순서·역할·성공조건과 함께 명시된다(마이그레이션 1~5절 반영)
  - [x] 유니크 제약/성능/중복 필터링 등 위험요인이 사전 경고된다(마이그레이션 6~8절 반영)
  - [x] 각 단계의 검증 포인트/롤백 전략이 포함된다(마이그레이션 7절 반영)

---

### 5) 예제·레시피·테스트 연동 및 FAQ 정리
- 작업 내용
  - 예제/레시피: 
    - 단일 엔티티 소프트 삭제/복구(업데이트 기반)
    - 업서트 + 시퀀셜 코드 + 병합 정책 비교(IGNORE vs OVERWRITE)
    - 관계 3단계(부모-자식-손자) 삭제: RECURSIVE vs BULK 비교
    - 조건 기반 삭제/조회/락(필드/맵/조건/페이지네이션)
    - 성능 튜닝: flush-interval, Alive 인덱스, 벌크 업데이트
  - FAQ: 복구 API 제공 여부, Alive 필터 커스터마이즈, @SQLRestriction 혼용, NullMergePolicy 권장값 등
- 산출물(Deliverables)
  - 예제/레시피 섹션(필수 설명 + 선택 코드 스니펫)
  - (변경) 라이브러리 경로 표 삭제, 대신 재현 가능한 최소 예제 코드 포함
- 수용 기준(Acceptance Criteria)
  - [x] 각 예제가 실제 공개 API/설정 키만으로 재현 가능하다(예제/레시피 1~5절 반영)
  - [x] (변경) 테스트 경로 표 대신, 독립 실행 가능한 최소 예제 코드가 포함되었다(예제 6절 반영)
  - [x] FAQ가 상위 문의를 포괄한다(가이드 8절 반영)

---

### 6) 품질 게이트·교차참조·발행 준비
- 작업 내용
  - 일관성/완전성: 용어/섹션/표 스타일 통일, 요구사항 대비 누락 보완
  - 버전/환경 블록: Java/Kotlin/Spring Boot 권장 버전, JPA/Hibernate 주의, 최종 갱신 일시
  - 인덱스/네비게이션: apidoc/index.md에 SoftDelete 문서 링크 추가, 문서 간 상호 링크(Guide ↔ Reference ↔ Migration ↔ Examples)
- 산출물(Deliverables)
  - 교차참조·링크 점검표, 버전 정보 블록, 인덱스 반영 설계
  - 릴리스 노트(문서 추가/변경 요약)
- 수용 기준(Acceptance Criteria)
  - [x] 모든 링크/앵커가 동작한다(내부 교차 링크 기준 검토 완료)
  - [x] 버전/환경/갱신 일시 블록이 상단에 표시된다(모든 문서 메타 블록 추가)
  - [x] 인덱스에서 SoftDelete 문서 진입 동선이 확보된다(apidoc/index.md 섹션 반영)

---

## JpaRepository 기본 기능 교차 사용 시 주의사항·장단점·한계(별도 섹션)
- 섹션 위치: 사용자 가이드와 레퍼런스 양쪽에 요약/상세를 제공하며, 고급 사용법 이후 또는 FAQ 직전 배치 권장
- 다룰 항목(요약)
  - 교차 사용 개요: `JpaRepository`의 기본 메서드(`deleteById`, `delete`, `findAll`, `findById`, `count`, `existsById` 등)와 SoftDelete 확장의 상호작용
  - 주의사항
    - 실제 삭제 API(`delete*`) 호출 금지/치환 가이드: soft-delete 전략과 충돌, 영속성 컨텍스트 불일치 가능성
    - `find*`/`count*` 사용 시 Alive 필터 적용 여부: SoftDelete 리포지토리로 제공되는 메서드와 순수 JpaRepository 메서드의 차이 명시
    - `existsById`/`findById`: 삭제 마크된 행의 처리(존재로 간주 여부) 정책 명시 및 커스터마이징 포인트
    - 스펙/커스텀 쿼리 혼용: @Query/@SQLRestriction와 Alive 필터의 중복/누락 리스크
    - 트랜잭션: 혼용 시 flush/clear 타이밍 불일치로 인한 조회/삭제 결과 왜곡 가능성
  - 장점
    - 기본 메서드 재사용으로 학습 비용 절감, 테스트/유틸의 호환성 유지
    - 단순 조회·카운트는 SoftDelete 확장과의 통합으로 개발 생산성 향상
  - 단점/한계(SoftDeleteJpaRepository)
    - 전용 복구 API 부재: 업데이트 기반 복구를 문서로 안내(명시적 책임/검증 필요)
    - 스펙/커스텀 쿼리에서 Alive 필터 누락 가능성: 안전 가드/검증 필요
    - 다층 관계에서 RECURSIVE/BULK 선택에 따른 일관성/성능 트레이드오프
    - 유니크 제약 유지 중 소프트 삭제 보존 시 충돌 위험(조건부 유니크/부분 인덱스 고려)
    - 비관적 락과 배치 처리 혼용 시 데드락/타임아웃 리스크
- 산출물(Deliverables)
  - 사용자 가이드: “JpaRepository 교차 사용” 섹션(주의/장단점/베스트 프랙티스/안전 가드)
  - 레퍼런스: 기본 메서드별 동작/Alive 필터 적용 여부/권장 대체 API 표
- 수용 기준(Acceptance Criteria)
  - [x] 기본 메서드별로 SoftDelete 정책과의 상호작용이 명확히 표기된다(적용/미적용/대체 API)
  - [x] 위험 시나리오와 회피책(대체 API/설정/코딩 규약)이 체크리스트로 제공된다
  - [x] 한계(복구 API 부재/쿼리 필터 누락/관계·성능 트레이드오프/제약 충돌/락·배치 리스크)가 별도 박스로 정리된다

---

## 작성 톤/스타일 가이드(요약)
- 기존 apidoc 문서의 톤/구성 준수: 개요 → 환경/의존성 → 핵심 → 고급 → FAQ → Appendix
- 표/체크리스트/경고 박스 적극 활용(특히 정책/제약/성능/트랜잭션)
- 코드 스니펫은 최소화하되, 예제 재현 가능성을 해치지 않을 정도로 제공
- 모든 공개 API/설정 키는 목적/입력/출력/예외/트랜잭션/기본값/부작용을 표로 요약

---

## 최종 완전성 체크리스트(계획 준수 여부 검증용)
- [x] 문서 묶음(가이드/레퍼런스/마이그레이션/(선택)예제) 모두 존재하고 apidoc/index.md에서 탐색 가능하다
- [x] 공개 API 전부(upsert, upsertAll, updateBy*, softDelete*, find*, count*, rowLock*, refresh, sequentialCodeCacheStats)가 목적/시그니처/트랜잭션/동작/주의로 문서화되었다
- [x] 설정 키 전부(softdelete.upsert-all.flush-interval, softdelete.upsert.null-merge, softdelete.delete.strategy, softdelete.alive-predicate.enabled, softdelete.query.strict)가 기본값/효과/부작용과 함께 표기되었다
- [x] DeleteMark/Alive 규칙과 DeleteMarkValue 매핑표가 포함되고 타입별 기본값/동작(STRING/NUMBER/BOOLEAN/DATETIME 등)이 명확하다
- [x] 삭제 전략/관계 처리(부모→자식→손자), @SQLRestriction 중복 경고 및 대응책이 포함되었다
- [x] 복구(undelete) 절차가 업데이트 기반으로 단계/주의와 함께 제공되었다
- [x] 유니크 제약·인덱스·성능 권장사항(Alive 컬럼 인덱스/부분 인덱스/배치 플러시)이 포함되었다
- [x] 트랜잭션/락/타임아웃/예외 정책이 메서드 기준으로 명확히 서술되었다
- [x] 시퀀셜 코드(프리픽스/SpEL/provider/캐시 미스/테스트 결과) 섹션이 포함되었다
- [x] 테스트 연동 표가 실제 파일/심볼과 매칭되며 예제는 재현 가능하다
- [x] 최종 교차링크·버전/환경·갱신 일시 블록이 유효하고 오탈자 없음이 확인되었다
- [x] JpaRepository 기본 기능 교차 사용 섹션이 포함되며, 주의/장단점/한계 및 대체 API 표가 완비되어 있다

---

## 다음 단계(작업자용 체크리스트)
1. 1단계 스켈레톤 커밋 → PR 리뷰
2. 2단계 개념/설정/Quick Start 작성 → 리뷰
3. 3단계 고급 사용법 추가 → 리뷰
4. 4단계 마이그레이션 작성 → 리뷰
5. 5단계 예제/FAQ/테스트 연동 → 리뷰
6. 6단계 품질 게이트/인덱스 반영/릴리스 노트 → 머지

비고: 데이터베이스 벤더(MySQL/MariaDB/…)
- DATETIME/BOOLEAN/부분 인덱스/조건부 유니크 등은 우선 MySQL/MariaDB 기준으로 작성 후, 필요 시 별도 Appendix로 벤더별 차이를 보완한다.

---

(참고) 본 계획서는 PersistJpaRepository를 명시적으로 제외한다. 향후 PersistJpaRepository가 완성되더라도, SoftDeleteJpaRepository 문서와는 별도 트랙의 문서화 계획/문서로 관리한다.
