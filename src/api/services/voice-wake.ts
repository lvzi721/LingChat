import { Channel, invoke } from "@tauri-apps/api/core";

import { isAndroid } from "@/utils/platform";

export interface VoiceWakeSettings {
  enabled: boolean;
  phrase: string;
  phraseEnabled: boolean;
  ackEnabled: boolean;
  ackText: string;
  ackAudioPath?: string | null;
}

export interface VoiceWakeStatus {
  supported: boolean;
  modelPresent: boolean;
  localAsrPresent: boolean;
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

export interface VoiceWakeRecognizedEvent {
  text: string;
  phrase: string;
  timestamp: number;
}

const DEFAULT_SETTINGS: VoiceWakeSettings = {
  enabled: false,
  phrase: "灵灵",
  phraseEnabled: true,
  ackEnabled: true,
  ackText: "我在",
  ackAudioPath: null,
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
  void ensureRecognizedChannel();
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

let recognizedChannel: Channel<VoiceWakeRecognizedEvent> | null = null;
let recognizedPromise: Promise<void> | null = null;
const recognizedHandlers = new Set<(event: VoiceWakeRecognizedEvent) => void>();

async function ensureRecognizedChannel(): Promise<void> {
  if (!isAndroid() || recognizedChannel) return;
  if (recognizedPromise) return recognizedPromise;

  recognizedPromise = (async () => {
    const next = new Channel<VoiceWakeRecognizedEvent>();
    next.onmessage = (event) => {
      for (const handler of recognizedHandlers) {
        try {
          handler(event);
        } catch (error) {
          console.warn("[VoiceWake] 识别事件处理器失败:", error);
        }
      }
    };
    await invoke("plugin:voice-wake|registerListener", {
      event: "recognized",
      handler: next,
    });
    recognizedChannel = next;
  })().finally(() => {
    recognizedPromise = null;
  });

  return recognizedPromise;
}

export function onVoiceWakeRecognized(
  handler: (event: VoiceWakeRecognizedEvent) => void
): () => void {
  recognizedHandlers.add(handler);
  void ensureRecognizedChannel().catch((error) => {
    console.warn("[VoiceWake] 注册原生识别事件失败:", error);
  });
  return () => recognizedHandlers.delete(handler);
}

/** 取走锁屏时由原生服务暂存的识别结果。 */
export async function voiceWakeTakePendingText(): Promise<string | null> {
  const resp = await invoke<{ text?: string }>("voice_wake_take_pending_text");
  return resp?.text ?? null;
}

/** 手动麦克风：用内置模型识别一次（端点或超时结束）。 */
export const voiceWakeRecognizeOnce = (maxMs = 15000) =>
  invoke<string>("voice_wake_recognize_once", { maxMs });

/** 诊断：录音约 5 秒用内置模型识别一次。 */
export const voiceWakeTestRecognition = () => invoke<string>("voice_wake_test_recognition");

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

/** 用当前角色的本地 TTS 音色预合成应答音频，返回缓存路径（失败时抛错）。 */
export const voiceWakePrepareAck = (text: string) =>
  invoke<string | null>("voice_wake_prepare_ack", { text });

export const voiceWakeGetStatus = () => invoke<VoiceWakeStatus>("voice_wake_get_status");

export const voiceWakeEnsureStarted = () => invoke<VoiceWakeStatus>("voice_wake_ensure_started");

export const voiceWakeSetPaused = (paused: boolean) =>
  invoke<VoiceWakeStatus>("voice_wake_set_paused", { paused });

export const voiceWakeRequestPermissions = () => invoke<void>("voice_wake_request_permissions");

export const voiceWakeOpenSystemSettings = () => invoke<void>("voice_wake_open_system_settings");

export const getDefaultVoiceWakeSettings = (): VoiceWakeSettings => ({ ...DEFAULT_SETTINGS });
