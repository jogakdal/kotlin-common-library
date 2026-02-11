# TBEG Changelog

## 1.1.1

### 버그 수정

- **같은 행의 다중 독립 repeat 영역 처리 수정**: 하나의 행에 열 범위가 겹치지 않는 여러 repeat 영역이 있을 때 일부 영역이 누락되거나 non-repeat 셀이 중복 렌더링되는 버그 수정
  - `TemplateAnalyzer`: repeat 영역을 `SheetSpec.repeatRegions`로 분리하여 같은 행의 모든 repeat 영역 보존
  - `SxssfRenderingStrategy`/`XssfRenderingStrategy`: non-repeat 셀 필터링 시 같은 행의 모든 repeat 열 범위를 합산하여 판별
  - 스트리밍 모드에서 repeat 영역별 독립 추적 (`RepeatKey`: sheetIndex, collectionName, startRow, startCol)
- **다중 repeat 변수 인식**: `UnifiedMarkerParser`가 같은 행의 여러 repeat 변수를 모두 인식하도록 `repeatItemVariable: String` → `repeatItemVariables: Set<String>`으로 변경

### 새 기능

- **중복 마커 감지**: 범위를 취급하는 마커(repeat, image)에 대해 중복 감지 기능 추가
  - repeat: 같은 컬렉션 + 같은 대상 범위(시트+영역)가 중복되면 경고 로그 출력 후 마지막 마커만 유지
  - image: 같은 이름 + 같은 위치(시트+셀) + 같은 크기가 중복되면 경고 로그 출력 후 마지막 마커만 유지
  - 시트 간 중복도 감지 (시트 접두사로 같은 대상을 참조하는 경우)
  - `TemplateAnalyzer`를 4단계 분석 구조로 개편 (수집 → repeat 중복 제거 → SheetSpec 생성 → 셀 마커 중복 제거)

### 구조 개선

- **행 명세 구조 전면 개선**: `RowSpec`을 sealed class(`StaticRow`/`RepeatRow`/`RepeatContinuation`)에서 단일 data class로 단순화하고, repeat 정보를 `RepeatRegionSpec`으로 분리하여 행 단위 처리의 복잡도를 근본적으로 축소
- **공통 타입 도입**: `IndexRange`/`RowRange`/`ColRange` 범위 타입과 `CollectionSizes` value class를 도입하여 원시 타입 사용을 의미 있는 타입으로 통합 (`CollectionSizes.of()`, `buildCollectionSizes` 팩토리 제공)
- **`CellCoord` 타입 공개 및 활용 확대**: `TemplateAnalyzer` 내부 private class였던 `CellCoord`를 공개 타입으로 전환하고, row/col 정수 페어 패턴을 `CellCoord` 직접 전달 방식으로 개선
- **`CellArea` 타입 도입 및 `RepeatRegionSpec` 단순화**: 셀 영역(start/end 좌표)을 표현하는 `CellArea` 타입을 도입하고, `RepeatRegionSpec`의 개별 좌표 프로퍼티(startRow, endRow, startCol, endCol)와 계산 프로퍼티를 제거하여 `area: CellArea` 단일 프로퍼티로 통합. 영역 겹침 판별 로직(`overlapsColumns`, `overlapsRows`, `overlaps`)을 `CellArea`로 이동
- 내부 코드 리팩토링 및 KDoc 현행화

### 테스트

- 같은 행의 다중 독립 repeat 영역 검증 테스트 추가 (XSSF/SXSSF 모드별 매개변수화 테스트)
- 중복 마커 감지 테스트 추가 (repeat 7건 + image 6건: 같은 시트/시트 간, 중복/비중복 케이스)

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
