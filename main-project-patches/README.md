# 主工程改造补丁（fork: lvzi721/LingChat）

目标：把 tauri-plugin-shizuku 接入 LingChat，让 execute_command 在 Android 上
经 Shizuku（uid=2000 shell）执行 shell。共改 5 个源文件。

| # | 文件 | 动作 | 补丁文件 |
|---|------|------|----------|
| 1 | src-tauri/Cargo.toml | +1 依赖 | 02_Cargo_toml.patch.md |
| 2 | src-tauri/src/lib.rs | +1 .plugin() | 03_lib_rs.patch.md |
| 3 | .../skill_agent/tools.rs | 删 3 处 cfg(desktop) | tools_gates.patch.rs |
| 3b| .../tools/mod.rs | 删 2 处 cfg(desktop) | 04_tools_mod_rs.patch.md |
| 4 | .../skill_agent/command_executor.rs | 拆 Android/桌面 | command_executor.patch.rs |

两条路径都改到：
- 剧本编辑器 Skill Agent：skill_agent/tools.rs（execute_command）
- 主聊天角色工具：tools/mod.rs（ExecuteCommand）
两者最终都调用 skill_agent/command_executor.rs，故补丁 4 是唯一执行后端。

capabilities 不用改（走原生 IPC，不经 JS 权限系统）。

关于 identifier：tauri.conf.json 里是 `com.noiq.ling-chat`（带连字符，非法），
但上游已提供 src-tauri/tauri.android.conf.json 覆盖为 `com.noiq.lingchat`，
Android 构建会自动采用后者，无需你处理。

自签名与官方 APK 不同，安装前须先卸载官方版。
