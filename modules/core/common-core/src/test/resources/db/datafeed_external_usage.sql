-- 외부 프로젝트에서 DataFeed를 통해 시딩하는 예제 스크립트
CREATE TABLE IF NOT EXISTS ext_users (
    id BIGINT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    active BOOLEAN NOT NULL
);

INSERT INTO ext_users(id, name, active) VALUES (1, 'Alice', true);
INSERT INTO ext_users(id, name, active) VALUES (2, 'Bob', false);
-- 문자열 내부 세미콜론
INSERT INTO ext_users(id, name, active) VALUES (3, 'Carol;Semicolon', true);

