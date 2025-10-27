# CHANGELOG

## [Unreleased]
- (예정) Deprecated 패키지 `com.hunet.common.lib.std_api_documentation` 제거
- (예정) Deprecated 어노테이션 `@SwaggerDescribable`, `@RequestDescription` 제거
- 문서 개선: `apidoc/index.md` Quick Dependency 섹션 추가, 루트 `README.md` 문서 인덱스 링크 경로(`docs/`→`apidoc/`) 수정 및 최신 모듈명 반영

## 1.0.1-SNAPSHOT (리팩터링 적용)
### 변경
- 문서/어노테이션 모듈 명 변경: `std-api-documentation` -> `apidoc-core`, `std-api-annotations` -> `apidoc-annotations`
- 패키지 명 표준화: `com.hunet.common.lib.std_api_documentation` 제거, 신규 `com.hunet.common.apidoc.*` 구조 도입 (core / annotations / enum / scan)
- 테스트 패키지 경로 및 디렉토리 구조 실제 물리 경로로 정렬
- 버전 카탈로그(libs.versions.toml) 확장: springTest, springdocStarterWebmvcUi, h2, junitPlatformLauncher, mockito, junit 번들 추가
- 모든 모듈 build.gradle.kts 에서 하드코딩 의존성 문자열을 버전 카탈로그 참조로 치환
- 문서 디렉토리 `docs/` -> `apidoc/` 이전 및 관련 빌드 태스크 경로 갱신
- 표준 응답 가이드/예제/어노테이션 레퍼런스 문서 내 옛 artifactId (`std-api-annotations`) 를 `apidoc-annotations` 로 변경

### 마이그레이션 가이드
1. Gradle/Maven 의존성에서 `std-api-annotations` 대신 `apidoc-annotations` 사용
2. 패키지 import: `com.hunet.common.lib.std_api_documentation.*` → `com.hunet.common.apidoc.*`
3. Deprecated typealias (레거시 패키지 호환) 는 추후 릴리스에서 제거 예정
4. @SwaggerDescribable / @RequestDescription 사용 중인 필드는 @Schema 또는 @SwaggerDescription 으로 교체
5. enum 문서화 문자열에서 DESCRIPTION_MARKER 활용 시 기존 로직 그대로 동작 (패키지 변경만 영향)

### 주의
- BOM(Spring Boot Dependencies) 적용 실패 시 annotationProcessor 버전 명시 필요 → 버전 카탈로그 항목으로 통일
- 레거시 문서 링크가 캐시되어 404 발생 시 apidoc/index.md 를 통해 새 링크 확인
