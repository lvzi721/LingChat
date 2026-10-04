use crate::error::{Error, Result};
use crate::models::{AvailableResponse, ExecuteRequest, ExecuteResponse, PermissionResponse};
use serde::de::DeserializeOwned;
use std::future::Future;
use std::pin::Pin;
use std::sync::{Arc, OnceLock};
use tauri::plugin::PluginHandle;
use tauri::Runtime;

/// 与 Kotlin 侧 `@TauriPlugin` 注册名保持一致。
pub const PLUGIN_IDENTIFIER: &str = "com.noiq.lingchat.shizuku";

/// Kotlin 类名（`@TauriPlugin` 注解的类）。
pub const PLUGIN_CLASS: &str = "ShizukuPlugin";

/// 装箱的异步 Future 类型别名。
pub type BoxFuture<T> = Pin<Box<dyn Future<Output = T> + Send + 'static>>;
/// 全局异步命令执行器类型。
pub type Executor =
    Box<dyn Fn(ExecuteRequest) -> BoxFuture<Result<ExecuteResponse>> + Send + Sync + 'static>;

static GLOBAL_EXECUTOR: OnceLock<Executor> = OnceLock::new();

/// 注册全局执行器（插件初始化时调用一次）。
///
/// 主工程（如 `command_executor.rs`）可在没有 `AppHandle` 的上下文中
/// 通过 [`global_execute`] 直接调用，无需自行持有插件句柄。
pub fn set_global_executor<F>(f: F)
where
    F: Fn(ExecuteRequest) -> BoxFuture<Result<ExecuteResponse>> + Send + Sync + 'static,
{
    let _ = GLOBAL_EXECUTOR.set(Box::new(f));
}

/// 是否已注册全局执行器。
pub fn global_ready() -> bool {
    GLOBAL_EXECUTOR.get().is_some()
}

/// 通过已注册的全局执行器执行命令。
///
/// 未初始化（非 Android 或插件未加载）时返回错误。
pub async fn global_execute(req: ExecuteRequest) -> Result<ExecuteResponse> {
    match GLOBAL_EXECUTOR.get() {
        Some(f) => f(req).await,
        None => Err(Error::PluginInvoke(
            "Shizuku 全局执行器尚未注册（插件未初始化）".into(),
        )),
    }
}

/// 持有 Android 插件句柄。
pub struct Shizuku<R: Runtime> {
    handle: Arc<PluginHandle<R>>,
}

impl<R: Runtime> Shizuku<R> {
    /// 插件初始化时调用。
    pub fn init(api: &tauri::plugin::PluginApi<R>) -> Result<Self> {
        let handle = api
            .register_android_plugin(PLUGIN_IDENTIFIER, PLUGIN_CLASS)
            .map_err(|e| Error::PluginInvoke(e.to_string()))?;
        Ok(Self {
            handle: Arc::new(handle),
        })
    }

    /// 获取句柄的共享引用（供注册全局执行器使用）。
    pub fn handle(&self) -> Arc<PluginHandle<R>> {
        Arc::clone(&self.handle)
    }

    /// 调用 Kotlin 侧 `execute` 方法。
    pub fn execute(&self, req: ExecuteRequest) -> Result<ExecuteResponse> {
        self.invoke("execute", req)
    }

    /// 调用 Kotlin 侧 `available` 方法。
    pub fn available(&self) -> Result<AvailableResponse> {
        self.invoke("available", serde_json::json!({}))
    }

    /// 调用 Kotlin 侧 `check` 方法。
    pub fn check(&self) -> Result<PermissionResponse> {
        self.invoke("check", serde_json::json!({}))
    }

    /// 调用 Kotlin 侧 `request` 方法。
    pub fn request(&self) -> Result<PermissionResponse> {
        self.invoke("request", serde_json::json!({ "requestCode": 1001 }))
    }

    /// 通用同步调用封装（内部走 `run_mobile_plugin`，会阻塞当前线程）。
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

/// 用给定句柄构造一个可全局共享的异步执行器。
///
/// `PluginHandle::run_mobile_plugin` 是阻塞调用，因此这里把它放到
/// Tauri 的 blocking 线程池中执行，避免阻塞 async 运行时。
pub fn make_executor<R: Runtime>(handle: Arc<PluginHandle<R>>) -> Executor {
    Box::new(move |req: ExecuteRequest| {
        let handle = Arc::clone(&handle);
        Box::pin(async move {
            tauri::async_runtime::spawn_blocking(move || {
                handle
                    .run_mobile_plugin::<ExecuteResponse>("execute", req)
                    .map_err(|e| Error::PluginInvoke(e.to_string()))
            })
            .await
            .map_err(|e| Error::PluginInvoke(format!("blocking task join failed: {e}")))?
        })
    })
}
