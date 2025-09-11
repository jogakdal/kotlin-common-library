package com.hunet.common_library.lib

object YnFlag {
    const val Y = "Y"
    const val N = "N"
    const val y = "y"
    const val n = "n"
    const val UNKNOWN = ""

    fun isY(target: String, ignoreCase: Boolean = true) = target.equals(Y, ignoreCase)
    fun isN(target: String, ignoreCase: Boolean = true) = target.equals(N, ignoreCase)

    fun getYn(condition: Boolean) = if (condition) Y else N
}

fun String.isY(ignoreCase: Boolean = true) = equals(YnFlag.Y, ignoreCase)
fun String.isN(ignoreCase: Boolean = true) = equals(YnFlag.N, ignoreCase)
fun Boolean.getYn() = YnFlag.getYn(this)
