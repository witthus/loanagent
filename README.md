# Loanagent

小规模（首期 5–20 台）Android 端上 Agent 与云端编排系统。当前包含可复现工程、协议、本地
基础设施、Device Owner / DPC、无障碍 Agent（debug 云桥）、FastAPI control-plane，以及
矩阵助手 Vue 运营台（账号/设备/任务/私信/评论/排期等）。

## 仓库结构

- `android/device-controller`：M0 Device Owner / DPC application APK。
- `android/agent`：无障碍 Agent；debug 构建含云桥心跳与任务执行。
- `android/fixture-app`：可控按钮、输入、滚动、弹窗、状态文案和自绘 OCR 区域。
- `control-plane`：FastAPI 控制面（任务、账号、设备、媒体、运营 API）与 pytest。
- `ops-web`：矩阵助手运营前端（Vue 3 SPA）；开发态 `profile: web`，生产由 control-plane 静态托管。
- `schemas`：Task、Event、selector bundle、update manifest 与 MQTT 契约。
- `docker`：固定 Python/uv、JDK/Gradle/Android SDK 的构建镜像。
- `infra/compose.yaml`：仅用于本地开发的 control-plane、Postgres、EMQX 与按需
  Android builder / ops-web；不是生产部署清单。
- `infra/compose.server.yaml`：国内服务器部署（镜像源友好）。
- `agent-releases/`：已发布 Agent APK 与安装指引 PDF（目录内产物默认 gitignore）。

## Docker-only 开发

宿主机只需要 Docker/Docker Compose。不要在宿主机安装或运行 Python、uv、Java、Gradle 或
Android SDK。

```bash
# 验证 Compose
docker compose -f infra/compose.yaml config

# control-plane 全部测试（包含 schema 契约）
docker compose -f infra/compose.yaml build control-plane
docker compose -f infra/compose.yaml up -d --wait postgres
docker compose -f infra/compose.yaml run --rm --no-deps control-plane \
  uv run --frozen pytest

# Python lint
docker compose -f infra/compose.yaml run --rm --no-deps control-plane \
  uv run --frozen ruff check --no-cache src tests

# Android 三模块单元测试、debug/release lint 与构建；严格校验锁和依赖 checksum
docker compose -f infra/compose.yaml --profile android build android-builder
docker compose -f infra/compose.yaml --profile android run --rm --no-deps android-builder \
  ./gradlew clean test lint lintRelease assembleDebug assembleRelease \
  -Dorg.gradle.dependency.verification.console=verbose --no-daemon \
  --project-cache-dir /home/gradle/work/project-cache \
  --dependency-verification=strict
```

首次启动前可复制 `.env.example` 为 `.env` 并替换其中的本地开发凭证。示例值仅用于绑定在
`127.0.0.1` 的开发环境，绝不能用于生产。启动本地依赖并在 control-plane 容器内检查健康端点：

```bash
docker compose -f infra/compose.yaml up -d --build control-plane
docker compose -f infra/compose.yaml exec -T control-plane python -c \
  'import json, urllib.request; print(json.load(urllib.request.urlopen("http://localhost:8000/health")))'
```

`android-builder` 位于 `android` profile，只用于一次性构建，不是常驻服务。
矩阵助手运营 UI（`ops-web`）位于 `web` profile：

```bash
# 开发：Vite 热更新，代理到 control-plane
docker compose -f infra/compose.yaml --profile web up -d --build control-plane ops-web
# 浏览器打开 http://127.0.0.1:5173/ ，登录令牌为 OPS_TOKEN（默认 dev-only-ops-token）

# 前端单测
docker compose -f infra/compose.yaml --profile web run --rm --no-deps ops-web npm test

# 生产镜像内嵌 SPA（runtime target）
docker build -f docker/control-plane/Dockerfile --target runtime -t loanagent-cp .
```

control-plane 与 Android builder 均以非 root 用户运行；源码只读挂载，Python 虚拟环境、uv/Gradle
缓存和 Android 构建输出保存在 named volumes。

Android 构建完成后的 debug APK 位于 builder volume：
`/home/gradle/work/build/device-controller/outputs/apk/debug/device-controller-debug.apk` 和
`/home/gradle/work/build/agent/outputs/apk/debug/agent-debug.apk`。用于 Redmi Note 12 Turbo
后续测试的稳定、宿主可见交付路径为 `ops/m0/generated/device-controller-debug.apk` 和
`ops/m0/generated/agent-m0-debug.apk`；Docker-only 复制、无线 ADB 安装与手动开启无障碍/IME
命令见 `ops/m0/redmi-note-12-turbo-test.md`。

完成 Android 构建后运行 `bash ops/m0/stage-apks.sh`。脚本本身只调用 Compose
`android-builder`，会复制 Agent 与 DPC APK、生成
`ops/m0/generated/checksums.sha256`、`signing-certificates.sha256` 与
`provenance.txt`，并在容器内立即校验 APK checksum 和 debug 测试签名摘要。
这些文件均标记为 `UNTRUSTED_DEBUG_TEST_ONLY`，不包含生产签名。

Device Owner 二维码生成和两台 Xiaomi/Redmi 无线 ADB 验证命令见
`ops/m0/device-owner-checklist.md`。两者都只通过 Compose 容器运行；不得使用宿主 `adb`、
Python 或在线二维码服务。实体机结果在实际执行前保持 `NOT_RUN`。

M0 的 `/enroll` endpoint 使用 Postgres 持久化一次性 token 的 SHA-256、过期时间、消费时间
和绑定的 `device_id`；响应丢失后，同一设备可幂等重试，不同设备会得到冲突。原子消费成功后
DPC 才应用最小策略并完成 `ADMIN_POLICY_COMPLIANCE`。该 endpoint 只覆盖注册闭环，不是完整
任务 API。

## 协议边界

MQTT 使用 QoS 1 和至少一次投递：

- 下行：`devices/{deviceId}/commands`
- 上行：`devices/{deviceId}/events`
- 接收端持久化 `task_id` 去重。
- `sequence` 与 `nonce` 防重放。
- 已提交或结果不确定的非幂等副作用不得自动再次执行。

规范详情见 `schemas/mqtt-contract.json` 和四个 JSON Schema。

当前 EMQX 未启用设备客户端认证，只监听宿主机 loopback。进入任何共享、测试或生产网络前，
必须先完成设备级证书、broker mTLS、ACL 与证书轮换，未达到该门槛不得对外开放 1883。

## 供应链与 Gradle 约定

- Python、uv、Gradle 基础镜像使用已验证 digest；Compose 基础服务镜像也固定 tag 与 manifest
  digest。
- Android commandline-tools 使用固定归档 checksum；SDK platform、build-tools 与 platform-tools
  在镜像构建时核对 revision。
- Gradle Wrapper 分发包和 wrapper JAR 均有 SHA-256；依赖使用 lockfile 和
  `gradle/verification-metadata.xml` 严格校验。
- 三个 Android 模块当前重复少量 `compileSdk`、JVM 与测试依赖配置。这是刻意保持的 YAGNI
  边界：只有出现第三项以上共享构建逻辑或配置开始漂移时，再提取 convention plugin，避免为空
  骨架引入额外 build-logic 模块。

## 小规模持久化方向

首期不引入 Redis。任务、设备状态和审计记录以 Postgres 为事实来源；素材与媒体文件落在本地
`MEDIA_ROOT`（经 HMAC 签名 URL 下载）。后续异步投递可采用与任务状态同事务写入的 Postgres
outbox，再由独立 worker 投递到 EMQX。该边界避免在 20 台规模过早增加分布式缓存/对象存储的
一致性与运维成本。
