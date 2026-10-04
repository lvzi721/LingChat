use serde::{Serialize, Serializer};

#[derive(Debug, thiserror::Error)]
pub enum Error {
    /// 非 Android 平台调用 Shizuku 能力。
    #[error("Shizuku 仅在 Android 平台可用")]
    NotSupported,

    /// 底层插件调用失败（通道关闭、参数解析失败等）。
    #[error("插件调用失败: {0}")]
    PluginInvoke(String),

    /// 自定义错误消息。
    #[error("{0}")]
    Message(String),
}

impl Serialize for Error {
    fn serialize<S>(&self, serializer: S) -> Result<S::Ok, S::Error>
    where
        S: Serializer,
    {
        serializer.serialize_str(&self.to_string())
    }
}

pub type Result<T> = std::result::Result<T, Error>;
