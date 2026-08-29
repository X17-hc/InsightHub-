import type { ElementType, ReactNode } from 'react'

export type PerformativeComponent = ElementType

export interface PerformativeButtonProps {
  variant?: 'glow' | 'shimmer' | 'ghost' | 'solid' | 'wave'
  size?: 'sm' | 'md' | 'lg'
  sparkle?: boolean
  loading?: boolean
  block?: boolean
  children?: ReactNode
}

export interface PerformativeStatusDotProps {
  color?: string
  static?: boolean
}

export interface PerformativeAuroraProps {
  blobs?: Array<{ color: string; x: number; y: number; size?: number }>
  blur?: number
  static?: boolean
  animated?: boolean
}

export interface PerformativeTokenStreamProps {
  text: string
  speedMs?: number | [number, number]
  loop?: boolean
  hideCaret?: boolean
}

export interface PerformativeSpinnerProps {
  verbs?: string[]
  verbInterval?: number
  glyphColor?: string
  info?: ReactNode
}
