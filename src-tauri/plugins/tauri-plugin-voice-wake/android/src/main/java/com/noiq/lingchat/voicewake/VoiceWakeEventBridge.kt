package com.noiq.lingchat.voicewake

import app.tauri.plugin.JSObject
import java.lang.ref.WeakReference

/**
 * The Tauri plugin instance owns the registered JS channels. The foreground
 * service lives beyond the activity lifecycle, so keep a weak reference to the
 * current plugin and let it emit events when the wake phrase is detected.
 */
internal object VoiceWakeEventBridge {
    private var pluginRef = WeakReference<VoiceWakePlugin>(null)

    fun register(plugin: VoiceWakePlugin) {
        pluginRef = WeakReference(plugin)
    }

    fun unregister(plugin: VoiceWakePlugin) {
        if (pluginRef.get() === plugin) {
            pluginRef.clear()
        }
    }

    fun hasListener(event: String): Boolean = pluginRef.get()?.hasListener(event) == true

    fun emit(event: String, payload: JSObject): Boolean {
        val plugin = pluginRef.get() ?: return false
        if (!plugin.hasListener(event)) return false
        plugin.trigger(event, payload)
        return true
    }
}

