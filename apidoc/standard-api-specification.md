# Standard API 사양 (Specification)

> ### 관련 문서 (Cross References)
> | 문서                                                                               | 목적 / 차이점                                                         |
> |----------------------------------------------------------------------------------|------------------------------------------------------------------|
> | [standard-api-response-library-guide.md](standard-api-response-library-guide.md) | 라이브러리 **사용자 가이드**: 표준 응답 생성, 역직렬화, 케이스 변환, 사용 패턴 심화 설명           |
> | [standard-api-response-reference.md](standard-api-response-reference.md)         | **레퍼런스 매뉴얼**: 모듈 / 내부 타입 세부 설명                                   |
> | [standard-api-response-examples.md](standard-api-response-examples.md)           | 실전 예시 모음: payload 구성, 페이지/커서 처리, 역직렬화, 케이스 변환, Alias/Canonical 등 |
>
> 우선순위 충돌 시: **spec > 사용자 가이드 > 예제**. README는 구현 레벨 참고 문서.

## API 응답 표준

- 메시지 교환 방식은 REST 방식으로 통일합니다.
- 구조화된 응답은 JSON 포맷으로 통일합니다.(HTTP 헤더에 'application/json' 명시)
- 메시지 encoding은 UTF-8로 통일합니다.
- TimeStamp 포맷은 사람이 읽기 어려우므로 지양하고 ISO-8601 계열의 DateTime 포맷을 사용합니다.
- DateTime 포맷은 **UTC (Zulu)** 또는 **명시적인 오프셋**(+09:00)을 넣어 줍니다.
- 응답 코드 또는 오류 코드에 대해서는 별도 정의 문서를 작성하여 공유합니다.
- API 문서는 **OpenAPI 3.x** 사양으로 작성합니다.

### 1. HTTP 상태 코드

HTTP 상태 코드는 요청 처리 결과를 표현하기 위해 국제 표준([RFC 7231](https://datatracker.ietf.org/doc/html/rfc7231))에 따라 사용됩니다. 단순한 통신 성공 여부만이 아닌, **요청 처리의 전반적인 상태와 결과를 표현**하기 위해 정확한 상태 코드를 반환해야 합니다.

#### 1.1 성공 응답 (2xx)

단일 리소스를 조회하는 API에서 데이터가 존재하지 않는 경우는 성공(2xx)에 해당하지 않습니다. 이 표준에서는 해당 상황을 오류(4xx)로 처리하는 것을 권장합니다.

| 상태 코드            | 설명                   | 사용 예시                                            |
|------------------|----------------------|--------------------------------------------------|
| `200 OK`         | 요청이 성공적으로 처리됨        | `GET`, `PUT`, `DELETE` 요청이 성공하고 별도의 데이터를 응답하는 경우 |
| `201 Created`    | 새로운 리소스가 성공적으로 생성됨   | `POST` 요청으로 새로운 리소스 생성 시                         |
| `204 No Content` | 요청은 성공했으나 반환할 콘텐츠 없음 | `DELETE`, `PUT` 성공하고, 별도 본문 응답이 필요 없는 경우         |

#### 1.2 리다이렉션 응답 (3xx)

| 상태 코드                   | 설명                    | 사용 예시                     |
|-------------------------|-----------------------|---------------------------|
| `301 Moved Permanently` | 리소스가 영구적으로 이동됨        | 리소스 URI 변경 후 영구적 리다이렉션 시  |
| `302 Found`             | 리소스가 임시적으로 다른 위치에 존재함 | 임시 리다이렉션 필요 시             |
| `304 Not Modified`      | 클라이언트 캐시된 버전이 여전히 유효함 | 조건부 `GET` 요청 결과 리소스 변경 없음 |

#### 1.3 클라이언트 오류 응답 (4xx)

| 상태 코드                      | 설명                                               | 사용 예시                                                                         |
|----------------------------|--------------------------------------------------|-------------------------------------------------------------------------------|
| `400 Bad Request`          | 요청 구문 오류, 파라미터 오류, 단일 리소스 조회에서 조건에 맞는 데이터가 없는 경우 | • 잘못된 형식의 요청<br>• 필수 파라미터 누락<br>• 단일 리소스 조회(단건 SELECT) 요청에서 조건에 맞는 데이터가 없는 경우 |
| `401 Unauthorized`         | 인증 실패 또는 인증 정보 누락                                | 로그인되지 않은 사용자의 인증이 필요한 요청                                                      |
| `403 Forbidden`            | 인증은 되었으나 권한이 없는 경우                               | 리소스에 대한 접근 권한 부족                                                              |
| `404 Not Found`            | 요청한 API 경로(엔드포인트)를 찾을 수 없음                       | • 존재하지 않는 URI로 요청한 경우<br>• 잘못된 경로 또는 HTTP Method 조합으로 인해 라우팅되는 리소스가 없는 경우     |
| `409 Conflict`             | 리소스 상태 충돌 발생                                     | 중복 데이터 삽입, 동시성 충돌                                                             |
| `422 Unprocessable Entity` | 요청은 유효하나 처리할 수 없는 상태                             | 유효성 검사 실패, 의미적 오류                                                             |
| `429 Too Many Requests`    | 너무 많은 요청으로 인해 제한됨                                | Rate Limit 초과                                                                 |

#### 1.4 서버 오류 응답 (5xx)

| 상태 코드                       | 설명                 | 사용 예시                     |
|-----------------------------|--------------------|---------------------------|
| `500 Internal Server Error` | 서버 내부 오류           | 예기치 않은 예외 또는 처리 실패        |
| `502 Bad Gateway`           | 게이트웨이가 잘못된 응답 수신   | 업스트림 서버 오류 발생             |
| `503 Service Unavailable`   | 서버가 일시적으로 사용 불가 상태 | 서버 점검, 과부하 등으로 일시적 서비스 중단 |
| `504 Gateway Timeout`       | 게이트웨이 응답 시간 초과     | 업스트림 서버 응답 지연             |

#### 1.5 상태 코드 및 Payload 반환 규칙

- 상태 코드와 응답 의미의 일관성
  - 오류 상황에서는 2xx 상태 코드를 사용하지 않습니다.
  - 단일 리소스 조회에서 데이터가 존재하지 않는 경우는 '리소스 부재'로 간주하며 4xx 범주로 처리합니다.
  - 정상 응답(2xx)에서는 API 명세에 정의된 스키마 구조를 그대로 유지해야 합니다.
- Payload 반환 규칙
  - null, 빈 객체({}), 빈 payload를 응답 본문으로 반환하지 않습니다.
  - 오류 상황에서는 오류 응답 스키마를 반환합니다. (오류 응답 형식 참조)
- 상태 코드 별 Payload 스키마 및 status 필드 규칙

| 상태 코드            | Payload                                                                                            | status 필드                                                                           |
|------------------|----------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------|
| **2xx** (204 제외) | API 명세에 약속된 스키마를 반환해야 합니다. 예를 들어 `Member` 타입을 반환하기로 명시된 API가 `ErrorPayload` 등 다른 스키마를 반환해서는 안 됩니다. | `SUCCESS`를 권고하지만, 상황에 따라 `SUSPENDED`, `DEPRECATED`, `STREAMING` 등 다른 값을 활용할 수 있습니다. |
| **204**          | 응답 본문이 없습니다.                                                                                       | —                                                                                   |
| **4xx / 5xx**    | 오류 정보를 담는 스키마([오류 응답 형식](#52-오류실패-응답-형식) 참조)를 포함할 것을 권고합니다.                                        | `FAILURE`로 명시할 것을 권고합니다.                                                            |

#### 1.6 단일 리소스 조회 응답 규칙

단일 리소스를 조회하는 API에서 응답할 데이터가 존재하지 않는 경우 다음 원칙을 적용합니다.

- 성공 응답(2xx)을 반환하지 않습니다.
- null, 빈 객체({}), 빈 payload를 반환하지 않습니다.
- 본 표준에서는 해당 상황을 '리소스 부재'로 간주하며 4xx 범주의 오류로 처리합니다.
- 구체적인 오류 코드는 서비스 도메인 특성에 따라 선택할 수 있습니다.

단일 리소스 조회에서 데이터가 없는 경우 예:

```json5
// HTTP 400 Bad Request
{
  "status": "FAILURE",
  "version": "1.0",
  "datetime": "2024-03-25T04:10:27.257626Z",
  "duration": 70,
  "traceid": "7f7c9e2b-5d3b-4e9e-8f11-0b2d2d7c9a01",
  "payload": {
    "errors": [
      {
        "code": "E_RESOURCE_EMPTY",
        "message": "요청 조건에 해당하는 데이터가 존재하지 않습니다."
      }
    ]
  }
}
```

### 2. 공통 헤더

클라이언트와 서버 간의 일관된 통신을 위해 모든 API 요청과 응답에 다음과 같은 공통 헤더와 인코딩 규칙을 적용합니다.

#### 2.1 요청 헤더 (Request Headers)

모든 API 요청은 아래의 헤더를 포함해야 합니다.

| 헤더       | 설명                                       |
|----------|------------------------------------------|
| `Accept` | 클라이언트가 수신 가능한 응답 형식 (`application/json`) |

#### 2.2 응답 헤더 (Response Headers)

모든 API 응답에는 다음의 헤더가 포함됩니다.

| 헤더             | 설명                                                         |
|----------------|------------------------------------------------------------|
| `Content-Type` | 응답 본문의 데이터 형식 및 문자 인코딩 (`application/json; charset=utf-8`) |

### 3. 인코딩 및 데이터 포맷 규칙

#### 3.1 문자 인코딩

- 모든 요청 및 응답은 `UTF-8` 인코딩을 사용합니다.

#### 3.2 날짜/시간 포맷

- 국제 표준인 ISO 8601 형식을 따릅니다.
- 예시:
  - `2025-05-20T08:15:30Z` (UTC)
  - `2025-05-20T17:15:30+09:00` (KST)
- ⚠️주의사항
  - 날짜/시간을 **단순 문자열(string)** 로 전달하는 방식은 **지양**합니다. → 포맷 파싱 오류나 클라이언트 간 호환성 이슈를 유발할 수 있습니다.
  - 날짜/시간에는 반드시 타임존(`Z`, `+09:00`)을 포함합니다.

#### 3.3 숫자 포맷

##### 3.3.1 정수형 (Integer)

- **JSON number 타입**으로 전달해야 합니다.

```
✅ 올바른 예:
{ "age": 25, "count": 100 }
❌ 잘못된 예:
{ "age": "25", "count": "100" }
```

##### 3.3.2 실수형 (Float / Double)

- JSON number 타입으로 소수점 포함하여 전달합니다.

```
✅ 올바른 예:
{ "price": 99.99, "rate": 3.14159 }
❌ 잘못된 예:
{ "price": "99.99", "rate": "3.14159" }
```

##### 3.3.3 통화 표현 (Currency)

- 통화는 **정수형, 실수형 으로 최소 단위**로 표현하는 것을 권장합니다.

```
✅ 올바른 예:
{ "amount": 1500 } // 1,500원 (단위: 원)
{ "amount": 19.99 } // $19.99 (단위: 달러)
❌ 잘못된 예:
{ "amount": "1,500원" } // 1,500원 (단위: 원)
{ "amount": "$19.99" } // $19.99 (단위: 달러)
```

#### 3.4 Boolean 포맷

- **JSON Boolean 타입**(`true`/`false`)을 사용합니다.

```
✅ 올바른 예:
{ "isActive": true, "deleted": false }
❌ 잘못된 예:
{ "isActive": "Y", "deleted": "N" }
{ "isActive": 1, "deleted": 0 }
```

> 참고: 데이터베이스에 `Y/N`, `1/0`으로 저장되더라도, API 응답에서는 Boolean 타입으로 변환해 제공해야 합니다.

#### 3.5 배열 및 객체

##### 3.5.1 일반 배열 및 객체

배열([])과 객체({})는 JSON 표준 형식을 따르며 다음 원칙을 적용합니다.

- 객체는 정의된 스키마에 명시된 필드만 포함합니다. (추가/누락 금지)
- 배열의 요소 타입은 스키마에서 정의된 단일 타입을 유지해야 합니다.
- 배열 요소는 모두 동일한 구조를 갖는 것을 권장합니다. (list item consistency)

##### 3.5.2 빈 배열

- 데이터가 없는 경우에도 **빈 배열([])** 로 응답합니다.

```
✅ 올바른 예:
{ "users": [], "total_count": 0 }
❌ 잘못된 예:
{ "users": null }
```

##### 3.5.3 `null` vs `{}` vs `[]` 구분 원칙

- `null`: 속성이 아예 **없거나 미설정** 상태
- `{}`: 객체 구조는 존재하나 **속성이 없음**
- `[]`: 배열 구조는 존재하나 **요소가 없음**

실제 예시:

```json5
{
  "user": {
    "id": 123,
    "name": "홍길동",
    "profile": null,        // 프로필 미설정
    "avatar": null,          // 아바타 없음
    "tags": [],              // 태그 없음
    "preferences": {
      "theme": "dark",
      "notifications": {},   // 알림 설정 구조는 있으나 설정값 없음
      "privacy": null        // 개인정보 설정 없음
    },
    "last_login": null       // 로그인 기록 없음
  }
}
```

### 4. request 규칙

#### 4.1 URI 형식

**\<base path\> / \<version\> / \<resource\>**

ex. https://api.gamification.dev.hunet.io **\<base path\>** /v1 **\<version\>** / [mission](https://mission.api.gamification.dev.hunet.io/) **\<resource\>**

- URI는 기본적으로 정보의 자원를 표현합니다.
- version 정보는 major version 정보만 사용합니다.(`v1`, `v2`, 등)
- `_`(underscore), ` `(white space), 대문자를 사용하지 않습니다. 오직 영소문자, `"-"`(dash), 숫자 만을 사용합니다.

#### 4.2 Method

| **Method** | **용도** | **비고** |
|-----------|---------|---------|
| GET | 자원을 받아 올 때 사용 | |
| POST | 새로운 자원을 추가할 때 사용 | |
| PUT | 기존의 자원을 변경할 때 사용 | 기존에 존재하지 않는 자원인 경우 상황에 맞게 처리(신규 자원 추가 OR 오류 응답) POST는 여러 자원을 한꺼번에 추가할 수 있으나 PUT은 항상 단일 자원만 UPSERT |
| DELETE | 기존의 자원을 삭제할 때 사용 | 기존에 존재하지 않는 자원인 경우 상황에 맞게 응답 |

- PATCH method는 사용하지 않습니다.

#### 4.3 Resource

- 명명 원칙:
  - resource는 동사보다는 명사를 사용합니다.
  - resource의 도큐먼트(단일 객체) 이름으로는 단수 명사를 사용합니다.
  - resource의 컬렉션 이름으로는 복수 명사를 사용합니다.

### 5. response 규칙

- response 내용 뿐만이 아니라 **response 자체에 대한 메타 정보**도 포함합니다.
- 실제 응답할 데이터는 `payload` 필드 아래에 포함합니다.

#### 5.1 기본 응답 형식

>```
>{
>  "status": <payload의 상태(optional)>,    // 응답 성공 여부 (recommended)
>  "version": <버전 정보>,                   // API 버전 (detail version)
>  "datetime": <응답 일시>,                  // DateTime 포맷 권고(TimeStamp 포맷 지양)
>  "duration": <처리 시간>,                  // 실제 API가 수행된 시간. milli second 단위 (optional)
>  "traceid": <요청 체인 상 추적을 위한 ID, UUID 형식 String, optional>,
>  "payload": {                              // 실제 응답할 데이터
>    <키_1>: <값_1>,
>    <키_2>: <값_2>,
>    …
>  }
>}
>```

- 기본 응답 예

```json
{
  "status": "SUCCESS",
  "version": "1.0.0.5",
  "datetime": "2024-03-25T04:10:27.257626Z",
  "duration": 70,
  "traceid": "7f7c9e2b-5d3b-4e9e-8f11-0b2d2d7c9a01",
  "payload": {
    "name": "황용호",
    "email": "jogakdal@gmail.com"
  }
}
```

#### 5.2 오류(실패) 응답 형식

status 필드의 표준값은 **SUCCESS**/**FAILURE**이며, 실패 상황에서는 `status="FAILURE"`로 표준화합니다. 2xx 응답에서는 SUCCESS 외에 상황에 따라 다른 값(SUSPENDED, DEPRECATED, STREAMING 등)을 활용할 수 있습니다.

```
{
  "status": "FAILURE",
  "version": <버전 정보>,
  "datetime": <응답 일시>,
  "duration": <처리 시간>,
  "traceid": <추적 키>,
  "payload": {    // 오류 정보
    "errors": [    // 다중 오류 표현을 위해 array 구조로 정의
      {
        "code": <오류 코드>,
        "message": <오류 내용>
      },
      …
    ],
    "appendix": {    // 추가 상세 정보 (optional)
      <추가 정보 키>: <추가 정보 값>,
      ...
    }
  }
}
```

- 오류 응답 예:

```json
{
  "status": "FAILURE",
  "version": "1.0",
  "datetime": "2024-03-25T04:10:27.257626Z",
  "duration": 70,
  "traceid": "7f7c9e2b-5d3b-4e9e-8f11-0b2d2d7c9a01",
  "payload": {
    "errors": [
      {
        "code": "E_DBMS_NOT_RESPONSE",
        "message": "데이터베이스가 응답하지 않습니다."
      }
    ],
    "appendix": {
      "database": "database1",
      "table": "table1",
      "key": "12345",
      "debug": {
        "trace": "...",
        "context": "..."
      }
    }
  }
}
```

- 다중 오류 응답 예:

```json
{
  "status": "FAILURE",
  "version": "1.0",
  "datetime": "2024-03-25T04:10:27.257626Z",
  "duration": 70,
  "traceid": "7f7c9e2b-5d3b-4e9e-8f11-0b2d2d7c9a01",
  "payload": {
    "errors": [
      {
        "code": "E_INVALID_SOCIAL_NUMBER",
        "message": "주민번호 형식이 맞지 않습니다."
      },
      {
        "code": "E_TOO_SHORT_PASSWORD",
        "message": "패스워드는 8자리 이상이어야 합니다."
      }
    ],
    "appendix": {
    }
  }
}
```

#### 5.3 페이지네이션 리스트

- 페이지네이션 형식의 리스트를 포함한 response 포맷:

```
{
  "status": <payload의 상태>,
  "version": <버전 정보>,    // API 버전
  "datetime": <응답 일시>,
  "duration": <처리 시간>,
  "traceid": <추적 키>,
  "payload": {    // 실제 응답할 데이터
    <키_1>: <값_1>,    // 리스트 외의 데이터
    <키_2>: <값_2>,    // 리스트 외의 데이터
    …,
    "pageable": {    // 페이지네이션 데이터
      "page": {    // 페이지 정보
        "size": <페이지 크기>,
        "total": <총 페이지 수>,
        "current": <현재 페이지 번호>
      },
      "order": {    // 리스트 순서 정보 (optional)
        "sorted": <정렬 여부>,    // Boolean
        "by": [    // 정렬 필드 정보
          { "field": <필드명>, "direction": <순서("asc" / "desc")> },
          …
        ]
      },
      "items": {    // 실제 리턴하는 아이템 정보
        "total": <총 아이템 수>,    // 실제 (서버 상에 존재하는) 총 데이터의 수. optional
        "current": <현재 리턴하는 아이템 수>,    // list의 크기와 동일. optional
        "list": [    // 실제 아이템 리스트
          { <키>: <값>, … },
          …
        ]
      }
    }
  }
}
```

- 페이지네이션 리스트의 필드명이 반드시 pageable일 필요는 없습니다.
- 페이지네이션 리스트 포함 응답 예:

```json
{
  "status": "SUCCESS",
  "version": "1.0",
  "datetime": "2024-03-25T04:10:27.257626Z",
  "duration": 70,
  "traceid": "7f7c9e2b-5d3b-4e9e-8f11-0b2d2d7c9a01",
  "payload": {
    "company": "hunet",
    "department": "공통플랫폼팀",
    "pageable": {
      "page": {
        "size": 5,
        "total": 20,
        "current": 1
      },
      "order": {
        "sorted": true,
        "by": [ {"field": "id", "direction": "asc"} ]
      },
      "items": {
        "total": 100,
        "current": 5,
        "list": [
          {"id": "hu1234", "name": "황용호"},
          {"id": "hu1235", "name": "홍용호"},
          {"id": "hu1236", "name": "형용호"},
          {"id": "hu1237", "name": "하용호"},
          {"id": "hu1238", "name": "한용호"}
        ]
      }
    }
  }
}
```

#### 5.4 더보기 리스트

- 더보기 형식의 리스트(커서 기반 리스트)를 포함한 response 형식:

```json5
{
  "status": <payload의 상태>,
  "version": <버전 정보>,
  "datetime": <응답 일시>,
  "duration": <처리 시간>,
  "traceid": <추적 키>,
  "payload": {  // 실제 응답할 데이터
    <키_1>: <값_1>,  // 리스트 외의 데이터
    <키_2>: <값_2>,  // 리스트 외의 데이터
    …,
    "incremental": {  // 확장형(더보기 형태) 데이터
      "cursor": {  // 커서 정보
        "field": <기준이 되는 필드>,  // optional
        "start": <현 응답 리스트의 시작 인덱스>,  // Any 타입, Nullable
        "end": <현 응답 리스트의 끝 인덱스>,  // Any 타입, Nullable
        "expandable": <이 후 추가 데이터 존재 여부(true/false)>  // optional
      },
      "order": {  // 리스트 순서 정보 (optional)
        "sorted": <정렬 여부>,  // Boolean
        "by": [  // 정렬 필드 정보
          { "field": <필드명>, "direction": <순서("asc" / "desc")> },
          …
        ]
      },
      "items": {  // 실제 리턴하는 아이템 정보
        "total": <총 아이템 수>,  // 실제 (서버 상에 존재하는) 총 데이터의 수. optional
        "current": <현재 리턴하는 아이템 수>,  // list의 크기와 동일. optional
        "list": [  // 실제 아이템 리스트
          { <키>: <값>, … },
          …
        ]
      }
    }
  }
}
```

- 더보기 리스트의 필드명이 반드시 incremental일 필요는 없습니다.
- 더보기 리스트 포함 응답 예

```json
{
  "status": "SUCCESS",
  "version": "1.0",
  "datetime": "2024-03-25T04:10:27.257626Z",
  "duration": 70,
  "traceid": "7f7c9e2b-5d3b-4e9e-8f11-0b2d2d7c9a01",
  "payload": {
    "company": "hunet",
    "department": "공통플랫폼팀",
    "incremental": {
      "cursor": {
        "field": "id",
        "start": "hu1234",
        "end": "hu1238",
        "expandable": true
      },
      "order": {
        "sorted": true,
        "by": [ {"field": "id", "direction": "asc"} ]
      },
      "items": {
        "total": 100,
        "current": 5,
        "list": [
          {"id": "hu1234", "name": "황용호"},
          {"id": "hu1235", "name": "홍용호"},
          {"id": "hu1236", "name": "형용호"},
          {"id": "hu1237", "name": "하용호"},
          {"id": "hu1238", "name": "한용호"}
        ]
      }
    }
  }
}
```

#### 5.5 페이지네이션 없는 전체 리스트 응답

- 페이지네이션이 없이 전체 리스트를 응답할 경우에도 페이지 정보를 넣어 줍니다.
- `page.size`를 총 아이템 수(`items.total`)와 동일하게 지정하고 `page.total`과 `page.current`를 항상 1로 지정
- 현 시점에서 전체 리스트를 리턴하더라도 시간이 지남에 따라 데이터가 더 생길 수 있는 리스트라면 더보기 형식의 리스트 응답 포맷을 고려하는 것이 좋습니다.
- 전체 리스트 응답 예 (페이지네이션 형식)

```json
{
  "status": "SUCCESS",
  "version": "1.0",
  "datetime": "2024-03-25T04:10:27.257626Z",
  "duration": 70,
  "traceid": "7f7c9e2b-5d3b-4e9e-8f11-0b2d2d7c9a01",
  "payload": {
    "company": "hunet",
    "department": "공통플랫폼팀",
    "pageable": {
      "page": {
        "size": 5,
        "total": 1,
        "current": 1
      },
      "order": {
        "sorted": true,
        "by": [ {"field": "id", "direction": "asc"} ]
      },
      "items": {
        "total": 5,
        "current": 5,
        "list": [
          {"id": "hu1234", "name": "황용호"},
          {"id": "hu1235", "name": "홍용호"},
          {"id": "hu1236", "name": "형용호"},
          {"id": "hu1237", "name": "하용호"},
          {"id": "hu1238", "name": "한용호"}
        ]
      }
    }
  }
}
```

- 전체 리스트 응답 예 (더보기 형식)

```json
{
  "status": "SUCCESS",
  "version": "1.0",
  "datetime": "2024-03-25T04:10:27.257626Z",
  "duration": 70,
  "traceid": "7f7c9e2b-5d3b-4e9e-8f11-0b2d2d7c9a01",
  "payload": {
    "company": "hunet",
    "department": "공통플랫폼팀",
    "incremental": {
      "cursor": {
        "field": "id",
        "start": "hu1234",
        "end": "hu1238",
        "expandable": false
      },
      "order": {
        "sorted": true,
        "by": [ {"field": "id", "direction": "asc"} ]
      },
      "items": {
        "total": 5,
        "current": 5,
        "list": [
          {"id": "hu1234", "name": "황용호"},
          {"id": "hu1235", "name": "홍용호"},
          {"id": "hu1236", "name": "형용호"},
          {"id": "hu1237", "name": "하용호"},
          {"id": "hu1238", "name": "한용호"}
        ]
      }
    }
  }
}
```

#### 5.6 페이지네이션 또는 더보기 리스트로만 구성된 응답

- 페이지네이션 또는 더보기 리스트로만 구성된 응답은 `pageable` 또는 `incremental` 구조체를 직접 `payload`로 사용해도 무방합니다.
- 이 때도 `pageable` 또는 `incremental`의 구조는 반드시 준수되어야 합니다.
- 필드명은 항상 `payload`로 유지합니다.
- pageable 로만 구성된 응답 예:

```json
{
  "status": "SUCCESS",
  "version": "1.0",
  "datetime": "2024-03-25T04:10:27.257626Z",
  "duration": 70,
  "traceid": "7f7c9e2b-5d3b-4e9e-8f11-0b2d2d7c9a01",
  "payload": {
    "page": {
      "size": 5,
      "total": 1,
      "current": 1
    },
    "order": {
      "sorted": true,
      "by": [ {"field": "id", "direction": "asc"} ]
    },
    "items": {
      "total": 5,
      "current": 5,
      "list": [
        {"id": "hu1234", "name": "황용호"},
        {"id": "hu1235", "name": "홍용호"},
        {"id": "hu1236", "name": "형용호"},
        {"id": "hu1237", "name": "하용호"},
        {"id": "hu1238", "name": "한용호"}
      ]
    }
  }
}
```

#### 5.7 빈 리스트 응답

- 리스트를 응답할 때 리스트가 비어 있는 경우 `list` 필드의 value에 `null` 대신 빈 리스트(`[]`)로 지정해 줍니다.
- 이 때도 `pageable` 또는 `incremental`의 구조는 준수되어야 합니다.
- pageable 형식으로 빈 리스트 응답 예:

```json
{
  "status": "SUCCESS",
  "version": "1.0",
  "datetime": "2024-03-25T04:10:27.257626Z",
  "duration": 70,
  "traceid": "7f7c9e2b-5d3b-4e9e-8f11-0b2d2d7c9a01",
  "payload": {
    "company": "hunet",
    "department": "공통플랫폼팀",
    "pageable": {
      "page": {
        "size": 5,
        "total": 1,
        "current": 2
      },
      "order": {
        "sorted": true,
        "by": [ {"field": "id", "direction": "asc"} ]
      },
      "items": {
        "total": 5,
        "current": 0,
        "list": []
      }
    }
  }
}
```

#### 5.8 2개 이상의 리스트를 포함한 응답

- 이 때도 각각의 리스트는 `pageable` 또는 `incremental`의 구조를 준수합니다.
- 다만 필드명이 겹칠 수 있으므로 각 리스트의 의미에 맞게 필드명을 지정해 줍니다.
- 2개의 리스트 응답 예

```json
{
  "status": "SUCCESS",
  "version": "1.0",
  "datetime": "2024-03-25T04:10:27.257626Z",
  "duration": 70,
  "traceid": "7f7c9e2b-5d3b-4e9e-8f11-0b2d2d7c9a01",
  "payload": {
    "company": "hunet",
    "department": "공통플랫폼팀",
    "members": {
      "page": {
        "size": 5,
        "total": 1,
        "current": 1
      },
      "order": {
        "sorted": true,
        "by": [ {"field": "id", "direction": "asc"} ]
      },
      "items": {
        "total": 5,
        "current": 5,
        "list": [
          {"id": "hu1234", "name": "황용호"},
          {"id": "hu1235", "name": "홍용호"},
          {"id": "hu1236", "name": "형용호"},
          {"id": "hu1237", "name": "하용호"},
          {"id": "hu1238", "name": "한용호"}
        ]
      }
    },
    "roles": {
      "page": {
        "size": 5,
        "total": 1,
        "current": 2
      },
      "order": {
        "sorted": true,
        "by": [ {"field": "id", "direction": "asc"} ]
      },
      "items": {
        "total": 5,
        "current": 0,
        "list": []
      }
    }
  }
}
```

### 6. 오류 코드 관리

**오류 코드(`code`)는 각 서비스 또는 도메인 별로 정의하여 문서화해야 합니다.**

- 도메인별 코드 정의: 비즈니스 도메인에 맞는 코드 체계를 구축합니다.
- 문서화 필수: 정의된 코드는 반드시 문서화하여 관리합니다.
- 코드 일관성: 서비스 내에서 일관된 명명 규칙을 적용할 것을 권장합니다.

### 7. 응답 Payload 모듈러 설계 (Modular Composition)

응답 `payload` 구조는 **작게 분리된 재사용 가능 단위(Atomic 요소)** 들을 조합하여 **Aggregate(조합형) Payload**를 형성하도록 설계해야 합니다. 이는 재사용성, 변경 격리, 전송량 최적화, 테스트 용이성을 높여 장기적인 유지보수 비용을 낮춥니다.

#### 7.1 용어 정의

| 용어                | 정의                                                                          | 비고                    |
|-------------------|-----------------------------------------------------------------------------|-----------------------|
| Atomic 요소         | 더 이상 내부로 세분화할 실익이 없는 최소 단위 데이터 객체                                           | 예: 사용자 요약, 프로젝트 기본 정보 |
| Aggregate Payload | 하나 이상의 Atomic 요소 + 하나 이상의 리스트(pageable / incremental) + 카운트/메타 등을 조합한 응답 구조 | 화면/유즈케이스 단위           |
| Projection (View) | 특정 목적에 필요한 필드만 축약한 형태                                                       | 보안/전송량 최적화            |

#### 7.2 설계 목표

- 재사용: 동일 Atomic 구조가 여러 API에서 일관되게 활용되게 하여 중복을 제거합니다.
- 변경 격리: Atomic 수정이 Aggregate 전체 재작성으로 확산되지 않도록 합니다.
- 전송량 절감: 초기 표시(첫 뷰)에 필요한 최소 필드만 포함하고 추가 정보는 후속 호출로 분리합니다.
- 테스트 단순화: Atomic 단위 스냅샷 검증 및 Aggregate 조합 검증으로 범위를 축소합니다.
- 명료성: 과도한 중첩/중복 필드를 제거하여 소비 측 파싱 복잡도를 감소시킵니다.

#### 7.3 기본 원칙

| 원칙      | 규칙                                            | 실무 가이드                         |
|---------|-----------------------------------------------|--------------------------------|
| 단일 책임   | Atomic 은 단일 개념/엔티티 슬라이스만 표현해야 합니다.            | 불필요한 외부 도메인 끌어오지 말 것           |
| 최소 필드   | 첫 화면/주요 기능 수행에 필수인 필드만 포함해야 합니다.              | 부가 상세는 별도 API 권장               |
| 중첩 제한   | 2단계 초과 깊은 중첩은 지양합니다.                          | `list -> object -> list` 반복 방지 |
| 식별자 분리  | 참조만 필요한 경우 전체 객체 대신 id(+label)만 제공합니다.        | 과도한 중첩/용량 감소                   |
| 버전 경계   | 대규모 변경은 새 Aggregate로 분리합니다. (예: `DetailV2`)   | 구버전 병행 기간 운영                   |
| 리스트 일관성 | 모든 리스트는 `pageable` 또는 `incremental` 규칙을 따릅니다. | 임의 배열 금지 (단순 소량 고정 목록 제외)      |
| 중복 제거   | 동일 의미 구조의 반복 정의를 지양합니다.                       | Atomic 재사용                     |
| 일관된 네이밍 | 필드 명칭은 동일 의미에 동일 이름을 재사용합니다.                  | 혼동/alias 난립 방지                 |

#### 7.4 Aggregate 구성 예시

```json
{
  "status": "SUCCESS",
  "version": "1.0",
  "datetime": "2025-10-16T09:10:11Z",
  "duration": 42,
  "traceid": "7f7c9e2b-5d3b-4e9e-8f11-0b2d2d7c9a01",
  "payload": {
    "user": {
      "user_id": 10,
      "display_name": "황용호",
      "role": "ADMIN"
    },
    "projects": {
      "page": {"size": 5, "total": 12, "current": 1},
      "order": {"sorted": true, "by": [{"field": "id", "direction": "asc"}]},
      "items": {
        "total": 60,
        "current": 5,
        "list": [
          {"project_id": 101, "name": "PJT-A"},
          {"project_id": 102, "name": "PJT-B"},
          {"project_id": 103, "name": "PJT-C"},
          {"project_id": 104, "name": "PJT-D"},
          {"project_id": 105, "name": "PJT-E"}
        ]
      }
    },
    "unread_count": 7
  }
}
```

#### 7.5 Incremental(더보기) 리스트와 혼합 예시

```json
{
  "status": "SUCCESS",
  "version": "1.0",
  "datetime": "2025-10-16T09:10:11Z",
  "duration": 33,
  "traceid": "7f7c9e2b-5d3b-4e9e-8f11-0b2d2d7c9a01",
  "payload": {
    "user": {"user_id": 10, "display_name": "황용호"},
    "activity_feed": {
      "cursor": {"field": "id", "start": 9001, "end": 9005, "expandable": true},
      "items": {
        "total": 500,
        "current": 5,
        "list": [
          {"id": 9001, "type": "LOGIN", "ts": "2025-10-16T09:09:58Z"},
          {"id": 9002, "type": "VIEW", "ts": "2025-10-16T09:09:59Z"},
          {"id": 9003, "type": "EDIT", "ts": "2025-10-16T09:10:00Z"},
          {"id": 9004, "type": "VIEW", "ts": "2025-10-16T09:10:01Z"},
          {"id": 9005, "type": "LOGOUT", "ts": "2025-10-16T09:10:02Z"}
        ]
      }
    },
    "highlight_projects": {
      "page": {"size": 3, "total": 1, "current": 1},
      "items": {
        "total": 3,
        "current": 3,
        "list": [
          {"project_id": 201, "name": "HI-A"},
          {"project_id": 202, "name": "HI-B"},
          {"project_id": 203, "name": "HI-C"}
        ]
      }
    }
  }
}
```

#### 7.6 빈/소량 리스트 처리

- 데이터가 없으면 `list: []`로 표현합니다.
- 별도 메타가 의미 없을 정도로 **고정/소량(예: 1~3개)** 의 단순 코드 목록은 예외적으로 평문 배열 사용을 허용할 수 있으나, 일관성을 위해 가능하면 동일한 리스트 구조를 유지합니다.

#### 7.7 다중 리스트 조합

여러 리스트를 포함하는 경우 각 리스트 블록은 `pageable` 또는 `incremental` 규칙을 독립적으로 충족해야 합니다. 의미가 다른 리스트는 명확한 필드명(`members`, `roles`, `activity_feed` 등)을 사용합니다.

#### 7.8 마이그레이션 전략

| 변경 유형            | 권장 전략                                 |
|------------------|---------------------------------------|
| 필드 추가 (Optional) | 추가 후 문서화, 클라이언트 하위 호환 유지              |
| 필드 제거            | Deprecation 기간 표시 후 제거, 필요 시 대체 필드 병행 |
| 의미 변경 (호환 불가)    | 새 필드명 또는 새 Aggregate(`*V2`) 도입        |
| 대규모 구조 재조합       | 구 구조 병행 노출 → 마이그레이션 완료 후 제거           |

#### 7.9 지양할 점

| 패턴                    | 문제            | 개선                |
|-----------------------|---------------|-------------------|
| 거대 단일 Payload (과다 필드) | 전송량 증가, 변경 파급 | 원자화 + 조합          |
| 비슷한 구조 Copy & Paste   | 중복 유지보수       | 공용 Atomic 재사용     |
| 깊은 중첩 (3단계 이상)        | 파싱/가독성 저하     | 구조 평탄화, 별도 API 분리 |
| 상세/요약 혼합된 리스트 아이템     | 불일치/분기 증가     | 요약 리스트 + 상세 개별 조회 |
| 동일 의미 필드 서로 다른 이름     | 혼동, 매핑 비용     | 표준 이름 단일화         |

#### 7.10 적용 절차 (권장 Flow)

1. 핵심 엔터티/뷰 요구사항 식별 → Atomic 요소 초안 정의
2. 화면/유즈케이스 별 Aggregate 구성 초안 (필수 vs 선택 필드 구분)
3. 각 리스트 성격에 따라 pageable / incremental 결정
4. 필드 명명 표준화 및 중복/모호성 제거
5. JSON 스키마 예시 작성 및 리뷰 (빈/다중 리스트, 에러 케이스 포함)
6. 직렬화 → 역직렬화 왕복 테스트 (정상 + 빈 리스트 + 다중 리스트)
7. 문서 반영 및 클라이언트 공유
8. 변경 발생 시 버전 전략 적용 (필요 시 `*V2` 구조 병행)
