# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Communication Language

**모든 응답은 한국어로 작성합니다.**

## Project Overview

Kotlin multi-module library providing reusable components for Spring Boot microservices. Main features: standard API response handling, JPA soft-delete extensions, and testing utilities.

- **Language**: Kotlin 2.1.20, Java 21
- **Build**: Gradle 8.13.0 (Kotlin DSL)
- **Spring Boot**: 3.4.4 (optional dependency)

## Build Commands

```bash
# Full build (includes docs update)
./gradlew build

# Build without tests
./gradlew build -x test

# Run tests for a specific module
./gradlew :module-name:test

# Run specific test pattern
./run_test.sh "*Test*" ":standard-api-response"
# or directly:
./gradlew :module-name:test --tests "*Test*"

# Update documentation versions from gradle.properties
./gradlew updateLibraryGuideVersions

# Sync code snippets from test examples to documentation
./gradlew syncSnippets

# Publish to Nexus
./gradlew publish -Pnexus.id=$ID -Pnexus.password=$PASS
```

## Module Structure

```
modules/
├── core/common-core/                   # Core utilities: VariableProcessor, DataFeed, CommonLogger
├── response/standard-api-response/     # Standard API response wrapper with serialization
├── apidoc/apidoc-annotations/          # Swagger/OpenAPI annotation wrappers
├── apidoc/apidoc-core/                 # Documentation generation helpers
├── jpa/jpa-repository-extension/       # Soft-delete JPA repository, sequence generator
└── test/test-support/                  # AbstractControllerTest, test utilities
```

Module names for Gradle: `:common-core`, `:standard-api-response`, `:apidoc-annotations`, `:apidoc-core`, `:jpa-repository-extension`, `:test-support`

## Key Architectural Components

### Standard API Response (`com.hunet.common.stdapi.response`)
Unified REST response format with status, duration, traceId, and typed payloads.
- Factory methods: `StandardResponse.ok()`, `.error()`, etc.
- Payloads: `StatusPayload`, `ErrorPayload`, `PageableList<T>`, `IncrementalList<T,P>`
- Features: Auto duration injection, case convention conversion (snake_case/camelCase)

### Soft Delete JPA Repository (`com.hunet.common.data.jpa.softdelete`)
Logical delete pattern for JPA entities.
- `@DeleteMark` annotation on entity class marks deletion field
- Supported delete field types: BOOLEAN, STRING, DATETIME, ENUM
- Auto-filtering of deleted records in queries
- Delete strategies: RECURSIVE (cascade) or BULK

### Variable Processor (`com.hunet.common.lib`)
Template string variable substitution with customizable resolvers.
- Default syntax: `%{variableName}%`

## Version Management

Per-module versions defined in `gradle.properties`:
```properties
moduleVersion.common-core=1.1.0-SNAPSHOT
moduleVersion.standard-api-response=1.3.1-SNAPSHOT
moduleVersion.jpa-repository-extension=1.2.0-SNAPSHOT
```

## Documentation

- **Main index**: `apidoc/index.md`
- **Snippet sync**: Code examples in test files sync to markdown via `./gradlew syncSnippets`
  - Source markers: `// snippet:name:start` / `// snippet:name:end`
- **Version blocks**: Updated via `./gradlew updateLibraryGuideVersions`
  - Markers: `<!-- version-info:start -->` / `<!-- version-info:end -->`

## Language & Conventions

- **Korean**: Comments, documentation, and commit messages are in Korean
- **Package root**: `com.hunet.common.*`
- **Testing**: JUnit Jupiter + Mockito; use `AbstractControllerTest` for Spring Boot integration tests
- **Null safety**: `-Xjsr305=strict` compiler flag enforced

## Excel Generator 모듈 (`com.hunet.common.excel`)

JXLS 기반 템플릿 엔진을 사용한 Excel 파일 생성 모듈.

### Excel 서식 유지 원칙

**이 원칙은 Excel 생성 관련 모든 코드 수정 시 반드시 준수해야 합니다.**

1. **템플릿 서식 완전 보존**: 템플릿에 작성된 모든 서식(정렬, 글꼴, 색상, 테두리, 채우기 등)은 생성된 Excel에 동일하게 적용되어야 한다.

2. **자동/반복 생성 셀의 서식 상속**: 피벗 테이블을 포함한 자동 생성 또는 반복 생성되는 셀은 템플릿의 기준 셀 서식을 모두 적용해야 한다.
   - 피벗 테이블 헤더 행: 템플릿의 헤더 행 서식 적용
   - 피벗 테이블 데이터 행: 템플릿의 첫 번째 데이터 행 서식 적용
   - 피벗 테이블 Grand Total 행: 피벗 테이블 기본 스타일(PivotStyleLight16 등)에 위임

3. **숫자 서식 자동 지정 예외**: 자동 생성되는 셀의 데이터가 숫자 타입이고, 템플릿 셀의 "표시 형식"이 없거나 "일반"인 경우에만 "숫자" 범주로 자동 지정한다. 이 경우에도 해당 셀의 나머지 모든 서식(정렬, 글꼴, 색상 등)은 원칙 1, 2를 준수한다.

### StyleInfo에서 유지해야 하는 서식 속성

- `horizontalAlignment`: 가로 정렬
- `verticalAlignment`: 세로 정렬
- `fontBold`: 굵게
- `fontItalic`: 기울임꼴
- `fontUnderline`: 밑줄 (U_NONE, U_SINGLE, U_DOUBLE 등)
- `fontStrikeout`: 취소선
- `fontName`: 글꼴 이름
- `fontSize`: 글꼴 크기
- `fontColorRgb`: 글꼴 색상
- `fillForegroundColorRgb`: 채우기 색상
- `fillPatternType`: 채우기 패턴
- `borderTop/Bottom/Left/Right`: 테두리
- `dataFormat`: 표시 형식