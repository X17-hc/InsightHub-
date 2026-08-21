import { beforeEach, describe, expect, it, vi } from 'vitest'

const mocks = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
}))

vi.mock('./http', () => ({
  http: {
    get: mocks.get,
    post: mocks.post,
    put: vi.fn(),
    delete: vi.fn(),
  },
}))

import { researchTaskApi } from './researchTask'

describe('researchTaskApi plan endpoints', () => {
  beforeEach(() => vi.clearAllMocks())

  it('queries the current plan and immutable history', () => {
    researchTaskApi.currentPlan('workspace-1', 'task-1')
    researchTaskApi.planHistory('workspace-1', 'task-1')
    expect(mocks.get).toHaveBeenNthCalledWith(1, '/v1/workspaces/workspace-1/research/tasks/task-1/plan')
    expect(mocks.get).toHaveBeenNthCalledWith(2, '/v1/workspaces/workspace-1/research/tasks/task-1/plans')
  })

  it('submits only optimistic-lock approval fields', () => {
    researchTaskApi.approvePlan('workspace-1', 'task-1', { expectedRevision: 3, remark: '同意执行' })
    expect(mocks.post).toHaveBeenCalledWith(
      '/v1/workspaces/workspace-1/research/tasks/task-1/plan/approve',
      { expectedRevision: 3, remark: '同意执行' },
    )
  })

  it('submits a textual revision instead of plan JSON', () => {
    researchTaskApi.revisePlan('workspace-1', 'task-1', { expectedRevision: 3, revision: '补充竞品定价研究' })
    expect(mocks.post).toHaveBeenCalledWith(
      '/v1/workspaces/workspace-1/research/tasks/task-1/plan/revise',
      { expectedRevision: 3, revision: '补充竞品定价研究' },
    )
  })
})
