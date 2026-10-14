import { invoke } from "@tauri-apps/api/core";

/** 一条 LingChat 自建闹钟（与后端 AlarmRecord 对应）。 */
export interface AlarmRecord {
  id: string;
  label: string;
  hour: number;
  minute: number;
  /** 重复星期：周日=1 … 周六=7；空数组表示仅响一次。 */
  days: number[];
  enabled: boolean;
  ringtoneUri: string | null;
  ringtoneTitle: string;
  nextTriggerAt: number | null;
  /** 已经格式化好的本地时间文本，仅用于展示。 */
  nextTriggerText: string | null;
  /** false 表示系统未授予精确闹钟权限，实际响铃可能被系统延后。 */
  exact: boolean;
}

export interface RingtoneRecord {
  title: string;
  uri: string;
  isDefault: boolean;
}

export interface AlarmPermissions {
  notificationsGranted: boolean;
  exactAlarmAllowed: boolean;
  batteryOptimizationIgnored: boolean;
}

export interface AlarmOperationResult {
  ok: boolean;
  message?: string | null;
}

export interface AlarmUpsertInput {
  id?: string | null;
  label?: string | null;
  hour: number;
  minute: number;
  days?: number[];
  enabled?: boolean;
  ringtoneUri?: string | null;
}

/** 静音铃声的约定 URI。 */
export const SILENT_RINGTONE_URI = "silent";

export const alarmList = () => invoke<{ alarms: AlarmRecord[] }>("alarm_list");

export const alarmUpsert = (input: AlarmUpsertInput) =>
  invoke<AlarmRecord>("alarm_upsert", {
    id: input.id ?? null,
    label: input.label ?? null,
    hour: input.hour,
    minute: input.minute,
    days: input.days ?? [],
    enabled: input.enabled ?? true,
    ringtoneUri: input.ringtoneUri ?? null,
  });

export const alarmCancel = (id: string) =>
  invoke<AlarmOperationResult>("alarm_cancel", { id });

export const alarmSetRingtone = (id: string, uri: string) =>
  invoke<AlarmRecord>("alarm_set_ringtone", { id, uri });

export const alarmListRingtones = () =>
  invoke<{ ringtones: RingtoneRecord[] }>("alarm_list_ringtones");

export const alarmStop = () => invoke<AlarmOperationResult>("alarm_stop");

export const alarmPermissions = () => invoke<AlarmPermissions>("alarm_permissions");

export const alarmRequestPermissions = () =>
  invoke<AlarmPermissions>("alarm_request_permissions");
