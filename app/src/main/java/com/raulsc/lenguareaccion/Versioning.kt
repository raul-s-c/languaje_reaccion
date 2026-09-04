package com.raulsc.lenguareaccion

internal fun isNewerVersion(candidate: String, current: String): Boolean {
    val next = versionParts(candidate)
    val installed = versionParts(current)
    val size = maxOf(next.size, installed.size)
    for (index in 0 until size) {
        val left = next.getOrElse(index) { 0 }
        val right = installed.getOrElse(index) { 0 }
        if (left != right) return left > right
    }
    return false
}

private fun versionParts(value: String): List<Int> = value
    .removePrefix("v")
    .substringBefore('-')
    .split('.')
    .map { it.toIntOrNull() ?: 0 }

