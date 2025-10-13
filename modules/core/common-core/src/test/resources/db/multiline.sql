-- 초기 사용자 추가
/* block comment single line */
INSERT INTO users(id, name)
VALUES (1, 'Alice');
-- 역할 추가
INSERT INTO roles(id, name) VALUES (1, 'ADMIN');
/* mid block */ INSERT INTO features(flag, enabled) VALUES ('exp-x', true);

INSERT INTO trailing_semicolon(id) VALUES (99);

