use thiserror::Error;

pub type Result<T> = std::result::Result<T, Error>;

#[derive(Debug, Error)]
pub enum Error {
    #[error("语音唤醒仅在 Android 平台可用")]
    UnsupportedPlatform,
    #[error("语音唤醒插件未初始化")]
    NotInitialized,
    #[error("Android 插件调用失败: {0}")]
    PluginInvoke(String),
}

