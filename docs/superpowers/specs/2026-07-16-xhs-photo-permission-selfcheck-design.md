# 矩阵助手：小红书相册权限检测 + 发布素材自检

Date: 2026-07-16  
Status: approved for implementation

## Goal

降低发布笔记失败 `MEDIA_MISSING`（文案：「相册里没选到素材，图片可能还没下载完，或点选位置不准确。」）中 **「图没进相册 / 小红书读不到相册」** 两类原因。在矩阵助手状态页：

1. 说明云端图由谁写入相册、小红书需要什么权限  
2. 被动检测小红书照片/相册权限  
3. 一键「发布素材自检」：用 APK 内置图写入 `DCIM/Camera` 并核对 MediaStore + 小红书权限  

## Non-goals

- 不打开小红书验证相册格子可见性（点选几何另案）  
- 不拆分/改写线上 `MEDIA_MISSING` 失败文案分型  
- 不做云端 URL 下载自检（与网络解耦）  
- 不要求矩阵助手申请 `READ_MEDIA_*`（MediaStore 写入路径不需要）  

## Publish media path (facts)

1. Debug 云桥任务带 `media_urls` → `MediaBridge` 下载到 agent cache  
2. **矩阵助手**（`com.loanagent.agent`）经 `ContentResolver` 插入 **MediaStore**，`RELATIVE_PATH = DCIM/Camera`  
3. 小红书（`com.xingin.xhs`）从系统相册读图；须具备照片/媒体读权限  

## UI

在现有「熄屏保活检查」卡与「执行就绪」行上扩展（方案 ①）：

| 元素 | 行为 |
|------|------|
| 灰色说明 | 「云端发布图由矩阵助手写入系统相册 DCIM/Camera；小红书需开启照片/相册权限才能选到。」 |
| Issue `XHS_PHOTO_DENIED` | 小红书已安装且未授予可读媒体权限时红字 +「去小红书权限」→ `ACTION_APPLICATION_DETAILS_SETTINGS`（`package:com.xingin.xhs`） |
| 执行就绪行 | 追加 `相册权限: 已开/未开/未装小红书` |
| 按钮「发布素材自检」 | 插入内置图 → 查 MediaStore → 查小红书权限 → Toast/文案结果；成功后删除测试行 |

## Permission probe

Cross-package `GET_PERMISSIONS` flags are unreliable on HyperOS/Android 14+ for reading another app's runtime grants (false negatives even when dumpsys shows `READ_MEDIA_IMAGES: granted=true`). Probe uses `PackageManager.checkPermission` plus AppOps fallback.

## Self-check

- 资源：`assets/selfcheck_media.png`（极小 PNG）  
- Display name：`la_selfcheck_{timestamp}.png`  
- 写入逻辑与 `MediaBridge.insertIntoMediaStore` 同路径（抽共享 helper 到 main，或自检内复刻同等 MediaStore 写入）  
- 通过条件：MediaStore 能按 display name 查到行 **且** 小红书权限探测为已开  
- 失败分项：`WRITE_FAILED` / `XHS_PHOTO_DENIED` / `XHS_NOT_INSTALLED`  

## Tests

- `KeepAliveHealthChecker`：未装 / 已装未授权 / 已装已授权  
- 纯函数 `XhsPhotoAccess.decide(grantedPermissions)`  
- MediaStore helper 可用 Robolectric 或抽纯路径单元测（至少 permission decide + checker）  

## Version / ship

- `versionCode` +1，`versionName` patch +1（当前 6 / 0.1.5 → 7 / 0.1.6）  
- `stage-apks.sh` → `publish-agent-release.sh` → 同步服务器 `agent-latest.apk` → 本机 ADB 安装  

## Ops

可选：`keep-alive-screen-check.sh` 增加小红书 `READ_MEDIA*` 授予探测一行（非阻塞）。
