# Standard API Response Reference 패치 전략

본 문서는 `standard-api-response-reference.md` 개선을 위해 단계별(Phase) 수정 범위와 실행 전략을 정의합니다. 사용자 요청에 따라 현재 커밋에서는 Phase 1(불일치 수정)만 적용합니다.

## Phase 개요
| Phase | 이름 | 목적 | 범위 | 부수효과 리스크 | 진행 상태 |
|-------|------|------|------|----------------|-----------|
| 1 | 불일치 수정 | 구현 코드와 레퍼런스 간 즉시 오류/오해 유발 요소 정정 | Enum 직렬화 값, Status fallback, Duration 측정 흐름, Alias 충돌 모드/전략, GlobalAliasMaps 추가 필드, canonical 적용 범위 명시 | 문서 표현만 변경 (코드 영향 없음) | 완료 ✅ |
| 2 | 누락 내용 보강 | 사용자/운영자 관점에서 필요한 추가 정보 채움 | StatusPayload/ErrorPayload 비교, Jackson 설정, toJson 우선순위, Page/Incremental 생성자 오버로드 표 등 | 문서 증가로 복잡도 상승 가능 | 완료 ✅ |
| 3 | 표현/정밀 개선 | 모호하거나 축약된 기술을 정밀화 | Case 캐시 구조, cursor 공식 상세, SkipCaseKeys 처리 예시 | 유지보수성 향상 | 진행 중 🔄 |
| 4 | 구조 재편성 | 문서 섹션 재배치로 탐색성 향상 | 코어 타입/빌더/역직렬화/성능/운영 분리 | 내부 링크 변경 필요 | 예정 |
| 5 | 운영/디버깅 심화 | 장애 분석 및 최적화 도움 | 로그 패턴, 캐시 무효화, 모드 전환 체크리스트 | 유지보수 효율 향상 | 예정 |
| 6 | 확장 포인트 심화 | 커스터마이징 패턴 문서화 | 커스텀 Payload 설계, BEST_MATCH 상세 알고리즘 | 고급 사용자 지원 | 예정 |

## Phase 1 상세 (이번 적용 범위)
수정 항목 목록:
1. OrderDirection 직렬화 실제 값(`asc`/`desc`) 명시
2. StandardStatus 내부 value (NONE=""), fromString 미인식 fallback → SUCCESS 명시
3. Duration 측정: 빌더 내 1차 측정 vs Advice 필터 기반 override 최종 결정 흐름 추가
4. Alias 충돌 모드/전략 (AliasConflictMode= WARN|ERROR, AliasConflictResolution= FIRST_WIN|BEST_MATCH) + 설정 키 표 추가
5. GlobalAliasMaps 추가 필드 설명(conflictCandidates, propertyAliasLower) 문서 반영
6. canonical 적용 범위: 최상위(status/version/datetime/duration)는 canonical 매칭만, payload에는 alias+canonical 재귀 적용 명시

비수정(Phase 2 이후 처리 예정):
- StatusPayload / ErrorPayload 비교표 보강
- Jackson ObjectMapper 세부 설정
- Case 캐시 구조 정밀화 등

## Phase 3 상세 (표현/정밀 개선)
수정 대상 세부:
1. Case 캐시 구조 정밀 기술: 자료구조(Map<CaseConvention, ConcurrentHashMap<String,String>>), 변환 토큰화 정규식, 캐시 키 수명, 무효화 조건(clearAliasCaches 영향 없음 명시)
2. Cursor 계산 공식 상세: safeStart / safeHowMany / totalItems 보정 로직, endIndex 공식, expandable 판정, 대표 edge case 표 (start >= total, howMany<=0, total=0 등)
3. SkipCaseKeys 처리 예시: @NoCaseTransform 적용 시 원래 키 + underscore/hyphen variant + alias variant 모두 추가되는 과정과 예시(JSON before/after) 제공
4. Canonical 키 생성 구체적 규칙: filter(isLetterOrDigit) 후 lowercase → 충돌 가능 패턴 예시(user-id vs user_id vs UserID)
5. BEST_MATCH 전략 내부 의사결정 예시(짧은 key, 부분 포함 케이스) 간단히 한 줄 설명 (Phase 3 범위 내 간단 표기만, 전체 알고리즘은 Phase 6 예정)

제외(다음 Phase):
- 문서 구조 재배치(섹션 번호 대폭 변경), 성능 튜닝 가이드, 로깅 패턴 상세

실행 절차:
- 레퍼런스 문서 내 관련 섹션(5,6,10,11) 위치 파악 후 보강 블록 삽입
- 코드블록은 Kotlin 컴파일러 파싱 피하기 위해 `text` 언어 지정 또는 표/리스트 활용
- 변경 후 get_errors 검사 (문서이므로 정상이어야 함)

완료 기준:
- 위 5개 항목 모두 레퍼런스 문서에 신규/보강 문장 또는 표로 반영
- Phase 3 작업 외 다른 Phase 내용 없음 확인
- get_errors 무오류

## 실행 절차
1. 기존 문서 전체 스캔 → 삽입 위치 확정
2. 3.1 상태/공통 구조 블록 하단에 Enum 직렬화/Status fallback 주석 추가
3. Duration 섹션(8)에 측정 흐름 차이 추가
4. 구성 프로퍼티 섹션(12) 하단 혹은 별도 12.1 로 Alias 충돌 설정 표 삽입
5. Alias & Canonical 섹션(6)에 최상위 vs payload 처리 차이 문장 추가
6. 캐시 & 성능 섹션(11)에 GlobalAliasMaps 추가 필드 정의 추가
7. get_errors 실행(문서이므로 영향 미미) → 완료 보고

## 수정 원칙
- Phase 1 에서 다른 개선 항목과 혼합 금지
- 한국어 유지, 기존 스타일(표/리스트) 일관성 유지
- 코드 스니펫 불필요한 재작성 지양 (설명 주석 추가 위주)

## 완료 기준
- 위 6개 항목 모두 문서에 반영
- 다른 Phase 항목 미반영 확인
- 작성 후 문서 빌드 영향 없음(get_errors 무문제)

---
Phase 1 적용 후 사용자 승인 시 Phase 2 진행.
