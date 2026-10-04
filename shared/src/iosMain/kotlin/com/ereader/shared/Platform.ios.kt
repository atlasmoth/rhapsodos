@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ereader.shared

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.ereader.shared.database.ReaderDatabase
import platform.Foundation.NSDate
import platform.Foundation.NSRecursiveLock
import platform.Foundation.timeIntervalSince1970

object ReaderCoreFactory {
    @Throws(Exception::class)
    fun open(path: String): ReaderCore {
        val name = path.substringAfterLast('/')
        val directory = path.substringBeforeLast('/', "")
        require(name.isNotBlank() && directory.startsWith('/')) { "Database needs an absolute protected path" }
        val driver = NativeSqliteDriver(ReaderDatabase.Schema, name, onConfiguration = { configuration ->
            configuration.copy(extendedConfig = configuration.extendedConfig.copy(basePath = directory))
        })
        return ReaderCore(driver)
    }
}

internal actual class StoreLock {
    private val lock = NSRecursiveLock()
    actual fun <T> withLock(block: () -> T): T {
        lock.lock()
        return try { block() } finally { lock.unlock() }
    }
}

internal actual fun epochMillis(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()
