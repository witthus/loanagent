import type { Platform } from '@/platform'

/** Platform-specific verbs; douyin keys reserved, unused while ACTIVE_PLATFORMS=[xhs]. */
export const publishVerb: Record<Platform, string> = {
  xhs: '发笔记',
  douyin: '发视频',
}

export const zhCN = {
  brand: '矩阵助手',
  nav: {
    today: '今日待办',
    publish: '发笔记',
    comments: '评论',
    inbox: '私信',
    leads: '线索',
    contents: '内容库',
    engagement: '互动链',
    accounts: '账号与设备',
    devices: '账号与设备',
    tasks: '任务',
    alerts: '异常',
    help: '遇到问题怎么办',
    logout: '退出',
    daily: '日常工作',
    system: '系统',
  },
  login: {
    title: '登录矩阵助手',
    token: '运营令牌',
    submit: '进入',
    hint: '向管理员索取登录令牌，粘贴后进入。',
    badToken: '令牌不正确，请重试。',
  },
  comingSoon: '此功能正在接入，请稍后再试。',
}
