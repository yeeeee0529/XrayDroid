package io.github.xraydroid.runtime

import android.content.Context
import io.github.xraydroid.BuildConfig
import java.io.File

class RuntimeLayout(private val context: Context) {
    val xui = File(context.applicationInfo.nativeLibraryDir, "libxui.so")
    val xray = File(context.applicationInfo.nativeLibraryDir, "libxray.so")
    private val root = File(context.filesDir, "server")
    private val xrayData = File(root, "xray")
    private val database = File(root, "db")
    private val logs = File(root, "log")

    fun prepare() {
        check(xui.canExecute() && xray.canExecute()) {
            "Bundled native executables are missing or not executable"
        }
        listOf(root, xrayData, database, logs).forEach {
            check(it.isDirectory || it.mkdirs()) { "Unable to create runtime directory" }
        }
        val marker = File(xrayData, ".asset-version")
        val currentVersion = BuildConfig.XRAY_VERSION
        val update = !marker.isFile || marker.readText() != currentVersion
        listOf("geoip.dat", "geosite.dat").forEach { name ->
            val target = File(xrayData, name)
            if (update || !target.isFile) {
                val temporary = File(xrayData, "$name.tmp")
                context.assets.open("core/$name").use { input ->
                    temporary.outputStream().use { output -> input.copyTo(output) }
                }
                check(temporary.renameTo(target)) { "Unable to install core asset" }
            }
        }
        marker.writeText(currentVersion)
    }

    fun builder(vararg arguments: String): ProcessBuilder = ProcessBuilder(listOf(xui.absolutePath) + arguments).apply {
        directory(root)
        redirectErrorStream(true)
        environment().putAll(
            mapOf(
                "XUI_XRAY_BINARY" to xray.absolutePath,
                "XUI_BIN_FOLDER" to xrayData.absolutePath,
                "XUI_DB_FOLDER" to database.absolutePath,
                "XUI_LOG_FOLDER" to logs.absolutePath,
                "XRAY_LOCATION_ASSET" to xrayData.absolutePath,
                "XUI_INIT_WEB_BASE_PATH" to "/",
                "XUI_PORT" to "2053",
                "XUI_LOG_LEVEL" to "warning",
                "TMPDIR" to context.cacheDir.absolutePath
            )
        )
    }
}
