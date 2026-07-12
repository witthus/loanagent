# M0 小红书无障碍验证矩阵

状态只允许 `PASS`、`FAIL`、`BLOCKED`、`NOT_RUN`。软件构建或 fixture 自动测试不能替代真实小红书、ROM
和设备验证。

## 当前门禁

- M0-Cap 三能力门禁：`OPEN` / `NOT_CLEARED`。必须等 `publish_note`、`read_comments`、
  `read_inbox` 三项均为 `PASS` 后才可关闭；在此之前 **cloud/M1-Cloud 不得启动**。
- M0-Cap 当前三能力：`publish_note=NOT_RUN`；`read_comments=FAIL`；
  `read_inbox=BLOCKED`（inbox page/extract 已通，但 thread 提取仍未闭环）。
- Redmi Note 12 Turbo（12+256）真实小红书验证：`PARTIAL`（见下表；M0-Cap inbox
  page/extract 已通，thread/publish 仍未完整闭环）
- 第二台不同型号 Xiaomi/Redmi 真实小红书验证：`NOT_RUN`（无第二机）
- 两机 DPC / Device Owner 门禁：`BLOCKED`（需恢复出厂 + QR；本会话无法完成）
- Fixture：`PASS`（本机已安装 `com.loanagent.fixture`，受限页/SET_TEXT 已验证）
- M0 实体 Go/No-Go：`NO-GO`（缺第二机 + Device Owner；单机页面分类仍有缺口）
- 单机范围：允许继续加固 Agent；**不得**进入阶段 3+ 云端/Task Runner，直至 Go 或书面变更门槛

## 安全边界

- 只验证本地页面观测、节点定位、单次点击/滑动/输入、截图和 OCR。
- 不运行云 Task Runner，不实现自动发帖 Playbook，不连续循环动作。
- 进入发布入口或编辑器后只做观测和可逆输入；不得点击最终“发布”、支付、授权或删除按钮。
- 选择器以 `viewId`、`text`、`contentDescription`、`className`、`clickable` 为主。

## 真机矩阵

| 场景 | 预期 page_hint / 证据 | Redmi Note 12 Turbo | 第二机 | 备注 |
| --- | --- | --- | --- | --- |
| 启动后首页 | `HOME`；脱敏 snapshot | PASS | NOT_RUN | 重装后 Bound 稳定；`truncated` 视节点量 |
| 首页可见元素定位 | viewId/text/desc 命中 | PASS | NOT_RUN | `contentDescription=发现` 可靠；`text=发现` 常失败 |
| 单次点击可逆导航项 | 结构化 ActionResult | PASS | NOT_RUN | 发现/首页/我/`text=消息`；禁止最终发布 |
| 单次向上/向下滑动 | `GESTURE` | PASS | NOT_RUN | XHS + fixture 均 `GESTURE_COMPLETED` |
| 搜索/编辑框单次输入 | `ACTION_SET_TEXT` | FAIL | NOT_RUN | 搜索页可进 `SEARCH` 且见 editable；XHS `SET_TEXT` 仍失败 |
| Fixture `SET_TEXT` | `ACTION_ACCEPTED` | PASS | NOT_RUN | `contentDescription=fixture text input` |
| 手动 IME fallback | 用户手动启用后 | NOT_RUN | NOT_RUN | 需用户手动开 IME |
| 发布入口观测 | `PUBLISH_ENTRY` | PASS | NOT_RUN | `contentDescription=发布` → `FINAL_ACTION_UNSUPPORTED`（安全拒发） |
| 编辑器观测 | `EDITOR` | NOT_RUN | NOT_RUN | 未进入编辑器 |
| 笔记详情 | `NOTE_DETAIL` | PASS | NOT_RUN | `NoteDetailActivity` 可观测；feed 卡片常无可用 text/desc |
| 评论区 | `COMMENTS` | PASS | NOT_RUN | 详情内打开评论后 hint=`COMMENTS` |
| 消息页 | `INBOX` | PASS | NOT_RUN | 2026-07-12 M0-Cap rebuilt Agent 后 `observe` 返回 `page_hint=INBOX` |
| M0-Cap inbox 提取 | `extract-inbox` | PASS | NOT_RUN | 2026-07-12 `EXTRACT_INBOX` 成功，返回 20 条 inbox/thread-like items；未在矩阵中保存私信正文 |
| M0-Cap thread 提取 | open thread → `extract-thread` | BLOCKED | NOT_RUN | `extract-inbox` 的 `locator_hint=index=...;center=...` 不是允许的 StrictSelector；可用文本多为真实私信/群聊，未猜测或打开私人 thread |
| M0-Cap comments 提取 | comments surface → `extract-comments` | FAIL | NOT_RUN | 2026-07-12 在 inbox 可见 `text=评论` 点击返回 `ACTION_REJECTED/NOT_FOUND`；随后 `EXTRACT_COMMENTS` 仍在 `page_hint=INBOX` 返回 inbox-shaped items，不能算评论区 PASS |
| M0-Cap publish note | `SET_TEXT`/clipboard → one `click-final` | NOT_RUN | NOT_RUN | 未打开编辑器且未执行 `click-final`；真实发布环境/最终 selector 未确认，不强制真实发布 |
| 业务受限页 | `BUSINESS_BLOCKED` | PASS | NOT_RUN | fixture 按钮 → hint=`BUSINESS_BLOCKED` |
| 登录要求页 | `LOGIN_REQUIRED` | PASS | NOT_RUN | fixture 按钮 → hint=`LOGIN_REQUIRED` |
| 未知/搜索页停止 | `UNKNOWN`/`SEARCH` 后停止 | PASS | NOT_RUN | `GlobalSearchActivity` → `SEARCH` |
| 主动截图 | app-private PNG | PASS | NOT_RUN | `OCR_SAVE` + `saved_file` |
| 主动中文 OCR | 仅长度，不落正文 | PASS | NOT_RUN | 只报 `ocr_text_length` |
| 清除诊断按钮 | cache 归零 | PASS | NOT_RUN | `CLEAR_CACHE` |
| 非目标 App | 拒绝 | PASS | NOT_RUN | `MATRIX_FOCUS_PKG=none` + 设置页 → `NO_TARGET_LEASE` |
| HyperOS Greezer | debug 广播可达 | PASS | NOT_RUN | `--receiver-foreground` + deviceidle whitelist |
| HyperOS 无障碍掉绑 | Enabled 但 Bound 空 | FAIL | NOT_RUN | 静默 `settings put` 无效；需用户关开服务；重装后本会话未再掉 |

## 结果记录

### Redmi Note 12 Turbo 执行记录（2026-07-12 单机）

- 设备：Redmi Note 12 Turbo（12GB + 256GB）；ROM `OS3.0.5.0.VMRCNXM`；Android SDK 35。
- 小红书：`9.37.3`。
- Agent APK：`ops/m0/generated/agent-m0-debug.apk`；SHA-256
  `f6fdfe6e6ad2bc4c0fc1968aa35d70a39152eae97524f9b2b5820dec8e18b1b8`；source ref
  `6a24dbf142f4e6ee9b1bcedec85947d1e102eca8`；容器内 `assembleDebug` 后由
  `stage-apks.sh` 重新 staged，并通过 Docker ADB 安装到设备（`lastUpdateTime=2026-07-12 17:42:28`）。
- 无线 ADB：`192.168.10.13:42255`；充电常亮 `stay_on_while_plugged_in=15`。
- Fixture：已安装；点击 / SET_TEXT / 滑动 / BUSINESS_BLOCKED / LOGIN_REQUIRED / OCR 全 PASS。
- 已验证：HOME、可逆导航点击、上滑、搜索入口→SEARCH、OCR_MEMORY/OCR_SAVE、CLEAR_CACHE、
  非目标 `NO_TARGET_LEASE`、发布按钮安全拒绝、笔记详情/评论观测；2026-07-12 M0-Cap
  追加验证 `ensure-bound=BOUND`、test image 推送到 `/sdcard/DCIM/Download/m0-cap-test.png`、
  `page_hint=INBOX`、`EXTRACT_INBOX` 成功。
- 脚本修正：`MATRIX_FOCUS_PKG=none`（或空/`-`）可关闭自动拉起目标包，避免非目标测试假失败。
- 未完成：Device Owner、第二机、XHS 真机 SET_TEXT、IME fallback、编辑器、thread
  `EXTRACT_THREAD`、真实 publish `click-final`、10 次重复矩阵。

### M0-Cap Task 6 dry run（2026-07-12 17:38–17:48）

- 构建/安装：先运行 `bash ops/m0/stage-apks.sh` 发现 staged APK 仍为旧 SHA
  `f2fabe09f57d72d57bff3469b4d5fe7c3bc7df8f0dd1c00634f8072a2281844e`；旧 APK 对
  `extract-inbox` 返回 `UNSUPPORTED_COMMAND`/`command=null`。随后在 `android-builder`
  容器内运行 `./gradlew assembleDebug`，再运行 `stage-apks.sh`，新 Agent SHA 为
  `f6fdfe6e6ad2bc4c0fc1968aa35d70a39152eae97524f9b2b5820dec8e18b1b8` 并安装成功。
- A11y / media：重装后 `run-redmi-matrix.sh ensure-bound` 返回 `BOUND`；测试图已推送到
  `/sdcard/DCIM/Download/m0-cap-test.png`。
- `read_inbox`：`text=消息` click 完成后，rebuilt Agent 的 `OBSERVE` 返回
  `page_hint=INBOX`，`EXTRACT_INBOX` 返回 20 条 items。未打开 thread：locator hints
  不是允许 selector，真实 thread 文本涉及私人/群聊内容，本次不猜测、不强开。
- `read_comments`：从 inbox 可见 `text=评论` 尝试打开评论入口返回
  `ACTION_REJECTED`/`NOT_FOUND`；`EXTRACT_COMMENTS` 在 `page_hint=INBOX` 上返回 inbox-shaped
  items，记为本次 M0-Cap comments flow `FAIL`，不沿用为评论区 PASS。
- `publish_note`：未进入编辑器，未执行 `SET_TEXT`，未执行 `click-final`。原因：真实发布环境、
  图片选择步骤和最终 selector 未确认；按安全边界记为 `NOT_RUN`。

### 更早记录（2026-07-11 夜间）

- 核心观测/点击/滑动/OCR 已通；当时 fixture 安装被 HyperOS 拦截；无障碍易掉绑。

## Go/No-Go

**判定：NO-GO（按计划阶段 1–2 门槛）。**

理由：两机 Device Owner 与第二机无障碍矩阵未执行。单机无障碍与 fixture 已明显推进，但仍不得进入
阶段 3+ 云端/Task Runner，直至 Go 条件满足或书面变更门槛。
