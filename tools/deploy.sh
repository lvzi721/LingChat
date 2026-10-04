#!/usr/bin/env bash
# LingChat + Shizuku 一键部署脚本
# 用法：在能连 GitHub 的电脑上执行  bash deploy.sh
# 前置：git 已配置好对 fork 仓库的 push 权限（SSH key 或 PAT）
set -euo pipefail

REPO_URL="${1:-git@github.com:lvzi721/LingChat.git}"   # 可用第 1 参数覆盖
WORKDIR="${2:-./LingChat-build}"
HERE="$(cd "$(dirname "$0")/.." && pwd)"              # patch 根目录

echo ">> clone fork: $REPO_URL"
rm -rf "$WORKDIR"
git clone "$REPO_URL" "$WORKDIR"
cd "$WORKDIR"

echo ">> 铺插件与 workflow"
mkdir -p src-tauri/plugins .github/workflows
cp -r "$HERE/src-tauri/plugins/tauri-plugin-shizuku" src-tauri/plugins/
cp "$HERE/.github/workflows/build-android.yml" .github/workflows/
cp "$HERE/.github/workflows/gen-keystore.yml"  .github/workflows/

echo ">> 应用 5 处主工程补丁"
python3 "$HERE/tools/apply_patches.py" .

echo ">> 提交并推送"
git add -A
git commit -m "feat(android): integrate Shizuku plugin for shell execution"
git push origin HEAD
echo ">> 完成。请到 GitHub Actions 依次运行 gen-keystore 与 build-android。"
