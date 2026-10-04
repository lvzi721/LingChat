#!/usr/bin/env python3
"""对 LingChat fork 应用 5 处主工程改动（幂等）。
用法: python3 apply_patches.py <repo_root>
"""
import sys, os

root = sys.argv[1] if len(sys.argv) > 1 else "."
def p(*a): return os.path.join(root, *a)

def edit(path, old, new, must=True):
    with open(path, "r", encoding="utf-8") as f:
        s = f.read()
    if new.strip() and new not in s and old not in s:
        print(f"[skip] 未找到锚点: {path}")
        if must: raise SystemExit(f"锚点缺失: {path}\n  old={old[:80]!r}")
        return
    if old in s:
        s = s.replace(old, new, 1)
        with open(path, "w", encoding="utf-8") as f:
            f.write(s)
        print(f"[ok] {path}")
    else:
        print(f"[already] {path}")

# 1) Cargo.toml 追加依赖
edit(p("src-tauri/Cargo.toml"),
     'tauri-plugin-android-fs = "28"',
     'tauri-plugin-android-fs = "28"\ntauri-plugin-shizuku = { path = "plugins/tauri-plugin-shizuku" }')

# 2) lib.rs 注册插件
edit(p("src-tauri/src/lib.rs"),
     '        .plugin(tauri_plugin_android_fs::init());',
     '        .plugin(tauri_plugin_android_fs::init())\n        .plugin(tauri_plugin_shizuku::init());')

# 3) skill_agent/tools.rs 删 3 处 cfg(desktop)
t = p("src-tauri/src/ai_service/skill_agent/tools.rs")
edit(t, '#[cfg(desktop)]\nuse crate::ai_service::skill_agent::command_executor;',
        'use crate::ai_service::skill_agent::command_executor;')
edit(t, '        #[cfg(desktop)]\n        ToolDefinition::new(\n            "execute_command",',
        '        ToolDefinition::new(\n            "execute_command",')
edit(t, '        #[cfg(desktop)]\n        "execute_command" => {',
        '        "execute_command" => {')

# 4) ai_service/tools/mod.rs 删 2 处 cfg(desktop)
m = p("src-tauri/src/ai_service/tools/mod.rs")
edit(m, '#[cfg(desktop)]\nuse skill_files::ExecuteCommand;',
        'use skill_files::ExecuteCommand;')
edit(m, '''    // Android/iOS 没有稳定、可审批的桌面 shell 环境。移动端不注册命令工具，
    // 避免模型选中 execute_command 后才得到系统级执行失败。
    #[cfg(desktop)]
    registry.register(Arc::new(ExecuteCommand::new(tool_settings.clone())))?;''',
        '''    // Android 上经 Shizuku（uid=2000 shell）执行；桌面走本地进程。
    registry.register(Arc::new(ExecuteCommand::new(tool_settings.clone())))?;''')

# 5) command_executor.rs 拆分 Android/桌面实现
ce = p("src-tauri/src/ai_service/skill_agent/command_executor.rs")
old_head = '''async fn run_shell_command_with_limits(
    sandbox_dir: &Path,
    command: &str,
    cwd: &str,
    timeout: Duration,
    output_limit: usize,
) -> anyhow::Result<CommandOutput> {
    if command.trim().is_empty() {
        anyhow::bail!("命令不能为空");
    }
    let cwd_path = resolve_working_directory(sandbox_dir, cwd)?;
'''
new_head = '''/// Android：通过 Shizuku（uid=2000 shell）执行命令。
#[cfg(target_os = "android")]
async fn run_shell_command_with_limits(
    sandbox_dir: &Path,
    command: &str,
    cwd: &str,
    timeout: Duration,
    _output_limit: usize,
) -> anyhow::Result<CommandOutput> {
    if command.trim().is_empty() {
        anyhow::bail!("命令不能为空");
    }
    let cwd_path = resolve_working_directory(sandbox_dir, cwd)?;
    let timeout_ms = timeout.as_millis().min(u64::MAX as u128) as u64;
    let response = tauri_plugin_shizuku::global_execute(tauri_plugin_shizuku::ExecuteRequest {
        command: command.to_string(),
        timeout_ms,
        cwd: Some(cwd_path.to_string_lossy().into_owned()),
    })
    .await
    .map_err(|e| anyhow::anyhow!("Shizuku 执行失败: {e}"))?;
    if response.timed_out {
        anyhow::bail!(
            "命令执行超时（{} 秒），已终止进程。\\n退出码: {}\\nstdout:\\n{}\\nstderr:\\n{}",
            timeout.as_secs_f32(),
            response.exit_code,
            response.stdout,
            response.stderr
        );
    }
    Ok(CommandOutput {
        stdout: response.stdout,
        stderr: response.stderr,
        exit_code: response.exit_code,
    })
}

/// 桌面端（Windows / Linux / macOS）：本地进程执行（原逻辑不变）。
#[cfg(not(target_os = "android"))]
async fn run_shell_command_with_limits(
    sandbox_dir: &Path,
    command: &str,
    cwd: &str,
    timeout: Duration,
    output_limit: usize,
) -> anyhow::Result<CommandOutput> {
    if command.trim().is_empty() {
        anyhow::bail!("命令不能为空");
    }
    let cwd_path = resolve_working_directory(sandbox_dir, cwd)?;
'''
edit(ce, old_head, new_head)
print("\n全部补丁处理完毕。")
