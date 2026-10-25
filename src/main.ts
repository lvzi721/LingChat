import { createApp } from "vue";
import { invoke } from "@tauri-apps/api/core";
import pinia from "./stores";
import { initializeEventProcessors } from "./core/events";
import { initializeTauriEventListeners, initializeCastWindowListeners } from "./api/tauri-events";

import App from "./App.vue";
import "./assets/styles/base.css";
import "./assets/styles/variables.css";
import { i18n } from "./locales";

// WebSocket handlers 保留用于未来剧本模式参考
// import "./api/websocket/handlers/script-handler";
// import "./api/websocket/handlers/adventure-handler";

import { getCurrentWindow } from "@tauri-apps/api/window";
import router from "./router";
import { autoConfigurePerformance } from "./api/services/cpu-perf";
import { initAudioOutputManager } from "./utils/audioOutputManager";

// 仅主窗口启动时清除加载过渡标记，避免设置窗口等其他窗口误清除
if (getCurrentWindow().label === "main") {
  localStorage.removeItem("lingchat_loading_shown");
}

const app = createApp(App);

let startupFailureVisible = false;

function showStartupFailure(title: string, error: unknown) {
  if (startupFailureVisible) return;
  startupFailureVisible = true;

  const message = error instanceof Error ? error.message : String(error);
  const stack = error instanceof Error ? error.stack || "" : "";
  const details = stack && !stack.includes(message) ? `${message}\n\n${stack}` : stack || message;

  const overlay = document.createElement("div");
  overlay.id = "lingchat-startup-failure";
  Object.assign(overlay.style, {
    position: "fixed",
    inset: "0",
    zIndex: "2147483647",
    display: "flex",
    alignItems: "center",
    justifyContent: "center",
    padding: "24px",
    boxSizing: "border-box",
    background: "#0f172a",
    color: "#e2e8f0",
    fontFamily: "system-ui, sans-serif",
  });

  const panel = document.createElement("div");
  Object.assign(panel.style, {
    width: "min(100%, 760px)",
    maxHeight: "85vh",
    overflow: "auto",
    padding: "24px",
    border: "1px solid #475569",
    borderRadius: "8px",
    background: "#1e293b",
    boxShadow: "0 20px 60px rgba(0, 0, 0, 0.45)",
  });

  const heading = document.createElement("h1");
  heading.textContent = title;
  Object.assign(heading.style, {
    margin: "0 0 12px",
    fontSize: "20px",
    lineHeight: "1.4",
  });

  const hint = document.createElement("p");
  hint.textContent = "请截图此页面并反馈。详细信息也已写入 LingChat 日志。";
  Object.assign(hint.style, {
    margin: "0 0 16px",
    color: "#cbd5e1",
    lineHeight: "1.6",
  });

  const detail = document.createElement("pre");
  detail.textContent = details;
  Object.assign(detail.style, {
    margin: "0",
    padding: "14px",
    overflow: "auto",
    whiteSpace: "pre-wrap",
    wordBreak: "break-word",
    borderRadius: "6px",
    background: "#020617",
    color: "#f8fafc",
    font: "13px/1.55 ui-monospace, monospace",
  });

  panel.append(heading, hint, detail);
  overlay.append(panel);
  document.body.append(overlay);

  void invoke("frontend_report_error", {
    level: "error",
    message: `[Startup] ${title}: ${details}`,
  }).catch(() => {});
}

window.addEventListener("error", (event) => {
  showStartupFailure("LingChat 前端发生错误", event.error ?? event.message);
});

window.addEventListener("unhandledrejection", (event) => {
  showStartupFailure("LingChat 前端异步任务失败", event.reason);
});

app.config.errorHandler = (error, _instance, info) => {
  showStartupFailure("LingChat 界面渲染失败", `${String(error)}\n组件阶段: ${info}`);
};

router.onError((error, to) => {
  showStartupFailure("LingChat 页面加载失败", `${String(error)}\n目标页面: ${to.fullPath}`);
});

initializeEventProcessors();

// 投屏窗口是独立 webview：不注册驱动事件队列的全局监听（ai:reply 等），
// 台词由主窗口镜像（cast:mirror）驱动，只注册投屏需要的即时状态事件。
const isCastWindow = new URLSearchParams(window.location.search).get("window") === "cast";
if (isCastWindow) {
  initializeCastWindowListeners();
} else {
  initializeTauriEventListeners();
}

app.use(pinia);
app.use(i18n);
app.use(router);

// 独立日志窗口：通过 index.html?window=log 打开时直接进入日志路由
if (new URLSearchParams(window.location.search).get("window") === "log") {
  router.replace("/log-window");
}

// 投屏窗口：通过 index.html?window=cast 打开时直接进入投屏路由
if (isCastWindow) {
  router.replace("/cast");
}

app.mount("#app");

void router.isReady().then(() => {
  if (router.currentRoute.value.path !== "/") return;
  window.setTimeout(() => {
    if (!document.querySelector("[data-lingchat-main-menu]")) {
      showStartupFailure(
        "LingChat 主界面未加载",
        `路由: ${router.currentRoute.value.fullPath}\n页面: ${window.location.href}`
      );
    }
  }, 8000);
});

// 初始化全局音频输出设备管理器（需 pinia 就绪）
initAudioOutputManager();

// 延迟执行 CPU+GPU 画质自适应，确保 pinia store 已就绪
setTimeout(autoConfigurePerformance, 1000);
