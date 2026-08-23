import { describe, expect, it } from 'vitest'
import { canRetryTask } from './display'

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
