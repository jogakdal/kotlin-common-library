package com.hunet.common.data.jpa.softdelete.test;

import com.hunet.common.data.jpa.converter.GenericEnumConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = false)
public class StatusConverter extends GenericEnumConverter<Status, String> {
    public StatusConverter() { super(Status.class, String.class); }
}

