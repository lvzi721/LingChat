import { Channel, invoke } from "@tauri-apps/api/core";

import { isAndroid } from "@/utils/platform";

export interface VoiceWakeSettings {
  enabled: boolean;
  phrase: string;
}

export interface VoiceWakeStatus {
  supported: boolean;
  modelPresent: boolean;
  engineReady: boolean;
  serviceRunning: boolean;
  permissionGranted: boolean;
  notificationPermissionGranted: boolean;
  suspended: boolean;
  phrase: string;
  keywordTokens?: string | null;
  lastDetectionSerial: number;
  lastDetectionAt: number;
  lastDetectionTranscript?: string | null;
  lastError?: string | null;
}

export interface VoiceWakeDetectedEvent {
  phrase: string;
  transcript: string;
  timestamp: number;
  serial: number;
}

const DEFAULT_SETTINGS: VoiceWakeSettings = {
  enabled: false,
  phrase: "灵灵",
};

let channel: Channel<VoiceWakeDetectedEvent> | null = null;
let listenerPromise: Promise<void> | null = null;
let pollTimer: number | null = null;
let lastDeliveredSerial = 0;
const detectedHandlers = new Set<(event: VoiceWakeDetectedEvent) => void>();

function emitDetected(event: VoiceWakeDetectedEvent): void {
  if (!event.serial || event.serial <= lastDeliveredSerial) return;
  lastDeliveredSerial = event.serial;
  for (const handler of detectedHandlers) {
    try {
      handler(event);
    } catch (error) {
      console.warn("[VoiceWake] 唤醒事件处理器失败:", error);
    }
  }
}

function startDetectionPolling(): void {
  if (!isAndroid() || pollTimer !== null) return;
  pollTimer = window.setInterval(async () => {
    try {
      const status = await voiceWakeGetStatus();
      if (status.lastDetectionSerial <= lastDeliveredSerial) return;
      emitDetected({
        phrase: status.phrase,
        transcript: status.lastDetectionTranscript ?? status.phrase,
        timestamp: status.lastDetectionAt,
        serial: status.lastDetectionSerial,
      });
    } catch (error) {
      console.debug("[VoiceWake] 轮询唤醒状态失败:", error);
    }
  }, 1000);
}

export async function ensureVoiceWakeListener(): Promise<void> {
  if (!isAndroid()) return;
  startDetectionPolling();
  if (channel) return;
  if (listenerPromise) return listenerPromise;

  listenerPromise = (async () => {
    const next = new Channel<VoiceWakeDetectedEvent>();
    next.onmessage = emitDetected;
    await invoke("plugin:voice-wake|registerListener", {
      event: "detected",
      handler: next,
    });
    channel = next;
  })().finally(() => {
    listenerPromise = null;
  });

  return listenerPromise;
}

export function onVoiceWakeDetected(handler: (event: VoiceWakeDetectedEvent) => void): () => void {
  detectedHandlers.add(handler);
  void ensureVoiceWakeListener().catch((error) => {
    console.warn("[VoiceWake] 注册原生唤醒事件监听失败:", error);
  });
  return () => detectedHandlers.delete(handler);
}

export const voiceWakeGetSettings = () => invoke<VoiceWakeSettings>("voice_wake_get_settings");

export const voiceWakeSaveSettings = (settings: VoiceWakeSettings) =>
  invoke<VoiceWakeStatus>("voice_wake_save_settings", { settings });

export const voiceWakeGetStatus = () => invoke<VoiceWakeStatus>("voice_wake_get_status");

export const voiceWakeEnsureStarted = () => invoke<VoiceWakeStatus>("voice_wake_ensure_started");

export const voiceWakeSetPaused = (paused: boolean) =>
  invoke<VoiceWakeStatus>("voice_wake_set_paused", { paused });

export const voiceWakeRequestPermissions = () => invoke<void>("voice_wake_request_permissions");

export const voiceWakeOpenSystemSettings = () => invoke<void>("voice_wake_open_system_settings");

export const getDefaultVoiceWakeSettings = (): VoiceWakeSettings => ({ ...DEFAULT_SETTINGS });
