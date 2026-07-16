# 新设备 ADB Device Owner 绑定指引（云端教程）

## 目标

用户可按运维台下载的 PDF /「遇到问题怎么办」从零准备一台新机，完成 Device Owner、禁锁屏、Agent 安装与账号绑定。

## 约束

- **仅 ADB 开通** Device Owner；当前机型（HyperOS）不支持扫码开通，教程不写 QR。
- 公网下载统一 `https://android.hashhub.com`。
- DPC 与 Agent 须同签名；侧载包为 `device-controller-latest.apk` / `agent-latest.apk`。

## 用户路径（摘要）

1. 恢复出厂、未登录账号、打开无线调试  
2. 安装 DPC → `dpm set-device-owner …LoanAgentDeviceAdminReceiver` → Apply policy（KEYGUARD_DISABLED）  
3. 安装 Agent 0.1.7+ → 无障碍 / IME / 电池 / 后台弹出界面  
4. 登录小红书 → 心跳出现 → 运维台创建并绑定  

## 交付

- 刷新服务器 `device-controller-latest.apk`  
- 重写 `device-bind-guide.pdf` 与 HelpView 文案  
- Ops「远程升级」页增加 DPC 下载入口  
