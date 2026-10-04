package com.ereader.shared

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.ereader.shared.database.ReaderDatabase
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

object AndroidCoreFactory {
    fun open(context: Context, databaseName: String = "reader.db"): ReaderCore =
        ReaderCore(AndroidSqliteDriver(ReaderDatabase.Schema, context.applicationContext, databaseName))
}

internal actual class StoreLock {
    private val lock = ReentrantLock()
    actual fun <T> withLock(block: () -> T): T = lock.withLock(block)
}

internal actual fun epochMillis(): Long = System.currentTimeMillis()
