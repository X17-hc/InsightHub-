/**
 * Small local visual adapters used by the Vue application.
 *
 * The previous UI referenced an unavailable React-only package. Keeping these
 * components local makes the application buildable while preserving the same
 * declarative call sites and avoiding a React runtime inside the Vue bundle.
 */
import { defineComponent, h, type Component, type PropType } from 'vue'

export const PerformativeIsland = defineComponent({
  name: 'PerformativeIsland',
  props: {
    component: { type: [Object, Function] as PropType<Component>, required: true },
    componentProps: { type: Object as PropType<Record<string, unknown>>, default: () => ({}) },
  },
  setup(props, { attrs }) {
    return () => h(props.component, { ...attrs, ...props.componentProps })
  },
})

export const Sparkle = defineComponent({
  name: 'Sparkle',
  setup: () => () => h('span', { 'aria-hidden': 'true' }, '✦'),
})

export const StatusDot = defineComponent({
  name: 'StatusDot',
  props: { color: { type: String, default: 'currentColor' } },
  setup: (props) => () => h('span', { class: 'online-dot', style: { backgroundColor: props.color }, 'aria-hidden': 'true' }),
})

export const WibblingSpinner = defineComponent({
  name: 'WibblingSpinner',
  setup: () => () => h('span', { class: 'critic-pulse', 'aria-hidden': 'true' }),
})

export const GlassCard = defineComponent({
  name: 'GlassCard',
  setup: () => () => h('span', { 'aria-hidden': 'true' }),
})

export const TokenStream = defineComponent({
  name: 'TokenStream',
  props: { text: { type: String, required: true } },
  setup: (props) => () => h('span', props.text),
})
