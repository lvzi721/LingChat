package com.noiq.lingchat.voicewake

import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService

/**
 * 快捷设置磁贴：下拉通知栏点一下即可唤醒。
 *
 * 媒体键要跟别的 App 抢会话，这个入口不受仲裁影响，作为可靠兜底。
 */
class VoiceWakeTileService : TileService() {
    override fun onClick() {
        super.onClick()
        try {
            val intent = Intent(this, VoiceWakeService::class.java)
                .setAction(VoiceWakeService.ACTION_WAKE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (_: Throwable) {
            // 服务无法拉起时静默失败，用户可再次点击
        }
    }
}

