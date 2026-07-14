export type HumanMessage = {
  title: string
  detail: string
  nextStep: string
}

const MAP: Record<string, HumanMessage> = {
  A11Y_DOWN: {
    title: '手机辅助功能还没就绪',
    detail: '自动化需要打开「矩阵助手」的无障碍服务。',
    nextStep: '打开「设备」页查看状态，或联系管理员。',
  },
  XHS_NOT_FOREGROUND: {
    title: '小红书没有在前台',
    detail: '手机可能停在了别的应用。',
    nextStep: '稍后点「重试」，仍失败请查看设备。',
  },
  NAV_TIMEOUT: {
    title: '手机还停在别的页面，这次没做成',
    detail: '自动跳转超时。',
    nextStep: '重试一次；多次失败请联系管理员。',
  },
  NAV_TARGET_NOT_FOUND: {
    title: '没找到对应的笔记或会话',
    detail: '标题可能对不上，或内容还没加载出来。',
    nextStep: '核对标题后重试，或先在手机上打开目标页。',
  },
  NAV_MISSING_HINT: {
    title: '缺少定位信息',
    detail: '系统不知道要打开哪一条。',
    nextStep: '从列表重新进入后再试。',
  },
  WRONG_PAGE: {
    title: '手机页面不对',
    detail: '当前屏幕不是评论区或私信页。',
    nextStep: '重试；仍失败请联系管理员。',
  },
  LOGIN_REQUIRED: {
    title: '小红书需要重新登录',
    detail: '账号可能掉线了。',
    nextStep: '请管理员在手机上重新登录后再试。',
  },
  DEVICE_UNAVAILABLE: {
    title: '手机不在线或未绑定',
    detail: '账号没有可用设备，或手机已掉线。',
    nextStep: '到「账号与设备」检查状态，可暂停账号、终止任务或删除设备后重新绑定。',
  },
  DEVICE_DELETED: {
    title: '设备已删除',
    detail: '关联任务因设备被删除而取消。',
    nextStep: '装好新手机并重新绑定账号后再继续。',
  },
  DEVICE_OFFLINE_CANCELLED: {
    title: '任务已终止',
    detail: '设备掉线后，进行中的任务已被手动终止。',
    nextStep: '等手机重新上线并确认无障碍后再重试。',
  },
  OPERATOR_CANCELLED: {
    title: '任务已取消',
    detail: '运营人员取消了该任务。',
    nextStep: '需要时重新提交即可。',
  },
  TASK_ALREADY_TERMINAL: {
    title: '任务已结束',
    detail: '该任务已经完成或失败，不能再取消。',
    nextStep: '在「任务」页查看结果，或重新下发。',
  },
}

export function humanizeError(errorCode: string | null | undefined): HumanMessage {
  if (!errorCode) {
    return {
      title: '出了点问题',
      detail: '没有具体错误码。',
      nextStep: '稍后重试，或打开「任务」查看详情。',
    }
  }
  const known = MAP[errorCode]
  if (known) return known
  return {
    title: '操作没有成功',
    detail: `系统代码：${errorCode}`,
    nextStep: '打开「任务」查看详情，或联系管理员。',
  }
}
