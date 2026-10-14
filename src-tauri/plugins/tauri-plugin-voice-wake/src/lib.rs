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
pub fn start<R: Runtime>(app: &tauri::AppHandle<R>, phrase: &str) -> Result<VoiceWakeStatus> {
    #[cfg(target_os = "android")]
    {
        let state = app
            .try_state::<VoiceWake<R>>()
            .ok_or(Error::NotInitialized)?;
        state.start(phrase)
    }
    #[cfg(not(target_os = "android"))]
    {
        let _ = app;
        desktop::start(phrase)
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

