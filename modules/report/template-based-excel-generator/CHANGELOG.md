# TBEG Changelog

## 1.1.1

### 버그 수정

- **같은 행의 다중 독립 repeat 영역 처리 수정**: 하나의 행에 열 범위가 겹치지 않는 여러 repeat 영역이 있을 때 일부 영역이 누락되거나 non-repeat 셀이 중복 렌더링되는 버그 수정
  - `TemplateAnalyzer`: repeat 영역 매핑을 `associateBy` → `groupBy`로 변경하여 같은 행의 모든 repeat 영역 보존
  - `SxssfRenderingStrategy`/`XssfRenderingStrategy`: non-repeat 셀 필터링 시 같은 행의 모든 repeat 열 범위를 합산하여 판별
  - 스트리밍 모드에서 repeat 처리 추적 단위를 `templateRowIndex` → `(templateRowIndex, collectionName)` 쌍으로 세분화
- **다중 repeat 변수 인식**: `UnifiedMarkerParser`가 같은 행의 여러 repeat 변수를 모두 인식하도록 `repeatItemVariable: String` → `repeatItemVariables: Set<String>`으로 변경

### 구조 개선

- **행 명세 구조 전면 개선**: `RowSpec`을 sealed class(`StaticRow`/`RepeatRow`/`RepeatContinuation`)에서 단일 data class로 단순화하고, repeat 정보를 `RepeatRegionSpec`으로 분리하여 행 단위 처리의 복잡도를 근본적으로 축소
- **공통 타입 도입**: `IndexRange`/`RowRange`/`ColRange` 범위 타입과 `CollectionSizes` value class를 도입하여 원시 타입 사용을 의미 있는 타입으로 통합 (`CollectionSizes.of()`, `buildCollectionSizes` 팩토리 제공)
- **`CellCoord` 타입 공개 및 활용 확대**: `TemplateAnalyzer` 내부 private class였던 `CellCoord`를 공개 타입으로 전환하고, row/col 정수 페어 패턴을 `CellCoord` 직접 전달 방식으로 개선
- 내부 코드 리팩토링 및 KDoc 현행화

### 테스트

- 같은 행의 다중 독립 repeat 영역 검증 테스트 추가 (XSSF/SXSSF 모드별 매개변수화 테스트)

## 1.1.0

### 새 기능

- **빈 컬렉션 처리**: repeat 영역의 데이터가 빈 컬렉션일 때 `emptyRange` 파라미터로 대체 콘텐츠를 지정할 수 있는 기능 추가
- **같은 행의 다중 독립 repeat 영역 지원**: 하나의 행에 서로 독립적인 여러 repeat 영역을 배치 가능
- **크로스 시트 수식 참조 확장**: 다른 시트의 repeat 영역을 참조하는 수식을 자동 확장

### 개선

- **통합 마커 파서 도입**: `UnifiedMarkerParser`로 REPEAT, IMAGE, SIZE 마커의 파싱 로직을 일원화
- **설정 클래스 리네이밍**: `ExcelGeneratorConfig` → `TbegConfig`, `ExcelPipeline` → `TbegPipeline` (후방 호환 타입 별칭 제공)
- 절대 참조(`$`) 파싱 지원
- 조건부 서식 처리 로직 개선 및 유틸리티 추출
- BOM 모듈 추가

### 테스트 및 샘플

- Kotlin/Java 샘플 6가지 사용 패턴 (기본, 지연 로딩, 비동기, 대용량, 암호화, 메타데이터)
- XSSF vs SXSSF 성능 벤치마크 추가
- 빈 컬렉션 처리 테스트 및 매개변수화 테스트 도입
