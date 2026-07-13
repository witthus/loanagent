# Ops Comment Tree & Inbox Transcript Design

**Date:** 2026-07-14  
**Status:** Approved  
**Approach:** Schema-first (方案二) — dedicated comment-tree table + strengthened inbox messages

## Problem

运营在矩阵助手里处理评论/私信时：

1. 「同步我的笔记」与笔记下拉未底对齐。
2. 评论同步是扁平列表，缺少时间与层级，和手机评论树不一致。
3. 私信列表只有会话人名，缺少可用预览；需进会话才能判断如何回复，但会话正文也常未入库。

## Goals

- 评论树、私信正文落库，便于手动同步与后续定时任务对账。
- 展示简单清晰：评论有时间与层级；私信列表有摘要，详情页看全文再回复。
- 默认读数据库；同步按钮与设备对账（设备已删则缓存删除）。

## Non-goals

- 不做实时 WebSocket。
- 本迭代不实现 cron（只保证 playbook/API 可被 cron 复用）。
- 不做超过两级的真实嵌套 UI（更深层级展平到回复区并标 `reply_to_author`）。

## Decisions

| 项 | 选择 |
| --- | --- |
| 私信列表 | B：列表 = 人名 + 最近摘要；点进会话看全文并回复 |
| 评论层级 | C：两级展示；更深层级展平到主评回复区并标被回复人 |
| 存储 | 新建 `note_comment_nodes`；强化 `inbox_messages` / `inbox_threads` |
| 旧表 | `note_comments` 迁移后保留只读兼容一期，运营读写改走 nodes；回复 API 改挂 `node_id` |

## Data model

### `note_comment_nodes`

| Column | Type | Notes |
| --- | --- | --- |
| `node_id` | TEXT PK | |
| `note_id` | TEXT FK → published_notes | |
| `account_id` | TEXT FK → accounts | |
| `parent_node_id` | TEXT NULL FK → self | 主评为 NULL |
| `root_node_id` | TEXT NOT NULL | 所属主评；主评等于自身 |
| `depth` | INT NOT NULL | 0 主评；≥1 回复区（更深展平仍为 1） |
| `author_summary` | TEXT NOT NULL | |
| `body_summary` | TEXT NOT NULL | |
| `posted_at_text` | TEXT NULL | 端上相对时间文案 |
| `reply_to_author` | TEXT NULL | 展平时被回复人 |
| `sort_index` | INT NOT NULL | 同步页序 |
| `locator_hint` | TEXT NULL | 端上回复定位 |
| `source_task_id` | TEXT NULL | |
| `synced_at` | TIMESTAMPTZ NOT NULL | |
| `created_at` | TIMESTAMPTZ NOT NULL | |

唯一约束建议：`(note_id, sort_index)`；对账键可用 `(note_id, author_summary, body_summary, depth, coalesce(reply_to_author,''))`。

成功同步某笔记：`DELETE FROM note_comment_nodes WHERE note_id = ?` 后按 payload 插入（整树替换）。

### Inbox

- `inbox_threads`：保留；`preview_summary` 由最近消息重算（最多拼 2～3 条）。
- `inbox_messages`：增加 `sort_index INT`、`posted_at_text TEXT NULL`；成功打开会话后按 `thread_id` 整段替换消息。

## Sync / playbooks

### Comments (`read_comments@1.0`)

Agent `extractComments` 升级为树：

```json
{
  "kind": "comments",
  "note_id": "...",
  "items": [
    {
      "author_summary": "逾期不候",
      "body_summary": "云测评回复请忽略-...",
      "posted_at_text": "昨天 01:17",
      "replies": [
        {
          "author_summary": "逾期不候",
          "body_summary": "...",
          "posted_at_text": "...",
          "reply_to_author": "静生百慧茶叶馆"
        }
      ]
    }
  ]
}
```

Control-plane ingest → `replace_comment_tree_from_payload`。

### Inbox (`inbox_sync@1.0`)

- 默认打开本批会话（`max_threads` 上限，如 10），每会话提取消息写入 payload：
  `threads[].messages[] = {sender_summary, body_summary, posted_at_text}`。
- 空线程列表仍成功（对账清空）。
- CP：`upsert_threads` 后对各线程 `replace_messages`，并刷新 `preview_summary`。

### Notes

`sync_notes@1.0` 不变。

## API

- `GET /api/v1/notes/{note_id}/comments` → 返回树形或扁平带 `parent_node_id`/`depth`/`posted_at_text`（前端可组装）。推荐扁平 + 字段，便于排序：
  `[{node_id, parent_node_id, root_node_id, depth, author_summary, body_summary, posted_at_text, reply_to_author, sort_index, ...}]`
- `POST /api/v1/comments/{node_id}/reply`（兼容旧 `comment_id` 路径，内部解析 node）。
- Inbox messages/list 已有；确保 sync 后 messages 非空时列表预览更新。

## Ops UI

### Comments

- 笔记下拉与「同步我的笔记」同一行、`align-items: flex-end`，按钮高度与 select 一致。
- 评论列表：主评卡片；子回复左边缩进 + 可选「回复 @xxx」；显示 `posted_at_text`。
- 回复框挂在每个 node 上。

### Inbox

- 列表：会话名 | 预览（最近消息摘要，可多行截断）| 未读 | 操作。
- 详情：消息时间线（sender、body、posted_at_text 或 created_at）+ 回复。

## Acceptance

1. 「同步我的笔记」与已发笔记选择框底对齐。
2. 同步后评论树可见时间与层级；示例结构（2 主评 + 1 主评下回复）可正确展示。
3. 选账号后私信列表有可读预览；进会话可见完整对话后再回复。
4. 单元/API 测试覆盖：comment tree replace、inbox messages replace + preview；extractor 覆盖主评/回复/时间。

## Migration

- Schema migration `19`：建 `note_comment_nodes`；`inbox_messages` 加列。
- 可选：从 `note_comments` 一次性迁入 depth=0 节点（无时间/无回复）。
