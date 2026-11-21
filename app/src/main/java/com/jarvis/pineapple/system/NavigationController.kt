package com.jarvis.pineapple.system

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.jarvis.pineapple.BuildConfig

/**
 * 导航与出行：路线规划（调用地图应用）。
 * 支持配置地图服务商：高德(Amap)、百度(Baidu)、谷歌(Google)
 * 地图服务商通过 BuildConfig.MAP_PROVIDER 配置："amap" | "baidu" | "google"
 */
class NavigationController(private val context: Context) {

    private val mapProvider = BuildConfig.MAP_PROVIDER.lowercase()

    /** 规划并导航到目的地 */
    fun navigateTo(destination: String): Boolean {
        if (destination.isBlank()) return false
        return when (mapProvider) {
            "amap" -> navigateWithAmap(destination)
            "baidu" -> navigateWithBaidu(destination)
            else -> navigateWithGoogle(destination)
        }
    }

    /** 高德地图导航 */
    private fun navigateWithAmap(destination: String): Boolean {
        val query = Uri.encode(destination)
        val primary = viewIntent(
            "androidamap://navi?sourceApplication=JARVIS&poiname=$query&dev=0",
            "com.autonavi.minimap"
        )
        val fallback = viewIntent("https://m.amap.com/navi/?dest=$query")
        return launch(primary) || launch(fallback)
    }

    /** 百度地图导航 */
    private fun navigateWithBaidu(destination: String): Boolean {
        val query = Uri.encode(destination)
        val primary = viewIntent("baidumap://map/navi?query=$query", "com.baidu.BaiduMap")
        val fallback = viewIntent("https://map.baidu.com/mobile/webapp/navi?query=$query")
        return launch(primary) || launch(fallback)
    }

    /** 谷歌地图导航（海外备选） */
    private fun navigateWithGoogle(destination: String): Boolean {
        val query = Uri.encode(destination)
        val primary = viewIntent("google.navigation:q=$query", "com.google.android.apps.maps")
        val fallback = viewIntent("geo:0,0?q=$query")
        return launch(primary) || launch(fallback)
    }

    private fun viewIntent(uri: String, packageName: String? = null): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            packageName?.let(::setPackage)
        }

    private fun launch(intent: Intent): Boolean =
        runCatching { context.startActivity(intent); true }.getOrDefault(false)

    /**
     * 应急路线：以 POI 关键词导航到最近的目的地（默认医院）。
     * 高德/百度关键词导航可直接搜索最近设施。
     */
    fun emergencyRoute(destination: String = "医院") {
        val encoded = Uri.encode(destination)
        val (uri, pkg) = when (mapProvider) {
            "baidu" -> Uri.parse("bdapp://map/navi?query=$encoded&type=poi") to "com.baidu.BaiduMap"
            "google" -> Uri.parse("google.navigation:q=$encoded") to "com.google.android.apps.maps"
            else -> Uri.parse("androidamap://keywordNavi?sourceApplication=JARVIS&keyword=$encoded&style=2") to
                "com.autonavi.minimap"
        }
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            pkg.let { setPackage(it) }
        }
        runCatching { context.startActivity(intent) }.onFailure {
            val web = when (mapProvider) {
                "baidu" -> "https://map.baidu.com/search/$destination"
                "google" -> "https://www.google.com/maps/search/$destination"
                else -> "https://m.amap.com/search/mapview/keywords=$destination"
            }
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(web)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            }
        }
    }

}
