package com.jarvis.pineapple.plugin

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.io.IOException

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
     * 目录不可读或卸载失败时保留现有记录并返回 false。
     */
    fun reload(): Boolean {
        val pluginFiles = try {
            pluginsDir.listFiles()
        } catch (e: SecurityException) {
            Log.e(TAG, "枚举插件目录失败", e)
            null
        }
        if (pluginFiles == null) {
            Log.e(TAG, "无法读取插件目录: ${pluginsDir.absolutePath}")
            return false
        }

        instances.values.forEach { loaded ->
            if (!deactivate(loaded)) {
                refresh()
                return false
            }
        }
        instances.clear()

        val enabled = enabledSet().toMutableSet()
        val files = fileMap()
        val discovered = LinkedHashSet<String>()

        pluginFiles.filter { it.isFile }.sortedBy { it.name }.forEach { file ->
            val loaded = loader.loadFrom(file) ?: return@forEach
            val id = loaded.metadata.id
            discovered.add(id)
            loaded.enabled = id in enabled
            if (loaded.enabled && !activate(loaded)) {
                loaded.enabled = false
                enabled.remove(id)
            }
            instances[id] = loaded
            files[id] = file.name
        }

        // 仅在目录枚举成功后清理已消失插件的记录。
        files.keys.retainAll(discovered)
        enabled.retainAll(discovered)
        persistFiles(files)
        persistEnabled(enabled)
        refresh()
        return true
    }

    /**
     * 安装插件：把源文件复制到插件目录并加载，成功后默认启用。
     * @return 插件 id，失败返回 null。
     */
    fun install(source: File): String? {
        if (!source.exists() || !source.isFile) return null
        var staging: File? = null
        return try {
            val staged = File(pluginsDir, ".install_${System.nanoTime()}.${source.extension}")
            staging = staged
            source.inputStream().use { input ->
                staged.outputStream().use { output -> input.copyTo(output) }
            }

            // 先在暂存文件上完成全部校验，失败时旧插件文件保持不动。
            val loaded = loader.loadFrom(staged) ?: return null
            val id = loaded.metadata.id
            val files = fileMap()
            val previous = instances[id]
            val target = previous?.sourcePath?.let(::File)
                ?: files[id]?.let { File(pluginsDir, it) }
                ?: File(pluginsDir, source.name)
            val conflictsWithOtherPlugin = instances.values.any { other ->
                other.metadata.id != id && File(other.sourcePath).canonicalPath == target.canonicalPath
            } || files.any { (otherId, name) ->
                otherId != id && File(pluginsDir, name).canonicalPath == target.canonicalPath
            }
            if (conflictsWithOtherPlugin || !activate(loaded)) return null

            try {
                replaceStagedFile(staged, target)
            } catch (e: Exception) {
                runCatching { loaded.instance.onUnload() }
                    .onFailure { Log.e(TAG, "安装失败后清理插件 onUnload 失败: $id", it) }
                throw e
            }
            staging = null

            if (previous?.enabled == true) {
                runCatching { previous.instance.onUnload() }
                    .onFailure { Log.e(TAG, "替换插件后旧实例 onUnload 失败: $id", it) }
            }
            instances[id] = loaded.copy(sourcePath = target.absolutePath, enabled = true)

            files[id] = target.name
            persistFiles(files)
            persistEnabled(enabledSet().toMutableSet().apply { add(id) })
            refresh()
            Log.i(TAG, "插件已安装: $id")
            id
        } catch (e: Exception) {
            Log.e(TAG, "安装插件失败", e)
            null
        } finally {
            staging?.delete()
        }
    }

    /** 卸载插件。 */
    fun uninstall(pluginId: String): Boolean {
        val loaded = instances[pluginId] ?: return false
        val wasEnabled = loaded.enabled
        if (!deactivate(loaded)) return false

        val file = File(loaded.sourcePath)
        val deleted = try {
            !file.exists() || file.delete()
        } catch (e: SecurityException) {
            Log.e(TAG, "删除插件文件失败: $pluginId", e)
            false
        }
        if (!deleted) {
            if (wasEnabled) {
                loaded.enabled = activate(loaded)
                if (!loaded.enabled) {
                    persistEnabled(enabledSet().toMutableSet().apply { remove(pluginId) })
                }
            }
            refresh()
            return false
        }

        instances.remove(pluginId)
        fileMap().apply { remove(pluginId) }.let { persistFiles(it) }
        persistEnabled(enabledSet().toMutableSet().apply { remove(pluginId) })
        refresh()
        Log.i(TAG, "插件已卸载: $pluginId")
        return true
    }

    /** 启用插件。 */
    fun enable(pluginId: String): Boolean {
        val loaded = instances[pluginId] ?: return false
        if (!loaded.enabled) {
            if (!activate(loaded)) return false
            loaded.enabled = true
        }
        persistEnabled(enabledSet().toMutableSet().apply { add(pluginId) })
        refresh()
        return true
    }

    /** 禁用插件。 */
    fun disable(pluginId: String): Boolean {
        val loaded = instances[pluginId] ?: return false
        if (!deactivate(loaded)) return false
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

    private fun deactivate(loaded: PluginLoader.LoadedPlugin): Boolean {
        if (!loaded.enabled) return true
        return try {
            loaded.instance.onUnload()
            loaded.enabled = false
            true
        } catch (e: Throwable) {
            Log.e(TAG, "插件 onUnload 失败: ${loaded.metadata.id}", e)
            false
        }
    }

    private fun activate(loaded: PluginLoader.LoadedPlugin): Boolean {
        val host = loader.createSandbox(loaded.metadata)
        return try {
            loaded.instance.onLoad(host)
            true
        } catch (e: Throwable) {
            Log.e(TAG, "插件 onLoad 失败: ${loaded.metadata.id}", e)
            false
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
        internal fun replaceStagedFile(staged: File, target: File) {
            if (!staged.renameTo(target)) throw IOException("无法替换插件文件: ${target.name}")
        }

        private const val TAG = "PluginManager"
        private const val PREFS = "jarvis_plugins"
        private const val KEY_FILES = "plugin_files"
        private const val KEY_ENABLED = "enabled_plugins"
    }
}
