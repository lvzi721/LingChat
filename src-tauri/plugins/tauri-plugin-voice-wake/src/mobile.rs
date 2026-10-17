use crate::error::{Error, Result};
use crate::models::{PauseRequest, StartRequest, VoiceWakeStatus};
use serde::de::DeserializeOwned;
use std::sync::Arc;
use tauri::plugin::PluginHandle;
use tauri::Runtime;

pub const PLUGIN_IDENTIFIER: &str = "com.noiq.lingchat.voicewake";
pub const PLUGIN_CLASS: &str = "VoiceWakePlugin";

pub struct VoiceWake<R: Runtime> {
    handle: Arc<PluginHandle<R>>,
}

impl<R: Runtime> VoiceWake<R> {
    pub fn init<C: DeserializeOwned>(api: &tauri::plugin::PluginApi<R, C>) -> Result<Self> {
        let handle = api
            .register_android_plugin(PLUGIN_IDENTIFIER, PLUGIN_CLASS)
            .map_err(|e| Error::PluginInvoke(e.to_string()))?;
        Ok(Self {
            handle: Arc::new(handle),
        })
    }

    pub fn start(
        &self,
        phrase: &str,
        ack_enabled: bool,
        ack_text: &str,
        ack_audio_path: Option<&str>,
    ) -> Result<VoiceWakeStatus> {
        self.invoke(
            "start",
            StartRequest {
                phrase: phrase.to_string(),
                ack_enabled,
                ack_text: ack_text.to_string(),
                ack_audio_path: ack_audio_path.map(str::to_string),
            },
        )
    }

    pub fn stop(&self) -> Result<VoiceWakeStatus> {
        self.invoke("stop", serde_json::json!({}))
    }

    pub fn status(&self) -> Result<VoiceWakeStatus> {
        self.invoke("status", serde_json::json!({}))
    }

    pub fn set_paused(&self, paused: bool) -> Result<VoiceWakeStatus> {
        self.invoke("setPaused", PauseRequest { paused })
    }

    pub fn request_wake_permissions(&self) -> Result<()> {
        self.handle
            .run_mobile_plugin::<()>("requestWakePermissions", serde_json::json!({}))
            .map_err(|e| Error::PluginInvoke(e.to_string()))
    }

    pub fn open_system_settings(&self) -> Result<()> {
        self.handle
            .run_mobile_plugin::<()>("openSystemSettings", serde_json::json!({}))
            .map_err(|e| Error::PluginInvoke(e.to_string()))
    }

    /// 取走锁屏时暂存的识别文本（前端重新就绪后回填）。
    pub fn take_pending_text(&self) -> Result<Option<String>> {
        #[derive(serde::Deserialize)]
        struct PendingText {
            #[serde(default)]
            text: Option<String>,
        }
        let resp: PendingText = self
            .handle
            .run_mobile_plugin("takePendingText", serde_json::json!({}))
            .map_err(|e| Error::PluginInvoke(e.to_string()))?;
        Ok(resp.text)
    }

    /// 手动麦克风：用内置模型识别一次，返回 (text, error)。
    pub fn recognize_once(&self, max_ms: u64) -> Result<(Option<String>, Option<String>)> {
        #[derive(serde::Deserialize)]
        struct OnceResult {
            #[serde(default)]
            text: Option<String>,
            #[serde(default)]
            error: Option<String>,
        }
        let resp: OnceResult = self
            .handle
            .run_mobile_plugin("recognizeOnce", serde_json::json!({ "maxMs": max_ms }))
            .map_err(|e| Error::PluginInvoke(e.to_string()))?;
        Ok((resp.text, resp.error))
    }

    fn invoke<P, T>(&self, method: &str, payload: P) -> Result<T>
    where
        P: serde::Serialize,
        T: DeserializeOwned,
    {
        self.handle
            .run_mobile_plugin::<T>(method, payload)
            .map_err(|e| Error::PluginInvoke(e.to_string()))
    }
}

