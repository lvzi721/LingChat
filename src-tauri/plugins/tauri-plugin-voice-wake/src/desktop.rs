use crate::error::{Error, Result};
use crate::models::VoiceWakeStatus;

pub fn start(_phrase: &str) -> Result<VoiceWakeStatus> {
    Err(Error::UnsupportedPlatform)
}

pub fn stop() -> Result<VoiceWakeStatus> {
    Err(Error::UnsupportedPlatform)
}

pub fn status() -> Result<VoiceWakeStatus> {
    Ok(VoiceWakeStatus::default())
}

pub fn set_paused(_paused: bool) -> Result<VoiceWakeStatus> {
    Err(Error::UnsupportedPlatform)
}

pub fn request_wake_permissions() -> Result<()> {
    Err(Error::UnsupportedPlatform)
}

pub fn open_system_settings() -> Result<()> {
    Err(Error::UnsupportedPlatform)
}

