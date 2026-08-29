import { beforeEach, describe, expect, it, vi } from 'vitest'

const { render, unmount, createRoot } = vi.hoisted(() => {
  const render = vi.fn()
  const unmount = vi.fn()
  return { render, unmount, createRoot: vi.fn(() => ({ render, unmount })) }
})

vi.mock('react-dom', () => ({
  default: { createRoot },
}))

import { mountPerformative } from './mountPerformative'

describe('mountPerformative', () => {
  beforeEach(() => {
    render.mockClear()
    unmount.mockClear()
    createRoot.mockClear()
  })

  it('mounts and disposes a React component', () => {
    const container = {} as HTMLElement
    const dispose = mountPerformative(container, () => null, { label: '状态' })

    expect(createRoot).toHaveBeenCalledWith(container)
    expect(render).toHaveBeenCalledOnce()
    dispose()
    expect(unmount).toHaveBeenCalledOnce()
  })

  it('unmounts an existing root before mounting again', () => {
    const container = {} as HTMLElement
    mountPerformative(container, () => null)
    mountPerformative(container, () => null)

    expect(unmount).toHaveBeenCalledOnce()
    expect(createRoot).toHaveBeenCalledTimes(2)
  })
})
