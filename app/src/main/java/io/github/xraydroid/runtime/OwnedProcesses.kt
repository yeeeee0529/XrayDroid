package io.github.xraydroid.runtime

import android.os.Process
import android.system.Os
import android.system.OsConstants
import java.io.File

internal object OwnedProcesses {
    fun groupFor(path: String, configPath: String? = null): Int? = File("/proc").listFiles()?.firstNotNullOfOrNull { directory ->
        val pid = directory.name.toIntOrNull() ?: return@firstNotNullOfOrNull null
        runCatching {
            if (Os.stat(directory.path).st_uid != Process.myUid() ||
                Os.readlink(File(directory, "exe").path).removeSuffix(" (deleted)") != path
            ) {
                return@runCatching null
            }
            if (configPath != null) {
                val arguments = File(directory, "cmdline").readBytes().toString(Charsets.UTF_8).split('\u0000')
                // 僅辨識以指定設定啟動的客戶端，不包含 verify 與版本探針。
                if (arguments.getOrNull(1) != "-c" || arguments.getOrNull(2) != configPath) return@runCatching null
            }
            val fields = File(directory, "stat").readText().substringAfterLast(") ").split(' ')
            // frpc 透過 setsid 建立自己的 session；不能清理 App 原有的程序群組。
            pid.takeIf { fields.getOrNull(2)?.toIntOrNull() == pid && fields.getOrNull(3)?.toIntOrNull() == pid }
        }.getOrNull()
    }

    fun terminateGroup(group: Int?, signal: Int = OsConstants.SIGTERM) {
        if (group == null || group <= 1 || group == Process.myPid()) return
        File("/proc").listFiles()?.forEach { directory ->
            val pid = directory.name.toIntOrNull() ?: return@forEach
            if (pid == Process.myPid()) return@forEach
            runCatching {
                if (Os.stat(directory.path).st_uid != Process.myUid()) return@runCatching
                val fields = File(directory, "stat").readText().substringAfterLast(") ").split(' ')
                if (fields.getOrNull(2)?.toIntOrNull() != group || fields.getOrNull(3)?.toIntOrNull() != group) return@runCatching
                val current = File(directory, "stat").readText().substringAfterLast(") ").split(' ')
                // 發送訊號前比對 UID、session 與啟動時間，避免 PID 重用。
                if (Os.stat(directory.path).st_uid == Process.myUid() &&
                    current.getOrNull(2) == fields.getOrNull(2) && current.getOrNull(3) == fields.getOrNull(3) &&
                    current.getOrNull(19) == fields.getOrNull(19)
                ) {
                    Os.kill(pid, signal)
                }
            }
        }
    }

    fun isRunning(path: String): Boolean = File("/proc").listFiles()?.any { directory ->
        directory.name.toIntOrNull() != null && runCatching {
            Os.stat(directory.path).st_uid == Process.myUid() &&
                Os.readlink(File(directory, "exe").path).removeSuffix(" (deleted)") == path
        }.getOrDefault(false)
    } ?: false

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
