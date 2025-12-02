package com.hunet.common.data.jpa.softdelete.test;

public enum Status {
    ACTIVE("A"), INACTIVE("I");

    private final String value;

    Status(String value) { this.value = value; }

    public String getValue() { return value; }

    public static Status fromValue(String v) {
        for (Status s : values()) {
            if (s.value.equals(v)) return s;
        }
        throw new IllegalArgumentException("No enum constant for value: " + v);
    }
}

