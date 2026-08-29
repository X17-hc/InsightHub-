import React from 'react'
import ReactDOM from 'react-dom'
import type { Root } from 'react-dom/client'
import type { PerformativeComponent } from './performativeTypes'

/**
 * 本仓库关闭了 Vite optimizeDeps 预构建时，浏览器会直接加载 CJS 的 react-dom/client.js，
 * 命名导入 `import { createRoot } from 'react-dom/client'` 会失败。
 * 改为从 react-dom 默认导出取 createRoot（React 18+ 已提供）。
 */
type ReactDomWithRoot = typeof ReactDOM & {
  createRoot: (container: Element | DocumentFragment) => Root
}

const roots = new WeakMap<HTMLElement, Root>()

export function mountPerformative(
  container: HTMLElement,
  component: PerformativeComponent,
  props: Record<string, unknown> = {},
): () => void {
  const existing = roots.get(container)
  existing?.unmount()
  const createRoot = (ReactDOM as ReactDomWithRoot).createRoot
  if (typeof createRoot !== 'function') {
    throw new Error('react-dom.createRoot is unavailable; require react-dom@18+')
  }
  const root = createRoot(container)
  roots.set(container, root)
  root.render(React.createElement(component, props))

  return () => {
    if (roots.get(container) === root) {
      root.unmount()
      roots.delete(container)
    }
  }
}
