use tauri::plugin::{Builder, TauriPlugin};
use tauri::{Manager, Runtime};

mod error;
mod models;

#[cfg(not(target_os = "android"))]
mod desktop;
#[cfg(target_os = "android")]
mod mobile;

pub use error::{Error, Result};
pub use models::*;

#[cfg(target_os = "android")]
pub use mobile::{
    global_cancel, global_list, global_list_ringtones, global_permissions,
    global_request_permissions, global_set_ringtone, global_stop, global_upsert,
};

#[tauri::command]
async fn upsert<R: Runtime>(
    #[allow(unused_variables)] app: tauri::AppHandle<R>,
    #[allow(unused_variables)] req: UpsertAlarmRequest,
) -> Result<AlarmRecord> {
    #[cfg(target_os = "android")]
    {
        app.try_state::<mobile::Alarm<R>>()
            .ok_or_else(|| Error::PluginInvoke("闹钟插件未初始化".into()))?
            .upsert(req)
    }
    #[cfg(not(target_os = "android"))]
    {
        desktop::upsert(req)
    }
}

#[tauri::command]
async fn list<R: Runtime>(#[allow(unused_variables)] app: tauri::AppHandle<R>) -> Result<AlarmListResponse> {
    #[cfg(target_os = "android")]
    {
        app.try_state::<mobile::Alarm<R>>()
            .ok_or_else(|| Error::PluginInvoke("闹钟插件未初始化".into()))?
            .list()
    }
    #[cfg(not(target_os = "android"))]
    {
        desktop::list()
    }
}

#[tauri::command]
async fn cancel<R: Runtime>(
    #[allow(unused_variables)] app: tauri::AppHandle<R>,
    #[allow(unused_variables)] req: CancelAlarmRequest,
) -> Result<AlarmOperationResponse> {
    #[cfg(target_os = "android")]
    {
        app.try_state::<mobile::Alarm<R>>()
            .ok_or_else(|| Error::PluginInvoke("闹钟插件未初始化".into()))?
            .cancel(req)
    }
    #[cfg(not(target_os = "android"))]
    {
        desktop::cancel(req)
    }
}

#[tauri::command]
async fn set_ringtone<R: Runtime>(
    #[allow(unused_variables)] app: tauri::AppHandle<R>,
    #[allow(unused_variables)] req: SetRingtoneRequest,
) -> Result<AlarmRecord> {
    #[cfg(target_os = "android")]
    {
        app.try_state::<mobile::Alarm<R>>()
            .ok_or_else(|| Error::PluginInvoke("闹钟插件未初始化".into()))?
            .set_ringtone(req)
    }
    #[cfg(not(target_os = "android"))]
    {
        desktop::set_ringtone(req)
    }
}

#[tauri::command]
async fn list_ringtones<R: Runtime>(
    #[allow(unused_variables)] app: tauri::AppHandle<R>,
) -> Result<RingtoneListResponse> {
    #[cfg(target_os = "android")]
    {
        app.try_state::<mobile::Alarm<R>>()
            .ok_or_else(|| Error::PluginInvoke("闹钟插件未初始化".into()))?
            .list_ringtones()
    }
    #[cfg(not(target_os = "android"))]
    {
        desktop::list_ringtones()
    }
}

#[tauri::command]
async fn stop<R: Runtime>(
    #[allow(unused_variables)] app: tauri::AppHandle<R>,
    #[allow(unused_variables)] req: StopAlarmRequest,
) -> Result<AlarmOperationResponse> {
    #[cfg(target_os = "android")]
    {
        app.try_state::<mobile::Alarm<R>>()
            .ok_or_else(|| Error::PluginInvoke("闹钟插件未初始化".into()))?
            .stop(req)
    }
    #[cfg(not(target_os = "android"))]
    {
        desktop::stop(req)
    }
}

#[tauri::command]
async fn permissions<R: Runtime>(
    #[allow(unused_variables)] app: tauri::AppHandle<R>,
) -> Result<AlarmPermissions> {
    #[cfg(target_os = "android")]
    {
        app.try_state::<mobile::Alarm<R>>()
            .ok_or_else(|| Error::PluginInvoke("闹钟插件未初始化".into()))?
            .permissions()
    }
    #[cfg(not(target_os = "android"))]
    {
        desktop::permissions()
    }
}

#[tauri::command]
async fn request_permissions<R: Runtime>(
    #[allow(unused_variables)] app: tauri::AppHandle<R>,
) -> Result<AlarmPermissions> {
    #[cfg(target_os = "android")]
    {
        app.try_state::<mobile::Alarm<R>>()
            .ok_or_else(|| Error::PluginInvoke("闹钟插件未初始化".into()))?
            .request_permissions()
    }
    #[cfg(not(target_os = "android"))]
    {
        desktop::request_permissions()
    }
}

pub fn init<R: Runtime>() -> TauriPlugin<R> {
    Builder::new("alarm")
        .invoke_handler(tauri::generate_handler![
            upsert,
            list,
            cancel,
            set_ringtone,
            list_ringtones,
            stop,
            permissions,
            request_permissions
        ])
        .setup(|app, api| {
            #[cfg(target_os = "android")]
            {
                let alarm = mobile::Alarm::<R>::init(&api)?;
                mobile::set_global_invoker(mobile::make_invoker(alarm.handle()));
                app.manage(alarm);
            }
            #[cfg(not(target_os = "android"))]
            {
                let _ = (app, api);
            }
            Ok(())
        })
        .build()
}
