# VariableProcessor (common-core)

(이 문서는 모듈 내부 사본입니다. 루트 docs/variable-processor.md 와 동일한 내용을 제공합니다.)

## Options 요약
| 필드 | 기본        | 설명                           |
|------|-----------|------------------------------|
| delimiters | `%{`,`}%` | 토큰 구분자                       |
| ignoreCase | true      | 토큰 이름 대소문자 무시                |
| ignoreMissing | false     | 미등록 토큰 원문 유지                 |
| enableDefaultValue | false     | `%{token\|fallback}%` 기본값 기능 |
| defaultDelimiter | `\|`      | 기본값 구분자 문자                   |
| escapeChar | `\\`      | 기본값 파싱 이스케이프                 |

정책 표, 사용 예 및 FAQ 는 루트 docs/variable-processor.md 참고.

