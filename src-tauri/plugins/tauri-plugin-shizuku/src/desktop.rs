use crate::error::{Error, Result};
use crate::models::{AvailableResponse, ExecuteRequest, ExecuteResponse, PermissionResponse};

pub fn execute(_req: ExecuteRequest) -> Result<ExecuteResponse> {
    Err(Error::NotSupported)
}

pub fn available() -> Result<AvailableResponse> {
    Ok(AvailableResponse {
        available: false,
        granted: false,
    })
}

pub fn check() -> Result<PermissionResponse> {
    Ok(PermissionResponse {
        granted: false,
        message: Some("Shizuku 仅在 Android 平台可用".into()),
    })
}

pub fn request() -> Result<PermissionResponse> {
    Ok(PermissionResponse {
        granted: false,
        message: Some("Shizuku 仅在 Android 平台可用".into()),
    })
}
