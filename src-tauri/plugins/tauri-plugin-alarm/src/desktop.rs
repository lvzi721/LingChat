use crate::error::{Error, Result};
use crate::models::*;

pub fn upsert(_req: UpsertAlarmRequest) -> Result<AlarmRecord> {
    Err(Error::NotSupported)
}

pub fn list() -> Result<AlarmListResponse> {
    Ok(AlarmListResponse { alarms: Vec::new() })
}

pub fn cancel(_req: CancelAlarmRequest) -> Result<AlarmOperationResponse> {
    Err(Error::NotSupported)
}

pub fn set_ringtone(_req: SetRingtoneRequest) -> Result<AlarmRecord> {
    Err(Error::NotSupported)
}

pub fn list_ringtones() -> Result<RingtoneListResponse> {
    Ok(RingtoneListResponse {
        ringtones: Vec::new(),
    })
}

pub fn stop(_req: StopAlarmRequest) -> Result<AlarmOperationResponse> {
    Err(Error::NotSupported)
}

pub fn permissions() -> Result<AlarmPermissions> {
    Ok(AlarmPermissions {
        notifications_granted: false,
        exact_alarm_allowed: false,
        battery_optimization_ignored: false,
    })
}

pub fn request_permissions() -> Result<AlarmPermissions> {
    Err(Error::NotSupported)
}
