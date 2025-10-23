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
class JavaEnumConverterTest {

    @Autowired
    private JavaEnumHolderRepository repository;

    @Test
    @DisplayName("Java enum + GenericEnumConverter 매핑/조회 검증")
    void persistAndLoadEnum() {
        JavaEnumHolder holder = new JavaEnumHolder();
        holder.setStatus(Status.ACTIVE);
        JavaEnumHolder saved = repository.upsert(holder);
        assertThat(saved.getId()).isNotNull();

        JavaEnumHolder found = repository.findOneById(saved.getId()).orElseThrow();
        assertThat(found.getStatus()).isEqualTo(Status.ACTIVE);
    }
}

