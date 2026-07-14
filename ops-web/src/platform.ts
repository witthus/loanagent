export type Platform = 'xhs' | 'douyin'

export const ACTIVE_PLATFORMS: Platform[] = ['xhs']

export const DEFAULT_PLATFORM: Platform = 'xhs'

export function platformLabel(platform: Platform): string {
  return platform === 'xhs' ? '小红书' : '抖音'
}
