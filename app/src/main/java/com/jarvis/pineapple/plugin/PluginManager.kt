package com.jarvis.pineapple.plugin

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File

/**
 * 插件管理器（#139 安全程序分化）。
 *
 * 负责插件的安装 / 卸载 / 启用 / 禁用 / 加载 / 执行，并把注册表持久化到
 * `jarvis_plugins` SharedPreferences。插件文件存放在 `filesDir/plugins`。
 */
class PluginManager(private val context: Context) {

    /** 对外暴露的插件信息。 */
    data class PluginInfo(
        val metadata: PluginMetadata,
        val sourcePath: String,
        val enabled: Boolean
    )

    private val loader = PluginLoader(context)
    private val pluginsDir = File(context.filesDir, "plugins").apply { mkdirs() }

    /** 已加载的插件实例（按 id）。 */
    private val instances = mutableMapOf<String, PluginLoader.LoadedPlugin>()

    private val _plugins = MutableStateFlow<List<PluginInfo>>(emptyList())
    val plugins: StateFlow<List<PluginInfo>> = _plugins.asStateFlow()

    /**
     * 重新扫描插件目录：卸载所有已加载插件后重新加载，并恢复启用状态。
     */
    fun reload() {
        instances.values.forEach { loaded ->
            if (loaded.enabled) runCatching { loaded.instance.onUnload() }
        }
        instances.clear()

        val enabled = enabledSet().toMutableSet()
        val files = fileMap()
        val discovered = LinkedHashSet<String>()

        pluginsDir.listFiles()?.filter { it.isFile }?.sortedBy { it.name }?.forEach { file ->
            val loaded = loader.loadFrom(file) ?: return@forEach
            val id = loaded.metadata.id
            discovered.add(id)
            loaded.enabled = id in enabled
            instances[id] = loaded
            if (loaded.enabled) activate(loaded)
            files[id] = file.name
        }

        // 清理已消失插件的记录
        files.keys.retainAll(discovered)
        enabled.retainAll(discovered)
        persistFiles(files)
        persistEnabled(enabled)
        refresh()
    }

    /**
     * 安装插件：把源文件复制到插件目录并加载，成功后默认启用。
     * @return 插件 id，失败返回 null。
     */
    fun install(source: File): String? {
        if (!source.exists() || !source.isFile) return null
        val target = File(pluginsDir, source.name)
        return try {
            if (target.canonicalPath != source.canonicalPath) {
                source.inputStream().use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }
            val loaded = loader.loadFrom(target)
            if (loaded == null) {
                target.delete()
                return null
            }
            val id = loaded.metadata.id
            loaded.enabled = true
            instances[id] = loaded
            activate(loaded)

            fileMap().apply { this[id] = target.name }.let { persistFiles(it) }
            persistEnabled(enabledSet().toMutableSet().apply { add(id) })
            refresh()
            Log.i(TAG, "插件已安装: $id")
            id
        } catch (e: Exception) {
            Log.e(TAG, "安装插件失败", e)
            target.delete()
            null
        }
    }

    /** 卸载插件。 */
    fun uninstall(pluginId: String): Boolean {
        val loaded = instances.remove(pluginId) ?: return false
        if (loaded.enabled) runCatching { loaded.instance.onUnload() }
        val file = File(loaded.sourcePath)
        fileMap().apply { remove(pluginId) }.let { persistFiles(it) }
        persistEnabled(enabledSet().toMutableSet().apply { remove(pluginId) })
        val deleted = !file.exists() || file.delete()
        refresh()
        Log.i(TAG, "插件已卸载: $pluginId")
        return deleted
    }

    /** 启用插件。 */
    fun enable(pluginId: String): Boolean {
        val loaded = instances[pluginId] ?: return false
        if (!loaded.enabled) {
            loaded.enabled = true
            activate(loaded)
        }
        persistEnabled(enabledSet().toMutableSet().apply { add(pluginId) })
        refresh()
        return true
    }

    /** 禁用插件。 */
    fun disable(pluginId: String): Boolean {
        val loaded = instances[pluginId] ?: return false
        if (loaded.enabled) {
            runCatching { loaded.instance.onUnload() }
            loaded.enabled = false
        }
        persistEnabled(enabledSet().toMutableSet().apply { remove(pluginId) })
        refresh()
        return true
    }

    /** 执行插件命令（仅对已启用插件）。 */
    fun execute(
        pluginId: String,
        command: String,
        args: Map<String, String> = emptyMap()
    ): PluginResult {
        val loaded = instances[pluginId]
            ?: return PluginResult(false, error = "插件未安装: $pluginId")
        if (!loaded.enabled) {
            return PluginResult(false, error = "插件未启用: $pluginId")
        }
        return try {
            loaded.instance.execute(command, args)
        } catch (e: Throwable) {
            Log.e(TAG, "插件执行异常: $pluginId/$command", e)
            PluginResult(false, error = "插件执行异常: ${e.message}")
        }
    }

    fun listPlugins(): List<PluginInfo> = _plugins.value

    fun getPlugin(pluginId: String): PluginInfo? =
        _plugins.value.firstOrNull { it.metadata.id == pluginId }

    // ==================== 内部 ====================

    private fun activate(loaded: PluginLoader.LoadedPlugin) {
        val host = loader.createSandbox(loaded.metadata)
        try {
            loaded.instance.onLoad(host)
        } catch (e: Throwable) {
            Log.e(TAG, "插件 onLoad 失败: ${loaded.metadata.id}", e)
        }
    }

    private fun refresh() {
        _plugins.value = instances.values
            .map { PluginInfo(it.metadata, it.sourcePath, it.enabled) }
            .sortedBy { it.metadata.name }
    }

    private fun prefs() =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun enabledSet(): Set<String> =
        prefs().getStringSet(KEY_ENABLED, emptySet())?.toSet() ?: emptySet()

    private fun persistEnabled(enabled: Set<String>) {
        prefs().edit().putStringSet(KEY_ENABLED, enabled.toMutableSet()).apply()
    }

    private fun fileMap(): MutableMap<String, String> {
        val raw = prefs().getString(KEY_FILES, null) ?: return mutableMapOf()
        val result = mutableMapOf<String, String>()
        runCatching {
            val json = JSONObject(raw)
            json.keys().forEach { key -> result[key] = json.getString(key) }
        }
        return result
    }

    private fun persistFiles(files: Map<String, String>) {
        val json = JSONObject()
        files.forEach { (id, name) -> json.put(id, name) }
        prefs().edit().putString(KEY_FILES, json.toString()).apply()
    }

    companion object {
        private const val TAG = "PluginManager"
        private const val PREFS = "jarvis_plugins"
        private const val KEY_FILES = "plugin_files"
        private const val KEY_ENABLED = "enabled_plugins"
    }
}
