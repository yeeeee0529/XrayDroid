package io.github.xraydroid.runtime

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit

internal class FrpLayout(context: Context) {
    val executable = File(context.applicationInfo.nativeLibraryDir, "libfrpc.so")
    val root = File(context.filesDir, "server/frp")
    val config = File(root, "frpc.toml")
    private val cache = context.cacheDir

    fun prepare() {
        check(executable.canExecute()) { "Bundled frpc executable is missing or not executable" }
        check(root.isDirectory || root.mkdirs()) { "Unable to create FRP runtime directory" }
    }

    fun builder(vararg arguments: String): ProcessBuilder = ProcessBuilder(
        listOf(executable.absolutePath) + arguments
    ).apply {
        directory(root)
        // 不讀取核心日誌；原始驗證錯誤亦可能含憑證或設定內容。
        redirectOutput(File("/dev/null"))
        redirectError(File("/dev/null"))
        environment()["TMPDIR"] = cache.absolutePath
    }

    fun verify(file: File = config, allowUnsafeTokenCommand: Boolean = false): Boolean {
        val arguments = listOf("verify", "-c", file.absolutePath) + unsafeArguments(allowUnsafeTokenCommand)
        val child = builder(*arguments.toTypedArray()).start()
        return try {
            child.waitFor(15, TimeUnit.SECONDS) && child.exitValue() == 0
        } finally {
            if (child.isAlive) {
                child.destroyForcibly()
                child.waitFor(2, TimeUnit.SECONDS)
            }
        }
    }

    fun unsafeArguments(enabled: Boolean): List<String> = if (enabled) listOf("--allow-unsafe=TokenSourceExec") else emptyList()
}
