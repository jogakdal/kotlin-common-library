# hunet-common-libs

멀티 모듈 공통 라이브러리. 각 모듈별 상세 문서는 해당 모듈 디렉터리의 README를 참고.

## 문서 (Documentation)
- 통합 문서 인덱스: [docs/index.md](./docs/index.md)

## 주요 모듈
- common-core
- standard-api-response
- std-api-annotations
- std-api-documentation
- jpa-repository-extension
- test-support

## 빠른 의존성 예 (Gradle Kotlin DSL)
```kotlin
dependencies {
    implementation("com.hunet.common_library:common-core:<version>")
    testImplementation("com.hunet.common_library:test-support:<version>")
}
```

## 스니펫 동기화
```bash
./gradlew syncSnippets
```

문서 상세: 반드시 [docs/index.md](./docs/index.md) 를 참조하세요.
