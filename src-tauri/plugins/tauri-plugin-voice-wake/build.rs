const COMMANDS: &[&str] = &[
    "start",
    "stop",
    "status",
    "set_paused",
    "request_wake_permissions",
    "open_system_settings",
    "takePendingText",
    "recognizeOnce",
];

fn main() {
    tauri_plugin::Builder::new(COMMANDS)
        .android_path("android")
        .build();
}

