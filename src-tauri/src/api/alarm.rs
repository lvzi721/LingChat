//! 闹钟管理命令（供设置页直接调用）。
//!
//! 真实的调度、响铃与持久化都在 `tauri-plugin-alarm` 里；这里只做一层薄封装，
//! 把插件的全局调用入口暴露成前端可 `invoke` 的 command。
//! 非 Android 平台没有 AlarmManager，统一返回错误或空列表。

pub use tauri_plugin_alarm::{
    AlarmListResponse, AlarmOperationResponse, AlarmPermissions, AlarmRecord,
    RingtoneListResponse,
};

#[cfg(not(target_os = "android"))]
const UNSUPPORTED: &str = "闹钟功能仅在 Android 平台可用";

/// 列出全部 LingChat 自建闹钟。
#[tauri::command]
pub async fn alarm_list() -> Result<AlarmListResponse, String> {
    #[cfg(target_os = "android")]
    {
        tauri_plugin_alarm::global_list()
            .await
            .map_err(|e| e.to_string())
    }
    #[cfg(not(target_os = "android"))]
    {
        Ok(AlarmListResponse { alarms: Vec::new() })
    }
}

/// 创建或更新闹钟；`id` 为空表示新建。
#[tauri::command]
pub async fn alarm_upsert(
    #[allow(unused_variables)] id: Option<String>,
    #[allow(unused_variables)] label: Option<String>,
    #[allow(unused_variables)] hour: u8,
    #[allow(unused_variables)] minute: u8,
    #[allow(unused_variables)] days: Option<Vec<u8>>,
    #[allow(unused_variables)] enabled: Option<bool>,
    #[allow(unused_variables)] ringtone_uri: Option<String>,
) -> Result<AlarmRecord, String> {
    #[cfg(target_os = "android")]
    {
        if hour > 23 {
            return Err("hour 必须在 0-23".into());
        }
        if minute > 59 {
            return Err("minute 必须在 0-59".into());
        }
        let days = days.unwrap_or_default();
        if let Some(bad) = days.iter().find(|day| !(1..=7).contains(*day)) {
            return Err(format!("days 只能包含 1-7（周日=1），收到 {bad}"));
        }
        let request = tauri_plugin_alarm::UpsertAlarmRequest {
            id: id.filter(|value| !value.trim().is_empty()),
            label: label.filter(|value| !value.trim().is_empty()),
            hour,
            minute,
            days,
            enabled: enabled.unwrap_or(true),
            ringtone_uri: ringtone_uri.filter(|value| !value.trim().is_empty()),
        };
        tauri_plugin_alarm::global_upsert(request)
            .await
            .map_err(|e| e.to_string())
    }
    #[cfg(not(target_os = "android"))]
    {
        Err(UNSUPPORTED.to_string())
    }
}

/// 永久删除一个闹钟。
#[tauri::command]
pub async fn alarm_cancel(
    #[allow(unused_variables)] id: String,
) -> Result<AlarmOperationResponse, String> {
    #[cfg(target_os = "android")]
    {
        tauri_plugin_alarm::global_cancel(tauri_plugin_alarm::CancelAlarmRequest { id })
            .await
            .map_err(|e| e.to_string())
    }
    #[cfg(not(target_os = "android"))]
    {
        Err(UNSUPPORTED.to_string())
    }
}

/// 修改闹钟铃声；`uri` 传 `silent` 表示静音。
#[tauri::command]
pub async fn alarm_set_ringtone(
    #[allow(unused_variables)] id: String,
    #[allow(unused_variables)] uri: String,
) -> Result<AlarmRecord, String> {
    #[cfg(target_os = "android")]
    {
        tauri_plugin_alarm::global_set_ringtone(tauri_plugin_alarm::SetRingtoneRequest {
            id,
            uri,
        })
        .await
        .map_err(|e| e.to_string())
    }
    #[cfg(not(target_os = "android"))]
    {
        Err(UNSUPPORTED.to_string())
    }
}

/// 列出设备可选的闹钟铃声（含 `silent`）。
#[tauri::command]
pub async fn alarm_list_ringtones() -> Result<RingtoneListResponse, String> {
    #[cfg(target_os = "android")]
    {
        tauri_plugin_alarm::global_list_ringtones()
            .await
            .map_err(|e| e.to_string())
    }
    #[cfg(not(target_os = "android"))]
    {
        Ok(RingtoneListResponse {
            ringtones: Vec::new(),
        })
    }
}

/// 立即停止当前响铃（不删除闹钟）。
#[tauri::command]
pub async fn alarm_stop() -> Result<AlarmOperationResponse, String> {
    #[cfg(target_os = "android")]
    {
        tauri_plugin_alarm::global_stop(tauri_plugin_alarm::StopAlarmRequest { id: None })
            .await
            .map_err(|e| e.to_string())
    }
    #[cfg(not(target_os = "android"))]
    {
        Err(UNSUPPORTED.to_string())
    }
}

/// 查询通知 / 精确闹钟 / 电池优化三项权限状态。
#[tauri::command]
pub async fn alarm_permissions() -> Result<AlarmPermissions, String> {
    #[cfg(target_os = "android")]
    {
        tauri_plugin_alarm::global_permissions()
            .await
            .map_err(|e| e.to_string())
    }
    #[cfg(not(target_os = "android"))]
    {
        Ok(AlarmPermissions {
            notifications_granted: false,
            exact_alarm_allowed: false,
            battery_optimization_ignored: false,
        })
    }
}

/// 打开系统授权页面（一次处理一项未授权的设置为准）。
#[tauri::command]
pub async fn alarm_request_permissions() -> Result<AlarmPermissions, String> {
    #[cfg(target_os = "android")]
    {
        tauri_plugin_alarm::global_request_permissions()
            .await
            .map_err(|e| e.to_string())
    }
    #[cfg(not(target_os = "android"))]
    {
        Err(UNSUPPORTED.to_string())
    }
}
