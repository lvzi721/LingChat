use std::future::Future;
use std::pin::Pin;
use std::sync::{Arc, OnceLock};

use serde::de::DeserializeOwned;
use serde::Deserialize;
use serde_json::Value;
use tauri::plugin::PluginHandle;
use tauri::Runtime;

use crate::error::{Error, Result};
use crate::models::*;

pub const PLUGIN_IDENTIFIER: &str = "com.noiq.lingchat.alarm";
pub const PLUGIN_CLASS: &str = "AlarmPlugin";

pub type BoxFuture<T> = Pin<Box<dyn Future<Output = T> + Send + 'static>>;
pub(crate) type Invoker =
    Box<dyn Fn(String, Value) -> BoxFuture<Result<Value>> + Send + Sync + 'static>;

static GLOBAL_INVOKER: OnceLock<Invoker> = OnceLock::new();

#[derive(Debug, Deserialize)]
struct MobileEnvelope<T> {
    ok: bool,
    data: Option<T>,
    error: Option<String>,
}

pub fn set_global_invoker<F>(f: F)
where
    F: Fn(String, Value) -> BoxFuture<Result<Value>> + Send + Sync + 'static,
{
    let _ = GLOBAL_INVOKER.set(Box::new(f));
}

async fn global_call<T>(method: &str, payload: Value) -> Result<T>
where
    T: DeserializeOwned,
{
    let invoker = GLOBAL_INVOKER
        .get()
        .ok_or_else(|| Error::PluginInvoke("闹钟插件尚未初始化".into()))?;
    let value = invoker(method.to_string(), payload).await?;
    let envelope: MobileEnvelope<T> = serde_json::from_value(value)
        .map_err(|e| Error::PluginInvoke(format!("闹钟插件响应格式无效: {e}")))?;
    if envelope.ok {
        envelope
            .data
            .ok_or_else(|| Error::PluginInvoke("闹钟插件响应缺少 data".into()))
    } else {
        Err(Error::Message(
            envelope
                .error
                .unwrap_or_else(|| "闹钟操作失败".to_string()),
        ))
    }
}

pub async fn global_upsert(req: UpsertAlarmRequest) -> Result<AlarmRecord> {
    let payload = serde_json::to_value(req)
        .map_err(|e| Error::PluginInvoke(format!("序列化闹钟请求失败: {e}")))?;
    global_call("upsert", payload).await
}

pub async fn global_list() -> Result<AlarmListResponse> {
    global_call("list", serde_json::json!({})).await
}

pub async fn global_cancel(req: CancelAlarmRequest) -> Result<AlarmOperationResponse> {
    let payload = serde_json::to_value(req)
        .map_err(|e| Error::PluginInvoke(format!("序列化闹钟请求失败: {e}")))?;
    global_call("cancel", payload).await
}

pub async fn global_set_ringtone(req: SetRingtoneRequest) -> Result<AlarmRecord> {
    let payload = serde_json::to_value(req)
        .map_err(|e| Error::PluginInvoke(format!("序列化铃声请求失败: {e}")))?;
    global_call("set_ringtone", payload).await
}

pub async fn global_list_ringtones() -> Result<RingtoneListResponse> {
    global_call("list_ringtones", serde_json::json!({})).await
}

pub async fn global_stop(req: StopAlarmRequest) -> Result<AlarmOperationResponse> {
    let payload = serde_json::to_value(req)
        .map_err(|e| Error::PluginInvoke(format!("序列化停止请求失败: {e}")))?;
    global_call("stop", payload).await
}

pub async fn global_permissions() -> Result<AlarmPermissions> {
    global_call("permissions", serde_json::json!({})).await
}

pub async fn global_request_permissions() -> Result<AlarmPermissions> {
    global_call("request_permissions", serde_json::json!({})).await
}

pub struct Alarm<R: Runtime> {
    handle: Arc<PluginHandle<R>>,
}

impl<R: Runtime> Alarm<R> {
    pub fn init<C: DeserializeOwned>(api: &tauri::plugin::PluginApi<R, C>) -> Result<Self> {
        let handle = api
            .register_android_plugin(PLUGIN_IDENTIFIER, PLUGIN_CLASS)
            .map_err(|e| Error::PluginInvoke(e.to_string()))?;
        Ok(Self {
            handle: Arc::new(handle),
        })
    }

    pub fn handle(&self) -> Arc<PluginHandle<R>> {
        Arc::clone(&self.handle)
    }

    fn invoke<P, T>(&self, method: &str, payload: P) -> Result<T>
    where
        P: serde::Serialize,
        T: DeserializeOwned,
    {
        let value: Value = self
            .handle
            .run_mobile_plugin(method, payload)
            .map_err(|e| Error::PluginInvoke(e.to_string()))?;
        let envelope: MobileEnvelope<T> = serde_json::from_value(value)
            .map_err(|e| Error::PluginInvoke(format!("闹钟插件响应格式无效: {e}")))?;
        if envelope.ok {
            envelope
                .data
                .ok_or_else(|| Error::PluginInvoke("闹钟插件响应缺少 data".into()))
        } else {
            Err(Error::Message(
                envelope
                    .error
                    .unwrap_or_else(|| "闹钟操作失败".to_string()),
            ))
        }
    }

    pub fn upsert(&self, req: UpsertAlarmRequest) -> Result<AlarmRecord> {
        self.invoke("upsert", req)
    }

    pub fn list(&self) -> Result<AlarmListResponse> {
        self.invoke("list", serde_json::json!({}))
    }

    pub fn cancel(&self, req: CancelAlarmRequest) -> Result<AlarmOperationResponse> {
        self.invoke("cancel", req)
    }

    pub fn set_ringtone(&self, req: SetRingtoneRequest) -> Result<AlarmRecord> {
        self.invoke("set_ringtone", req)
    }

    pub fn list_ringtones(&self) -> Result<RingtoneListResponse> {
        self.invoke("list_ringtones", serde_json::json!({}))
    }

    pub fn stop(&self, req: StopAlarmRequest) -> Result<AlarmOperationResponse> {
        self.invoke("stop", req)
    }

    pub fn permissions(&self) -> Result<AlarmPermissions> {
        self.invoke("permissions", serde_json::json!({}))
    }

    pub fn request_permissions(&self) -> Result<AlarmPermissions> {
        self.invoke("request_permissions", serde_json::json!({}))
    }
}

pub fn make_invoker<R: Runtime>(handle: Arc<PluginHandle<R>>) -> Invoker {
    Box::new(move |method: String, payload: Value| {
        let handle = Arc::clone(&handle);
        Box::pin(async move {
            tauri::async_runtime::spawn_blocking(move || {
                handle
                    .run_mobile_plugin::<Value>(&method, payload)
                    .map_err(|e| Error::PluginInvoke(e.to_string()))
            })
            .await
            .map_err(|e| Error::PluginInvoke(format!("blocking task join failed: {e}")))?
        })
    })
}
