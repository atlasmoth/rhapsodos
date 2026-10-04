package com.osuji.ereaderapp.data

import android.system.Os
import android.system.OsConstants
import java.io.File

internal fun syncDirectory(directory: File, root: File) {
    val boundary = root.canonicalFile
    var current = directory.canonicalFile
    require(current == boundary || current.path.startsWith(boundary.path + File.separator))
    while (true) {
        val descriptor = Os.open(current.absolutePath, OsConstants.O_RDONLY, 0)
        try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
        if (current == boundary) return
        current = checkNotNull(current.parentFile)
    }
}
