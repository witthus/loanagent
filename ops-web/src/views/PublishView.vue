<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '@/lib/api'
import { formatApiError } from '@/lib/apiError'
import { useFleetAccounts } from '@/composables/useFleetAccounts'
import { humanizeError } from '@/lib/humanize'
import { accountDisplayName } from '@/lib/labels'
import { useSubmittedTasks } from '@/composables/useSubmittedTasks'
import SubmittedTasksBanner from '@/components/SubmittedTasksBanner.vue'

type Content = { content_id: string; title: string; platform: string }
type Schedule = {
  schedule_id: string
  account_id: string
  content_id: string
  status: string
  task_id?: string | null
  error_code?: string | null
}

const { accounts, loadFleet, optionLabel, runnable } = useFleetAccounts()
const contents = ref<Content[]>([])
const selectedAccountIds = ref<string[]>([])
const contentId = ref('')
const manualEngagement = ref(false)
const message = ref('')
const error = ref('')
const publishing = ref(false)
const scheduling = ref(false)
const focusScheduleId = ref('')
const { tasks: submittedTasks, track, dismiss, statusText, isRunning } = useSubmittedTasks()
const router = useRouter()
const route = useRoute()

const publishers = computed(() =>
  accounts.value.filter((a) => a.role === 'PUBLISHER_MAIN' || a.role === 'PUBLISHER_MATRIX'),
)

watch(
  publishers,
  (list) => {
    if (!selectedAccountIds.value.length) {
      const firstReady = list.find((a) => runnable(a)) || list[0]
      if (firstReady) selectedAccountIds.value = [firstReady.account_id]
    }
  },
  { immediate: true },
)

function toggleAccount(id: string) {
  const account = publishers.value.find((a) => a.account_id === id)
  if (account && !runnable(account)) return
  if (selectedAccountIds.value.includes(id)) {
    selectedAccountIds.value = selectedAccountIds.value.filter((x) => x !== id)
  } else {
    selectedAccountIds.value = [...selectedAccountIds.value, id]
  }
}

function nameOf(accountId: string): string {
  const hit = accounts.value.find((a) => a.account_id === accountId)
  return accountDisplayName(hit)
}

onMounted(async () => {
  try {
    await loadFleet()
    contents.value = await api<Content[]>('/api/v1/content?platform=xhs')
    const scheduleId = route.query.schedule_id as string | undefined
    if (scheduleId) {
      focusScheduleId.value = scheduleId
      try {
        const schedules = await api<Schedule[]>('/api/v1/schedules')
        const hit = schedules.find((s) => s.schedule_id === scheduleId)
        if (hit) {
          selectedAccountIds.value = [hit.account_id]
          contentId.value = hit.content_id
        }
      } catch {
        /* ignore */
      }
    }
    if (!contentId.value && contents.value[0]) contentId.value = contents.value[0].content_id
  } catch {
    error.value = '加载账号或内容失败'
  }
})

async function publishNow() {
  error.value = ''
  message.value = ''
  if (!selectedAccountIds.value.length) {
    error.value = '请至少选择一个发帖账号'
    return
  }
  if (!contentId.value) {
    error.value = '请选择素材'
    return
  }
  publishing.value = true
  const submitted: string[] = []
  const failed: string[] = []
  try {
    for (const account_id of selectedAccountIds.value) {
      try {
        const task = await api<{ task_id: string }>('/api/v1/publish/immediate', {
          method: 'POST',
          body: JSON.stringify({
            account_id,
            content_id: contentId.value,
            engagement_mode: manualEngagement.value ? 'manual' : 'auto',
          }),
        })
        track(task.task_id, `发布笔记 · ${nameOf(account_id)}`)
        submitted.push(account_id)
      } catch (err) {
        failed.push(`${nameOf(account_id)}: ${formatApiError(err, '提交失败')}`)
      }
    }
    if (submitted.length) {
      message.value = `已提交 ${submitted.length} 个发布任务，手机在后台执行；可在下方或「任务」页查看进度。`
      if (manualEngagement.value) {
        message.value += ' 重要笔记请等发布成功后，再到「互动链」手动编排。'
      }
    }
    if (failed.length) error.value = failed.join('；')
  } finally {
    publishing.value = false
  }
}

async function addToSchedule() {
  error.value = ''
  message.value = ''
  if (!selectedAccountIds.value.length) {
    error.value = '请至少选择一个发帖账号'
    return
  }
  if (!contentId.value) {
    error.value = '请选择素材'
    return
  }
  scheduling.value = true
  const created: string[] = []
  const failed: string[] = []
  try {
    for (const account_id of selectedAccountIds.value) {
      try {
        await api('/api/v1/schedules', {
          method: 'POST',
          body: JSON.stringify({ account_id, content_id: contentId.value }),
        })
        created.push(account_id)
      } catch (err) {
        failed.push(`${nameOf(account_id)}: ${formatApiError(err, '失败')}`)
      }
    }
    if (created.length) {
      message.value = `已为 ${created.length} 个账号加入排期`
      await router.push({ name: 'schedules' })
      return
    }
    if (failed.length) error.value = failed.join('；')
  } finally {
    scheduling.value = false
  }
}

async function dispatchFocusedSchedule() {
  if (!focusScheduleId.value) return
  publishing.value = true
  error.value = ''
  message.value = ''
  try {
    const updated = await api<Schedule>(
      `/api/v1/schedules/${focusScheduleId.value}/dispatch`,
      { method: 'POST' },
    )
    if (updated.status === 'failed' || !updated.task_id) {
      const human = humanizeError(updated.error_code)
      error.value = `下发失败：${human.title}。${human.nextStep}`
      return
    }
    track(updated.task_id, '排期发布笔记')
    message.value = '排期已下发，手机在后台执行；可在下方或「任务」页查看进度。'
    focusScheduleId.value = ''
    await router.replace({ name: 'publish' })
  } catch (err) {
    error.value = formatApiError(err, '下发排期失败')
  } finally {
    publishing.value = false
  }
}
</script>

<template>
  <section>
    <h1>发笔记</h1>
    <p v-if="focusScheduleId" class="focus-banner">
      正在处理一条排期
      <button type="button" class="link-btn" @click="dispatchFocusedSchedule">按排期发布到手机</button>
      <router-link class="link-btn" :to="{ name: 'schedules', query: { schedule_id: focusScheduleId } }">
        打开排期板
      </router-link>
    </p>
    <SubmittedTasksBanner
      :tasks="submittedTasks"
      :status-text="statusText"
      :is-running="isRunning"
      @dismiss="dismiss"
    />
    <form class="form" @submit.prevent="publishNow">
      <fieldset>
        <legend>发帖账号（可多选，将依次提交到各绑定设备）</legend>
        <label v-for="a in publishers" :key="a.account_id" class="check" :class="{ disabled: !runnable(a) }">
          <input
            type="checkbox"
            :checked="selectedAccountIds.includes(a.account_id)"
            :disabled="!runnable(a)"
            @change="toggleAccount(a.account_id)"
          />
          {{ optionLabel(a) }}
        </label>
      </fieldset>
      <label>
        选用素材
        <select v-model="contentId" required>
          <option v-for="c in contents" :key="c.content_id" :value="c.content_id">
            {{ c.title }}
          </option>
        </select>
      </label>
      <label class="check">
        <input v-model="manualEngagement" type="checkbox" />
        这是重要笔记，发布成功后到「互动链」手动编排
      </label>
      <div class="actions">
        <button type="submit" :disabled="publishing || scheduling || !selectedAccountIds.length">
          {{ publishing ? '提交中…' : '立即发布' }}
        </button>
        <button
          type="button"
          class="secondary"
          :disabled="publishing || scheduling || !selectedAccountIds.length"
          @click="addToSchedule"
        >
          {{ scheduling ? '加入中…' : '加入排期' }}
        </button>
      </div>
      <p v-if="message" class="ok">{{ message }}</p>
      <p v-if="error" class="error">{{ error }}</p>
      <p class="hint">
        点击后立即返回，不等待手机跑完。进度看本页下方任务条，或打开「任务」。已暂停/离线账号不可选。
      </p>
    </form>
  </section>
</template>

<style scoped>
.form {
  max-width: 560px;
  background: #fff;
  border-radius: 12px;
  padding: 24px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}
fieldset { border: 1px solid #e5e7eb; border-radius: 8px; padding: 12px; }
legend { padding: 0 6px; font-weight: 600; }
.check { display: flex; gap: 8px; align-items: center; font-weight: 500; margin: 6px 0; }
.check.disabled { opacity: 0.55; }
label:not(.check) { display: flex; flex-direction: column; gap: 6px; font-weight: 600; }
select, button { padding: 10px 12px; border-radius: 8px; font: inherit; }
.actions { display: flex; flex-wrap: wrap; gap: 10px; }
button { background: #2d6a4f; color: #fff; border: 0; font-weight: 600; cursor: pointer; align-self: flex-start; }
button.secondary { background: #344054; }
button:disabled { opacity: 0.6; cursor: not-allowed; }
.focus-banner {
  background: #fff7ed;
  border: 1px solid #fdba74;
  border-radius: 8px;
  padding: 10px 14px;
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
  align-items: center;
  margin-bottom: 14px;
}
.link-btn {
  background: none;
  border: 0;
  color: #2d6a4f;
  font-weight: 600;
  cursor: pointer;
  padding: 0;
  text-decoration: none;
  font: inherit;
}
.ok { color: #2d6a4f; margin: 0; }
.error { color: #b42318; margin: 0; }
.hint { color: #5c6770; margin: 0; font-size: 0.9rem; }
</style>
