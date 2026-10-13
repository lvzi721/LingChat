---
name: lingchat-alarm
description: Create, update, list, silence, or cancel alarms owned by LingChat. Use when the user asks to set an alarm, change an alarm ringtone, inspect alarms, or cancel an alarm on Android.
---

# LingChat Alarm

Use LingChat's native alarm tools. Do not use `execute_command`, system Clock intents, or Huawei private APIs for LingChat-owned alarms.

## Workflow

1. For an existing alarm, call `alarm_list` first and use the returned `id`.
2. Create or update with `alarm_upsert`.
3. To change only the ringtone, call `alarm_list_ringtones`, then `alarm_set_ringtone`.
4. To remove an alarm, call `alarm_cancel` with its `id`.
5. After a mutation, call `alarm_list` to verify the result.

## Time Rules

- `hour`: 0-23
- `minute`: 0-59
- `days` uses Calendar numbering: Sunday=1, Monday=2, ..., Saturday=7
- Empty `days` means one-shot
- `[1,2,3,4,5,6,7]` means every day
- `[2,3,4,5,6]` means Monday through Friday

## Permissions

Before creating the first alarm, call `alarm_permissions`. If exact alarms or notifications are unavailable, call `alarm_request_permissions` and tell the user which system page must be approved. Exact permission denial is not fatal: LingChat falls back to an inexact alarm, and the returned record has `exact=false`.

## Ringtone Rules

Only use URIs returned by `alarm_list_ringtones`. The special URI `silent` means silent vibration-only alarm.

## Stop Rules

`alarm_stop` only stops the currently ringing alarm. It does not cancel or disable future alarms.
