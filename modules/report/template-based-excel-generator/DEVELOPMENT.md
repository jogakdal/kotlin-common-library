# TBEG 개발 가이드

이 문서는 Template-Based Excel Generator(TBEG) 모듈의 구현 원칙과 개발 가이드라인을 정의합니다.

## 구현 원칙

### 1. 렌더링 원칙

#### 1.1 템플릿 서식 완전 보존
템플릿에 작성된 모든 서식(정렬, 글꼴, 색상, 테두리, 채우기, 행 높이 등)은 생성된 Excel에 동일하게 적용되어야 합니다.

#### 1.2 Repeat 행 스타일 원칙
repeat에서 확장된 모든 행은 **repeat 기준 템플릿 행**의 스타일(높이, 서식)을 따릅니다.

- 단일 행 repeat: 모든 확장 행이 동일한 템플릿 행의 스타일 적용
- 다중 행 repeat: 템플릿 행 패턴이 순환하며 적용

```
예시: 단일 행 repeat (templateRow 6)
- actualRow 6 -> templateRow 6 스타일
- actualRow 7 -> templateRow 6 스타일
- actualRow 8 -> templateRow 6 스타일
```

#### 1.3 행 높이 충돌 해결
같은 actualRow에 여러 템플릿 행이 매핑되는 경우 (서로 다른 열 그룹), `maxOf`로 가장 높은 높이를 적용합니다.

- 이유: Excel에서 행 높이는 행 전체에 적용되므로, 모든 셀이 잘 표시되도록 함
- 처리 순서와 무관하게 일관된 결과 보장

#### 1.4 열 그룹 독립성
서로 다른 열 범위의 repeat은 독립적으로 위치가 계산됩니다.

```
예시:
- A-C 열: employees repeat (확장 +2행)
- F-H 열: department repeat (확장 +3행)

actualRow 10에서:
- A-C 열 관점: templateRow = actualRow - employees확장량
- F-H 열 관점: templateRow = actualRow - department확장량 (해당 열 범위에서만)
```

### 2. 메모리 관리 원칙 (SXSSF 모드)

#### 2.1 컬렉션 전체 메모리 로드 금지
SXSSF 모드에서는 대용량 데이터 처리를 위해 컬렉션 전체를 메모리에 올리지 않습니다.

| 모드 | 메모리 정책 | 이유 |
|------|------------|------|
| SXSSF | 컬렉션 전체를 메모리에 올리지 않음 | 대용량 데이터 처리 목적 |
| XSSF | 전체 메모리 로드 허용 | 소량 데이터 전용 모드 |

#### 2.2 DataProvider 재호출 방식
같은 컬렉션이 여러 repeat 영역에서 사용되면 DataProvider를 재호출합니다.

- 임시 파일(DiskCachedCollection) 사용하지 않음
- DataProvider.getItems()가 같은 데이터를 다시 제공할 수 있어야 함 (사용자 조건)

#### 2.3 Iterator 순차 소비
현재 처리 중인 아이템 1개만 메모리에 유지합니다.

```kotlin
// StreamingDataSource 사용
fun advanceToNextItem(repeatKey: RepeatKey): Any?
fun getCurrentItem(repeatKey: RepeatKey): Any?
```

### 3. 위치 계산 원칙

#### 3.1 수식/범위 자동 확장
repeat 영역에 포함된 수식과 범위 참조는 확장량만큼 자동 조정됩니다.

```
예시: =SUM(C6) -> =SUM(C6:C105) (100개 아이템 확장 시)
```

#### 3.2 정적 요소 위치 이동
repeat 확장에 영향받는 정적 요소(수식, 병합 셀, 조건부 서식 등)는 확장량만큼 밀립니다.

#### 3.3 PositionCalculator 위치 결정 규칙

1. 어느 repeat에도 영향받지 않는 요소: 템플릿 위치 그대로 고정
2. 하나의 repeat에만 영향받는 요소: 그 repeat 확장만큼 밀림
3. 두 개 이상의 repeat에 영향받는 요소: 가장 많이 밀리는 위치로 이동

### 4. 숫자 서식 원칙

#### 4.1 자동 숫자 서식 지정 조건
라이브러리에 의해 자동 생성된 값이 숫자 타입이고, 해당 셀의 "표시 형식"이 없거나 "일반"인 경우 자동으로 숫자 서식을 적용합니다.

#### 4.2 자동 정렬 지정 조건
라이브러리에 의해 자동 생성된 값이 숫자 타입이고, 해당 셀의 정렬이 "일반"인 경우 자동으로 오른쪽 정렬을 적용합니다.

#### 4.3 기존 서식 보존
위 4.1, 4.2 조건에 해당하더라도 해당 셀의 나머지 모든 서식(글꼴, 색상, 테두리 등)은 템플릿 서식을 유지합니다.

## StyleInfo 필수 속성

스타일 복사 시 유지해야 하는 속성 목록:

- `horizontalAlignment`: 가로 정렬
- `verticalAlignment`: 세로 정렬
- `fontBold`: 굵게
- `fontItalic`: 기울임꼴
- `fontUnderline`: 밑줄
- `fontStrikeout`: 취소선
- `fontName`: 글꼴 이름
- `fontSize`: 글꼴 크기
- `fontColorRgb`: 글꼴 색상
- `fillForegroundColorRgb`: 채우기 색상
- `fillPatternType`: 채우기 패턴
- `borderTop/Bottom/Left/Right`: 테두리
- `dataFormat`: 표시 형식
