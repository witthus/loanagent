<script setup lang="ts">
const steps = [
  {
    title: '发笔记',
    body: '在「内容库」写好标题和正文，再到「发笔记」选账号和素材，点「立即发布」。手机会自动打开小红书完成发帖。',
  },
  {
    title: '同步与回复评论',
    body: '发布后在「评论」页选择对应笔记，点「同步最新评论」拉取最新留言，在每条评论下方填写回复并发送。',
  },
  {
    title: '处理私信',
    body: '在「私信」页选择账号并点「同步私信」，进入会话查看消息，在底部输入框回复。不要在回复里写微信号或手机号。',
  },
  {
    title: '跟进线索',
    body: '在「线索」页查看高意向用户，更新状态（新线索 / 跟进中 / 高意向 / 已关闭）并添加备注，方便团队协同。',
  },
  {
    title: '编排互动链',
    body: '重要笔记发布时可勾选「手动编排互动」，发布成功后到「互动链」页选择互动号创建手动链；普通笔记会自动安排互动号评论。',
  },
  {
    title: '查看任务与告警',
    body: '操作失败时打开「任务」查看状态和错误码，「告警」页会汇总互动链异常等系统提醒。',
  },
  {
    title: '新手机从零准备并绑定（仅 Redmi Note 12 Turbo · ADB）',
    body:
      '当前机型不支持扫码开通。按「远程升级」页下载《新设备绑定安装指引》PDF：' +
      '恢复出厂 → 用电脑 adb 安装 Device Controller 并 set-device-owner → ' +
      '应用策略禁止锁屏 → 安装 Agent 0.1.7+ → 开无障碍/输入法/电池无限制/后台弹出界面 → ' +
      '登录小红书 → 运维台「待绑定设备」创建账号并绑定。下载地址基于 https://android.hashhub.com。',
  },
  {
    title: '设备红灯怎么办',
    body:
      '打开「设备」：离线则检查网络与矩阵助手云桥；无障碍未开则在手机上开启。' +
      '灭屏任务失败时确认 Device Owner 已禁锁屏，且 HyperOS「后台弹出界面」已允许。勿 force-stop 矩阵助手。',
  },
  {
    title: '仍无法解决',
    body: '记下任务 ID、设备 ID 和错误码，联系管理员协助排查。必要时让管理员在手机上重新登录小红书账号。',
  },
]
</script>

<template>
  <section>
    <h1>遇到问题怎么办</h1>
    <p class="lead">按下面步骤操作，大多数问题可以自行解决。</p>
    <ol class="steps">
      <li v-for="(step, i) in steps" :key="i" class="card">
        <strong>{{ i + 1 }}. {{ step.title }}</strong>
        <p>{{ step.body }}</p>
      </li>
    </ol>
  </section>
</template>

<style scoped>
.lead { color: #5c6770; margin: 0 0 20px; }
.steps {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
  max-width: 720px;
}
.card {
  background: #fff;
  border-radius: 12px;
  padding: 18px 20px;
  border: 1px solid #e5e7eb;
}
.card strong { color: #2d6a4f; display: block; margin-bottom: 8px; }
.card p { margin: 0; color: #1f2329; line-height: 1.6; }
</style>
