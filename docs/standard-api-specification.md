# Standard API 사양 (Specification)

> ### 관련 문서 (Cross References)
> | 문서                                                                               | 역할 / 초점                                                 | 이 문서와의 관계                                  |
> |----------------------------------------------------------------------------------|---------------------------------------------------------|--------------------------------------------|
> | [standard-api-response-library-guide.md](standard-api-response-library-guide.md) | 라이브러리 **사용자 가이드**: spec 을 준수하여 응답 생성/역직렬화 하는 헬퍼 API 설명. | spec 규칙을 실무 적용 형태로 해설. 규범 변경 시 여기 반영 필요.   |
> | [standard-api-response-examples.md](standard-api-response-examples.md)           | **예제 카탈로그**: 대표/복합/경계 케이스 코드 스니펫 모음.                    | 사용 패턴을 구체화, 규칙 자체 변형 없음. 최신 spec 기준 유지 필요. |
> | [standard-api-response-reference.md](standard-api-response-reference.md) | 레퍼런스 가이드: 모듈에 대한 상세 설명 제공                               |
> | [README.md](../README.md)                                                        | **라이브러리 내부 개발/기여 가이드**: 구조, 수정 시 참고.                    | 라이브러리 업그레이드 시 spec 영향 여부 점검 필요.          |
>
> 우선순위 충돌 시: **spec > 사용자 가이드 > 예제**. README 는 구현 레벨 참고 문서.

## API 응답 표준 

### 공통

- 메시지 교환 방식은 **REST** 방식으로 통일합니다.
- 응답은 구조화된 **JSON** 포맷을 사용하며, HTTP 헤더의 `Content-Type`을 `application/json; charset=utf-8`로 명시합니다.
- 모든 메시지는 **UTF‑8 인코딩**을 사용합니다.
- 사람이 읽기 어려운 TimeStamp 포맷 대신 **ISO‑8601 계열 DateTime** 포맷을 사용합니다.
- DateTime 값에는 **UTC(Zulu)** 또는 **오프셋(+09:00)** 을 명시합니다.
- 응답 코드 및 오류 코드에 대해서는 별도의 정의 문서를 작성하여 공유합니다.

### 중요

#### 1. HTTP 상태 코드

상태 코드는 RFC 7231 등 국제 표준에 따라 사용하며, 통신 성공 여부 뿐 아니라 **요청 처리의 전체 상태와 결과**를 표현해야 합니다.

##### 2xx 성공 응답

| 상태 코드 | 설명 | 사용 예시 |
| --- | --- | --- |
| **200 OK** | 요청이 성공적으로 처리됨 | GET, PUT, PATCH 요청 성공 시 |
| **201 Created** | 새 리소스가 성공적으로 생성됨 | POST 요청으로 새 리소스를 생성할 때 |
| **204 No Content** | 요청은 성공했으나 반환할 내용이 없음 | DELETE 성공, 본문 응답이 필요 없는 PUT/PATCH 요청 |

##### 3xx 리다이렉션 응답

| 상태 코드 | 설명 | 사용 예시 |
| --- | --- | --- |
| **301 Moved Permanently** | 리소스가 영구적으로 이동됨 | 리소스 URI 영구 변경 후 리다이렉션 |
| **302 Found** | 리소스가 임시적으로 다른 위치에 존재함 | 임시 리다이렉션 필요 시 |
| **304 Not Modified** | 클라이언트의 캐시 버전이 여전히 유효함 | 조건부 GET 요청 결과 리소스 변경 없음 |

##### 4xx 클라이언트 오류 응답

| 상태 코드 | 설명 | 사용 예시 |
| --- | --- | --- |
| **400 Bad Request** | 잘못된 요청 구문 또는 파라미터 오류 | 잘못된 형식의 요청, 필수 파라미터 누락 |
| **401 Unauthorized** | 인증 실패 또는 인증 정보 누락 | 로그인되지 않은 사용자의 인증이 필요한 요청 |
| **403 Forbidden** | 인증은 되었으나 권한이 없는 경우 | 리소스 접근 권한 부족 |
| **404 Not Found** | 요청한 리소스를 찾을 수 없음 | 존재하지 않는 URI 또는 ID |
| **409 Conflict** | 리소스 상태 충돌 발생 | 중복 데이터 삽입, 동시성 충돌 |
| **422 Unprocessable Entity** | 요청은 유효하나 처리할 수 없는 상태 | 유효성 검사 실패, 의미적 오류 |
| **429 Too Many Requests** | 너무 많은 요청으로 제한됨 | Rate Limit 초과 |

##### 5xx 서버 오류 응답

| 상태 코드 | 설명 | 사용 예시 |
| --- | --- | --- |
| **500 Internal Server Error** | 서버 내부 오류 | 예기치 않은 예외 또는 처리 실패 |
| **502 Bad Gateway** | 게이트웨이가 잘못된 응답 수신 | 업스트림 서버 오류 발생 |
| **503 Service Unavailable** | 서버가 일시적으로 사용 불가 | 서버 점검, 과부하 등으로 일시적 서비스 중단 |
| **504 Gateway Timeout** | 게이트웨이 응답 시간 초과 | 업스트림 서버 응답 지연 |

#### 2. 공통 헤더

요청과 응답 모두 아래와 같은 공통 헤더를 사용합니다.

| 구분 | 헤더 | 설명 |
| --- | --- | --- |
| **요청 헤더** | `Accept` | 클라이언트가 수신 가능한 응답 형식 (예: `application/json`) |
| **응답 헤더** | `Content-Type` | 응답 본문의 데이터 형식 및 문자 인코딩 (`application/json; charset=utf-8`) |

#### 3. 인코딩 및 데이터 포맷 규칙

##### 문자 인코딩

- 요청 및 응답 모두 **UTF‑8** 인코딩을 사용합니다.

##### 날짜/시간 포맷

- ISO 8601 형식 사용: 예) `2025-05-20T08:15:30Z` (UTC), `2025-05-20T17:15:30+09:00` (KST).
- 날짜/시간을 **단순 문자열로 전달하는 방식은 지양**합니다. 포맷 파싱 오류나 클라이언트 간 호환성 문제가 발생할 수 있습니다.
- **반드시 타임존**(`Z` 또는 `+09:00`)을 포함합니다.

##### 숫자 포맷

- **정수형(Integer)**: JSON `number` 타입으로 전달합니다. 예:
```json
{"age": 25, "count": 100}
```

- 잘못된 예: `{"age": "25", "count": "100"}` (문자열 사용 금지)
- **실수형(Float / Double)**: JSON `number` 타입으로 소수점을 포함하여 전달합니다. 예:
```json
{"price": 99.99, "rate": 3.14159}
```

- 잘못된 예: `{"price": "99.99", "rate": "3.14159"}`
- **통화 표현(Currency)**: 최소 단위로 정수형 또는 실수형으로 표현합니다. 예:
```json5
{"amount": 1500}   // 1,500원 (단위: 원)
{"amount": 19.99}  // $19.99 (단위: 달러)
```

- 잘못된 예: `{"amount": "1,500원"}`, `{"amount": "$19.99"}`.

##### Boolean 포맷

- JSON `Boolean` 타입(`true`/`false`)을 사용합니다. 예:
```json
{"isActive": true, "deleted": false}
```
- 잘못된 예: `{ "isActive": "Y", "deleted": "N" }`, `{ "isActive": 1, "deleted": 0 }`
- 데이터베이스에서 Y/N 또는 1/0으로 저장되더라도, API 응답에서는 Boolean 타입으로 변환해 제공해야 합니다.

##### 배열 및 객체

- **빈 배열**: 데이터가 없는 경우에도 빈 배열(`[]`)로 응답합니다. 예:

```json
{"users": [], "total_count": 0}
```

- 잘못된 예: `{"users": null}`
- **null vs {} vs [] 구분**:
    - `null` : 속성이 아예 없거나 미설정 상태
    - `{}` : 객체 구조는 존재하나 속성이 없음
    - `[]` : 배열 구조는 존재하나 요소가 없음

- **예시**:

```json5
{
  ...
  
  "payload": {
    "id": 123,
    "name": "황용호",
    "profile": null,        // 프로필 미설정
    "avatar": null,         // 아바타 없음
    "tags": [],             // 태그 없음
    "preferences": {
      "theme": "dark",
      "notifications": {},   // 알림 설정 구조는 있으나 설정값 없음
      "privacy": null        // 개인정보 설정 없음
    },
    "last_login": null      // 로그인 기록 없음
  }
}
```

### 4. Request 규칙

#### URI 형식

- 기본 형태: `<base_path>/<version>/<resource>` (예: `https://api.example.com/v1/mission`)
- URI는 **자원을 표현**하며, 버전은 `v1`, `v2` 등 **major version**만 사용합니다.
- `_`(underscore), 공백, 대문자를 사용하지 않고 **영소문자, `-`(dash), 숫자**만 사용합니다.

#### Method

| Method | 용도 | 비고 |
| --- | --- | --- |
| **GET** | 자원을 조회할 때 사용 | |
| **POST** | 새 자원을 추가할 때 사용 | |
| **PUT** | 기존 자원을 변경할 때 사용 | 존재하지 않는 자원일 경우 상황에 맞게 처리 (신규 자원 추가 또는 오류 응답). POST는 여러 자원을 한꺼번에 추가할 수 있으나 PUT은 항상 단일 자원만 UPSERT. |
| **DELETE** | 기존 자원을 삭제할 때 사용 | 존재하지 않는 자원인 경우 상황에 맞게 응답 |

> **참고:** `PATCH` 메서드는 사용하지 않습니다.

#### Resource 명명 원칙

- **동사보다 명사를 사용**합니다.
- 단일 객체는 **단수 명사**, 컬렉션은 **복수 명사**를 사용합니다.

### 5. Response 규칙

응답 본문에는 데이터 외에도 응답 자체의 메타 정보를 포함합니다. 실제 응답할 데이터는 `payload` 필드 아래에 위치합니다.

#### 기본 응답 형식

>```
>{
>  "status": <optional: payload의 상태>,
>  "version": <버전 정보>,
>  "datetime": <응답 일시>,
>  "duration": <처리 시간(ms)>,
>  "payload": {
>    <키_1>: <값_1>,
>    <키_2>: <값_2>,
>    ...
>  }
>}
>```

#### 기본 응답 예시

```json
{
  "status": "SUCCESS",
  "version": "1.0.0.5",
  "datetime": "2024-03-25T04:10:27.257626Z",
  "duration": 70,
  "payload": {
    "name": "황용호",
    "email": "jogakdal@gmail.com"
  }
}
```

#### 오류(실패) 응답 형식

StandardStatus 는 SUCCESS / FAILURE 로 구성되며, 실패 상황에서 `status="FAILURE"` 로 표준화합니다.
>```
>{
>  "status": "FAILURE",
>  "version": <버전 정보>,
>  "datetime": <응답 일시>,
>  "duration": <처리 시간>,
>  "payload": {
>    "errors": [    // 다중 오류 표현을 위해 배열 사용
>      {
>        "code": <오류 코드>,
>        "message": <오류 내용>
>      },
>      ...
>    ],
>    "appendix": { // 추가 상세 정보 (optional)
>      <추가 정보 키>: <추가 정보 값>,
>      ...
>    }
>  }
>}
>```

#### 실패 응답 예시:
```json
{
  "status": "FAILURE",
  "version": "1.0",
  "datetime": "2024-03-25T04:10:27.257626Z",
  "duration": 70,
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

#### 다중 오류(실패) 응답 예시:
```json
{
  "status": "FAILURE",
  "version": "1.0",
  "datetime": "2024-03-25T04:10:27.257626Z",
  "duration": 70,
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
    "appendix": {}
  }
}
```

#### 페이지네이션 리스트

페이지네이션 형식의 리스트를 포함할 때는 `pageable` 구조를 사용합니다.
- `page`: 페이지 정보 (`size`, `total`, `current`)
- `order`: 정렬 정보 (`sorted`, `by`) – optional
- `items`: 실제 데이터 목록과 총/현재 갯수 (`total`, `current`, `list`)

>```
>{
>  "status": <payload의 상태>,
>  "version": <버전 정보>,
>  "datetime": <응답 일시>,
>  "duration": <처리 시간>,
>  "payload": {
>    <키_1>: <값_1>,
>    ...,
>    "pageable": {
>      "page": {
>        "size": <페이지 크기>,
>        "total": <총 페이지 수>,
>        "current": <현재 페이지>
>      },
>      "order": {
>        "sorted": <정렬 여부>,
>        "by": [ { "field": <필드명>, "direction": <"asc" | "desc"> }, ... ]
>      },
>      "items": {
>        "total": <총 아이템 수>,
>        "current": <현재 리턴하는 아이템 수>,
>        "list": [ { <키>: <값>, ... }, ... ]
>      }
>    }
>  }
>}
>```

##### 페이지네이션 리스트 예시:
```json
{
  "status": "SUCCESS",
  "version": "1.0",
  "datetime": "2024-03-25T04:10:27.257626Z",
  "duration": 70,
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

#### 더보기 리스트 (cursor 기반)

커서 기반(더보기) 리스트를 사용할 때는 `incremental` 구조를 사용합니다.
- `cursor`: 커서 정보 (`field`, `start`, `end`, `expandable`) – 필드에 기준이 되는 필드명을 지정할 수 있으며, `expandable`은 이후 추가 데이터 존재 여부를 표시합니다.
- `order`: 정렬 정보 – optional
- `items`: 리스트와 아이템 수 정보

>```
>{
>  "status": <payload의 상태>,
>  "version": <버전 정보>,
>  "datetime": <응답 일시>,
>  "duration": <처리 시간>,
>  "payload": {
>    <키_1>: <값_1>,
>    ...,
>    "incremental": {
>      "cursor": {
>        "field": <기준 필드 (optional)>,
>        "start": <현 응답 리스트의 시작 인덱스>,
>        "end": <현 응답 리스트의 끝 인덱스>,
>        "expandable": <추가 데이터 존재 여부>
>      },
>      "order": {
>        "sorted": <정렬 여부>,
>        "by": [ { "field": <필드명>, "direction": <"asc" | "desc"> }, ... ]
>      },
>      "items": {
>        "total": <총 아이템 수>,
>        "current": <현재 리턴하는 아이템 수>,
>        "list": [ { <키>: <값>, ... }, ... ]
>      }
>    }
>  }
>}
>```

##### 더보기 리스트 예시:
```json
{
  "status": "SUCCESS",
  "version": "1.0",
  "datetime": "2024-03-25T04:10:27.257626Z",
  "duration": 70,
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

#### 페이지네이션 없는 전체 리스트 응답
전체 리스트를 페이지네이션 없이 응답할 경우에도 페이지 정보를 넣어 `pageable` 구조를 사용합니다. `page.size`를 `items.total`과 동일하게 지정하고 `page.total`과 `page.current`는 1로 지정합니다.

##### 전체 리스트 응답 예 (페이지네이션 형식):
```json
{
  "status": "SUCCESS",
  "version": "1.0",
  "datetime": "2024-03-25T04:10:27.257626Z",
  "duration": 70,
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

##### 전체 리스트 응답 예 (더보기 형식):
```json
{
  "status": "SUCCESS",
  "version": "1.0",
  "datetime": "2024-03-25T04:10:27.257626Z",
  "duration": 70,
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

#### 페이지네이션 또는 더보기 리스트로만 구성된 응답
리스트만으로 구성된 응답은 `pageable` 또는 `incremental` 구조를 직접 `payload`로 사용해도 됩니다. 이 경우에도 해당 구조를 준수해야 하며, `payload`라는 필드명은 유지합니다.

##### pageable로만 구성된 응답 예:
```json
{
  "status": "SUCCESS",
  "version": "1.0",
  "datetime": "2024-03-25T04:10:27.257626Z",
  "duration": 70,
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

#### 빈 리스트 응답
리스트가 비어 있는 경우 `list` 필드의 값은 `null` 대신 **빈 배열(`[]`)**로 지정합니다. `pageable` 또는 `incremental` 구조는 동일하게 준수합니다.

##### 빈 리스트 응답 예(pageable 형식):

```json
{
  "status": "SUCCESS",
  "version": "1.0",
  "datetime": "2024-03-25T04:10:27.257626Z",
  "duration": 70,
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

#### 두 개 이상의 리스트를 포함한 응답
여러 리스트를 포함할 때 각각의 리스트는 `pageable` 또는 `incremental` 구조를 준수합니다. 동일한 이름의 필드가 겹칠 수 있으므로 **각 리스트의 의미에 맞는 필드명**을 지정해 줍니다.

##### 2개의 리스트 응답 예:
```json
{
  "status": "SUCCESS",
  "version": "1.0",
  "datetime": "2024-03-25T04:10:27.257626Z",
  "duration": 70,
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
