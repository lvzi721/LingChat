# 补丁 4：src-tauri/src/ai_service/skill_agent/command_executor.rs
# 拆成 Android（走 Shizuku）与桌面（本地进程）两个互斥实现。

## 4.1 拆分函数头（原第 292-302 行）
### 查找：
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

### 替换为：
/// Android：通过 Shizuku（uid=2000 shell）执行命令。
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
            "命令执行超时（{} 秒），已终止进程。\n退出码: {}\nstdout:\n{}\nstderr:\n{}",
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

## 4.2 （可选）给 terminate_process_tree 加门控（原第 482 行）
### 查找： async fn terminate_process_tree(child: &mut tokio::process::Child) {
### 替换为： (前加一行) #[cfg(not(target_os = "android"))]
# 不加也能编译（顶部有 allow(dead_code)）。

## 4.3 类型来源（插件已 pub use，无需额外 import）
# tauri_plugin_shizuku::ExecuteRequest  { command, timeout_ms, cwd }
# tauri_plugin_shizuku::global_execute(req) -> Result<ExecuteResponse>
# ExecuteResponse  { stdout, stderr, exit_code:i32, timed_out:bool }
