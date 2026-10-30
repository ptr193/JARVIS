package com.jarvis.pineapple.plugin

import java.io.File

/**
 * JARVIS 插件接口（功能 #139 安全程序分化）。
 *
 * 第三方能力以独立 APK / DEX 的形式交付，由宿主在运行时动态加载，
 * 并通过 [PluginHost] 暴露受沙箱约束的能力。插件不得直接持有主程序对象，
 * 只能通过宿主提供的能力接口工作。
 */
interface JarvisPlugin {

    /** 插件元数据（在 [onLoad] 之前被宿主读取，用于构建沙箱）。 */
    fun metadata(): PluginMetadata

    /** 插件被加载后调用，宿主会传入受权限约束的 [PluginHost]。 */
    fun onLoad(host: PluginHost)

    /** 插件被禁用 / 卸载前调用，用于释放资源。 */
    fun onUnload()

    /**
     * 执行插件命令。
     *
     * @param command 命令名，由插件自行定义
     * @param args 命令参数
     */
    fun execute(command: String, args: Map<String, String> = emptyMap()): PluginResult
}

/**
 * 插件元数据。
 */
data class PluginMetadata(
    val id: String,
    val name: String,
    val version: String,
    val author: String = "",
    val description: String = "",
    /** 宿主插件 API 版本，用于兼容性判断。 */
    val apiVersion: Int = CURRENT_API_VERSION,
    /** 插件声明的能力（见 [PluginCapability]）。 */
    val requiredCapabilities: List<String> = emptyList()
) {
    companion object {
        const val CURRENT_API_VERSION = 1
    }
}

/**
 * 插件执行结果。
 */
data class PluginResult(
    val success: Boolean,
    val output: String = "",
    val error: String? = null
) {
    companion object {
        fun ok(output: String = "") = PluginResult(true, output)
        fun fail(error: String) = PluginResult(false, "", error)
    }
}

/**
 * 沙箱宿主：插件只能通过该接口访问宿主能力，所有能力都会做权限校验。
 */
interface PluginHost {
    /** 当前插件 id。 */
    val pluginId: String

    /** 写插件日志。 */
    fun log(message: String)

    /** 能力是否已被授权。 */
    fun isCapabilityGranted(capability: String): Boolean

    /** 读取插件私有偏好（需 STORAGE 能力）。 */
    fun getPreference(key: String, default: String? = null): String?

    /** 写入插件私有偏好（需 STORAGE 能力）。 */
    fun setPreference(key: String, value: String)

    /** 插件私有文件目录（需 STORAGE 能力）。 */
    fun pluginFilesDir(): File

    /** 发起 HTTP GET（需 NETWORK 能力）。失败时返回 null。 */
    fun httpGet(url: String, timeoutMs: Int = 10_000): String?

    /** 发送通知（需 NOTIFICATIONS 能力）。 */
    fun notify(title: String, message: String)
}

/** 插件能力常量（用于沙箱权限控制）。 */
object PluginCapability {
    const val NETWORK = "network"
    const val STORAGE = "storage"
    const val NOTIFICATIONS = "notifications"
    const val SMS = "sms"
    const val LOCATION = "location"
    const val CAMERA = "camera"

    val ALL: Set<String> = setOf(NETWORK, STORAGE, NOTIFICATIONS, SMS, LOCATION, CAMERA)
}
