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
    fun navigateTo(destination: String) {
        when (mapProvider) {
            "amap" -> navigateWithAmap(destination)
            "baidu" -> navigateWithBaidu(destination)
            else -> navigateWithGoogle(destination)
        }
    }

    /** 高德地图导航 */
    private fun navigateWithAmap(destination: String) {
        // 高德 URI 格式：androidamap://navi?sourceApplication=appname&poiname=目的地&lat=36.2&lng=116.1&dev=0
        val uri = Uri.parse("androidamap://navi?sourceApplication=JARVIS&poiname=$destination&dev=0")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            setPackage("com.autonavi.minimap")
        }
        runCatching { context.startActivity(intent) }.onFailure {
            // 降级到 Web 版
            val fallback = Intent(Intent.ACTION_VIEW, Uri.parse("https://m.amap.com/navi/?dest=$destination")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(fallback)
        }
    }

    /** 百度地图导航 */
    private fun navigateWithBaidu(destination: String) {
        // 百度 URI 格式：baidumap://map/navi?query=目的地
        val uri = Uri.parse("baidumap://map/navi?query=$destination")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            setPackage("com.baidu.BaiduMap")
        }
        runCatching { context.startActivity(intent) }.onFailure {
            val fallback = Intent(Intent.ACTION_VIEW, Uri.parse("https://map.baidu.com/mobile/webapp/navi?query=$destination")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(fallback)
        }
    }

    /** 谷歌地图导航（海外备选） */
    private fun navigateWithGoogle(destination: String) {
        val uri = Uri.parse("google.navigation:q=$destination")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            setPackage("com.google.android.apps.maps")
        }
        runCatching { context.startActivity(intent) }.onFailure {
            val fallback = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$destination")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(fallback)
        }
    }

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
