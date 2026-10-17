//! 锁屏本地唤醒词设置与 Android 前台服务桥接。

use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use tauri::{AppHandle, Manager};
use tauri_plugin_store::StoreExt;

use crate::ai_service::tts::local::{LocalTtsState, SynthesizeRequest};
use crate::db::managers::role_repo::RoleRepo;
use tauri_plugin_voice_wake::VoiceWakeStatus;

const STORE_KEY: &str = "VOICE_WAKE_SETTINGS";
const DEFAULT_PHRASE: &str = "灵灵";
const DEFAULT_ACK_TEXT: &str = "我在";

#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
pub struct VoiceWakeSettings {
    pub enabled: bool,
    pub phrase: String,
    /// 唤醒后是否先播一句应答，让用户确认已经收到唤醒词。
    #[serde(default = "default_ack_enabled")]
    pub ack_enabled: bool,
    #[serde(default = "default_ack_text")]
    pub ack_text: String,
    /// 由本地 TTS 预合成的应答音频路径（缓存，可为空表示尚未生成）。
    #[serde(default)]
    pub ack_audio_path: Option<String>,
}

impl Default for VoiceWakeSettings {
    fn default() -> Self {
        Self {
            enabled: false,
            phrase: DEFAULT_PHRASE.to_string(),
            ack_enabled: true,
            ack_text: DEFAULT_ACK_TEXT.to_string(),
            ack_audio_path: None,
        }
    }
}

fn default_ack_enabled() -> bool {
    true
}

fn default_ack_text() -> String {
    DEFAULT_ACK_TEXT.to_string()
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
    let ack = settings.ack_text.trim();
    settings.ack_text = if ack.is_empty() {
        DEFAULT_ACK_TEXT.to_string()
    } else {
        ack.chars().take(40).collect()
    };
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
    let mut settings = normalize_settings(settings);

    // 应答音频在服务端兜底生成：即使前端没调用 prepare，或缓存文件被清理，
    // 保存时也会补上，避免唤醒时静默无声（华为等机型通常没有系统 TTS 兜底）。
    if settings.ack_enabled {
        let missing = match settings.ack_audio_path.as_deref() {
            Some(path) => !std::path::Path::new(path).is_file(),
            None => true,
        };
        if missing {
            match prepare_ack_audio(&app, &settings.ack_text).await {
                Ok(path) => settings.ack_audio_path = path,
                Err(e) => {
                    tracing::warn!("[VoiceWake] 应答语音生成失败: {e}");
                    settings.ack_audio_path = None;
                },
            }
        }
    } else {
        settings.ack_audio_path = None;
    }

    save_settings(&app, &settings)?;

    if settings.enabled {
        tauri_plugin_voice_wake::start(
            &app,
            &settings.phrase,
            settings.ack_enabled,
            &settings.ack_text,
            settings.ack_audio_path.as_deref(),
        )
        .map_err(|e| e.to_string())
    } else {
        let _ = tauri_plugin_voice_wake::stop(&app);
        Ok(platform_status(&app, &settings))
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
    let mut settings = load_settings(&app)?;
    if !settings.enabled {
        return Ok(platform_status(&app, &settings));
    }

    // 冷启动兜底：历史设置里没有应答音频（例如功能上线前就已开启唤醒），
    // 这里补生成一次并落盘，避免服务启动后唤醒时静默。
    if settings.ack_enabled {
        let missing = match settings.ack_audio_path.as_deref() {
            Some(path) => !std::path::Path::new(path).is_file(),
            None => true,
        };
        if missing {
            if let Ok(Some(path)) = prepare_ack_audio(&app, &settings.ack_text).await {
                settings.ack_audio_path = Some(path);
                let _ = save_settings(&app, &settings);
            }
        }
    }

    tauri_plugin_voice_wake::start(
        &app,
        &settings.phrase,
        settings.ack_enabled,
        &settings.ack_text,
        settings.ack_audio_path.as_deref(),
    )
    .map_err(|e| e.to_string())
}

/// 用「当前角色的本地 TTS 音色」预合成唤醒应答，返回缓存文件路径。
///
/// 合成只发生在保存设置时：唤醒瞬间直接播放缓存音频，避免实时合成带来的
/// 1~3 秒延迟。缓存按 文本 + 音色 + 角色 + 合成参数 取哈希，重复保存不会重合成。
async fn prepare_ack_audio(app: &AppHandle, text: &str) -> Result<Option<String>, String> {
    let text = text.trim().to_string();
    if text.is_empty() {
        return Ok(None);
    }

    let state = app.state::<crate::AppState>();
    let role_id = {
        let svc = state.ai_service.lock().await;
        let gs = svc.game_status.lock().await;
        gs.current_role_id
    };
    // 从主菜单直接打开设置时还没有 current_role_id：退回第一个可用角色，
    // 否则应答音频永远生成不出来（唤醒后就只剩静默）。
    let role_id = match role_id {
        Some(id) => id,
        None => RoleRepo::get_all_main_roles(&state.db)
            .await
            .map_err(|e| format!("读取角色列表失败: {e}"))?
            .first()
            .map(|role| role.id)
            .ok_or_else(|| "还没有可用角色，无法生成角色应答".to_string())?,
    };

    let role_settings =
        RoleRepo::get_role_settings_by_id(&state.db, &crate::api::data_dir(), role_id)
            .await
            .map_err(|e| format!("读取角色配置失败: {e}"))?
            .ok_or_else(|| "角色配置不存在".to_string())?;
    let voice = role_settings.voice_models.clone().unwrap_or_default();
    let voice_id = voice.sbv2_local_voice_id.clone().unwrap_or_default();
    if voice_id.trim().is_empty() {
        return Err("当前角色未配置本地音色，请先在角色语音设置里选择本地语音".into());
    }

    let style_id = voice.sbv2_local_style_id.unwrap_or(0);
    let speaker_id = voice.sbv2_local_speaker_id.unwrap_or(0);
    let sdp_ratio = voice.sbv2_local_sdp_ratio.unwrap_or(0.2);
    let length_scale = voice.sbv2_local_length_scale.unwrap_or(1.0);

    let mut hasher = Sha256::new();
    hasher.update(text.as_bytes());
    hasher.update(b"|");
    hasher.update(voice_id.as_bytes());
    hasher.update(b"|");
    hasher.update(role_id.to_string().as_bytes());
    hasher.update(b"|");
    hasher.update(format!("{style_id}|{speaker_id}|{sdp_ratio}|{length_scale}").as_bytes());
    let digest = format!("{:x}", hasher.finalize());

    let dir = crate::api::data_dir().join("voice").join("wake_ack");
    tokio::fs::create_dir_all(&dir)
        .await
        .map_err(|e| format!("创建应答缓存目录失败: {e}"))?;
    let path = dir.join(format!("{}.wav", &digest[..16]));
    if path.is_file() {
        return Ok(Some(path.to_string_lossy().to_string()));
    }

    let local = app.state::<LocalTtsState>();
    if !local.engine.is_ready().await {
        if !local.paths.asset_present("deberta") {
            return Err("本地 TTS 未就绪（缺少 DeBERTa 模型）".into());
        }
        local
            .engine
            .init(&local.paths)
            .await
            .map_err(|e| format!("本地 TTS 初始化失败: {e}"))?;
    }
    local
        .engine
        .load_voice(&local.paths, &voice_id)
        .await
        .map_err(|e| format!("加载本地音色失败: {e}"))?;
    let bytes = local
        .engine
        .synthesize(SynthesizeRequest {
            voice_id: voice_id.clone(),
            text: text.clone(),
            style_id,
            speaker_id,
            sdp_ratio,
            length_scale,
        })
        .await
        .map_err(|e| format!("应答语音合成失败: {e}"))?;
    tokio::fs::write(&path, bytes)
        .await
        .map_err(|e| format!("写入应答缓存失败: {e}"))?;
    Ok(Some(path.to_string_lossy().to_string()))
}

#[tauri::command]
pub async fn voice_wake_prepare_ack(
    text: String,
    app: AppHandle,
) -> Result<Option<String>, String> {
    prepare_ack_audio(&app, &text).await
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

#[tauri::command]
pub async fn voice_wake_take_pending_text(app: AppHandle) -> Result<Option<String>, String> {
    tauri_plugin_voice_wake::take_pending_text(&app).map_err(|e| e.to_string())
}

/// 手动麦克风走内置模型：阻塞到识别结束（端点或超时），返回文本。
#[tauri::command]
pub async fn voice_wake_recognize_once(
    max_ms: Option<u64>,
    app: AppHandle,
) -> Result<String, String> {
    let ms = max_ms.unwrap_or(15_000).clamp(2_000, 30_000);
    let handle = app.clone();
    let (text, error) = tauri::async_runtime::spawn_blocking(move || {
        tauri_plugin_voice_wake::recognize_once(&handle, ms)
    })
    .await
    .map_err(|e| e.to_string())?
    .map_err(|e| e.to_string())?;

    match (text, error) {
        (Some(text), _) if !text.trim().is_empty() => Ok(text),
        (_, Some(error)) => Err(error),
        _ => Err("asr_empty".into()),
    }
}

/// 诊断：录音约 5 秒，用内置模型识别一次，返回文本或错误码。
#[tauri::command]
pub async fn voice_wake_test_recognition(app: AppHandle) -> Result<String, String> {
    let handle = app.clone();
    let (text, error) = tauri::async_runtime::spawn_blocking(move || {
        tauri_plugin_voice_wake::test_recognition(&handle, 5_000)
    })
    .await
    .map_err(|e| e.to_string())?
    .map_err(|e| e.to_string())?;

    match (text, error) {
        (Some(text), _) if !text.trim().is_empty() => Ok(text),
        (_, Some(error)) => Err(error),
        _ => Err("asr_empty".into()),
    }
}

