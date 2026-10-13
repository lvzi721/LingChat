use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AlarmRecord {
    pub id: String,
    pub label: String,
    pub hour: u8,
    pub minute: u8,
    pub days: Vec<u8>,
    pub enabled: bool,
    pub ringtone_uri: Option<String>,
    pub ringtone_title: String,
    pub next_trigger_at: Option<i64>,
    #[serde(default)]
    pub next_trigger_text: Option<String>,
    pub exact: bool,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct UpsertAlarmRequest {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub label: Option<String>,
    pub hour: u8,
    pub minute: u8,
    #[serde(default)]
    pub days: Vec<u8>,
    #[serde(default = "default_enabled")]
    pub enabled: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub ringtone_uri: Option<String>,
}

fn default_enabled() -> bool {
    true
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CancelAlarmRequest {
    pub id: String,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SetRingtoneRequest {
    pub id: String,
    pub uri: String,
}

#[derive(Debug, Clone, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct StopAlarmRequest {
    #[serde(default)]
    pub id: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AlarmListResponse {
    pub alarms: Vec<AlarmRecord>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RingtoneRecord {
    pub title: String,
    pub uri: String,
    pub is_default: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RingtoneListResponse {
    pub ringtones: Vec<RingtoneRecord>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AlarmPermissions {
    pub notifications_granted: bool,
    pub exact_alarm_allowed: bool,
    pub battery_optimization_ignored: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AlarmOperationResponse {
    pub ok: bool,
    #[serde(default)]
    pub message: Option<String>,
}
