use tauri::plugin::{Builder, TauriPlugin};
use tauri::{Manager, Runtime};

mod error;
mod models;
#[cfg(not(target_os = "android"))]
mod desktop;
#[cfg(target_os = "android")]
mod mobile;

pub use error::{Error, Result};
pub use models::{AvailableResponse, ExecuteRequest, ExecuteResponse, PermissionResponse};

#[cfg(target_os = "android")]
pub use mobile::{global_execute, global_ready, set_global_executor, Shizuku};

/// 执行一条 shell 命令。
#[tauri::command]
async fn execute<R: Runtime>(
    #[allow(unused_variables)] app: tauri::AppHandle<R>,
    #[allow(unused_variables)] req: ExecuteRequest,
) -> Result<ExecuteResponse> {
    #[cfg(target_os = "android")]
    {
        let state = app
            .try_state::<Shizuku<R>>()
            .ok_or_else(|| Error::PluginInvoke("Shizuku 插件未初始化".into()))?;
        state.execute(req)
    }
    #[cfg(not(target_os = "android"))]
    {
        desktop::execute(req)
    }
}

/// 查询 Shizuku 服务与权限状态。
#[tauri::command]
async fn available<R: Runtime>(
    #[allow(unused_variables)] app: tauri::AppHandle<R>,
) -> Result<AvailableResponse> {
    #[cfg(target_os = "android")]
    {
        match app.try_state::<Shizuku<R>>() {
            Some(state) => state.available(),
            None => Ok(AvailableResponse {
                available: false,
                granted: false,
            }),
        }
    }
    #[cfg(not(target_os = "android"))]
    {
        desktop::available()
    }
}

/// 查询权限是否已授予。
#[tauri::command]
async fn check<R: Runtime>(
    #[allow(unused_variables)] app: tauri::AppHandle<R>,
) -> Result<PermissionResponse> {
    #[cfg(target_os = "android")]
    {
        match app.try_state::<Shizuku<R>>() {
            Some(state) => state.check(),
            None => Ok(PermissionResponse {
                granted: false,
                message: Some("Shizuku 插件未初始化".into()),
            }),
        }
    }
    #[cfg(not(target_os = "android"))]
    {
        desktop::check()
    }
}

/// 请求 Shizuku 权限。
#[tauri::command]
async fn request<R: Runtime>(
    #[allow(unused_variables)] app: tauri::AppHandle<R>,
) -> Result<PermissionResponse> {
    #[cfg(target_os = "android")]
    {
        match app.try_state::<Shizuku<R>>() {
            Some(state) => state.request(),
            None => Ok(PermissionResponse {
                granted: false,
                message: Some("Shizuku 插件未初始化".into()),
            }),
        }
    }
    #[cfg(not(target_os = "android"))]
    {
        desktop::request()
    }
}

/// 初始化插件。
pub fn init<R: Runtime>() -> TauriPlugin<R> {
    Builder::new("shizuku")
        .invoke_handler(tauri::generate_handler![execute, available, check, request])
        .setup(|app, api| {
            #[cfg(target_os = "android")]
            {
                let shizuku = mobile::Shizuku::<R>::init(&api)?;
                // 注册全局执行器，使主工程 command_executor 无需 AppHandle 即可调用。
                let executor = mobile::make_executor(shizuku.handle());
                mobile::set_global_executor(executor);
                app.manage(shizuku);
            }
            #[cfg(not(target_os = "android"))]
            {
                let _ = (app, api);
            }
            Ok(())
        })
        .build()
}
