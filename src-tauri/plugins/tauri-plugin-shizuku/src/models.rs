use serde::{Deserialize, Serialize};

/// 执行命令的请求参数。
#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ExecuteRequest {
    /// 要执行的 shell 命令（以 `sh -c` 方式执行）。
    pub command: String,
    /// 超时毫秒数，0 表示不限制。
    #[serde(default = "default_timeout")]
    pub timeout_ms: u64,
    /// 工作目录，None 表示使用 shell 默认目录。
    #[serde(default)]
    pub cwd: Option<String>,
}

fn default_timeout() -> u64 {
    60_000
}

/// 命令执行结果。
#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ExecuteResponse {
    pub stdout: String,
    pub stderr: String,
    pub exit_code: i32,
    pub timed_out: bool,
}

/// Shizuku 可用性 / 权限状态。
#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AvailableResponse {
    pub available: bool,
    pub granted: bool,
}

/// 权限查询 / 请求结果。
#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PermissionResponse {
    pub granted: bool,
    #[serde(default)]
    pub message: Option<String>,
}
