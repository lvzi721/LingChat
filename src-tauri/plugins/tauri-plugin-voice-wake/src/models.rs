use serde::{Deserialize, Serialize};

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Eq)]
#[serde(default, rename_all = "camelCase")]
pub struct VoiceWakeStatus {
    pub supported: bool,
    pub service_running: bool,
    pub on_device_available: bool,
    pub permission_granted: bool,
    pub notification_permission_granted: bool,
    pub suspended: bool,
    pub phrase: String,
    pub last_detection_serial: u64,
    pub last_detection_at: i64,
    pub last_detection_transcript: Option<String>,
    pub last_error: Option<String>,
}

impl Default for VoiceWakeStatus {
    fn default() -> Self {
        Self {
            supported: false,
            service_running: false,
            on_device_available: false,
            permission_granted: false,
            notification_permission_granted: false,
            suspended: false,
            phrase: String::new(),
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
}

#[derive(Serialize, Deserialize, Clone, Debug, Default)]
#[serde(rename_all = "camelCase")]
pub struct PauseRequest {
    pub paused: bool,
}

