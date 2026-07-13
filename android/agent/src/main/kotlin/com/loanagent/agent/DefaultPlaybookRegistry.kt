package com.loanagent.agent

object DefaultPlaybookRegistry {
    fun create(): PlaybookRegistry =
        PlaybookRegistry()
            .register("ensure_app_ready", EnsureAppReadyPlaybook())
            .register("read_comments", ReadCommentsPlaybook())
            .register("sync_notes", SyncNotesPlaybook())
            .register("inbox_sync", InboxSyncPlaybook())
            .register("inbox_open_thread", InboxOpenThreadPlaybook())
            .register("publish_note", PublishNotePlaybook())
            .register("post_comment", PostCommentPlaybook())
            .register("reply_comment", ReplyCommentPlaybook())
            .register("reply_dm", ReplyDmPlaybook())
}
