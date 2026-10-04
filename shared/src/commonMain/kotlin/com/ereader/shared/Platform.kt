package com.ereader.shared

internal expect class StoreLock() {
    fun <T> withLock(block: () -> T): T
}

internal expect fun epochMillis(): Long
