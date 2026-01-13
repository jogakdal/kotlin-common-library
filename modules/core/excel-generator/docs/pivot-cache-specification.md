# OOXML 피벗 테이블 캐시 스펙 분석

## 개요

이 문서는 Excel이 피벗 테이블을 저장할 때 생성하는 XML 구조를 분석하여,
Apache POI로 생성한 피벗 테이블에 캐시를 미리 채워 `refreshOnLoad` 없이 동작하도록 하기 위한 참조 문서입니다.

## 파일 구조

```
xlsx 파일
├── xl/
│   ├── workbook.xml                    # pivotCaches 정의 (cacheId 매핑)
│   ├── pivotCache/
│   │   ├── pivotCacheDefinition1.xml   # 필드 정의, sharedItems
│   │   └── pivotCacheRecords1.xml      # 실제 데이터 레코드
│   └── pivotTables/
│       └── pivotTable1.xml             # 피벗 테이블 레이아웃
```

## 소유권 관계

```
Workbook
├── owns → pivotCacheDefinition
│            └── owns → pivotCacheRecords
└── owns → Sheet
             └── owns → pivotTable
                          └── references (not owns) → pivotCacheDefinition
```

- 여러 피벗 테이블이 하나의 캐시를 공유할 수 있음
- pivotTable은 cacheId로 pivotCacheDefinition을 참조

---

## 1. pivotCacheDefinition

### 1.1 주요 속성

| 속성 | 설명 | Apache POI 기본값 | Excel 저장 값 |
|------|------|-------------------|---------------|
| `refreshOnLoad` | 파일 열 때 캐시 새로고침 | `"true"` | `"1"` 또는 `"0"` |
| `recordCount` | 캐시 레코드 수 | (없음) | `"3"` |
| `refreshedVersion` | 새로고침한 앱 버전 | `"3"` | `"8"` |
| `createdVersion` | 생성한 앱 버전 | `"3"` | `"3"` |

### 1.2 cacheFields 구조

각 소스 데이터 열에 대해 `cacheField` 요소가 존재합니다.

```xml
<cacheFields count="3">
  <!-- 일반 문자열 필드 (피벗 축에 사용 안 됨) -->
  <cacheField name="이름" numFmtId="0">
    <sharedItems/>
  </cacheField>

  <!-- 축 필드 (Row/Column Label로 사용됨) - sharedItems에 고유값 필요 -->
  <cacheField name="직급" numFmtId="0">
    <sharedItems count="3">
      <s v="부장"/>
      <s v="과장"/>
      <s v="대리"/>
    </sharedItems>
  </cacheField>

  <!-- 숫자 필드 (Values 영역에 사용됨) -->
  <cacheField name="급여" numFmtId="176">
    <sharedItems containsSemiMixedTypes="0" containsString="0"
                 containsNumber="1" containsInteger="1"
                 minValue="4500" maxValue="8000"/>
  </cacheField>
</cacheFields>
```

### 1.3 sharedItems 규칙

| 필드 용도 | sharedItems 내용 | 비고 |
|-----------|------------------|------|
| 축 필드 (Row/Column Label) | 고유값 목록 `<s v="..."/>` | count 속성 필수 |
| 숫자 필드 (Values) | 메타데이터만 (min, max, 타입) | 값 없음 |
| 미사용 문자열 필드 | 비어있음 `<sharedItems/>` | |

### 1.4 숫자 필드 sharedItems 속성

```xml
<sharedItems
  containsSemiMixedTypes="0"   <!-- 혼합 타입 포함 여부 -->
  containsString="0"           <!-- 문자열 포함 여부 -->
  containsNumber="1"           <!-- 숫자 포함 여부 -->
  containsInteger="1"          <!-- 정수만 포함 여부 -->
  minValue="4500"              <!-- 최소값 -->
  maxValue="8000"/>            <!-- 최대값 -->
```

---

## 2. pivotCacheRecords

### 2.1 구조

```xml
<pivotCacheRecords xmlns="..." count="3">
  <r>
    <s v="김철수"/>    <!-- 문자열 값 직접 저장 -->
    <x v="0"/>         <!-- 축 필드: sharedItems 인덱스 참조 -->
    <n v="8000"/>      <!-- 숫자 값 직접 저장 -->
  </r>
  <r>
    <s v="이영희"/>
    <x v="1"/>
    <n v="6500"/>
  </r>
  <r>
    <s v="박민수"/>
    <x v="2"/>
    <n v="4500"/>
  </r>
</pivotCacheRecords>
```

### 2.2 값 타입

| 요소 | 설명 | 사용 시점 |
|------|------|-----------|
| `<s v="..."/>` | 문자열 값 | 축이 아닌 문자열 필드 |
| `<n v="..."/>` | 숫자 값 | 숫자 필드 |
| `<x v="N"/>` | sharedItems 인덱스 | 축 필드 (Row/Column Label) |
| `<b v="..."/>` | 불리언 값 | 불리언 필드 |
| `<d v="..."/>` | 날짜/시간 값 | 날짜 필드 |
| `<e v="..."/>` | 오류 값 | 오류가 있는 셀 |
| `<m/>` | 빈 값 | null/missing 데이터 |

### 2.3 핵심 규칙

**축 필드 (axis="axisRow" 또는 axis="axisCol"):**
- pivotCacheRecords에서 `<x v="index"/>` 형식 사용
- index는 해당 cacheField의 sharedItems 내 순서 (0-based)

**비축 필드:**
- pivotCacheRecords에서 직접 값 저장 (`<s>`, `<n>` 등)

---

## 3. pivotTableDefinition

### 3.1 Apache POI vs Excel 차이점

| 항목 | Apache POI | Excel |
|------|------------|-------|
| location ref | `"I6:J7"` (잘못된 크기) | `"I6:J10"` (정확한 크기) |
| Boolean 형식 | `"true"/"false"` | `"0"/"1"` |
| pivotField items | `<item t="default"/>` | `<item x="0"/><item x="1"/>...` |
| rowItems | (없음) | 있음 |
| colItems | (없음) | 있음 |
| dataField.baseField | (없음) | `baseField="0" baseItem="0"` |

### 3.2 location 계산

```
ref 범위 = 시작셀 : 끝셀
행 수 = 1 (헤더) + 데이터 항목 수 + 1 (총합계)

예: 3개 고유값이면
- 시작: I6
- 행 수: 1 + 3 + 1 = 5
- 끝: J10 (I6부터 5행, 2열)
```

### 3.3 pivotField items 구조

```xml
<!-- Apache POI 생성 (잘못됨) -->
<items count="4">
  <item t="default"/>
  <item t="default"/>
  <item t="default"/>
  <item t="default"/>
</items>

<!-- Excel 저장 (올바름) -->
<items count="4">
  <item x="0"/>         <!-- sharedItems[0] 참조 -->
  <item x="1"/>         <!-- sharedItems[1] 참조 -->
  <item x="2"/>         <!-- sharedItems[2] 참조 -->
  <item t="default"/>   <!-- 총합계 행 -->
</items>
```

### 3.4 rowItems 구조

```xml
<rowItems count="4">
  <i><x/></i>           <!-- 첫 번째 항목 (x 생략 = 0) -->
  <i><x v="1"/></i>     <!-- 두 번째 항목 -->
  <i><x v="2"/></i>     <!-- 세 번째 항목 -->
  <i t="grand"><x/></i> <!-- 총합계 행 -->
</rowItems>
```

### 3.5 colItems 구조

```xml
<colItems count="1">
  <i/>                  <!-- 데이터 필드용 열 -->
</colItems>
```

---

## 4. 구현 체크리스트

### 4.1 pivotCacheDefinition 수정

- [ ] `refreshOnLoad="0"` 설정
- [ ] `recordCount="N"` 추가 (N = 데이터 행 수)
- [ ] `refreshedVersion="8"` 업데이트
- [ ] 축 필드의 sharedItems에 고유값 채우기
- [ ] 숫자 필드의 sharedItems에 메타데이터 추가

### 4.2 pivotCacheRecords 생성

- [ ] `count="N"` 속성 설정
- [ ] 각 데이터 행에 대해 `<r>` 요소 생성
- [ ] 축 필드: `<x v="index"/>` (sharedItems 인덱스)
- [ ] 비축 문자열: `<s v="값"/>`
- [ ] 숫자: `<n v="값"/>`

### 4.3 pivotTableDefinition 수정

- [ ] Boolean 값 `"true"`→`"0"`, `"false"`→`"1"` 변환
- [ ] `updatedVersion="8"` 설정
- [ ] location ref 업데이트 (올바른 행 수)
- [ ] pivotField items를 `<item x="N"/>` 형식으로 변경
- [ ] rowItems 추가
- [ ] colItems 추가
- [ ] dataField에 `baseField="0" baseItem="0"` 추가

---

## 5. 참고 자료

- [ECMA-376 표준](https://ecma-international.org/publications-and-standards/standards/ecma-376/)
- [Microsoft Learn - Working with PivotTables](https://learn.microsoft.com/en-us/office/open-xml/spreadsheet/working-with-pivottables)
- [c-rex.net - pivotCacheDefinition](https://c-rex.net/samples/ooxml/e1/Part4/OOXML_P4_DOCX_pivotCacheDefinition_topic_ID0EKERAB.html)
- [c-rex.net - pivotCacheRecords](https://c-rex.net/samples/ooxml/e1/Part4/OOXML_P4_DOCX_pivotCacheRecords_topic_ID0ESTSAB.html)
