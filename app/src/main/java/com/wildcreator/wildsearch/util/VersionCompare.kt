package com.wildcreator.wildsearch.util

/**
 * 保留既有数字段兼容，并解析 SemVer 预发布与构建标识。
 * 无法解析时返回 null，调用方按「不更新」处理，避免误报。
 */
private data class Version(val core: List<java.math.BigInteger>, val prerelease: List<String>?)

private fun parseVersion(raw: String?): Version? {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    val withoutPrefix = trimmed.removePrefix("v").removePrefix("V")
    val metadata = withoutPrefix.split('+', limit = 2)
    val identifier = Regex("[0-9A-Za-z-]+")
    if (metadata.size == 2 && !metadata[1].split('.').all { identifier.matches(it) }) return null
    val version = metadata[0].split('-', limit = 2)
    val parts = version[0].split('.').map {
        if (!it.matches(Regex("[0-9]+"))) return null
        it.toBigInteger()
    }
    val prerelease = if (version.size == 2) version[1].split('.').also { ids ->
        if (!ids.all { identifier.matches(it) }) return null
        if (ids.any { it.all(Char::isDigit) && it.length > 1 && it.startsWith('0') }) return null
    } else null
    return Version(parts, prerelease)
}

/**
 * [candidate] 是否比 [current] 新。逐段比较，缺失段补 0，因此 1.2 == 1.2.0。
 * 同核心版本再比较预发布标识；构建元数据不参与排序。非法版本返回 false。
 */
fun isNewerVersion(candidate: String?, current: String?): Boolean {
    val newVersion = parseVersion(candidate) ?: return false
    val oldVersion = parseVersion(current) ?: return false
    for (index in 0 until maxOf(newVersion.core.size, oldVersion.core.size)) {
        val a = newVersion.core.getOrElse(index) { java.math.BigInteger.ZERO }
        val b = oldVersion.core.getOrElse(index) { java.math.BigInteger.ZERO }
        if (a != b) return a > b
    }
    val a = newVersion.prerelease ?: return oldVersion.prerelease != null
    val b = oldVersion.prerelease ?: return false
    for (index in 0 until minOf(a.size, b.size)) {
        if (a[index] == b[index]) continue
        val an = a[index].all(Char::isDigit)
        val bn = b[index].all(Char::isDigit)
        return when {
            an && bn -> a[index].toBigInteger() > b[index].toBigInteger()
            an != bn -> !an
            else -> a[index] > b[index]
        }
    }
    return a.size > b.size
}

/** 字节数友好展示。 */
fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
