# hunet-common-libs

멀티 모듈 공통 라이브러리. 각 모듈별 상세 문서는 해당 모듈 디렉터리의 README를 참고.

## 문서 (Documentation)
- 통합 문서 인덱스: [apidoc/index.md](apidoc/index.md)
- TBEG 매뉴얼: [modules/report/template-based-excel-generator/manual/index.md](modules/report/template-based-excel-generator/manual/index.md)

## 주요 모듈
- common-core
- standard-api-response
- std-api-annotations
- apidoc-annotations
- apidoc-core
- jpa-repository-extension
- test-support
- tbeg (template-based-excel-generator)

## 모듈 & 패키지 구조
각 모듈에서 제공하는 대표(루트/기능) 패키지 목록입니다. 테스트나 예제 전용(*.test, *.examples 등) 패키지는 필요 시만 참고하세요.

### common-core (`modules/core/common-core`)
- `com.hunet.common.lib` : 범용 유틸/컨텍스트/변수 처리 (VariableProcessor 등)
- `com.hunet.common.logging` : 로그 유틸
- `com.hunet.common.util` : 날짜/공통 유틸리티
- (tests/examples) `com.hunet.common.lib.examples`

### standard-api-response (`modules/response/standard-api-response`)
- `com.hunet.common.stdapi.response` : 표준 API 응답 구성, 직렬화, Advice, AutoConfiguration

### apidoc-core (`modules/apidoc/apidoc-core`)
- `com.hunet.common.apidoc.core` : 문서화 핵심 헬퍼
- `com.hunet.common.apidoc.scan` : Enum 등 스캔 지원
- `com.hunet.common.lib.std_api_documentation` : 표준 API 문서화 레거시 경로 (호환 목적)

### apidoc-annotations (`modules/apidoc/apidoc-annotations`)
- `com.hunet.common.apidoc.annotations` : Swagger 및 문서 애노테이션 래퍼
- `com.hunet.common.apidoc.enum` : Enum 관련 문서/직렬화 지원

### jpa-repository-extension (`modules/jpa/jpa-repository-extension`)
- `com.hunet.common.data.jpa.sequence` : 시퀀스 생성기
- `com.hunet.common.data.jpa.softdelete` : SoftDelete JPA 확장 (Repository, Properties 등)
- `com.hunet.common.data.jpa.softdelete.annotation` : 삭제 마킹 애노테이션
- `com.hunet.common.data.jpa.softdelete.internal` : 내부 구현(타입 정의 등)
- `com.hunet.common.data.jpa.converter` : 범용 Enum 컨버터
- (tests) `com.hunet.common.data.jpa.softdelete.test`

### test-support (`modules/test/test-support`)
- `com.hunet.common.test.support` : 통합 테스트 지원 (Controller Test 등)

### tbeg (`modules/report/template-based-excel-generator`)
- `com.hunet.common.tbeg` : 템플릿 기반 Excel 생성기 (TBEG)
- `com.hunet.common.tbeg.async` : 비동기 생성 지원 (Job, Listener)
- `com.hunet.common.tbeg.spring` : Spring Boot 자동 설정
- (tests/examples) `com.hunet.common.tbeg.spring` : Spring Boot 샘플

## 빠른 의존성 예 (Gradle Kotlin DSL)
```kotlin
dependencies {
    implementation("com.hunet.common:common-core:<version>")
    implementation("com.hunet.common:standard-api-response:<version>")
    implementation("com.hunet.common:std-api-annotations:<version>")
    implementation("com.hunet.common:apidoc-core:<version>")
    implementation("com.hunet.common:apidoc-annotations:<version>")
    implementation("com.hunet.common:jpa-repository-extension:<version>")
    implementation("com.hunet.common:tbeg:<version>")
    testImplementation("com.hunet.common:test-support:<version>")
}
```
`<version>` 값은 모듈별로 상이할 수 있으며 아래 "모듈 버전 관리" 참고.

## 모듈 버전 관리
`gradle.properties`에서 각 모듈별 override 속성을 통해 버전을 개별 관리합니다.
```properties
moduleVersion.common-core=1.2.1-SNAPSHOT
moduleVersion.standard-api-response=1.3.1-SNAPSHOT
moduleVersion.std-api-annotations=1.1.0-SNAPSHOT
moduleVersion.apidoc-core=1.1.0-SNAPSHOT
moduleVersion.apidoc-annotations=1.1.0-SNAPSHOT
moduleVersion.jpa-repository-extension=1.2.0-SNAPSHOT
moduleVersion.test-support=1.1.1-SNAPSHOT
moduleVersion.tbeg=1.0.0-SNAPSHOT
```
- 루트 `version` (예: 1.1.0-SNAPSHOT)이 기본이며, 존재하는 `moduleVersion.*` 키가 우선 적용됩니다.

## 빠른 사용 예 (Quick Usage)
### 1. Standard API Response
```kotlin
@RestController
@RequestMapping("/examples")
class ExampleController {
    @GetMapping("/ping")
    fun ping(): StandardResponse<SimplePayload> = StandardResponse.ok(SimplePayload("pong"))
}

data class SimplePayload(val message: String) : BasePayload
```
- `StandardResponse.ok(...)` 등 팩토리 사용.
- 키/필드 alias 및 직렬화 정책은 `JsonConfig` / `KeyNormalization` 등으로 커스터마이즈.

### 2. SoftDelete & Sequence (JPA)
```kotlin
@Entity
@DeleteMark
class UserEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    val name: String,
    var deleted: Boolean = false // DeleteMark 타입에 따라 필드 의미 달라질 수 있음
)
```
시퀀스 직접 사용 예(테스트/유틸 내부):
```kotlin
@Component
class OrderNumberService(private val generator: SequenceGenerator) {
    fun nextOrderNumber(): Long = generator.next("order.seq")
}
```

### 3. VariableProcessor (common-core)
`apidoc/variable-processor-examples.md` 에 동기화된 최신 Kotlin/Java 예제가 존재합니다. 스니펫 재동기화:
```bash
./gradlew syncSnippets
```

### 4. 통합 테스트 지원 (test-support)
```kotlin
class UserControllerTest : AbstractControllerTest() {
    @Test
    fun ping() {
        // request("GET", "/examples/ping") 등의 헬퍼 사용 가능
    }
}
```
`AbstractControllerTest` 는 스프링 컨텍스트 + MockMvc(or WebTestClient) 세팅을 캡슐화합니다.

### 5. TBEG (Template-Based Excel Generator)
```kotlin
@Service
class ReportService(
    private val excelGenerator: ExcelGenerator,
    private val resourceLoader: ResourceLoader
) {
    fun generateReport(): ByteArray {
        val template = resourceLoader.getResource("classpath:templates/report.xlsx")
        val data = mapOf(
            "title" to "직원 현황",
            "employees" to employeeRepository.findAll()
        )
        return excelGenerator.generate(template.inputStream, data)
    }
}
```
템플릿에서 `${title}`, `${repeat(employees, A3:C3, emp)}` 문법으로 데이터 바인딩. 상세 문서: [tbeg](modules/report/template-based-excel-generator/manual/index.md)

## 빌드 & 문서 작업
```bash
# 전체 빌드
./gradlew build

# 문서 내 버전 정보 갱신 (standard-api-response-library-guide.md)
./gradlew updateLibraryGuideVersions

# 문서 관련 태스크 집합(docs) 실행
./gradlew docs
```

## 스니펫 동기화
```bash
./gradlew syncSnippets
```
