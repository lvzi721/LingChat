use crate::command_executor::CommandOutput; // 复用原有结构体，别重复定义

#[cfg(target_os = "android")]
pub async fn exec_shell(
    app: &tauri::AppHandle,
    command: &str,
    cwd: Option<&str>,
) -> anyhow::Result<CommandOutput> {
    let res = app
        .shizuku()
        .run_shell(command.to_string(), cwd.map(String::from))
        .map_err(|e| anyhow::anyhow!("Shizuku: {e}"))?;
    Ok(CommandOutput { stdout: res.stdout, stderr: res.stderr, exit_code: res.exit_code })
}
