use serde::{Deserialize, Serialize};

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Eq)]
#[serde(default, rename_all = "camelCase")]
pub struct VoiceWakeStatus {
    pub supported: bool,
    pub model_present: bool,
    pub engine_ready: bool,
    pub service_running: bool,
    pub permission_granted: bool,
    pub notification_permission_granted: bool,
    pub suspended: bool,
    pub phrase: String,
    pub keyword_tokens: Option<String>,
    pub last_detection_serial: u64,
    pub last_detection_at: i64,
    pub last_detection_transcript: Option<String>,
    pub last_error: Option<String>,
}

impl Default for VoiceWakeStatus {
    fn default() -> Self {
        Self {
            supported: false,
            model_present: false,
            engine_ready: false,
            service_running: false,
            permission_granted: false,
            notification_permission_granted: false,
            suspended: false,
            phrase: String::new(),
            keyword_tokens: None,
            last_detection_serial: 0,
            last_detection_at: 0,
            last_detection_transcript: None,
            last_error: None,
        }
    }
}

#[derive(Serialize, Deserialize, Clone, Debug, Default)]
#[serde(rename_all = "camelCase")]
pub struct StartRequest {
    pub phrase: String,
    pub ack_enabled: bool,
    pub ack_text: String,
    #[serde(default)]
    pub ack_audio_path: Option<String>,
}

#[derive(Serialize, Deserialize, Clone, Debug, Default)]
#[serde(rename_all = "camelCase")]
pub struct PauseRequest {
    pub paused: bool,
}

