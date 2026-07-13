import { defineStore } from 'pinia'
import { ref } from 'vue'
import { api, ApiError } from '@/lib/api'

export const useAuthStore = defineStore('auth', () => {
  const checked = ref(false)
  const authed = ref(false)

  async function probe(): Promise<boolean> {
    try {
      await api<{ ok: boolean }>('/ops/api/session')
      authed.value = true
    } catch {
      authed.value = false
    }
    checked.value = true
    return authed.value
  }

  async function login(token: string): Promise<void> {
    await api<{ ok: boolean }>('/ops/api/login', {
      method: 'POST',
      body: JSON.stringify({ token }),
    })
    authed.value = true
    checked.value = true
  }

  async function logout(): Promise<void> {
    try {
      await api('/ops/api/logout', { method: 'POST' })
    } catch (err) {
      if (!(err instanceof ApiError)) {
        // ignore network blips on logout
      }
    }
    authed.value = false
  }

  return { checked, authed, probe, login, logout }
})
