<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter, RouterLink } from 'vue-router'
import message from 'ant-design-vue/es/message'
import { ArrowRight, LockKeyhole, UserRound } from '@lucide/vue'
import { useAuthStore } from '@/stores/auth'
const auth = useAuthStore(); const route = useRoute(); const router = useRouter(); const form = ref({ username: '', password: '' }); const loading = ref(false)
async function submit() { if (!form.value.username || !form.value.password) { message.warning('请输入用户名和密码'); return }; loading.value = true; try { await auth.login(form.value.username, form.value.password); message.success('欢迎回来'); router.replace(String(route.query.redirect || '/')) } catch (error) { message.error(error instanceof Error ? error.message : '登录失败，请稍后重试') } finally { loading.value = false } }
</script>
<template><main class="auth-page"><section class="auth-panel"><div class="auth-brand"><div class="brand-mark">I</div><div><strong>InsightHub</strong><span>智能研究工作台</span></div></div><div class="auth-heading"><div class="eyebrow">WELCOME BACK</div><h1>继续你的研究</h1><p>把问题交给研究 Agent，专注于更重要的判断。</p></div><a-form layout="vertical" class="auth-form" @submit.prevent="submit"><a-form-item label="用户名"><a-input v-model:value="form.username" size="large" placeholder="输入用户名" autocomplete="username"><template #prefix><UserRound :size="16" /></template></a-input></a-form-item><a-form-item label="密码"><a-input-password v-model:value="form.password" size="large" placeholder="输入密码" autocomplete="current-password"><template #prefix><LockKeyhole :size="16" /></template></a-input-password></a-form-item><a-button type="primary" size="large" block :loading="loading" html-type="submit">登录 <ArrowRight :size="16" /></a-button></a-form><div class="auth-footer">还没有账号？<RouterLink to="/register">创建账号</RouterLink></div><div class="auth-hint">演示账号：<code>demo</code> / <code>demo123456</code></div></section></main></template>
