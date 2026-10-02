package io.github.xraydroid.runtime

import android.content.Context
import androidx.annotation.StringRes

/** 顯示文字：資源 ID 加格式參數，由 UI 或服務在顯示前解析。 */
data class TextResource(@param:StringRes @get:StringRes val id: Int, val args: List<String> = emptyList()) {
    fun resolve(context: Context): String = if (args.isEmpty()) context.getString(id) else context.getString(id, *args.toTypedArray())
}
