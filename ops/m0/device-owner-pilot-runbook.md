# Device Owner 试点当日 Runbook（中文）

配套文档：

- 完整教程：`[device-owner-operator-guide.md](./device-owner-operator-guide.md)`
- HTTPS 边缘：`[japan-https-edge.md](./japan-https-edge.md)`
- 设计说明：`[2026-07-15-device-owner-wake-upgrade-design.md](../../docs/superpowers/specs/2026-07-15-device-owner-wake-upgrade-design.md)`

**当前生产 HTTPS：** `https://android.hashhub.com`（日本边缘 → 国内 control-plane）

**新机开通：** 仅 ADB Device Owner（不支持扫码）。用户教程：
`https://android.hashhub.com/downloads/device-bind-guide.pdf`

---

## 清机前检查

- [x] `curl -fsS https://android.hashhub.com/health` 返回 `{"status":"ok","service":"control-plane"}`
- [x] 国内 `.env` 已设 `PUBLIC_BASE_URL` / `HTTPS_PUBLIC_BASE_URL` = `https://android.hashhub.com`
- [x] DPC 与 Agent **同一签名证书**；已算好 QR 用的 `DPC_SIGNATURE_CHECKSUM`
- [x] 已签发一次性 enrollment token（文件权限 `0600`），并生成离线 `device-owner.png`
- [x] 试点手机可以恢复出厂；小红书账号密码已备好
- [x] QR 内域名均为 `android.hashhub.com`（禁止 IP）

信任主机（写进 QR）：


| 字段                           | 值                                                                    |
| ---------------------------- | -------------------------------------------------------------------- |
| `trusted_control_plane_host` | `android.hashhub.com`                                                |
| `trusted_update_host`        | `android.hashhub.com`                                                |
| Enrollment                   | `https://android.hashhub.com/enroll`                                 |
| DPC APK                      | `https://android.hashhub.com/downloads/device-controller-latest.apk` |
| Update manifest              | `https://android.hashhub.com/downloads/update-manifests/canary.json` |


---



## 开通过程

1. **恢复出厂** → 停在欢迎页（不要登小米账号，不要先进成普通用户）
2. 连上 **Wi‑Fi**
3. 欢迎页空白处连点约 **6 下** → 扫 `ops/m0/generated/device-owner.png`
4. 选 **完全托管 / Fully managed device**
5. 等 Device Controller 装完并合规
6. 打开 Device Controller：
  - Device Owner = true
  - 抄下 **Enrolled device_id**（远程升级推送用这个，不一定等于 Agent 的 `dev-…`）
  - 点「Apply minimum Device Owner policy」
  - Last recovery / policy 中应有 `KEYGUARD_DISABLED`
7. 安装同证书 Agent → 开无障碍 + Loanagent 输入法 + 电池无限制 → 登录小红书 → Ops 绑定
8. **HyperOS 必做（否则灭屏/后台无法拉起小红书）：** 允许 Agent「后台弹出界面」。ADB：

```bash
adb shell appops set com.loanagent.agent 10021 allow   # 后台弹出界面
adb shell appops set com.loanagent.agent 10020 allow
adb shell appops set com.loanagent.agent 10008 allow
```

也可在系统设置 → 应用 → Loanagent Agent → 其他权限里打开「后台弹出界面」。

**注意：** 不要对 Agent 使用 `force-stop`（会清掉无障碍）。

---



## 验收门禁


| 门禁   | 通过标准                                                      |
| ---- | --------------------------------------------------------- |
| 灭屏发布 | 灭屏 ≥5 分钟后发笔记成功，无 `SCREEN_NOT_READY`                       |
| 远程升级 | Ops「远程升级」页把 ring 推到 **enrolled** id → DPC 安装 → Agent 版本升高 |
| 重启恢复 | 重启后 Agent 自恢复 + 心跳正常                                      |


---



## 扩到整机队

**两台不同机型**都过上表后，再用同一 SOP 清其余矩阵机。  
单机型通过不得标「全队 GO」。

---



## 生成 / 刷新开通二维码（摘要）

详细命令见 operator guide 第 3 节。生产库签发 token 必须在国内机对真实 Postgres 执行；本机只做 provisioning 拼 QR 也可以，但 token 文件必须来自生产签发。

```bash
# 信任主机（生产）
export TRUSTED_CONTROL_PLANE_HOST=android.hashhub.com
export TRUSTED_UPDATE_HOST=android.hashhub.com
export CONTROL_PLANE_URL=https://android.hashhub.com/enroll
export DPC_APK_URL=https://android.hashhub.com/downloads/device-controller-latest.apk
export UPDATE_MANIFEST_URL=https://android.hashhub.com/downloads/update-manifests/canary.json
```

产物：

- `ops/m0/generated/enrollment-token`（`0600`，勿提交 git、勿贴到聊天）
- `ops/m0/generated/device-owner.json`（含 token，勿提交）
- `ops/m0/generated/device-owner.png`（用另一台手机/电脑打开扫；**不要**用在线 QR 网站重编码）

Token 过期后需重新 `enrollment issue` + 重新生成 PNG。

## 本次已生成（2026-07-15）


| 产物                    | 路径                                                |
| --------------------- | ------------------------------------------------- |
| 开通二维码 PNG             | `ops/m0/generated/device-owner.png`               |
| Provisioning JSON     | `ops/m0/generated/device-owner.json`（含 token，勿外传） |
| Enrollment token      | `ops/m0/generated/enrollment-token`（`0600`）       |
| Update 私钥（签 manifest） | `~/loanagent-keys/update-private.pem`（勿提交 git）    |


- Token TTL：**24 小时**（约至 2026-07-16 23:05 +08）
- 用另一台手机/电脑打开 PNG 扫；不要用在线 QR 网站重编码
- DPC 下载已通：`https://android.hashhub.com/downloads/device-controller-latest.apk`

### 安装 Agent（勿用旧包）

只装：

- `ops/m0/generated/agent-m0-debug.apk`（先 `bash ops/m0/stage-apks.sh`）

不要装：

- `ops/m0/generated/agent-debug.apk`（已废弃；`stage-apks.sh` 会删除）
- 任何未核对 `provenance.txt` 里 `agent_version_name` / `agent_version_code` 的 APK

当前应看到 `agent_version_name=0.1.7`、`agent_version_code=8`。发布到服务器用 `bash ops/m0/publish-agent-release.sh`。

