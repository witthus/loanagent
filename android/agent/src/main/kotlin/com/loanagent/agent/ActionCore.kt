package com.loanagent.agent

enum class M0Action {
    CLICK,
    SET_TEXT,
    SWIPE,
    BACK,
}

enum class ActionStatus {
    SUCCESS,
    BLOCKED,
    NOT_FOUND,
    AMBIGUOUS,
    INDETERMINATE,
    FAILED,
    IME_FALLBACK_REQUIRED,
}

enum class ActionPath {
    NODE_ACTION,
    BOUNDS_GESTURE_FALLBACK,
    GESTURE,
    GLOBAL_ACTION,
    NONE,
}

data class ActionResult(
    val status: ActionStatus,
    val action: M0Action,
    val path: ActionPath,
    val fallbackUsed: Boolean,
    val message: String,
    val stage: ExecutionStage = if (status == ActionStatus.SUCCESS) {
        ExecutionStage.ACTION_ACCEPTED
    } else {
        ExecutionStage.REJECTED
    },
)
