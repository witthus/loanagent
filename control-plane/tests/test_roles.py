from loanagent.roles import AccountRole, playbook_allowed_for_role


def test_engager_cannot_publish_note() -> None:
    assert playbook_allowed_for_role(AccountRole.ENGAGER, "publish_note@1.0") is False


def test_publisher_main_can_publish_note() -> None:
    assert playbook_allowed_for_role(AccountRole.PUBLISHER_MAIN, "publish_note@1.0") is True


def test_engager_can_post_comment() -> None:
    assert playbook_allowed_for_role(AccountRole.ENGAGER, "post_comment@1.0") is True
