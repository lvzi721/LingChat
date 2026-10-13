use std::time::Duration;

use async_trait::async_trait;
use serde::Serialize;
use serde_json::{json, Value};

use crate::ai_service::types::ToolDefinition;

use super::ensure_no_args;
use super::executor::{Tool, ToolContext, ToolError, ToolResult};

#[derive(Clone, Copy)]
pub(crate) enum AlarmToolKind {
    List,
    Upsert,
    Cancel,
    ListRingtones,
    SetRingtone,
    Stop,
    Permissions,
    RequestPermissions,
}

pub struct AlarmTool {
    kind: AlarmToolKind,
}

impl AlarmTool {
    pub(crate) fn new(kind: AlarmToolKind) -> Self {
        Self { kind }
    }
}

#[async_trait]
impl Tool for AlarmTool {
    fn definition(&self) -> ToolDefinition {
        match self.kind {
            AlarmToolKind::List => ToolDefinition::new(
                "alarm_list",
                "列出 LingChat 自己创建的闹钟，包括时间、重复星期、启用状态、铃声和下一次响铃时间。",
                json!({"type": "object", "properties": {}, "additionalProperties": false}),
            ),
            AlarmToolKind::Upsert => ToolDefinition::new(
                "alarm_upsert",
                "创建或更新一个 LingChat 自建闹钟。days 为空表示仅响一次；周日至周六分别用 1-7 表示；每天响传 [1,2,3,4,5,6,7]。",
                json!({
                    "type": "object",
                    "properties": {
                        "id": {"type": "string"},
                        "label": {"type": "string"},
                        "hour": {"type": "integer"},
                        "minute": {"type": "integer"},
                        "days": {"type": "array", "items": {"type": "integer"}},
                        "enabled": {"type": "boolean"},
                        "ringtoneUri": {"type": "string"}
                    },
                    "required": ["hour", "minute"],
                    "additionalProperties": false
                }),
            ),
            AlarmToolKind::Cancel => ToolDefinition::new(
                "alarm_cancel",
                "永久取消并删除一个 LingChat 自建闹钟。",
                json!({
                    "type": "object",
                    "properties": {"id": {"type": "string"}},
                    "required": ["id"],
                    "additionalProperties": false
                }),
            ),
            AlarmToolKind::ListRingtones => ToolDefinition::new(
                "alarm_list_ringtones",
                "列出设备可用于 LingChat 闹钟的铃声，返回标题和 URI。",
                json!({"type": "object", "properties": {}, "additionalProperties": false}),
            ),
            AlarmToolKind::SetRingtone => ToolDefinition::new(
                "alarm_set_ringtone",
                "修改一个 LingChat 自建闹钟的铃声。URI 可先通过 alarm_list_ringtones 获取，silent 表示静音。",
                json!({
                    "type": "object",
                    "properties": {
                        "id": {"type": "string"},
                        "uri": {"type": "string"}
                    },
                    "required": ["id", "uri"],
                    "additionalProperties": false
                }),
            ),
            AlarmToolKind::Stop => ToolDefinition::new(
                "alarm_stop",
                "立即停止当前正在响铃的 LingChat 闹钟，不删除未来闹钟。",
                json!({"type": "object", "properties": {}, "additionalProperties": false}),
            ),
            AlarmToolKind::Permissions => ToolDefinition::new(
                "alarm_permissions",
                "查看 LingChat 闹钟的通知、精确闹钟和电池优化权限状态。",
                json!({"type": "object", "properties": {}, "additionalProperties": false}),
            ),
            AlarmToolKind::RequestPermissions => ToolDefinition::new(
                "alarm_request_permissions",
                "打开 LingChat 闹钟所需的系统授权页面。",
                json!({"type": "object", "properties": {}, "additionalProperties": false}),
            ),
        }
    }

    fn timeout_hint(&self) -> Option<Duration> {
        Some(Duration::from_secs(15))
    }

    async fn execute(&self, _context: &ToolContext, arguments: Value) -> Result<ToolResult, ToolError> {
        match self.kind {
            AlarmToolKind::List => {
                ensure_no_args(&arguments, "alarm_list").map_err(ToolError::InvalidArguments)?;
                alarm_list().await
            },
            AlarmToolKind::Upsert => {
                let request = parse_upsert(&arguments)?;
                alarm_upsert(request).await
            },
            AlarmToolKind::Cancel => {
                let id = required_string(&arguments, "id")?;
                alarm_cancel(id).await
            },
            AlarmToolKind::ListRingtones => {
                ensure_no_args(&arguments, "alarm_list_ringtones")
                    .map_err(ToolError::InvalidArguments)?;
                alarm_list_ringtones().await
            },
            AlarmToolKind::SetRingtone => {
                let id = required_string(&arguments, "id")?;
                let uri = required_string(&arguments, "uri")?;
                alarm_set_ringtone(id, uri).await
            },
            AlarmToolKind::Stop => {
                ensure_no_args(&arguments, "alarm_stop").map_err(ToolError::InvalidArguments)?;
                alarm_stop().await
            },
            AlarmToolKind::Permissions => {
                ensure_no_args(&arguments, "alarm_permissions")
                    .map_err(ToolError::InvalidArguments)?;
                alarm_permissions().await
            },
            AlarmToolKind::RequestPermissions => {
                ensure_no_args(&arguments, "alarm_request_permissions")
                    .map_err(ToolError::InvalidArguments)?;
                alarm_request_permissions().await
            },
        }
    }
}

fn required_string(arguments: &Value, key: &str) -> Result<String, ToolError> {
    arguments
        .get(key)
        .and_then(Value::as_str)
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .map(str::to_string)
        .ok_or_else(|| ToolError::InvalidArguments(format!("缺少 {key} 参数")))
}

fn optional_string(arguments: &Value, key: &str) -> Option<String> {
    arguments
        .get(key)
        .and_then(Value::as_str)
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .map(str::to_string)
}

#[cfg(target_os = "android")]
fn parse_upsert(arguments: &Value) -> Result<tauri_plugin_alarm::UpsertAlarmRequest, ToolError> {
    let hour = arguments
        .get("hour")
        .and_then(Value::as_u64)
        .filter(|value| *value <= 23)
        .ok_or_else(|| ToolError::InvalidArguments("hour 必须是 0-23 的整数".into()))?
        as u8;
    let minute = arguments
        .get("minute")
        .and_then(Value::as_u64)
        .filter(|value| *value <= 59)
        .ok_or_else(|| ToolError::InvalidArguments("minute 必须是 0-59 的整数".into()))?
        as u8;
    let mut days = Vec::new();
    if let Some(values) = arguments.get("days").and_then(Value::as_array) {
        for value in values {
            let day = value
                .as_u64()
                .filter(|value| (1..=7).contains(value))
                .ok_or_else(|| {
                    ToolError::InvalidArguments("days 只能包含 1-7（周日=1）".into())
                })? as u8;
            if !days.contains(&day) {
                days.push(day);
            }
        }
    }
    Ok(tauri_plugin_alarm::UpsertAlarmRequest {
        id: optional_string(arguments, "id"),
        label: optional_string(arguments, "label"),
        hour,
        minute,
        days,
        enabled: arguments
            .get("enabled")
            .and_then(Value::as_bool)
            .unwrap_or(true),
        ringtone_uri: optional_string(arguments, "ringtoneUri"),
    })
}

#[cfg(not(target_os = "android"))]
fn parse_upsert(_arguments: &Value) -> Result<(), ToolError> {
    Err(unsupported())
}

fn unsupported() -> ToolError {
    ToolError::Execution("闹钟功能仅在 Android 平台可用".into())
}

fn json_value<T: Serialize>(value: T) -> Result<ToolResult, ToolError> {
    serde_json::to_value(value)
        .map_err(|error| ToolError::Execution(format!("闹钟结果序列化失败: {error}")))
}

#[cfg(target_os = "android")]
async fn alarm_list() -> Result<ToolResult, ToolError> {
    let response = tauri_plugin_alarm::global_list()
        .await
        .map_err(|error| ToolError::Execution(error.to_string()))?;
    json_value(response)
}

#[cfg(not(target_os = "android"))]
async fn alarm_list() -> Result<ToolResult, ToolError> {
    Err(unsupported())
}

#[cfg(target_os = "android")]
async fn alarm_upsert(
    request: tauri_plugin_alarm::UpsertAlarmRequest,
) -> Result<ToolResult, ToolError> {
    let response = tauri_plugin_alarm::global_upsert(request)
        .await
        .map_err(|error| ToolError::Execution(error.to_string()))?;
    json_value(response)
}

#[cfg(not(target_os = "android"))]
async fn alarm_upsert(_request: ()) -> Result<ToolResult, ToolError> {
    Err(unsupported())
}

#[cfg(target_os = "android")]
async fn alarm_cancel(id: String) -> Result<ToolResult, ToolError> {
    let response = tauri_plugin_alarm::global_cancel(
        tauri_plugin_alarm::CancelAlarmRequest { id },
    )
    .await
    .map_err(|error| ToolError::Execution(error.to_string()))?;
    json_value(response)
}

#[cfg(not(target_os = "android"))]
async fn alarm_cancel(_id: String) -> Result<ToolResult, ToolError> {
    Err(unsupported())
}

#[cfg(target_os = "android")]
async fn alarm_list_ringtones() -> Result<ToolResult, ToolError> {
    let response = tauri_plugin_alarm::global_list_ringtones()
        .await
        .map_err(|error| ToolError::Execution(error.to_string()))?;
    json_value(response)
}

#[cfg(not(target_os = "android"))]
async fn alarm_list_ringtones() -> Result<ToolResult, ToolError> {
    Err(unsupported())
}

#[cfg(target_os = "android")]
async fn alarm_set_ringtone(id: String, uri: String) -> Result<ToolResult, ToolError> {
    let response = tauri_plugin_alarm::global_set_ringtone(
        tauri_plugin_alarm::SetRingtoneRequest { id, uri },
    )
    .await
    .map_err(|error| ToolError::Execution(error.to_string()))?;
    json_value(response)
}

#[cfg(not(target_os = "android"))]
async fn alarm_set_ringtone(_id: String, _uri: String) -> Result<ToolResult, ToolError> {
    Err(unsupported())
}

#[cfg(target_os = "android")]
async fn alarm_stop() -> Result<ToolResult, ToolError> {
    let response = tauri_plugin_alarm::global_stop(
        tauri_plugin_alarm::StopAlarmRequest::default(),
    )
    .await
    .map_err(|error| ToolError::Execution(error.to_string()))?;
    json_value(response)
}

#[cfg(not(target_os = "android"))]
async fn alarm_stop() -> Result<ToolResult, ToolError> {
    Err(unsupported())
}

#[cfg(target_os = "android")]
async fn alarm_permissions() -> Result<ToolResult, ToolError> {
    let response = tauri_plugin_alarm::global_permissions()
        .await
        .map_err(|error| ToolError::Execution(error.to_string()))?;
    json_value(response)
}

#[cfg(not(target_os = "android"))]
async fn alarm_permissions() -> Result<ToolResult, ToolError> {
    Err(unsupported())
}

#[cfg(target_os = "android")]
async fn alarm_request_permissions() -> Result<ToolResult, ToolError> {
    let response = tauri_plugin_alarm::global_request_permissions()
        .await
        .map_err(|error| ToolError::Execution(error.to_string()))?;
    json_value(response)
}

#[cfg(not(target_os = "android"))]
async fn alarm_request_permissions() -> Result<ToolResult, ToolError> {
    Err(unsupported())
}
