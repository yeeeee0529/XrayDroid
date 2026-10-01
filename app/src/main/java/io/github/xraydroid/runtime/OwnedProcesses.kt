package io.github.xraydroid.runtime

import android.os.Process
import android.system.Os
import android.system.OsConstants
import java.io.File

internal object OwnedProcesses {
    fun terminate(paths: Set<String>, signal: Int = OsConstants.SIGTERM) {
        File("/proc").listFiles()?.forEach { directory ->
            val pid = directory.name.toIntOrNull() ?: return@forEach
            if (pid == Process.myPid()) return@forEach
            runCatching {
                val executable = File(directory, "exe").path
                val identity = Os.stat(directory.path)
                if (identity.st_uid != Process.myUid()) return@runCatching
                if (Os.readlink(executable).removeSuffix(" (deleted)") !in paths) return@runCatching
                // 發送訊號前再次比對，降低 PID 被重用時誤殺的機率。
                if (Os.stat(directory.path).st_uid == identity.st_uid &&
                    Os.readlink(executable).removeSuffix(" (deleted)") in paths
                ) {
                    Os.kill(pid, signal)
                }
            }
        }
    }
}
