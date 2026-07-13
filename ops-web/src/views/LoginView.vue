<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { zhCN } from '@/i18n/zh-CN'
import { useAuthStore } from '@/stores/auth'
import { ApiError } from '@/lib/api'

const token = ref('')
const error = ref('')
const loading = ref(false)
const auth = useAuthStore()
const router = useRouter()
const route = useRoute()

async function submit() {
  error.value = ''
  loading.value = true
  try {
    await auth.login(token.value.trim())
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/'
    await router.replace(redirect || '/')
  } catch (err) {
    if (err instanceof ApiError && err.status === 401) {
      error.value = zhCN.login.badToken
    } else {
      error.value = '登录失败，请检查网络后重试。'
    }
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <form class="card" @submit.prevent="submit">
      <h1>{{ zhCN.login.title }}</h1>
      <p class="hint">{{ zhCN.login.hint }}</p>
      <label>
        {{ zhCN.login.token }}
        <input v-model="token" type="password" autocomplete="current-password" required />
      </label>
      <p v-if="error" class="error">{{ error }}</p>
      <button type="submit" :disabled="loading">
        {{ loading ? '登录中…' : zhCN.login.submit }}
      </button>
    </form>
  </div>
</template>

<style scoped>
.login-page {
  min-height: 100vh;
  display: grid;
  place-items: center;
  background: linear-gradient(160deg, #d8f3dc 0%, #f5f6f8 45%, #e9ecef 100%);
  font-family: "PingFang SC", "Noto Sans SC", "Microsoft YaHei", sans-serif;
}
.card {
  width: min(420px, 92vw);
  background: #fff;
  border-radius: 16px;
  padding: 32px 28px;
  box-shadow: 0 12px 40px rgba(27, 67, 50, 0.12);
  display: flex;
  flex-direction: column;
  gap: 14px;
}
h1 {
  margin: 0;
  font-size: 1.6rem;
  color: #1b4332;
}
.hint {
  margin: 0;
  color: #5c6770;
  font-size: 0.95rem;
}
label {
  display: flex;
  flex-direction: column;
  gap: 8px;
  font-weight: 600;
}
input {
  padding: 12px 14px;
  border: 1px solid #d0d5dd;
  border-radius: 10px;
  font-size: 1rem;
}
.error {
  margin: 0;
  color: #b42318;
}
button {
  margin-top: 8px;
  padding: 12px;
  border: none;
  border-radius: 10px;
  background: #2d6a4f;
  color: #fff;
  font-size: 1rem;
  font-weight: 600;
  cursor: pointer;
}
button:disabled {
  opacity: 0.7;
  cursor: wait;
}
</style>
