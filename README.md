# hunet-common-libs

멀티 모듈 공통 라이브러리. 각 모듈별 상세 문서는 해당 모듈 디렉터리의 README를 참고.

# 주요 모듈
## common-core
각 라이브러리에서 공통으로 사용하는 기능 또는 VariableProcessor 등 유틸리티 성격의 공통 기능 모음 (modules/core/common-core/README.md 참고)

### 의존성 (예: Gradle Kotlin DSL)
```kotlin
dependencies {
    implementation("com.hunet.common_library:common-core:<version>-SNAPSHOT")
}
```

### SpringContextHolder
Bean이 아닌 곳에서 Spring Bean에 접근할 수 있도록 지원하는 유틸리티 클래스.

### VariableProcessor
템플릿 문자열 내 변수 치환 기능 제공.
- [상세 가이드](docs/variable-processor.md)
- [예제](examples/VariableProcessorExample.kt)

### CommonLogger
Kotlin/Java 공통 로거 래퍼.

### DataFeed

### 기타 유틸리티

---

## standard-api-annotation
이 라이브러리에서 정의한 어노테이션 모음

## standard-api-response
REST API 표준 응답 포맷 직렬화/역직렬화 및 키 변환 지원 라이브러리
- [사용자 가이드](./docs/standard-api-response-library-guide.md)
- [예제 모음](./docs/standard-api-response-examples.md)
- [표준 응답 스펙](./docs/standard-api-response-specification.md)

### 의존성 (예: Gradle Kotlin DSL)
```kotlin
dependencies {
    implementation("com.hunet.common_library:common-core:<version>") // 공통 코어 모듈
    implementation("com.hunet.common_library:std-api-annotation:<version>") // @InjectDuration 등 어노테이션 모듈
    implementation("com.hunet.common_library:standard-api-response:<version>") // 표준 응답 모듈
}
```
---

# 테스트 코드와 예제 코드 동기화: 
`./gradlew syncSnippets`
