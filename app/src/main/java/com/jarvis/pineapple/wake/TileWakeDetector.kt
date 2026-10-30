package com.jarvis.pineapple.wake

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 快捷设置磁贴唤醒：点击 Quick Settings Tile 唤醒。
 */
class TileWakeDetector {

    private val _detected = MutableStateFlow(false)
    val detected: StateFlow<Boolean> = _detected.asStateFlow()

    /** 由 TileService 回调 */
    fun onTileClick() {
        _detected.value = true
    }

    /** 消费本次唤醒信号，返回是否消费成功 */
    fun consume(): Boolean {
        if (_detected.value) {
            _detected.value = false
            return true
        }
        return false
    }

    /** TileService 实现 */
    class JarvisTileService : TileService() {
        override fun onTileAdded() {
            super.onTileAdded()
            val tile = qsTile
            tile.state = Tile.STATE_ACTIVE
            tile.updateTile()
        }

        override fun onClick() {
            super.onClick()
            TileWakeDetector.instance?.onTileClick()
            startActivity(
                android.content.Intent(this, com.jarvis.pineapple.ui.MainActivity::class.java).apply {
                    action = ACTION_TILE_WAKE
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }
    }

    companion object {
        /** 磁贴唤醒使用的 Intent action */
        const val ACTION_TILE_WAKE = "com.jarvis.pineapple.TILE_WAKE"

        private var instance: TileWakeDetector? = null

        fun getInstance(): TileWakeDetector {
            return instance ?: TileWakeDetector().also { instance = it }
        }
    }
}
