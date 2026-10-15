/**
 * 准备「锁屏本地唤醒」所需的引擎与模型文件。
 *
 * 产出（都在 tauri-plugin-voice-wake 插件内，均被 .gitignore 忽略）：
 *   android/libs/sherpa-onnx.jar                 引擎 Java/Kotlin API
 *   android/src/main/jniLibs/arm64-v8a/*.so      仅 arm64 原生库
 *   android/src/main/assets/kws/*                中文唤醒词模型与词表
 *
 * 只保留 arm64-v8a：官方 AAR 内含 4 个 ABI（合计约 78MB），而本项目 APK
 * 仅构建 aarch64，其余 ABI 只会白白撑大体积。
 */
import { spawn } from "node:child_process";
import { createWriteStream } from "node:fs";
import { copyFile, mkdir, readdir, rm, stat } from "node:fs/promises";
import { pipeline } from "node:stream/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

import sevenZip from "7zip-min";

const projectRoot = path.resolve(fileURLToPath(new URL("..", import.meta.url)));
const pluginRoot = path.join(
  projectRoot,
  "src-tauri",
  "plugins",
  "tauri-plugin-voice-wake",
  "android"
);
const cacheDir = path.join(
  projectRoot,
  "src-tauri",
  "plugins",
  "tauri-plugin-voice-wake",
  ".deps-cache"
);

const SHERPA_VERSION = "1.13.8";
const AAR_URL = `https://github.com/k2-fsa/sherpa-onnx/releases/download/v${SHERPA_VERSION}/sherpa-onnx-${SHERPA_VERSION}.aar`;

const MODEL_NAME = "sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01";
const MODEL_URL = `https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/${MODEL_NAME}.tar.bz2`;
const MODEL_FILES = [
  "encoder-epoch-12-avg-2-chunk-16-left-64.onnx",
  "decoder-epoch-12-avg-2-chunk-16-left-64.onnx",
  "joiner-epoch-12-avg-2-chunk-16-left-64.onnx",
  "tokens.txt",
  "keywords.txt",
];

const force = process.argv.includes("--force");

async function exists(p, minBytes = 1) {
  try {
    const s = await stat(p);
    return s.isFile() && s.size >= minBytes;
  } catch {
    return false;
  }
}

async function download(url, dest) {
  if (!force && (await exists(dest, 1024))) {
    console.log(`[deps] 复用缓存 ${path.basename(dest)}`);
    return dest;
  }
  await mkdir(path.dirname(dest), { recursive: true });
  console.log(`[deps] 下载 ${url}`);
  try {
    const res = await fetch(url, { redirect: "follow" });
    if (!res.ok || !res.body) {
      throw new Error(`HTTP ${res.status}`);
    }
    await pipeline(res.body, createWriteStream(dest));
  } catch (error) {
    // 某些网络环境下 Node 的 DNS 结果不可达；curl 走系统解析，作为兜底。
    console.warn(`[deps] fetch 失败（${error.message}），改用 curl`);
    await rm(dest, { force: true });
    await downloadWithCurl(url, dest);
  }
  const s = await stat(dest);
  console.log(`[deps] 已下载 ${path.basename(dest)} (${(s.size / 1048576).toFixed(1)} MB)`);
  return dest;
}

function downloadWithCurl(url, dest) {
  const curl = process.platform === "win32" ? "curl.exe" : "curl";
  return new Promise((resolve, reject) => {
    const child = spawn(curl, ["-L", "--fail", "--silent", "--show-error", "-o", dest, url], {
      stdio: ["ignore", "ignore", "inherit"],
    });
    child.on("error", reject);
    child.on("exit", (code) => {
      if (code === 0) resolve();
      else reject(new Error(`curl 退出码 ${code}`));
    });
  });
}

/**
 * 7za 对 .tar.bz2 只解开外层压缩，产物是 .tar，这里继续解第二层。
 */
async function unpackAll(archive, dest) {
  await sevenZip.unpack(archive, dest);
  const entries = await readdir(dest, { withFileTypes: true });
  for (const entry of entries) {
    if (entry.isFile() && entry.name.endsWith(".tar")) {
      const inner = path.join(dest, entry.name);
      await sevenZip.unpack(inner, dest);
      await rm(inner, { force: true });
    }
  }
}

async function main() {
  const libsDir = path.join(pluginRoot, "libs");
  const jniDir = path.join(pluginRoot, "src", "main", "jniLibs", "arm64-v8a");
  const assetsDir = path.join(pluginRoot, "src", "main", "assets", "kws");

  const needEngine =
    force ||
    !(await exists(path.join(libsDir, "sherpa-onnx.jar"), 10240)) ||
    !(await exists(path.join(jniDir, "libsherpa-onnx-jni.so"), 1024));
  const needModel =
    force ||
    !(await Promise.all(MODEL_FILES.map((n) => exists(path.join(assetsDir, n), 1024)))).every(
      Boolean
    );

  await mkdir(cacheDir, { recursive: true });

  if (needEngine) {
    const aar = await download(AAR_URL, path.join(cacheDir, `sherpa-onnx-${SHERPA_VERSION}.aar`));
    const unpackDir = path.join(cacheDir, "aar");
    if (force) await rm(unpackDir, { recursive: true, force: true });
    await mkdir(unpackDir, { recursive: true });
    console.log("[deps] 解包引擎");
    await unpackAll(aar, unpackDir);

    await mkdir(libsDir, { recursive: true });
    await copyFile(path.join(unpackDir, "classes.jar"), path.join(libsDir, "sherpa-onnx.jar"));

    await mkdir(jniDir, { recursive: true });
    const soSrc = path.join(unpackDir, "jni", "arm64-v8a");
    for (const name of await readdir(soSrc)) {
      if (name.endsWith(".so")) {
        await copyFile(path.join(soSrc, name), path.join(jniDir, name));
      }
    }
    console.log(`[deps] 引擎就绪：${SHERPA_VERSION}（仅 arm64-v8a）`);
  } else {
    console.log("[deps] 引擎已存在，跳过");
  }

  if (needModel) {
    const tar = await download(MODEL_URL, path.join(cacheDir, `${MODEL_NAME}.tar.bz2`));
    const unpackDir = path.join(cacheDir, "model");
    if (force) await rm(unpackDir, { recursive: true, force: true });
    await mkdir(unpackDir, { recursive: true });
    console.log("[deps] 解包模型");
    await unpackAll(tar, unpackDir);

    const modelDir = path.join(unpackDir, MODEL_NAME);
    await mkdir(assetsDir, { recursive: true });
    for (const name of MODEL_FILES) {
      await copyFile(path.join(modelDir, name), path.join(assetsDir, name));
    }
    console.log(`[deps] 模型就绪：${MODEL_NAME}`);
  } else {
    console.log("[deps] 模型已存在，跳过");
  }

  const total = (
    await Promise.all(
      [
        ...MODEL_FILES.map((n) => path.join(assetsDir, n)),
        path.join(libsDir, "sherpa-onnx.jar"),
      ].map(async (p) => (await stat(p)).size)
    )
  ).reduce((a, b) => a + b, 0);
  console.log(`[deps] 完成，资源合计 ${(total / 1048576).toFixed(1)} MB`);
}

main().catch((error) => {
  console.error("[deps] 失败：", error);
  process.exit(1);
});
