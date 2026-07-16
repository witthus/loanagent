# Device Owner 开通风 + 远程升级操作教程

面向当前环境：`119.45.36.208`（`/opt/loanagent`）、自研 DPC `com.loanagent.devicecontroller`、ops「远程升级」页。

**重要澄清：** 这里说的「刷机」是指 **恢复出厂设置到欢迎页**，不是刷第三方 ROM。小米/HyperOS 一般 **不要** 刷第三方系统，否则兼容性与账号风险更大。

---

## 0. 先搞清三件事

| 事项 | 说明 |
|------|------|
| 必须 HTTPS + **域名** | DPC 拒绝明文 HTTP，也拒绝 IP 字面量 URL。`http://119.45.36.208` **不能**用于 enrollment / update-manifest / APK 下载。 |
| DPC 与 Agent 同签名证书 | 调试可用同一 debug keystore；生产用企业证书。`RECOVER_AGENT` 是 signature 权限。 |
| 升级推送用 enrolled id | Device Controller 界面上的 `Enrolled device_id`，不一定等于 Agent 心跳的 `dev-…`。 |

当前生产已切到 **`https://android.hashhub.com`**（日本 Option A 边缘）。Enrollment / APK / update-manifest 均用该域名，不要再用 `http://119.45.36.208`。

---

## 1. 配置 HTTPS（必须先做）

### 1.0 本项目选定：日本机免备案边缘（推荐）

已验证可 SSH：`root@101.32.103.167`（密钥与国内机相同）。该机跑 Surge（`trojan-go:443` + Snell），**443 已被占用**，但 Trojan 非协议流量会回落到本机 `nginx:80`，可用 **SNI 分流** 在不影响 Surge 的前提下提供 loanagent HTTPS。

永久运维说明见：**[`japan-https-edge.md`](./japan-https-edge.md)**（架构、端口、Option A/B、切流清单）。

未备案域名请 **A 记录指向日本 IP**，不要指向 `119.45.36.208`。

### 1.1 备选：已备案域名直连国内机

1. 准备一个 **已备案** 域名，例如 `cp.example.com`，A 记录指向 `119.45.36.208`。
2. 在国内机用 Let’s Encrypt + Nginx/Caddy 反代到 control-plane。
3. 反代到本机 `127.0.0.1:80`（或 compose 只绑本机端口，由反代对外 443）。

在 `/opt/loanagent/.env` 增加并重启 control-plane：

```bash
PUBLIC_BASE_URL=https://cp.example.com
HTTPS_PUBLIC_BASE_URL=https://cp.example.com
```

```bash
cd /opt/loanagent
sudo docker compose --env-file .env -f infra/compose.server.yaml up -d --no-deps control-plane
```

此后：

- Enrollment：`https://cp.example.com/enroll`
- 侧载 APK：`https://cp.example.com/downloads/agent-latest.apk`
- DO manifest：`https://cp.example.com/downloads/update-manifests/canary.json`

`trusted_control_plane_host` / `trusted_update_host` 都填 **`cp.example.com`**（不要填 IP）。

### 1.2 临时替代：对象存储 HTTPS（仅托管 APK/manifest）

若短期没有域名反代：

- APK 与签名 manifest 放到 **带域名的 HTTPS** 对象存储（COS/OSS + CDN 域名）。
- Enrollment 仍需要 **HTTPS `/enroll`**（控制面本身也必须 HTTPS），否则扫码开通会失败。
- 因此：**没有 HTTPS 控制面就不要开始清机。**

---

## 2. 准备密钥与 APK

### 2.1 构建 DPC + Agent（本机 Docker）

```bash
cd /Users/witthu/Desktop/workspace/loanagent
bash ops/m0/stage-apks.sh
```

产物在 `ops/m0/generated/`：

- `device-controller-debug.apk`（DPC）
- `agent-m0-debug.apk`（Agent）
- `signing-certificates.sha256`（证书指纹）
- `provenance.txt`

确认两行证书 SHA-256 **相同**（同签名）。

### 2.2 计算 QR 用的 signature checksum

Android 开通二维码要的是：**签名证书 SHA-256 原始 32 字节 → URL-safe Base64（无 padding）**。

`signing-certificates.sha256` 里是 hex。转换示例（本机）：

```bash
# 取 DPC 那一行的 hex，转 unpadded urlsafe base64
DPC_CERT_HEX=$(awk '/device-controller-debug.apk/{print $1}' ops/m0/generated/signing-certificates.sha256)
python3 - <<PY
import base64, binascii, os
h=os.environ["DPC_CERT_HEX"]
print(base64.urlsafe_b64encode(binascii.unhexlify(h)).decode().rstrip("="))
PY
```

记下为 `DPC_SIGNATURE_CHECKSUM`。

### 2.3 上传 DPC/Agent 到 HTTPS 可下载位置

任选其一：

**A. 控制面 agent-releases（需 HTTPS 域名已通）**

```bash
# 侧载 latest（非 DO 通道）
scp -i ~/.ssh/id_ed25519_witt -o IdentitiesOnly=yes \
  ops/m0/generated/agent-m0-debug.apk ubuntu@119.45.36.208:/opt/loanagent/agent-releases/agent-latest.apk

# DO 开通时系统要能下 DPC：可放到同一目录或对象存储
scp -i ~/.ssh/id_ed25519_witt -o IdentitiesOnly=yes \
  ops/m0/generated/device-controller-debug.apk \
  ubuntu@119.45.36.208:/opt/loanagent/agent-releases/device-controller-latest.apk
```

若 compose 只挂载了 `agent-releases` 且没有对应 download 路由给 DPC，请把 DPC 放到你已配置的 HTTPS 静态路径，记下：

`DPC_APK_URL=https://cp.example.com/.../device-controller-latest.apk`

**B. 对象存储 HTTPS URL**（同样必须是域名，非 IP）。

### 2.4 生成 update-manifest 用的 ECDSA P-256 密钥对

在本机安全目录（勿提交 git）：

```bash
mkdir -p ~/loanagent-keys && cd ~/loanagent-keys
openssl ecparam -name prime256v1 -genkey -noout -out update-private.pem
openssl ec -in update-private.pem -pubout -outform DER -out update-public.der
UPDATE_PUBLIC_KEY_DER_BASE64=$(base64 < update-public.der | tr -d '\n')
UPDATE_KEY_ID=m0-key-1
echo "$UPDATE_PUBLIC_KEY_DER_BASE64"
```

`UPDATE_PUBLIC_KEY_DER_BASE64` 会写进开通二维码；私钥只用于离线签 manifest。

---

## 3. 生成开通二维码（清机前）

在本机（需能连到服务器 Postgres，或在服务器上用 tools 容器对同一 DB）：

```bash
export ENROLLMENT_TTL_SECONDS=3600
export TRUSTED_CONTROL_PLANE_HOST=cp.example.com
export TRUSTED_UPDATE_HOST=cp.example.com   # 若 APK/manifest 同域可相同
export CONTROL_PLANE_URL=https://cp.example.com/enroll
export DPC_APK_URL=https://cp.example.com/downloads/...或你的HTTPS_DPC地址
export DPC_SIGNATURE_CHECKSUM='上一节算出的值'
export UPDATE_MANIFEST_URL=https://cp.example.com/downloads/update-manifests/canary.json
export UPDATE_KEY_ID=m0-key-1
export UPDATE_PUBLIC_KEY_DER_BASE64='上一节 base64'

# 发 token（路径按你实际挂载调整；服务器上 DATABASE_URL 已在 .env）
docker compose -f infra/compose.yaml --profile tools run --rm \
  control-plane-tools uv run --frozen python -m loanagent.enrollment issue \
  --ttl-seconds "$ENROLLMENT_TTL_SECONDS" \
  --output /workspace/ops/m0/generated/enrollment-token

chmod 600 ops/m0/generated/enrollment-token

docker compose -f infra/compose.yaml --profile tools run --rm --no-deps \
  control-plane-tools uv run --frozen python -m loanagent.provisioning \
  --apk-url "$DPC_APK_URL" \
  --signature-checksum "$DPC_SIGNATURE_CHECKSUM" \
  --enrollment-token-file /workspace/ops/m0/generated/enrollment-token \
  --control-plane-url "$CONTROL_PLANE_URL" \
  --trusted-control-plane-host "$TRUSTED_CONTROL_PLANE_HOST" \
  --update-manifest-url "$UPDATE_MANIFEST_URL" \
  --update-key-id "$UPDATE_KEY_ID" \
  --update-public-key "$UPDATE_PUBLIC_KEY_DER_BASE64" \
  --trusted-update-host "$TRUSTED_UPDATE_HOST" \
  --json-output /workspace/ops/m0/generated/device-owner.json \
  --png-output /workspace/ops/m0/generated/device-owner.png
```

用另一台手机或电脑打开 `ops/m0/generated/device-owner.png` 备扫。**不要**用在线 QR 网站重新生成。

若 enrollment 在服务器 Postgres，请在服务器上对 `/opt/loanagent` 的 compose 执行同等命令，并保证 `DATABASE_URL` 指向生产库。

---

## 4. ADB 设为 Device Owner（当前机型不支持扫码）

HyperOS / 当前试点机型**不支持**欢迎页扫码开通。用户侧完整步骤见运维台 PDF：
`https://android.hashhub.com/downloads/device-bind-guide.pdf`。

运维摘要：

1. 恢复出厂，**不要**登录小米账号；打开 USB/无线调试。  
2. 用 Docker ADB 脚本安装 DPC 并设为 Device Owner（推荐）：

```bash
# 无线调试（配对码单独参数，勿跟在 --pair 后面）
bash ops/m0/set-device-owner.sh \
  --pair 192.168.10.18:40271 \
  --pair-code 822087 \
  --connect 192.168.10.18:40129 \
  --install-dpc

# USB 已连上时
bash ops/m0/set-device-owner.sh --install-dpc
```

脚本在宿主会自动 `docker compose … android-builder` 跑 adb；组件为  
`com.loanagent.devicecontroller/...LoanAgentDeviceAdminReceiver`。

3. 打开 Device Controller → Device Owner = true →「Apply minimum Device Owner policy」→ **Last recovery** 确认含 `KEYGUARD_DISABLED`（在 `applied=[…]` 内）。  
4. 再装同证书 Agent，开无障碍 / IME / 电池 / HyperOS「后台弹出界面」，登录小红书，Ops 绑定。

### 4.1 首次开通踩坑 checklist

| 现象 | 原因 / 处理 |
|------|-------------|
| `Calling identity is not authorized` | 未开「USB 调试（安全设置）」→ 开发者选项打开并确认 |
| `already some accounts on the device` | 仍有小米/谷歌账号 → 设置里退出删除，或再清机且欢迎页不登录 |
| `device owner … is already set` | 本 DPC 已是 Owner → 视为成功，去点 Apply policy |
| 界面 Device Owner = false | 看错机，或 set-device-owner 未成功 → `adb shell dpm list-owners` 核对 |
| 看不到 KEYGUARD_DISABLED | 未点 Apply policy；看 **Last recovery** 的 `applied=[…]`，不是单独标题 |
| 无线 pair/connect 失败 | 配对端口 ≠ 调试端口；配对码用 `--pair-code`；端口变更需重新 pair |
| 远程升级无反应 | 推送到 enrolled id，不是 Agent `dev-…`；ADB-DO 机还需 DPC 内有 update 公钥配置 |

（历史扫码流程已停用，勿再生成 enrollment QR 给用户。）

---

## 5. 装 Agent 并接到云端

1. 安装与 DPC **同证书** 的 Agent APK（可从 ops「远程升级」页下侧载包，或 `adb install`）。  
2. 开启：无障碍、Loanagent 输入法、电池无限制 / 忽略优化、自启动（按机型）。
3. HyperOS：允许「后台弹出界面」（设置 → 应用 → Loanagent Agent → 其他权限），或 ADB：
   `appops set com.loanagent.agent 10021 allow`（建议同时 `10020` / `10008`）。未开时灭屏任务会亮屏但拉不起小红书，最终 `EXECUTION_TIMEOUT` / `XHS_NOT_FOREGROUND`。
4. **不要**对 Agent 使用 `force-stop`（易清掉无障碍）。
5. 登录小红书。  
6. Ops → 设备：等心跳出现 → 绑定账号。  
7. 灭屏 ≥5 分钟 → 发一篇测试笔记：不得出现 `SCREEN_NOT_READY`。

---

## 6. 服务器上测试「远程升级推送」

### 6.1 准备更高 versionCode 的 Agent

重新 `assembleDebug` 前把 `android/agent` 的 `versionCode` 调高，再 `stage-apks.sh`。  
APK 放到 HTTPS：

```bash
# 示例：放到 agent-releases，并由静态/下载路由暴露为 https://cp.example.com/.../agent-canary.apk
scp ... agent-m0-debug.apk ubuntu@119.45.36.208:/opt/loanagent/agent-releases/agent-canary.apk
```

记下：

- `APK_URL=https://cp.example.com/.../agent-canary.apk`  
- `APK_SHA256=`（文件 sha256 hex）  
- `APK_SIZE=`（字节数）  
- `AGENT_VERSION` / `MANIFEST_VERSION`（语义版本，且 manifest_version 要高于设备已成功装过的）

### 6.2 构造并签名 update-manifest

Wire 格式（仓库 schema）用 **`artifacts` 数组**；DPC 验签的 **canonical 字节**用单数 **`artifact`** 对象（顺序固定，见 checklist）。

实用做法：

1. 按 DPC `canonicalPayload()` 拼出 **无 `signature.value`** 的 canonical JSON，ECDSA-SHA256 签名，得到 DER → 标准 Base64。  
2. 发布用的文件仍按 schema 写成带 `artifacts:[{...}]` 与 `signature.value` 的 JSON。

OpenSSL 签名示例（对 canonical 文件）：

```bash
# canonical.json = 上一节规则生成的 UTF-8 字节（无 signature.value）
openssl dgst -sha256 -sign ~/loanagent-keys/update-private.pem -out /tmp/sig.der canonical.json
SIGNATURE_VALUE=$(base64 < /tmp/sig.der | tr -d '\n')
```

把 `SIGNATURE_VALUE` 填进完整 manifest，例如：

```json
{
  "schema_version": "1.0",
  "manifest_version": "0.2.0",
  "agent_version": "0.2.0",
  "minimum_agent_version": "0.1.0",
  "rollout_ring": "canary",
  "artifacts": [
    {
      "name": "agent.apk",
      "url": "https://cp.example.com/.../agent-canary.apk",
      "sha256": "<64位hex>",
      "size_bytes": 12345678
    }
  ],
  "issued_at": "2026-07-15T14:00:00Z",
  "signature": {
    "algorithm": "ECDSA-P256-SHA256",
    "key_id": "m0-key-1",
    "value": "<base64 DER>"
  }
}
```

`issued_at` 须在约 24 小时内。

### 6.3 发布到服务器

**方式 A — Ops 页面**

1. 打开 http(s)://你的域名/upgrades  
2. 「发布已签名 JSON」：选 ring=`canary`，粘贴 JSON → 发布  
3. 确认 ring 表显示「已发布」，公开链接可打开  

**方式 B — 脚本**

```bash
ops/m0/publish-update-manifest.sh canary /path/to/signed-canary.json
# 再把生成的 json scp 到服务器
# /opt/loanagent/agent-releases/update-manifests/canary.json
```

### 6.4 推送到试点机

1. `/upgrades` → 勾选 **Enrolled device_id** 那一行（或单台「推送」）  
2. 目标 ring = `canary`  
3. 手机打开 Device Controller → **Check remote Agent upgrade**（或等约 15 分钟 Job）  
4. 看 Last install / Last upgrade poll；Agent `versionName`/`versionCode` 上升  
5. 重启手机 → Agent 被 DPC recover → 心跳恢复  

失败时优先查：

- manifest / APK URL 是否 HTTPS 域名  
- `.env` 的 `HTTPS_PUBLIC_BASE_URL` 是否与拼出的 URL 一致  
- `versionCode` 是否上升、证书是否与 DPC 相同  
- `issued_at` / 签名 / `key_id` 是否匹配开通时写入的公钥  

---

## 7. 建议当天验收清单

| 步骤 | 通过标准 |
|------|----------|
| HTTPS | 手机浏览器能打开 `https://域名/health` |
| DO | `dpm list-owners` 有本 DPC；策略含 KEYGUARD_DISABLED |
| 灭屏任务 | 灭屏 ≥5min 发帖成功 |
| 远程升级 | Ops 推送 → DPC 安装成功 → 版本升高 |
| 重启 | 开机后 Agent 恢复 + 心跳 |

两台不同型号都 PASS 后再全量清机扩容（见 `device-owner-checklist.md` Fleet rollout）。

---

## 8. 常见坑

1. **只用 IP / 只用 HTTP** → DPC 直接拒。  
2. **先登录小米再想设 DO** → 必须再清机。  
3. **推送到 Agent 的 `dev-…`** → 可能对不上 DPC 轮询 id；用 enrolled id。  
4. **force-stop Agent** → 无障碍丢失，无线 ADB 难自动恢复。  
5. **禁锁屏后手机无密码** → 物理保管好矩阵机。  
6. **侧载 latest.json ≠ DO manifest** → 远程升级必须走签名 update-manifest。

---

## 9. 相关文件

- 当日短单：[`device-owner-pilot-runbook.md`](./device-owner-pilot-runbook.md)  
- 门禁与 QR 命令：[`device-owner-checklist.md`](./device-owner-checklist.md)  
- 设计：[`docs/superpowers/specs/2026-07-15-device-owner-wake-upgrade-design.md`](../../docs/superpowers/specs/2026-07-15-device-owner-wake-upgrade-design.md)  
- Ops：`/upgrades`  
