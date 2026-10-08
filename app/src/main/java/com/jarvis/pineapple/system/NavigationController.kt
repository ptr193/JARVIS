package com.jarvis.pineapple.system

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * 导航与出行：路线规划（调用地图应用）
 */
class NavigationController(private val context: Context) {

    /** 规划并导航到目的地 */
    fun navigateTo(destination: String) {
        val uri = Uri.parse("google.navigation:q=$destination")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            setPackage("com.google.android.apps.maps")
        }
        runCatching { context.startActivity(intent) }.onFailure {
            // 回退到通用地图 Intent
            val fallback = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$destination")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(fallback)
        }
    }

    /** 应急路线规划（预留） */
    fun emergencyRoute(destination: String) = navigateTo(destination)
}
