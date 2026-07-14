import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/LoginView.vue'),
      meta: { public: true },
    },
    {
      path: '/',
      component: () => import('@/layouts/AppShell.vue'),
      children: [
        { path: '', name: 'today', component: () => import('@/views/TodayView.vue') },
        { path: 'publish', name: 'publish', component: () => import('@/views/PublishView.vue') },
        { path: 'schedules', name: 'schedules', component: () => import('@/views/SchedulesView.vue'), meta: { title: '排期' } },
        { path: 'comments', name: 'comments', component: () => import('@/views/CommentsView.vue'), meta: { title: '评论' } },
        { path: 'inbox', name: 'inbox', component: () => import('@/views/InboxView.vue'), meta: { title: '私信' } },
        { path: 'inbox/:threadId', name: 'inbox-thread', component: () => import('@/views/InboxThreadView.vue'), meta: { title: '私信会话' } },
        { path: 'leads', name: 'leads', component: () => import('@/views/LeadsView.vue'), meta: { title: '线索' } },
        { path: 'contents', name: 'contents', component: () => import('@/views/ContentsView.vue') },
        { path: 'engagement', name: 'engagement', component: () => import('@/views/EngagementView.vue'), meta: { title: '互动链' } },
        { path: 'accounts', name: 'accounts', component: () => import('@/views/AccountsView.vue'), meta: { title: '账号' } },
        { path: 'devices', name: 'devices', component: () => import('@/views/DevicesView.vue'), meta: { title: '设备' } },
        { path: 'tasks', name: 'tasks', component: () => import('@/views/TasksView.vue'), meta: { title: '任务' } },
        { path: 'alerts', name: 'alerts', component: () => import('@/views/AlertsView.vue'), meta: { title: '异常' } },
        { path: 'help', name: 'help', component: () => import('@/views/HelpView.vue'), meta: { title: '遇到问题怎么办' } },
      ],
    },
  ],
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()
  if (!auth.checked) {
    await auth.probe()
  }
  if (to.meta.public) {
    if (auth.authed && to.name === 'login') return { name: 'today' }
    return true
  }
  if (!auth.authed) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  return true
})

export default router
