# M0 小红书无障碍验证矩阵

状态只允许 `PASS`、`FAIL`、`BLOCKED`、`NOT_RUN`。软件构建或 fixture 自动测试不能替代真实小红书、ROM
和设备验证。

## 当前门禁

- M0-Cap 三能力门禁：`CLEARED`（2026-07-12 19:42 单机证据齐全）。`publish_note` /
  `read_comments` / `read_inbox`（含 1:1 thread）均为 `PASS`。**云端/M1-Cloud 仍须书面启动**，
  本矩阵清门禁不等于自动开云。
- M0-Cap 当前三能力：`publish_note=PASS`；`read_comments=PASS`；`read_inbox=PASS`
  （列表 + `ChatActivity` 上 `EXTRACT_THREAD`）。
- Redmi Note 12 Turbo（12+256）真实小红书验证：`PASS`（M0-Cap 三能力闭环；见下表）
- 第二台不同型号 Xiaomi/Redmi 真实小红书验证：`NOT_RUN`（无第二机）
- 两机 DPC / Device Owner 门禁：`BLOCKED`（产品已延后 DO/MDM；非 M0-Cap 阻塞项）
- Fixture：`PASS`（本机已安装 `com.loanagent.fixture`，受限页/SET_TEXT 已验证）
- M0 实体 Go/No-Go：`CONDITIONAL-GO`（M0-Cap 单机已通；第二机/DO 仍缺，不阻塞角色漏斗下一阶段的书面启动）
- 单机范围：允许继续加固 Agent；进入云端前需书面确认门槛变更

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
| 编辑器观测 | `EDITOR` | PASS | NOT_RUN | `CapaPostNotePlatformActivity`；hint 匹配 `添加标题`/`添加正文` |
| 笔记详情 | `NOTE_DETAIL` | PASS | NOT_RUN | `NoteDetailActivity` 可观测；feed 卡片常无可用 text/desc |
| 评论区 | `COMMENTS` | PASS | NOT_RUN | 详情内打开评论后 hint=`COMMENTS`；笔记页也可 `EXTRACT_COMMENTS` |
| 消息页 | `INBOX` | PASS | NOT_RUN | 2026-07-12 M0-Cap rebuilt Agent 后 `observe` 返回 `page_hint=INBOX` |
| M0-Cap inbox 提取 | `extract-inbox` | PASS | NOT_RUN | 列表可抽；消息页偶发 `UNKNOWN`/活动噪声，需点进会话验证 |
| M0-Cap thread 提取 | open thread → `extract-thread` | PASS | NOT_RUN | 2026-07-12 19:42：`ChatActivity` + `EXTRACT_THREAD` count=6，含 `你好茶叶不错` |
| M0-Cap comments 提取 | comments surface → `extract-comments` | PASS | NOT_RUN | 2026-07-12 19:42：笔记 `曦瓜大红袍` 上 count=10；含好友「首评」；有 chrome 噪声 |
| M0-Cap publish note | `SET_TEXT` → one `click-final` | PASS | NOT_RUN | 2026-07-12：盖碗茶图 + 品鉴正文 + `click-final`；标记 `M0CAP-193716` |
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
- 未完成：第二机、IME fallback、10 次重复矩阵；DO/MDM 已按产品延后。

### M0-Cap 闭环（2026-07-12 19:30–19:42）

- 用户授权真实发布；相册第一格曾为全黑坏媒体，改为推送盖碗茶图至
  `DCIM/Camera/IMG_M0CAP_DAHONGPAO.jpg` 后选第一格发布。
- `publish_note`：标题 `曦瓜大红袍｜岩茶品鉴手记`；正文含岩韵品鉴 + 标记
  `M0CAP-193716`；`SET_TEXT`（hint=`添加标题`/`添加正文`）+ `text=发布笔记`
  `allow_final_action` → Index。
- `read_comments`：个人页打开该笔记 → `EXTRACT_COMMENTS` status=SUCCESS count=10；
  可见好友「静生百慧茶叶馆」与「首评」；含页面 chrome 噪声。
- `read_inbox`：消息 Tab 可见会话；点进 `ChatActivity` → `EXTRACT_THREAD`
  status=SUCCESS count=6；含 `你好茶叶不错`（19:42）。`page_hint` 在会话页仍可能
  `UNKNOWN`，但结构化抽取成立。
- M0-Cap 三能力门禁记为 `CLEARED`。

- M0-Cap 闭环后追加：`reply_dm` / `reply_comment`（2026-07-12 19:46–19:52）
  - 私信：`ChatActivity` 对「你好茶叶不错」回复
    `谢谢认可，这泡曦瓜大红袍岩韵和回甘都比较稳，有兴趣可再交流品鉴感受。`；
    `SET_TEXT` hint=`发消息…` + `text=发送` `allow_final_action`；`EXTRACT_THREAD` 可见该条。
  - 评论：评论面板 hint=`让大家听到你的声音`；点「回复」进入
    `NoteCommentActivity` hint=`回复 @静生百慧茶叶馆：`；`SET_TEXT` + `发送` 成功；
    UI 可见「感谢首评…岩韵…回甘…」。

- 代码修复（2026-07-12 19:54+，待重装 Agent 后复验）：
  - 评论/私信抽取过滤 XHS chrome（首评/你的好友/赞和收藏/当前在线/逛逛店铺等），
    作者-正文/会话配对更稳。
  - 页面分类：首页底栏「消息」+ feed「赞」不再误判 INBOX；评论输入
    `让大家听到你的声音`→COMMENTS；聊天 `发消息…`+`当前在线`→INBOX。
  - EditText hint 匹配 + `findAccessibilityNodeInfosByText` 空结果 DFS 回退（此前已合入）。
  - debug `confirmed` 同时接受 boolean / 字符串 `"true"`。

- 代码修复后复验（2026-07-12 20:06，Agent SHA
  `b04c9f312ee9ff25e2fdbb924ab835345fe39a9b7f8c599be86fd36027a4cac9`）：
  - 评论：`EXTRACT_COMMENTS` 抽到好友「这个茶叶多钱？M0CAP-190713」与作者回复
    「感谢首评！这泡岩韵…」；仍偶发笔记标题行。
  - 私信会话：`ChatActivity` 上 `page_hint=INBOX`，`EXTRACT_THREAD` 含对方与我方回复正文；
    列表页 `EXTRACT_INBOX` 本次 count=0（消息 Tab 表面态不稳定，但点进会话可抽）。

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

### Playbook Engine 云闭环（E0–E3，2026-07-13）

端上引擎（方案 C）经 debug Cloud Bridge HTTP poll 由 CP `POST /api/v1/tasks` 下发；
设备 `redmi-note-12` / 账号 `phone-publisher-1`；服务 `119.45.36.208`。

| 阶段 | Playbook | 云回执 | 备注 |
| --- | --- | --- | --- |
| E0 | `ensure_app_ready@1.0` | succeeded / committed=True | a11y Bound 后稳定 |
| E1 | `inbox_sync@1.0` | succeeded | 消息 Tab 可抽 |
| E1 | `read_comments@1.0` | succeeded（`pb-readc-1783875802` / `read-comments-cloud-1783876657`） | 非目标页仍 `WRONG_PAGE` |
| E2 | `publish_note@1.0` | succeeded / `effect_committed=true` | 真机副作用提交 |
| E3 | `reply_comment@1.0` | succeeded / `effect_committed=true`（`reply-comment-cloud-1783876597`） | 新 hint「有话要说，快来评论」；a11y 不可点时 `composer_tap_*` 打开 NoteCommentActivity |
| E3 | `reply_dm@1.0` | succeeded / `effect_committed=true`（`reply-dm-cloud-1783875423`） | ChatActivity 页上云闭环；非私信面 `WRONG_PAGE` |

单元：`:agent:testDebugUnitTest` BUILD SUCCESSFUL；CP 任务/角色相关用例 PASS。
重装后若 Bound 空，可用 `settings put` 尝试回绑；仍失败需用户关开无障碍。

## Go/No-Go

**判定：CONDITIONAL-GO（M0-Cap 单机三能力已通；Playbook 引擎 E0–E3 云可调度且页上副作用已验）。**

理由：`publish_note` / `read_comments` / `inbox_sync` / `reply_dm` / `reply_comment` 均有 2026-07-13 云下发 succeeded 证据。
第二机与 Device Owner 仍缺，不阻塞下一阶段（M2a 素材/排期）书面启动。
