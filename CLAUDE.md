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