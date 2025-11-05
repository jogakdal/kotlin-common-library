# standard-api-response-examples.md 단계별 수정 계획서

작성일: 2025-11-05
목적: `standard-api-response-examples.md` 문서를 실제 라이브러리 구현과 100% 정합되도록 교정하고, "빠르게 복사해 사용할 수 있는 순수 예제 모음"이라는 컨셉을 강화한다.

## 1. 전반 전략
- 한 번에 대규모 수정 대신, 작은 PR/커밋 단위(Phase)로 점진적 반영
- 각 Phase 완료 후 문서 재검토 및 차 diff 확인 → 오탈자/불일치 추가 교정
- 사용자 가이드(`standard-api-response-library-guide.md`)와 중복되는 과도한 개념 설명은 줄이고, 예제 중심 구조 재편
- 모든 코드 블록은 실제 컴파일 가능 / 런타임 시그니처와 동일하도록 유지 (Java/Kotlin)
- JSON 예시는 실제 직렬화 결과와 구조적으로 동일하도록(필드명/값/포맷/케이스)

## 2. 우선순위 개요
| Priority | 영역 | 문제 유형 | 요약 |
|----------|------|-----------|------|
| P0 | IncrementalList / CursorInfo JSON | 구조 불일치 | size/total → start/end/expandable 교정 |
| P0 | PageableList PageInfo.total 의미 | 의미 혼동 | total=총 페이지 수로 수정 (items.total=총 아이템 수) |
| P0 | OrderBy / OrderDirection 출력 | 필드/값 불일치 | `dir` → `direction`, 값 `ASC`→`asc` |
| P0 | Java StatusPayload/ ErrorPayload 생성 | 잘못된 생성자 사용 | 실제 시그니처로 수정 (appendix 추가 또는 companion of) |
| P0 | Duration 수동 설정 | 존재하지 않는 API | `setDuration` 제거 → build(duration=) 사용 |
| P0 | Batch 예제 | Non-existing API/타입 | copy(), ErrorEntry 제거 후 ErrorDetail 누적 패턴 |
| P1 | ErrorPayload 표현 | 구조 오해 가능 | code/message 직접 필드 오해 방지 주석 보강 |
| P1 | CaseConvention CAMEL_CASE 표 | 변환 결과 오탈자 | `apiUrlVersion2` 로 교정 |
| P1 | 잘못된 datetime 포맷 | 포맷 오류 | `2025-10-21T13:01:45Z` 형태로 통일 |
| P1 | 역직렬화 실패 코드 | 코드 불일치 | `DESERIALIZE_FAIL` → `E_DESERIALIZE_FAIL` 통일 권장 |
| P2 | Dynamic typeHint 예제 | 스키마 확장 명시 | 표준 외 필드임을 명시하는 짧은 주석 추가 |
| P2 | Alias 충돌 로그 예시 | 표현 미묘 차이 | 실제 로그 형식과 다를 수 있음을 문구로 명시 |
| P2 | 성능/캐시 | 보강 | CaseKeyCache 한 줄 소개 |
| P3 | 문서 구조 재편 | 가독성 향상 | 섹션 재배치 및 중복 제거 |
| P3 | 추가 유용 예제 | 누락 | PageListPayload / IncrementalListPayload / fromPageJava / itemsAsList |

## 3. 단계(Phase) 정의
### Phase 1 (핵심 구조 교정)
- CursorInfo / IncrementalList 모든 JSON 블록 수정
- PageableList page.total 수정 (총 페이지 수 계산 예: totalItems=10, size=2 → total=5)
- OrderInfo 예제 수정 (direction 소문자, 필드명 `direction`)
- datetime 포맷 교정 (잘못된 콜론 제거)
- 역직렬화 실패 코드 예제 통일 (`E_DESERIALIZE_FAIL`)

### Phase 2 (Java/Kotlin 코드 정합성)
- Java StatusPayload 생성자/사용 부분 수정 (companion of 또는 3파라미터 ctor)
- ErrorPayload 예제에서 code/message 직접 필드처럼 보이지 않도록 JSON 재확인 + 부가 설명 추가
- Duration 수동 설정 예제 교정
- Batch 예제 완전 재작성 (ErrorDetail 누적 / appendix 활용 / 대표 코드 정책)

### Phase 3 (케이스/매트릭스 교정 & Alias 명확화)
- CaseConvention CAMEL_CASE 매트릭스 교정
- NoCaseTransform 설명 문구 다듬기 ("출력 케이스 변환 스킵" 명확히)
- Alias 충돌 로그 예시는 "예시 로그"임을 명시
- Dynamic typeHint 예제에 표준 스키마 외 필드 주석

### Phase 4 (추가 예제 & 구조 재편)
- PageListPayload / IncrementalListPayload 간단 추가 예제
- fromPage / fromPageJava / buildFromTotalJava 샘플 코드 블록 추가
- itemsAsList 활용 한 줄 예제
- clearAliasCaches() / CaseKeyCache 짧은 소개 섹션
- 문서 내부 개념 중복(가이드와 동일한 긴 설명) 축약: 링크로 대체

### Phase 5 (품질 / 마무리)
- 전체 표/코드 블록 맞춤법/오탈자 검사
- JSON 일관성 (따옴표, 들여쓰기, key ordering) 최소 정렬
- 내부 링크(anchor) 점검 / Cross References 최신화
- 최종 diff 리뷰 후 필요 시 추가 미세 교정

## 4. 상세 변경 체크리스트 (추적용)
| ID | 항목 | Phase | 상태 |
|----|------|-------|------|
| C01 | 모든 cursor JSON 교정 | 1 | Done |
| C02 | page.total 의미 교정 | 1 | Done |
| C03 | OrderInfo 필드/값 교정 | 1 | Done |
| C04 | datetime 포맷 교정 | 1 | Done |
| C05 | 역직렬화 실패 코드 통일 | 1 | Done |
| J01 | Java StatusPayload 생성자 수정 | 2 | Done |
| J02 | Duration 수동 설정 수정 | 2 | Done |
| J03 | Batch 예제 재작성 | 2 | Done |
| E01 | ErrorPayload 구조 설명 보강 | 2 | Done |
| K01 | CaseConvention CAMEL_CASE 교정 | 3 | TODO |
| K02 | NoCaseTransform 설명 개선 | 3 | TODO |
| A01 | Alias 충돌 로그 예시 주석 | 3 | TODO |
| D01 | Dynamic typeHint 표준외 필드 주석 | 3 | TODO |
| X01 | PageListPayload 예제 추가 | 4 | TODO |
| X02 | IncrementalListPayload 예제 추가 | 4 | TODO |
| X03 | fromPage / fromPageJava 예제 | 4 | TODO |
| X04 | buildFromTotalJava 예제 | 4 | TODO |
| X05 | itemsAsList 예제 | 4 | TODO |
| X06 | clearAliasCaches & CaseKeyCache 소개 | 4 | TODO |
| R01 | 중복 설명 축약 | 4 | TODO |
| F01 | 표/오탈자/링크 최종 점검 | 5 | TODO |
| F02 | JSON Validity 최종 확인 | 5 | TODO |

## 5. 산출물/커밋 계획
- 각 Phase 완료 시 커밋 메시지 규칙: `docs(std-api-examples): phase-<n> <핵심요약>`
  - 예: `docs(std-api-examples): phase-1 fix cursor/page totals`
- 필요 시 Phase 2에서 Batch 예제 수정 전/후 별도 커밋(가독성)

## 6. 검증 방법
- 눈검: JSON 구조와 실제 클래스 필드 대조 (StandardResponse.kt / payload.kt)
- 샘플 역직렬화 간단 테스트(옵션): 추후 원하면 별도 임시 테스트 파일 추가 가능
- 오타 검사: grep 활용 (e.g., `grep -R "size\"\s*:"` 확인 등)

## 7. 리스크 및 완화
| 위험 | 설명 | 완화 |
|------|------|------|
| 과도한 장황 유지 | 예제 문서가 다시 가이드화 | Phase 4에서 축약 강제 |
| 후속 JSON 실수 | page.total/ cursor 재오류 | 체크리스트 ID C01~C03 완료 후 재검증 |
| 사용자가 copy 시 혼동 | 남은 개념적 설명으로 복잡 | 상단에 "개념 설명은 가이드 참조" 배너 추가 |

## 8. 다음 액션
- Phase 2 착수 준비(J01~J03, E01)

---
