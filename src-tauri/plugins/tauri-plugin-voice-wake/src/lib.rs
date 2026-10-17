use tauri::plugin::{Builder, TauriPlugin};
use tauri::{Manager, Runtime};

mod error;
mod models;
#[cfg(not(target_os = "android"))]
mod desktop;
#[cfg(target_os = "android")]
mod mobile;

pub use error::{Error, Result};
pub use models::VoiceWakeStatus;

#[cfg(target_os = "android")]
pub use mobile::VoiceWake;

/// 启动 Android 前台语音唤醒服务。
pub fn start<R: Runtime>(
    app: &tauri::AppHandle<R>,
    phrase: &str,
    ack_enabled: bool,
    ack_text: &str,
    ack_audio_path: Option<&str>,
) -> Result<VoiceWakeStatus> {
    #[cfg(target_os = "android")]
    {
        let state = app
            .try_state::<VoiceWake<R>>()
            .ok_or(Error::NotInitialized)?;
        state.start(phrase, ack_enabled, ack_text, ack_audio_path)
    }
    #[cfg(not(target_os = "android"))]
    {
        let _ = app;
        desktop::start(phrase, ack_enabled, ack_text, ack_audio_path)
    }
}

/// 停止 Android 前台语音唤醒服务。
pub fn stop<R: Runtime>(app: &tauri::AppHandle<R>) -> Result<VoiceWakeStatus> {
    #[cfg(target_os = "android")]
    {
        let state = app
            .try_state::<VoiceWake<R>>()
            .ok_or(Error::NotInitialized)?;
        state.stop()
    }
    #[cfg(not(target_os = "android"))]
    {
        let _ = app;
        desktop::stop()
    }
}

/// 查询 Android 前台语音唤醒服务状态。
pub fn status<R: Runtime>(app: &tauri::AppHandle<R>) -> Result<VoiceWakeStatus> {
    #[cfg(target_os = "android")]
    {
        let state = app
            .try_state::<VoiceWake<R>>()
            .ok_or(Error::NotInitialized)?;
        state.status()
    }
    #[cfg(not(target_os = "android"))]
    {
        let _ = app;
        desktop::status()
    }
}

/// 暂停或恢复唤醒词识别，避免和普通 ASR 同时占用麦克风。
pub fn set_paused<R: Runtime>(
    app: &tauri::AppHandle<R>,
    paused: bool,
) -> Result<VoiceWakeStatus> {
    #[cfg(target_os = "android")]
    {
        let state = app
            .try_state::<VoiceWake<R>>()
            .ok_or(Error::NotInitialized)?;
        state.set_paused(paused)
    }
    #[cfg(not(target_os = "android"))]
    {
        let _ = app;
        desktop::set_paused(paused)
    }
}

/// 请求录音和通知权限。
pub fn request_wake_permissions<R: Runtime>(app: &tauri::AppHandle<R>) -> Result<()> {
    #[cfg(target_os = "android")]
    {
        let state = app
            .try_state::<VoiceWake<R>>()
            .ok_or(Error::NotInitialized)?;
        state.request_wake_permissions()
    }
    #[cfg(not(target_os = "android"))]
    {
        let _ = app;
        desktop::request_wake_permissions()
    }
}

/// 打开 Android 应用详情或电池优化设置，方便用户放行后台运行。
pub fn open_system_settings<R: Runtime>(app: &tauri::AppHandle<R>) -> Result<()> {
    #[cfg(target_os = "android")]
    {
        let state = app
            .try_state::<VoiceWake<R>>()
            .ok_or(Error::NotInitialized)?;
        state.open_system_settings()
    }
    #[cfg(not(target_os = "android"))]
    {
        let _ = app;
        desktop::open_system_settings()
    }
}

/// 取走服务端暂存的识别文本。
pub fn take_pending_text<R: Runtime>(
    app: &tauri::AppHandle<R>,
) -> Result<Option<String>> {
    #[cfg(target_os = "android")]
    {
        let state = app
            .try_state::<VoiceWake<R>>()
            .ok_or(Error::NotInitialized)?;
        state.take_pending_text()
    }
    #[cfg(not(target_os = "android"))]
    {
        let _ = app;
        Ok(None)
    }
}

/// 手动麦克风：用内置模型识别一次，返回 (text, error)。
pub fn recognize_once<R: Runtime>(
    app: &tauri::AppHandle<R>,
    max_ms: u64,
) -> Result<(Option<String>, Option<String>)> {
    #[cfg(target_os = "android")]
    {
        let state = app
            .try_state::<VoiceWake<R>>()
            .ok_or(Error::NotInitialized)?;
        state.recognize_once(max_ms)
    }
    #[cfg(not(target_os = "android"))]
    {
        let _ = app;
        let _ = max_ms;
        Err(Error::UnsupportedPlatform)
    }
}

pub fn init<R: Runtime>() -> TauriPlugin<R> {
    Builder::new("voice-wake")
        .setup(|app, api| {
            #[cfg(target_os = "android")]
            {
                let state = mobile::VoiceWake::<R>::init(&api)?;
                app.manage(state);
            }
            #[cfg(not(target_os = "android"))]
            {
                let _ = (app, api);
            }
            Ok(())
        })
        .build()
}

