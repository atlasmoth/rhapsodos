package com.osuji.ereaderapp.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class OwnedFiles(private val context: Context, private val store: ReaderStore) {
    suspend fun clean(paths: List<String>? = null) = withContext(Dispatchers.IO) {
        val root = context.filesDir.canonicalFile
        (paths ?: store.pendingRemovals()).forEach { path ->
            val file = File(path).canonicalFile
            require(file.path.startsWith(root.path + File.separator)) { "Only app-owned files can be removed." }
            if (!file.exists() || (file.isFile && file.delete())) store.acknowledgeRemoval(path)
        }
    }
}
