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
    detail: '助手已尝试自动打开小红书，但这次没有成功切到前台。',
    nextStep: '确认手机亮屏且无障碍已开启后重试；仍失败请打开「矩阵助手」点「启动小红书」。',
  },
  SCREEN_NOT_READY: {
    title: '手机还没亮屏或仍在锁屏',
    detail: '助手会尝试亮屏并滑开无密码锁屏；有密码/指纹时无法自动解开。',
    nextStep: '测试机请设为「滑动解锁」或关闭锁屏后重试；有密码需先手动解锁。',
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
    title: '绑定的手机当前不可用',
    detail: '账号没有在线设备，或设备心跳已超时。',
    nextStep: '打开「设备」确认手机在线，并保持矩阵助手运行。',
  },
  ACCOUNT_UNAVAILABLE: {
    title: '账号当前不可用',
    detail: '账号可能已暂停或未绑定可用设备。',
    nextStep: '到「账号」或「设备」检查绑定与状态后再试。',
  },
  PLAYBOOK_FORBIDDEN: {
    title: '该账号角色不能执行此操作',
    detail: '主号、矩阵号、互动号可执行的任务不同。',
    nextStep: '换有权限的账号，或到「账号」确认角色。',
  },
  TASK_DISPATCH_FAILED: {
    title: '任务没能下发到手机',
    detail: '云端到设备的指令通道异常。',
    nextStep: '稍后重试；仍失败请查看「任务」并联系管理员。',
  },
  THREAD_NOT_FOUND: {
    title: '找不到这条私信会话',
    detail: '会话可能已被同步对账删除，或链接已过期。',
    nextStep: '回到「私信」重新同步后再进入。',
  },
  SCHEDULE_NOT_FOUND: {
    title: '找不到这条排期',
    detail: '排期可能已删除或链接已过期。',
    nextStep: '打开「排期」重新查看列表。',
  },
  SCHEDULE_NOT_DISPATCHABLE: {
    title: '这条排期现在不能发布',
    detail: '只有「待发布」或「失败」的排期可以再次下发。',
    nextStep: '到「排期」确认状态后再试。',
  },
  SCHEDULE_NOT_EDITABLE: {
    title: '这条排期不能再改',
    detail: '已下发或已结束的排期不能编辑。',
    nextStep: '可删除后重新创建，或新建一条排期。',
  },
  DEVICE_NOT_FOUND: {
    title: '还找不到这台手机',
    detail: '设备尚未完成首次心跳，或设备 ID 不正确。',
    nextStep: '打开手机上的矩阵助手并保持运行约 30 秒，待「待绑定设备」出现后再绑定。',
  },
  DEVICE_ALREADY_BOUND: {
    title: '这台手机已经绑过账号了',
    detail: '一台手机同时只能绑定一个账号。',
    nextStep: '先解绑原账号，或换一台未绑定的手机。',
  },
  ACCOUNT_ALREADY_EXISTS: {
    title: '账号 ID 已存在',
    detail: '系统生成的账号标识冲突。',
    nextStep: '换一个显示名称后再试，或绑定已有未绑定账号。',
  },
  SET_TEXT_FAILED: {
    title: '没能把文字填进输入框',
    detail: '小红书编辑框没有正确获得焦点，或界面文案与预期不一致。',
    nextStep: '保持矩阵助手在前台运行，手机亮屏解锁后重试；多次失败请确认小红书已进入编辑页。',
  },
  EDITOR_NOT_READY: {
    title: '还没进入发笔记编辑页',
    detail: '相册选图或「下一步」可能未完成，标题输入框尚未出现。',
    nextStep: '确认手机相册权限已开，素材已下载到相册后重试发布。',
  },
  PUBLISH_ENTRY_FAILED: {
    title: '打不开发笔记入口',
    detail: '没有成功点到小红书底部「发布」或「从相册选择」。',
    nextStep: '先手动打开小红书首页，保持亮屏后重试。',
  },
  MEDIA_MISSING: {
    title: '相册里没选到素材',
    detail: '图片可能还没下载完，或点选位置不准确。',
    nextStep: '稍后重试；仍失败请换一张图或检查手机存储权限。',
  },
  MEDIA_DOWNLOAD_FAILED: {
    title: '下载图片失败',
    detail: '设备从云端拉取媒体时失败（已自动重试）。',
    nextStep: '检查手机能否访问控制面；看设备 logcat MediaBridge 明细后重试。',
  },
  MEDIA_STORE_FAILED: {
    title: '图片写入系统相册失败',
    detail: '下载成功但无法写入 MediaStore。',
    nextStep: '检查存储权限后重试；仍失败请清相册缓存或重启 Agent。',
  },
  AGENT_UPGRADE_PENDING: {
    title: 'Agent 升级已排队',
    detail: '控制面已标记 Device Owner 设备待升级，等待 DPC 拉取签名 manifest。',
    nextStep: '在 Device Controller 点「Check remote Agent upgrade」，或等待约 15 分钟轮询。',
  },
  FINAL_ACTION_BLOCKED: {
    title: '最后一步点「发布」失败了',
    detail: '内容可能已填好，但发布按钮未点中或被拦截。',
    nextStep: '到手机上看是否停在编辑页，可手动点发布；或清空后重试。',
  },
  NETWORK_POLICY_VIOLATION: {
    title: '当前网络不符合账号策略',
    detail: '账号要求仅蜂窝上网时，手机连着 Wi‑Fi 不能发帖、评论等副作用任务；任务不会创建。',
    nextStep: '关掉手机 Wi‑Fi 只用流量，或到「账号」把网络策略改为允许 Wi‑Fi 后再试。',
  },
  PUBLISH_QUOTA_EXCEEDED: {
    title: '今日发帖配额已用完',
    detail: '该账号当天成功发布次数已达上限，新的发帖任务不会创建。',
    nextStep: '明天再发，或到「账号」提高每日发帖配额。',
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
