# hunet-common-libs

멀티 모듈 공통 라이브러리. 각 모듈별 상세 문서는 해당 모듈 디렉터리의 README를 참고.

## 문서 (Documentation)
- 통합 문서 인덱스: [apidoc/index.md](apidoc/index.md)

## 주요 모듈
- common-core
- standard-api-response
- apidoc-annotations (구 std-api-annotations)
- apidoc-core (구 std-api-documentation)
- jpa-repository-extension
- test-support

## 빠른 의존성 예 (Gradle Kotlin DSL)
```kotlin
dependencies {
    implementation("com.hunet.common:common-core:<version>")
    implementation("com.hunet.common:apidoc-core:<version>")
    implementation("com.hunet.common:apidoc-annotations:<version>")
    testImplementation("com.hunet.common:test-support:<version>")
}
```

## 스니펫 동기화
```bash
./gradlew syncSnippets
```

마이그레이션 안내: 기존 std-api-annotations / std-api-documentation 은 각각 apidoc-annotations / apidoc-core 로 변경되었습니다. 레거시 패키지 `com.hunet.common.lib.std_api_documentation` 은 추후 제거 예정이므로 신규 `com.hunet.common.apidoc.*` 구조 사용을 권장합니다.
