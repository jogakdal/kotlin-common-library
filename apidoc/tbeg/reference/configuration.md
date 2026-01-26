# Excel Generator 설정 옵션 레퍼런스

## 목차
1. [ExcelGeneratorConfig](#1-excelgeneratorconfig)
2. [Spring Boot 프로퍼티](#2-spring-boot-프로퍼티)
3. [Enum 타입](#3-enum-타입)
4. [프리셋 설정](#4-프리셋-설정)

---

## 1. ExcelGeneratorConfig

### 패키지
```kotlin
com.hunet.common.tbeg.ExcelGeneratorConfig
```

### 전체 옵션

| 옵션 | 타입 | 기본값 | 설명 |
|------|------|--------|------|
| `streamingMode` | `StreamingMode` | `AUTO` | 스트리밍 모드 설정 |
| `streamingRowThreshold` | `Int` | `1000` | AUTO 모드에서 SXSSF 전환 기준 행 수 |
| `fileNamingMode` | `FileNamingMode` | `TIMESTAMP` | 파일명 생성 모드 |
| `timestampFormat` | `String` | `"yyyyMMdd_HHmmss"` | 파일명 타임스탬프 형식 |
| `fileConflictPolicy` | `FileConflictPolicy` | `SEQUENCE` | 파일명 충돌 시 처리 정책 |
| `progressReportInterval` | `Int` | `100` | 진행률 콜백 호출 간격 (행 수) |
| `preserveTemplateLayout` | `Boolean` | `true` | 템플릿 레이아웃(열 폭, 행 높이) 보존 |
| `pivotIntegerFormatIndex` | `Short` | `37` | 피벗 테이블 정수 필드 포맷 인덱스 |
| `pivotDecimalFormatIndex` | `Short` | `39` | 피벗 테이블 소수점 필드 포맷 인덱스 |

---

### 옵션 상세

#### streamingMode

스트리밍 모드를 설정합니다. 대용량 데이터 처리 시 메모리 효율성을 위해 사용됩니다.

| 값 | 설명 |
|----|------|
| `AUTO` | 데이터 크기에 따라 자동 결정 (기본값) |
| `ENABLED` | 항상 SXSSF(Streaming) 사용 - 대용량에 적합 |
| `DISABLED` | 항상 XSSF 사용 - 소량에 적합, 모든 기능 지원 |

```kotlin
ExcelGeneratorConfig(streamingMode = StreamingMode.ENABLED)
```

#### streamingRowThreshold

`streamingMode = AUTO`일 때 SXSSF로 전환되는 행 수 기준입니다.

```kotlin
ExcelGeneratorConfig(
    streamingMode = StreamingMode.AUTO,
    streamingRowThreshold = 500  // 500행 초과 시 스트리밍 모드
)
```

#### fileNamingMode

`generateToFile()` 사용 시 파일명 생성 모드입니다.

| 값 | 예시 |
|----|------|
| `NONE` | `report.xlsx` |
| `TIMESTAMP` | `report_20260115_143052.xlsx` (기본값) |

```kotlin
ExcelGeneratorConfig(fileNamingMode = FileNamingMode.NONE)
```

#### timestampFormat

`fileNamingMode = TIMESTAMP`일 때 사용되는 타임스탬프 형식입니다.

```kotlin
ExcelGeneratorConfig(
    fileNamingMode = FileNamingMode.TIMESTAMP,
    timestampFormat = "yyyy-MM-dd_HH-mm"  // report_2026-01-15_14-30.xlsx
)
```

#### fileConflictPolicy

동일한 파일명이 이미 존재할 때 처리 정책입니다.

| 값 | 동작 |
|----|------|
| `ERROR` | `FileAlreadyExistsException` 발생 |
| `SEQUENCE` | 시퀀스 번호 추가: `report_1.xlsx`, `report_2.xlsx` (기본값) |

```kotlin
ExcelGeneratorConfig(fileConflictPolicy = FileConflictPolicy.ERROR)
```

#### progressReportInterval

비동기 작업에서 `onProgress` 콜백이 호출되는 간격 (행 수)입니다.

```kotlin
ExcelGeneratorConfig(progressReportInterval = 500)  // 500행마다 콜백
```

#### preserveTemplateLayout

`${repeat(...)}`으로 행이 확장될 때 원본 템플릿의 열 폭과 행 높이를 보존할지 여부입니다.

- `true`: 레이아웃 보존 (기본값)
- `false`: 기본 동작 (일부 레이아웃 변경 가능)

```kotlin
ExcelGeneratorConfig(preserveTemplateLayout = true)
```

#### pivotIntegerFormatIndex / pivotDecimalFormatIndex

피벗 테이블 값 필드에 적용할 Excel 내장 포맷 인덱스입니다.

| 인덱스 | 형식 |
|--------|------|
| 37 | `#,##0 ;(#,##0)` - 정수, 천 단위 구분 (기본값) |
| 38 | `#,##0 ;[Red](#,##0)` - 정수, 음수 빨간색 |
| 39 | `#,##0.00 ;(#,##0.00)` - 소수점 2자리 (기본값) |
| 40 | `#,##0.00 ;[Red](#,##0.00)` - 소수점, 음수 빨간색 |

```kotlin
ExcelGeneratorConfig(
    pivotIntegerFormatIndex = 38,  // 음수 빨간색
    pivotDecimalFormatIndex = 40   // 음수 빨간색
)
```

---

### 생성 방법

#### Kotlin - 직접 생성

```kotlin
val config = ExcelGeneratorConfig(
    streamingMode = StreamingMode.ENABLED,
    streamingRowThreshold = 500
)
val generator = ExcelGenerator(config)
```

#### Kotlin - copy() 사용

```kotlin
val config = ExcelGeneratorConfig()
    .withStreamingMode(StreamingMode.ENABLED)
    .withStreamingRowThreshold(500)

val generator = ExcelGenerator(config)
```

#### Java - Builder 사용

```java
ExcelGeneratorConfig config = ExcelGeneratorConfig.builder()
    .streamingMode(StreamingMode.ENABLED)
    .streamingRowThreshold(500)
    .build();

ExcelGenerator generator = new ExcelGenerator(config);
```

---

## 2. Spring Boot 프로퍼티

### 패키지
```kotlin
com.hunet.common.tbeg.spring.ExcelGeneratorProperties
```

### application.yml 예시

```yaml
hunet:
  excel:
    # 스트리밍 모드: auto, enabled, disabled
    streaming-mode: auto

    # AUTO 모드에서 SXSSF 전환 기준 행 수
    streaming-row-threshold: 1000

    # 파일명 생성 모드: none, timestamp
    file-naming-mode: timestamp

    # 파일명 타임스탬프 형식
    timestamp-format: yyyyMMdd_HHmmss

    # 파일명 충돌 정책: error, sequence
    file-conflict-policy: sequence

    # 진행률 콜백 호출 간격
    progress-report-interval: 100

    # 템플릿 레이아웃 보존
    preserve-template-layout: true

    # 피벗 테이블 정수 필드 포맷 인덱스
    pivot-integer-format-index: 37

    # 피벗 테이블 소수점 필드 포맷 인덱스
    pivot-decimal-format-index: 39
```

### 프로퍼티 매핑

| application.yml 키 | ExcelGeneratorConfig 속성 |
|--------------------|--------------------------|
| `streaming-mode` | `streamingMode` |
| `streaming-row-threshold` | `streamingRowThreshold` |
| `file-naming-mode` | `fileNamingMode` |
| `timestamp-format` | `timestampFormat` |
| `file-conflict-policy` | `fileConflictPolicy` |
| `progress-report-interval` | `progressReportInterval` |
| `preserve-template-layout` | `preserveTemplateLayout` |
| `pivot-integer-format-index` | `pivotIntegerFormatIndex` |
| `pivot-decimal-format-index` | `pivotDecimalFormatIndex` |

---

## 3. Enum 타입

### StreamingMode

```kotlin
enum class StreamingMode {
    DISABLED,  // 스트리밍 비활성화 (XSSF 사용)
    ENABLED,   // 스트리밍 활성화 (SXSSF 사용)
    AUTO       // 자동 결정
}
```

| 값 | 사용 시나리오 |
|----|-------------|
| `DISABLED` | 소량 데이터, 모든 Excel 기능 필요 |
| `ENABLED` | 대용량 데이터, 메모리 효율성 우선 |
| `AUTO` | 일반적인 경우 (데이터 크기에 따라 자동 결정) |

### FileNamingMode

```kotlin
enum class FileNamingMode {
    NONE,      // 기본 파일명만 사용
    TIMESTAMP  // 타임스탬프 추가
}
```

| 값 | 결과 예시 |
|----|----------|
| `NONE` | `report.xlsx` |
| `TIMESTAMP` | `report_20260115_143052.xlsx` |

### FileConflictPolicy

```kotlin
enum class FileConflictPolicy {
    ERROR,     // 예외 발생
    SEQUENCE   // 시퀀스 번호 추가
}
```

| 값 | 동작 |
|----|------|
| `ERROR` | 파일 존재 시 `FileAlreadyExistsException` 발생 |
| `SEQUENCE` | `report.xlsx` → `report_1.xlsx` → `report_2.xlsx` |

---

## 4. 프리셋 설정

### default()

기본 설정을 반환합니다.

```kotlin
val config = ExcelGeneratorConfig.default()
// 또는
val config = ExcelGeneratorConfig()
```

### forLargeData()

대용량 데이터 처리에 최적화된 설정입니다.

```kotlin
val config = ExcelGeneratorConfig.forLargeData()
// streamingMode = ENABLED
// progressReportInterval = 500
```

### forSmallData()

소량 데이터 처리에 최적화된 설정입니다.

```kotlin
val config = ExcelGeneratorConfig.forSmallData()
// streamingMode = DISABLED
```

---

## 설정 예시

### 대용량 보고서 생성

```kotlin
val config = ExcelGeneratorConfig(
    streamingMode = StreamingMode.ENABLED,
    streamingRowThreshold = 500,
    progressReportInterval = 1000
)
```

### 파일 중복 방지 (오류 발생)

```kotlin
val config = ExcelGeneratorConfig(
    fileNamingMode = FileNamingMode.NONE,
    fileConflictPolicy = FileConflictPolicy.ERROR
)
```

### 커스텀 타임스탬프 형식

```kotlin
val config = ExcelGeneratorConfig(
    fileNamingMode = FileNamingMode.TIMESTAMP,
    timestampFormat = "yyyy-MM-dd"  // report_2026-01-15.xlsx
)
```

---

## 다음 단계

- [API 레퍼런스](./api-reference.md) - ExcelGenerator API 상세
- [사용자 가이드](../user-guide.md) - 전체 가이드
