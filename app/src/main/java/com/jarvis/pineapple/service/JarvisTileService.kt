package com.jarvis.pineapple.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.jarvis.pineapple.ui.MainActivity

/**
 * 快捷设置磁贴唤醒（API 24+）。
 * 下拉快捷设置面板可见 JARVIS 磁贴，点击唤起 AI。
 */
@RequiresApi(Build.VERSION_CODES.N)
class JarvisTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = "JARVIS"
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        qsTile?.apply {
            state = if (state == Tile.STATE_ACTIVE) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
            updateTile()
        }
        // 唤起主界面，并附带磁贴唤醒 action（MainActivity 转为 WakeEngine TILE 唤醒事件）
        val intent = Intent(this, MainActivity::class.java).apply {
            action = com.jarvis.pineapple.wake.TileWakeDetector.ACTION_TILE_WAKE
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        // Android 14+ 需使用 PendingIntent 变体
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pi = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            startActivityAndCollapse(pi)
        } else {
            @SuppressLint("StartActivityAndCollapseDeprecated")
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
