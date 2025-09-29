/* 첫 블록 주석: 세미콜론; 여러개; */
/* multi line start
   second line ; still comment
   third line;
*/
INSERT INTO a(id, val) VALUES (1, 'A');
/* inline block */ INSERT INTO b(id, txt) VALUES (2, "B;B");
/*
 multi line block with ; and "quotes;" and 'single;quote'
 should be ignored fully
*/
INSERT INTO c(id, msg) VALUES (3, 'C;C;C');

