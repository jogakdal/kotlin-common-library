# Documentation Index

Hunet Common Library 문서 모음. 각 항목은 User Guide(실용 사용법) 와 Reference Manual(구조/시그니처) 로 구성됩니다.

## Core Test Utilities
- AbstractControllerTest
  - [User Guide](./abstract-controller-test-user-guide.md)
  - [Reference Manual](./abstract-controller-test-reference.md)
- DataFeed (테스트 데이터 시딩 / SQL 실행)
  - [User Guide](./datafeed-user-guide.md)
  - [Reference Manual](./datafeed-reference.md)

## Standard API Response
- [Library Guide](./standard-api-response-library-guide.md)
- [Examples](./standard-api-response-examples.md)
- [Specification](./standard-api-specification.md)
- [Reference Manual](./standard-api-response-reference.md)

## Quick Dependency (Gradle Kotlin DSL)
```kotlin
dependencies {
    implementation("com.hunet.common:common-core:<version>")
    implementation("com.hunet.common:standard-api-response:<version>")
    implementation("com.hunet.common:apidoc-core:<version>")
    implementation("com.hunet.common:apidoc-annotations:<version>")
}
```
> Maven 사용 시 동일 artifactId 대비 groupId `com.hunet.common` 적용.

## apidoc Annotations
- [Mini Reference](./std-api-annotations-reference.md)

## Variable Processor
- [User Guide](./variable-processor.md)
- [Examples](./variable-processor-examples.md)

## 기타
- 모듈별 추가 README / 샘플 코드는 각 모듈 디렉터리 참조

---
문서 개선 제안이나 오류 제보는 PR 또는 Issue 로 환영합니다.
