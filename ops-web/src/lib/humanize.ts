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
