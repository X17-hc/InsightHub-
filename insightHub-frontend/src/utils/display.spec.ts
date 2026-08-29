import { describe, expect, it } from 'vitest'
import { canRetryTask, isReportVersion } from './display'

describe('research execution and quality retry policy', () => {
  it('allows retry for execution failure and completed low-quality reports', () => {
    expect(canRetryTask('FAILED', 'NOT_EVALUATED')).toBe(true)
    expect(canRetryTask('COMPLETED', 'FAIL')).toBe(true)
    expect(canRetryTask('COMPLETED', 'LEGACY_SYNTHETIC')).toBe(true)
  })

  it('does not retry a completed quality pass', () => {
    expect(canRetryTask('COMPLETED', 'PASS')).toBe(false)
  })
})

describe('isReportVersion', () => {
  it('accepts positive integers only', () => {
    expect(isReportVersion(1)).toBe(true)
    expect(isReportVersion(4)).toBe(true)
    expect(isReportVersion(undefined)).toBe(false)
    expect(isReportVersion('undefined')).toBe(false)
    expect(isReportVersion(0)).toBe(false)
    expect(isReportVersion(1.5)).toBe(false)
  })
})
