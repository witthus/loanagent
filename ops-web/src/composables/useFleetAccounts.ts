import { ref } from 'vue'
import { api } from '@/lib/api'
import {
  accountOptionLabel,
  buildDeviceMap,
  isAccountRunnable,
  type FleetAccount,
  type FleetDevice,
} from '@/lib/fleet'
import { buildAccountNameMap } from '@/lib/labels'
import { DEFAULT_PLATFORM } from '@/platform'

/** Shared account + device load for ops pickers. */
export function useFleetAccounts() {
  const accounts = ref<FleetAccount[]>([])
  const devicesById = ref<Record<string, FleetDevice>>({})
  const accountNames = ref<Record<string, string>>({})

  async function loadFleet() {
    const [accountRows, deviceRows] = await Promise.all([
      api<FleetAccount[]>(`/api/v1/accounts?platform=${DEFAULT_PLATFORM}`),
      api<FleetDevice[]>('/api/v1/devices').catch(() => [] as FleetDevice[]),
    ])
    accounts.value = accountRows
    devicesById.value = buildDeviceMap(deviceRows)
    accountNames.value = buildAccountNameMap(accountRows)
  }

  function optionLabel(account: FleetAccount): string {
    return accountOptionLabel(account, devicesById.value)
  }

  function runnable(account: FleetAccount): boolean {
    return isAccountRunnable(account, devicesById.value)
  }

  return {
    accounts,
    devicesById,
    accountNames,
    loadFleet,
    optionLabel,
    runnable,
  }
}
