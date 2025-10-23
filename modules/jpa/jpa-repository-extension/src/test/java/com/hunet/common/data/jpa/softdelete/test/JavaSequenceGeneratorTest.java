package com.hunet.common.data.jpa.softdelete.test;

import com.hunet.common.data.jpa.sequence.SequenceGenerator;
import static com.hunet.common.data.jpa.sequence.SequenceGeneratorKt.applySequentialCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class JavaSequenceGeneratorTest {

    @Test
    @DisplayName("Java 엔티티 필드 @GenerateSequentialCode 코드 생성 검증")
    void generateSequentialCodeOnJavaField() {
        JavaSeqEntity entity = new JavaSeqEntity();
        SequenceGenerator generator = new SequenceGenerator() {
            long counter = 0L;
            @Override
            public Object generateKey(String prefix, Object entity) {
                return prefix + (++counter);
            }
        };
        applySequentialCode(entity, generator);
        assertThat(entity.getCode()).isNotBlank();
        assertThat(entity.getCode()).startsWith("JX-");
    }
}
