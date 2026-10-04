# 补丁 3b：src-tauri/src/ai_service/tools/mod.rs
# 主聊天路径的 execute_command（ExecuteCommand）也被 #[cfg(desktop)] 门控，
# 需删除 2 处，使该工具在 Android 上注册。

## 3b-1 第 39 行（use）
### 查找：
#[cfg(desktop)]
use skill_files::ExecuteCommand;
### 替换为：
use skill_files::ExecuteCommand;

## 3b-2 第 128-131 行（注册）
### 查找：
    // Android/iOS 没有稳定、可审批的桌面 shell 环境。移动端不注册命令工具，
    // 避免模型选中 execute_command 后才得到系统级执行失败。
    #[cfg(desktop)]
    registry.register(Arc::new(ExecuteCommand::new(tool_settings.clone())))?;
### 替换为：
    // Android 上经 Shizuku（uid=2000 shell）执行；桌面走本地进程。
    registry.register(Arc::new(ExecuteCommand::new(tool_settings.clone())))?;

# 说明：skill_files.rs 内 ExecuteCommand 自带 #[cfg_attr(not(desktop), allow(dead_code))]，
# 移除注册门控后移动端可正常编译。执行仍统一走 command_executor.rs（补丁 4）。
