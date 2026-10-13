use serde::{Serialize, Serializer};

#[derive(Debug, thiserror::Error)]
pub enum Error {
    #[error("闹钟功能仅在 Android 平台可用")]
    NotSupported,

    #[error("插件调用失败: {0}")]
    PluginInvoke(String),

    #[error("{0}")]
    Message(String),
}

impl Serialize for Error {
    fn serialize<S>(&self, serializer: S) -> std::result::Result<S::Ok, S::Error>
    where
        S: Serializer,
    {
        serializer.serialize_str(&self.to_string())
    }
}

pub type Result<T> = std::result::Result<T, Error>;
