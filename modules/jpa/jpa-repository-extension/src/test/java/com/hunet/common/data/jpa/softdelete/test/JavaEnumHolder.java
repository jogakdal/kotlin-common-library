package com.hunet.common.data.jpa.softdelete.test;

import jakarta.persistence.*;

@Entity
@Table(name = "java_enum_holder")
public class JavaEnumHolder {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Convert(converter = StatusConverter.class)
    @Column(name = "status_code")
    private Status status;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
}

