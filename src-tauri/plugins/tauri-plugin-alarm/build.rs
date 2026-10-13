const COMMANDS: &[&str] = &[
    "upsert",
    "list",
    "cancel",
    "set_ringtone",
    "list_ringtones",
    "stop",
    "permissions",
    "request_permissions",
];

fn main() {
    tauri_plugin::Builder::new(COMMANDS)
        .android_path("android")
        .build();
}
