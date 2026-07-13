<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { zhCN } from '@/i18n/zh-CN'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const daily = [
  { to: '/', label: zhCN.nav.today },
  { to: '/publish', label: zhCN.nav.publish },
  { to: '/comments', label: zhCN.nav.comments },
  { to: '/inbox', label: zhCN.nav.inbox },
  { to: '/leads', label: zhCN.nav.leads },
  { to: '/contents', label: zhCN.nav.contents },
  { to: '/engagement', label: zhCN.nav.engagement },
]

const system = [
  { to: '/accounts', label: zhCN.nav.accounts },
  { to: '/tasks', label: zhCN.nav.tasks },
  { to: '/alerts', label: zhCN.nav.alerts },
]

const activePath = computed(() => route.path)

function isActive(path: string): boolean {
  if (path === '/') return activePath.value === '/'
  return activePath.value.startsWith(path)
}

async function onLogout() {
  await auth.logout()
  await router.push({ name: 'login' })
}
</script>

<template>
  <div class="shell">
    <aside class="sidebar">
      <div class="brand">{{ zhCN.brand }}</div>
      <p class="group">{{ zhCN.nav.daily }}</p>
      <router-link
        v-for="item in daily"
        :key="item.to"
        :to="item.to"
        class="nav-item"
        :class="{ active: isActive(item.to) }"
      >
        {{ item.label }}
      </router-link>
      <p class="group">{{ zhCN.nav.system }}</p>
      <router-link
        v-for="item in system"
        :key="item.to"
        :to="item.to"
        class="nav-item"
        :class="{ active: isActive(item.to) }"
      >
        {{ item.label }}
      </router-link>
    </aside>
    <div class="main">
      <header class="topbar">
        <router-link to="/help" class="help">{{ zhCN.nav.help }}</router-link>
        <button type="button" class="logout" @click="onLogout">{{ zhCN.nav.logout }}</button>
      </header>
      <main class="content">
        <router-view />
      </main>
    </div>
  </div>
</template>

<style scoped>
.shell {
  --bp-md: 960px;
  display: flex;
  min-height: 100vh;
  background: #f5f6f8;
  color: #1f2329;
  font-family: "PingFang SC", "Noto Sans SC", "Microsoft YaHei", sans-serif;
}
.sidebar {
  width: 220px;
  flex-shrink: 0;
  background: #1b4332;
  color: #f8faf8;
  padding: 24px 16px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.brand {
  font-size: 1.35rem;
  font-weight: 700;
  margin-bottom: 20px;
  letter-spacing: 0.02em;
}
.group {
  margin: 16px 8px 6px;
  font-size: 0.75rem;
  opacity: 0.65;
  text-transform: none;
}
.nav-item {
  display: block;
  padding: 10px 12px;
  border-radius: 8px;
  color: inherit;
  text-decoration: none;
  font-size: 0.95rem;
}
.nav-item:hover {
  background: rgba(255, 255, 255, 0.08);
}
.nav-item.active {
  background: #2d6a4f;
  font-weight: 600;
}
.main {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.topbar {
  display: flex;
  justify-content: flex-end;
  align-items: center;
  gap: 16px;
  padding: 12px 24px;
  background: #fff;
  border-bottom: 1px solid #e5e7eb;
}
.help {
  color: #2d6a4f;
  text-decoration: none;
}
.logout {
  border: 1px solid #d0d5dd;
  background: #fff;
  border-radius: 8px;
  padding: 6px 12px;
  cursor: pointer;
}
.content {
  padding: 24px 28px 40px;
  flex: 1;
}
@media (max-width: 960px) {
  .shell {
    flex-direction: column;
  }
  .sidebar {
    width: 100%;
  }
}
</style>
