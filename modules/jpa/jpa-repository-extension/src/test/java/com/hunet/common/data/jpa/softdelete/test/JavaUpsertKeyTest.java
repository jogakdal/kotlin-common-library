package com.hunet.common.data.jpa.softdelete.test;

import com.hunet.common.data.jpa.softdelete.SoftDeleteJpaRepositoryAutoConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@EntityScan(basePackages = "com.hunet.common.data.jpa.softdelete.test")
@Import(SoftDeleteJpaRepositoryAutoConfiguration.class)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false"
})
class JavaUpsertKeyTest {

    @Autowired
    private JavaMemberRepository repository;

    @Test
    @DisplayName("Java 엔티티 @UpsertKey 기반 upsert 동작 검증")
    void upsertByJavaFieldAnnotation() {
        // given: 최초 엔티티 (loginId=user01, name=A)
        JavaMember first = new JavaMember();
        first.setLoginId("user01");
        first.setName("A");

        JavaMember saved1 = repository.upsert(first);
        assertThat(saved1.getId()).isNotNull();
        Long persistedId = saved1.getId();

        // when: 같은 loginId로 새로운 객체 (name=B) upsert
        JavaMember second = new JavaMember();
        second.setLoginId("user01"); // 동일 키
        second.setName("B"); // 변경된 값
        JavaMember saved2 = repository.upsert(second);

        // then: row 개수 1, name 값 갱신
        assertThat(repository.count()).isEqualTo(1);
        JavaMember fetched = repository.findFirstByField("loginId", "user01").orElseThrow();
        assertThat(fetched.getId()).isEqualTo(persistedId); // 같은 PK 유지
        assertThat(fetched.getName()).isEqualTo("B");
        // second 객체는 merge된 existing 이므로 id 채워졌을 가능성
        assertThat(saved2.getId()).isEqualTo(persistedId);
    }
}
