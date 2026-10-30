package com.jarvis.pineapple.plugin

import android.content.Context
import android.content.pm.PackageManager
import dalvik.system.DexClassLoader
import java.io.File

/**
 * 插件加载器（#139）。
 *
 * 从 `filesDir/plugins` 中的 `.apk` / `.dex` / `.jar` 文件加载实现了
 * [JarvisPlugin] 的入口类：
 * 1. 读取插件包 `AndroidManifest` 的 `meta-data`（`jarvis.plugin.entry`）；
 * 2. 校验插件签名与宿主一致（可通过 `allow_unsigned` 开关关闭，默认强制校验）；
 * 3. 使用 [DexClassLoader] 加载入口类并实例化；
 * 4. 依据插件声明的能力创建 [PluginSandbox]，调用 `onLoad`。
 */
class PluginLoader(private val context: Context) {

    data class LoadedPlugin(
        val metadata: PluginMetadata,
        val instance: JarvisPlugin,
        val sourcePath: String,
        var enabled: Boolean
    )

    /**
     * 从单个插件文件加载。失败时返回 null（并记录原因）。
     */
    fun loadFrom(file: File): LoadedPlugin? {
        if (!file.exists()) return null
        val path = file.absolutePath
        if (!file.name.endsWith(".apk") && !file.name.endsWith(".dex") && !file.name.endsWith(".jar")) {
            return null
        }

        if (!verifySignature(path)) {
            android.util.Log.w(TAG, "插件签名校验失败，拒绝加载: ${file.name}")
            return null
        }

        val entryClass = readEntryClass(path)
        if (entryClass.isNullOrBlank()) {
            android.util.Log.w(TAG, "插件缺少 jarvis.plugin.entry 元数据: ${file.name}")
            return null
        }

        return try {
            val classLoader = DexClassLoader(
                path,
                context.codeCacheDir.absolutePath,
                null,
                javaClass.classLoader
            )
            val clazz = classLoader.loadClass(entryClass)
            val instance = clazz.getDeclaredConstructor().newInstance() as? JarvisPlugin
            if (instance == null) {
                android.util.Log.w(TAG, "入口类未实现 JarvisPlugin: $entryClass")
                return null
            }
            val metadata = instance.metadata()
            if (metadata.apiVersion != API_VERSION) {
                android.util.Log.w(
                    TAG,
                    "插件 API 版本不兼容: ${metadata.id} v${metadata.apiVersion} != $API_VERSION"
                )
                return null
            }
            LoadedPlugin(metadata, instance, path, enabled = false)
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "加载插件失败: ${file.name}", e)
            null
        }
    }

    /** 依据插件声明的能力创建沙箱宿主。 */
    fun createSandbox(metadata: PluginMetadata): PluginHost =
        PluginSandbox(metadata.id, context, metadata.requiredCapabilities.toSet())

    /** 读取插件入口类名（来自 APK Manifest 的 meta-data）。 */
    private fun readEntryClass(path: String): String? {
        return try {
            val info = context.packageManager.getPackageArchiveInfo(
                path,
                PackageManager.GET_META_DATA
            ) ?: return null
            val appInfo = info.applicationInfo ?: return null
            appInfo.sourceDir = path
            appInfo.publicSourceDir = path
            appInfo.metaData?.getString(META_KEY_ENTRY)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "读取插件元数据失败: $path", e)
            null
        }
    }

    /**
     * 校验插件签名。
     *
     * 默认要求插件与宿主使用相同证书签名；若在 `jarvis_plugins` 偏好中将
     * `allow_unsigned` 设为 true，则允许加载（仅用于开发调试）。
     */
    fun verifySignature(path: String): Boolean {
        if (context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_ALLOW_UNSIGNED, false)
        ) {
            return true
        }
        return try {
            val flags = PackageManager.GET_SIGNATURES
            val pluginInfo = context.packageManager.getPackageArchiveInfo(path, flags) ?: return false
            val pluginSigs = pluginInfo.signatures ?: return false
            val selfInfo = context.packageManager.getPackageInfo(context.packageName, flags)
            val selfSigs = selfInfo.signatures ?: return false
            pluginSigs.any { ps -> selfSigs.any { ss -> ps.toCharsString() == ss.toCharsString() } }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "签名校验异常: ${e.message}")
            false
        }
    }

    companion object {
        private const val TAG = "PluginLoader"
        private const val META_KEY_ENTRY = "jarvis.plugin.entry"
        private const val PREFS = "jarvis_plugins"
        private const val KEY_ALLOW_UNSIGNED = "allow_unsigned"
        const val API_VERSION = 1
    }
}
