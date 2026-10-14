//! 锁屏本地唤醒词设置与 Android 前台服务桥接。

use serde::{Deserialize, Serialize};
use tauri::AppHandle;
use tauri_plugin_store::StoreExt;

use tauri_plugin_voice_wake::VoiceWakeStatus;

const STORE_KEY: &str = "VOICE_WAKE_SETTINGS";
const DEFAULT_PHRASE: &str = "灵灵";

#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
pub struct VoiceWakeSettings {
    pub enabled: bool,
    pub phrase: String,
}

impl Default for VoiceWakeSettings {
    fn default() -> Self {
        Self {
            enabled: false,
            phrase: DEFAULT_PHRASE.to_string(),
        }
    }
}

fn load_settings(app: &AppHandle) -> Result<VoiceWakeSettings, String> {
    let store = app
        .store("settings.json")
        .map_err(|e| format!("读取语音唤醒设置失败: {e}"))?;
    let mut settings = store
        .get(STORE_KEY)
        .and_then(|v| serde_json::from_value::<VoiceWakeSettings>(v).ok())
        .unwrap_or_default();
    settings.phrase = normalize_phrase(&settings.phrase);
    Ok(settings)
}

fn save_settings(app: &AppHandle, settings: &VoiceWakeSettings) -> Result<(), String> {
    let store = app
        .store("settings.json")
        .map_err(|e| format!("读取语音唤醒设置失败: {e}"))?;
    store.set(
        STORE_KEY,
        serde_json::to_value(settings).map_err(|e| format!("序列化语音唤醒设置失败: {e}"))?,
    );
    store
        .save()
        .map_err(|e| format!("保存语音唤醒设置失败: {e}"))
}

fn normalize_phrase(value: &str) -> String {
    let phrase = value.trim();
    if phrase.is_empty() {
        DEFAULT_PHRASE.to_string()
    } else {
        phrase.chars().take(24).collect()
    }
}

fn normalize_settings(mut settings: VoiceWakeSettings) -> VoiceWakeSettings {
    settings.phrase = normalize_phrase(&settings.phrase);
    settings
}

fn platform_status(app: &AppHandle, settings: &VoiceWakeSettings) -> VoiceWakeStatus {
    let mut status = tauri_plugin_voice_wake::status(app).unwrap_or_default();
    status.phrase = settings.phrase.clone();
    status
}

#[tauri::command]
pub async fn voice_wake_get_settings(app: AppHandle) -> Result<VoiceWakeSettings, String> {
    load_settings(&app)
}

#[tauri::command]
pub async fn voice_wake_save_settings(
    settings: VoiceWakeSettings,
    app: AppHandle,
) -> Result<VoiceWakeStatus, String> {
    let settings = normalize_settings(settings);
    save_settings(&app, &settings)?;

    if settings.enabled {
        tauri_plugin_voice_wake::start(&app, &settings.phrase).map_err(|e| e.to_string())
    } else {
        let _ = tauri_plugin_voice_wake::stop(&app);
        platform_status(&app, &settings)
    }
}

#[tauri::command]
pub async fn voice_wake_get_status(app: AppHandle) -> Result<VoiceWakeStatus, String> {
    let settings = load_settings(&app)?;
    let mut status = tauri_plugin_voice_wake::status(&app).map_err(|e| e.to_string())?;
    status.phrase = settings.phrase;
    Ok(status)
}

#[tauri::command]
pub async fn voice_wake_ensure_started(app: AppHandle) -> Result<VoiceWakeStatus, String> {
    let settings = load_settings(&app)?;
    if !settings.enabled {
        return Ok(platform_status(&app, &settings));
    }
    tauri_plugin_voice_wake::start(&app, &settings.phrase).map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn voice_wake_set_paused(paused: bool, app: AppHandle) -> Result<VoiceWakeStatus, String> {
    tauri_plugin_voice_wake::set_paused(&app, paused).map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn voice_wake_request_permissions(app: AppHandle) -> Result<(), String> {
    tauri_plugin_voice_wake::request_wake_permissions(&app).map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn voice_wake_open_system_settings(app: AppHandle) -> Result<(), String> {
    tauri_plugin_voice_wake::open_system_settings(&app).map_err(|e| e.to_string())
}

