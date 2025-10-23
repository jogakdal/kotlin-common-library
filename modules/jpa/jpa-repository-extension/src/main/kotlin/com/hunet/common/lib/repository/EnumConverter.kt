package com.hunet.common.lib.repository

import com.hunet.common.data.jpa.converter.GenericEnumConverter

@Deprecated(
    message = "Moved to com.hunet.common.data.jpa.converter.GenericEnumConverter",
    replaceWith = ReplaceWith(
        "GenericEnumConverter",
        imports = ["com.hunet.common.data.jpa.converter.GenericEnumConverter"]
    )
)
typealias GenericEnumConverter<E, V> = GenericEnumConverter<E, V>
