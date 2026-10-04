# 补丁 2：src-tauri/src/lib.rs（builder 链，原第 285 行）
## 查找：
        .plugin(tauri_plugin_android_fs::init());
## 替换为：
        .plugin(tauri_plugin_android_fs::init())
        .plugin(tauri_plugin_shizuku::init());
