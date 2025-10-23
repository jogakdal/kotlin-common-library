# 패키지/모듈 리팩토링 계획 (초안)

본 문서는 `kotlin-common-library` 내 패키지 네이밍 및 구조를 표준 관례(소문자, 점 구분, 책임 분리)에 맞게 재구성하기 위한 실행 계획 초안입니다. 추후 리뷰 후 확정 및 단계별 Issue/PR 로 진행합니다.

---
## 1. 리팩토링 목표
- 언더스코어(`_`) 제거, 소문자 + 점(dot) 기반 표준 패키지 컨벤션 준수
- 도메인/레이어 중심 패키징 (core, data.jpa, stdapi, test.support 등)
- 표준 API 관련 기능(어노테이션, 문서, 응답) 네이밍 통일 (`stdapi`)
- JPA 확장 기능을 `data.jpa` 하위로 명확히 배치
- 공개 API / 내부 구현 경계 명확화 (`internal`/`impl` 하위 패키지 도입)
- Spring Boot AutoConfiguration 패키지 분리 (`com.hunet.common.autoconfigure` 및 세분화)
- 테스트 지원 코드 분리(`com.hunet.common.test.support`) 및 프로덕션 코드와의 혼동 최소화
- 예제/샘플은 독립 패키지(`com.hunet.common.examples`)

---
## 2. 현재 주요 패키지 구조 (문제 사례)
| 모듈 | 기존 대표 패키지 | 문제점 |
|------|------------------|--------|
| common-core | com.hunet.common.autoconfigure / com.hunet.common.lib.* | lib 중복, 책임 혼재 |
| jpa-repository-extension | com.hunet.common.lib.repository | 의미 불명확(lib), 확장 성격 불분명 |
| std-api-annotations | com.hunet.common.lib.std_api_documentation | snake_case, annotations vs 문서 혼재 |
| std-api-documentation | com.hunet.common.lib.std_api_documentation | 동일 패키지 충돌, 책임 불분리 |
| standard-api-response | com.hunet.common.lib.standard_api_response | 길고 snake_case, std-api 네이밍 불통일 |
| test-support | com.hunet.common.test_support | snake_case 및 최상위 공개 의도 불명확 |
| examples(테스트 내) | com.hunet.common.lib.examples | lib 네이밍 잔존, 위치 혼재 |

---
## 3. 목표 패키지 매핑 (1차 제안)
| 기존 | 신규 (제안) | 비고 |
|------|-------------|------|
| com.hunet.common.lib.repository | com.hunet.common.data.jpa.repository | SoftDelete, Converter 포함 |
| com.hunet.common.lib.repository.softdelete 관련 | com.hunet.common.data.jpa.softdelete | 삭제 마킹 관련 기능 집중 |
| com.hunet.common.lib.std_api_documentation (annotations) | com.hunet.common.stdapi.annotation | Swagger/문서용 커스텀 어노테이션 |
| com.hunet.common.lib.std_api_documentation (documentation helper) | com.hunet.common.stdapi.doc | 문서/스캐폴딩/헬퍼 |
| com.hunet.common.lib.standard_api_response | com.hunet.common.stdapi.response | 표준 응답 처리/직렬화 |
| com.hunet.common.test_support | com.hunet.common.test.support | 테스트 지원 전용 |
| com.hunet.common.lib.examples | com.hunet.common.examples | 샘플/QuickStart 코드 |
| com.hunet.common.autoconfigure (유지) | com.hunet.common.autoconfigure | 공통 AutoConfig 루트 유지 |
| (선택) data.jpa AutoConfig | com.hunet.common.data.jpa.autoconfigure | 모듈 별 자동 설정 분리 |

부가 세분화 예:
- `com.hunet.common.data.jpa.sequence`
- `com.hunet.common.data.jpa.converter`
- 내부 구현: `com.hunet.common.data.jpa.softdelete.internal` / `impl`

---
## 4. 디렉터리/모듈 구조 재정렬 (논리)
```
modules/
  core/common-core -> com.hunet.common.core, com.hunet.common.autoconfigure
  jpa/jpa-repository-extension -> com.hunet.common.data.jpa.*
  annotations/std-api-annotations -> com.hunet.common.stdapi.annotation
  docs/std-api-documentation -> com.hunet.common.stdapi.doc
  response/standard-api-response -> com.hunet.common.stdapi.response
  test/test-support -> com.hunet.common.test.support
  docs/std-api-documentation (문서 생성 코드) -> (doc 하위 유지)
```
예제/샘플은 별도 모듈을 도입하거나 기존 테스트 소스에서 분리 권장:
- `modules/examples` (선택) 또는 `examples` 디렉터리 신설.

---
## 5. AutoConfiguration 전략
- 공통: `com.hunet.common.autoconfigure.CommonCoreAutoConfiguration`
- JPA 확장: `com.hunet.common.data.jpa.autoconfigure.JpaExtensionAutoConfiguration` (신규) ⇒ soft delete, sequence generator Bean 등록
- stdapi.response: `com.hunet.common.stdapi.response.autoconfigure.StandardApiResponseAutoConfiguration` (신규)
- stdapi.doc: 필요 시 `DocumentationAutoConfiguration`
- Spring Boot 3.x 사용 시 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 파일 업데이트
- 기존 `spring.factories` 사용 중이면 동일 위치 경로 수정

---
## 6. 공개 API vs 내부 구현 분리 정책
- 외부 공개: 인터페이스, DTO, 어노테이션, 확장 포인트(SPI)
- 내부: 구현체(`impl`), 전략(`internal`), 헬퍼/리플렉션 유틸
- Kotlin `internal` 키워드 + 패키지 네임(`internal`, `impl`) 조합
- 문서화(KDoc)에서 내부 패키지 제외 설정 (Dokka 사용 시 `perPackageOptions`)

---
## 7. 마이그레이션 단계 상세
1. 패키지 매핑 확정 (이 문서 리뷰 후 태그: `plan-approved`)
2. 모듈별 순차 리팩토링 (PR 분리)
   - A. jpa-repository-extension
   - B. std-api-annotations / std-api-documentation / standard-api-response (stdapi 통합 세트)
   - C. test-support
   - D. examples 추출
3. 각 단계에서 수행:
   - IDE 패키지 rename (import 자동 업데이트)
   - AutoConfiguration 클래스 패키지 이동
   - `META-INF` 설정파일 경로 수정
   - README / docs 내 코드 스니펫 경로 치환 (grep + sed)
   - 빌드/테스트/Publish to MavenLocal (`./gradlew publishToMavenLocal`) 확인
4. 통합 리그레션 테스트 & 문서 빌드
5. Deprecated Forwarder (선택): 이전 패키지 위치에 1 릴리스 동안 경고
6. 버전 업데이트: `0.0.x` -> `0.1.0` (Breaking change)
7. 릴리스 노트 작성 (변경 요약, 마이그레이션 가이드)

---
## 8. 위험 요소 및 완화
| 위험 | 설명 | 대응 |
|------|------|------|
| 외부 사용자 호환성 손실 | API 패키지 경로 변경 | Deprecated Forwarder + 릴리스 노트 제공 |
| 누락된 import/경로 | 대규모 rename로 컴파일 오류 | 단계별 모듈 리팩토링 + CI 빌드 강제 |
| AutoConfiguration 로딩 실패 | spring.factories / imports 경로 변경 누락 | 리팩토링 직후 통합 테스트 (`@SpringBootTest`) 실행 |
| 문서 링크 깨짐 | docs/*.md 내 패키지 경로 변경 | grep/sed 일괄 치환 + 수동 검수 |
| 예제 코드 실행 실패 | 패키지 이동 후 클래스명 경로 불일치 | examples 모듈 도입 후 독립 빌드 시나리오 추가 |

---
## 9. 검증 체크리스트
- [x] 모든 모듈 빌드 성공 (`./gradlew build`)
- [ ] 단위/통합 테스트 성공 (추후 실행)
- [x] SoftDelete 패키지 재배치 완료
- [ ] MavenLocal 퍼블리시 후 외부 샘플 확인
- [ ] AutoConfiguration Bean 로딩 통합 테스트
- [ ] 문서(Dokka, Markdown) 패키지 경로 최종 반영
- [ ] 릴리스 노트 포함 마이그레이션 가이드 작성

---
## 10. 리팩토링 스크립트(참고)
예시 (macOS/zsh):
```
# 패키지 경로 치환 (Dry-run 제거 후 실행)
grep -rl "com.hunet.common.lib.repository" . | xargs sed -i '' 's/com\.hunet\.common\.lib\.repository/com.hunet.common.data.jpa.repository/g'

# std_api_documentation -> stdapi.doc
grep -rl "com.hunet.common.lib.std_api_documentation" . | xargs sed -i '' 's/com\.hunet\.common\.lib\.std_api_documentation/com.hunet.common.stdapi.doc/g'

# standard_api_response -> stdapi.response
grep -rl "com.hunet.common.lib.standard_api_response" . | xargs sed -i '' 's/com\.hunet\.common\.lib\.standard_api_response/com.hunet.common.stdapi.response/g'

# test_support -> test.support
grep -rl "com.hunet.common.test_support" . | xargs sed -i '' 's/com\.hunet\.common\.test_support/com.hunet.common.test.support/g'
```
주의: 패키지 디렉터리 물리적 이동(폴더 rename)은 IDE Refactor 기능을 우선 사용 후 일괄 검수.

---
## 11. 예시: SoftDelete 관련 재배치
현재: `com.hunet.common.lib.repository.SoftDeleteJpaRepository`
목표: `com.hunet.common.data.jpa.softdelete.SoftDeleteJpaRepository`
추가 분할:
- 삭제 마킹 어노테이션: `com.hunet.common.data.jpa.softdelete.annotation.DeleteMark`
- 시퀀스 생성: `com.hunet.common.data.jpa.sequence.SequenceGenerator`
- 내부 헬퍼: `com.hunet.common.data.jpa.softdelete.internal.*`

[완료] 2025-10-23 적용됨
- 신규 패키지로 코드 이동 및 기존 경로 Deprecated forwarder 제공
- AutoConfiguration.imports 에 `com.hunet.common.data.jpa.softdelete.SoftDeleteJpaRepositoryAutoConfiguration` 추가
- 내부 타입(DeleteMarkValue, DeleteMarkInfo) `internal` 패키지로 분리
- 문서 내 구 패키지 참조 없음(검색 확인)
- 향후: Deprecated forwarder 1 릴리스 유지 후 제거 예정

---
## 12. 후속 개선 아이디어 (초안 범위 밖 - 선택)
- DeleteMarkValue 전략을 sealed interface + value object로 단순화
- Criteria API 동적 조건 빌더 DSL 추출
- `copyAndMerge` 로직을 별도 유틸/서비스로 이동 (SRP 강화)
- 테스트 지원 모듈에서 WebTestClient/MockMvc 확장 유틸 표준화

---
## 13. 의사결정 필요 항목
| 항목 | 선택지 | 필요 결정 |
|------|--------|-----------|
| stdapi vs standardapi | 짧음 vs 명확성 | 유지: stdapi |
| examples 모듈 분리 | 분리 / 유지 | 분리 권장 |
| Deprecated forwarder 유지 기간 | 0 / 1 릴리스 / 2 릴리스 | 제안: 1 릴리스 |
| AutoConfig 세분화 범위 | core+jpa 통합 / 모듈별 분리 | 모듈별 분리 권장 |

---
## 14. 일정(예시)
| 주차 | 작업 | 산출물 |
|------|------|--------|
| 1주차 | 계획 승인 / Issue 등록 | 확정판 PLAN, 이슈 목록 |
| 2주차 | jpa 모듈 패키지 리팩토링 | PR #jpa-refactor |
| 3주차 | stdapi 세트(annotations/doc/response) | PR #stdapi-refactor |
| 4주차 | test-support / examples 분리 | PR #test-examples-refactor |
| 5주차 | Deprecated forwarder (선택) + 문서 | PR #compat-layer |
| 6주차 | 통합 검증 / 릴리스 | v0.1.0 릴리스 노트 |

---
## 15. 버전 및 배포 정책
- 리팩토링 완료 후 첫 릴리스: 0.1.0 (Breaking Changes)
- README 최상단에 이전 버전 → 새로운 패키지 매핑 테이블 추가
- Maven Central 또는 내부 Nexus 퍼블리시 전에 샘플 프로젝트 1개로 연동 테스트

---
## 16. 승인의 다음 단계
1. 본 초안 리뷰 의견 반영
2. 확정판 작성 (섹션 3, 7 매핑/단계 확정)
3. 이슈 생성 (GitHub/Jira 등) → 각 단계 추적
4. 실행 착수

---
(끝)
