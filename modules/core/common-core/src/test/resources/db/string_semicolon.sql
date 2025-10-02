-- 문자열 내부 세미콜론은 구분자로 처리되지 않아야 함
INSERT INTO notes(id, body) VALUES (1, 'hello;world;still one');
INSERT INTO notes(id, body) VALUES (2, 'second');

