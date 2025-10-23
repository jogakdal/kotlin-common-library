package com.hunet.common.data.jpa.softdelete.test;

import com.hunet.common.data.jpa.softdelete.UpsertKey;
import jakarta.persistence.*;

@Entity
@Table(name = "tb_java_member")
public class JavaMember {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @UpsertKey
    @Column(name = "login_id", nullable = false, unique = true)
    private String loginId;

    @Column(name = "name")
    private String name;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getLoginId() { return loginId; }
    public void setLoginId(String loginId) { this.loginId = loginId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
}

