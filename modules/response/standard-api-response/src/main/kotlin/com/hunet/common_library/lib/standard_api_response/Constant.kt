package com.hunet.common_library.lib.standard_api_response

import com.hunet.common_library.lib.YnFlag
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDate

object NullResponse {
    const val STRING = "null"
    const val INT = 0
    const val LONG = 0L
    const val DOUBLE = 0.0
    const val FLOAT = 0F
    val DATE = LocalDate.MIN
    const val YEAR = 9999
    const val YN = YnFlag.Y
    val DURATION = Duration.ZERO
    val BIG_DECIMAL = BigDecimal.ZERO
}
