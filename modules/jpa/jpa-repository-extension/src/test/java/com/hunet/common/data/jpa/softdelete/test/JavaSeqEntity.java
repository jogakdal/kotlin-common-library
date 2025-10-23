package com.hunet.common.data.jpa.softdelete.test;

import com.hunet.common.data.jpa.sequence.GenerateSequentialCode;
import jakarta.persistence.*;

@Entity
@Table(name = "java_seq_entity")
public class JavaSeqEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @GenerateSequentialCode(prefixExpression = "'JX-'")
    @Column(name = "code")
    private String code;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
}

