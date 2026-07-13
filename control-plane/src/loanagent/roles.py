from __future__ import annotations

from enum import StrEnum


class AccountRole(StrEnum):
    PUBLISHER_MAIN = "PUBLISHER_MAIN"
    PUBLISHER_MATRIX = "PUBLISHER_MATRIX"
    ENGAGER = "ENGAGER"


_PUBLISHERS = {AccountRole.PUBLISHER_MAIN, AccountRole.PUBLISHER_MATRIX}

# playbook name before @version
_ALLOW: dict[AccountRole, frozenset[str]] = {
    AccountRole.PUBLISHER_MAIN: frozenset({
        "ensure_app_ready", "publish_note", "read_comments", "sync_notes", "post_comment",
        "reply_comment", "inbox_sync", "inbox_open_thread", "reply_dm",
        "dismiss_interruptions",
    }),
    AccountRole.PUBLISHER_MATRIX: frozenset({
        "ensure_app_ready", "publish_note", "read_comments", "sync_notes", "post_comment",
        "reply_comment", "inbox_sync", "inbox_open_thread", "reply_dm",
        "dismiss_interruptions",
    }),
    AccountRole.ENGAGER: frozenset({
        "ensure_app_ready", "read_comments", "sync_notes", "post_comment",
        "inbox_sync", "inbox_open_thread", "reply_dm", "dismiss_interruptions",
    }),
}


def playbook_base(playbook: str) -> str:
    return playbook.split("@", 1)[0]


def playbook_allowed_for_role(role: AccountRole, playbook: str) -> bool:
    return playbook_base(playbook) in _ALLOW[role]


def is_publisher(role: AccountRole) -> bool:
    return role in _PUBLISHERS
