import { ApiError } from '@/lib/api'
import { humanizeError } from '@/lib/humanize'

function parseApiErrorCode(body: string): string | null {
  try {
    const json = JSON.parse(body)
    const detail = json?.detail
    if (typeof detail === 'string') return null
    return detail?.code ?? json?.code ?? null
  } catch {
    return null
  }
}

/** Prefer humanized title+nextStep when API returns a known code. */
export function formatApiError(err: unknown, fallback = '操作失败'): string {
  if (!(err instanceof ApiError)) return fallback
  const code = parseApiErrorCode(err.body)
  if (code === 'CONTACT_FORBIDDEN') {
    return '不能直接在私信里写微信号或手机号，请用平台内私信沟通。'
  }
  if (code === 'COMPLIANCE_REJECTED' || err.body.includes('COMPLIANCE_REJECTED')) {
    return '内容未通过合规检查，请改写后再试。'
  }
  if (code) {
    const human = humanizeError(code)
    return `${human.title}。${human.nextStep}`
  }
  return err.body || fallback
}
