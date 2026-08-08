<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import message from 'ant-design-vue/es/message'
import { Layers3, Plus } from '@lucide/vue'
import { useWorkspaceStore } from '@/stores/workspace'
const workspace = useWorkspaceStore(); const router = useRouter(); const empty = ref(false); const creating = ref(false); const form = ref({ name: '', description: '' })
onMounted(async () => { try { await workspace.load(); if (workspace.currentId) router.replace({ name: 'tasks', params: { workspaceId: workspace.currentId } }); else empty.value = true } catch (error) { empty.value = true; message.error(error instanceof Error ? error.message : '工作空间加载失败') } })
async function createWorkspace() { if (!form.value.name.trim()) { message.warning('请输入工作空间名称'); return }; creating.value = true; try { const created = await workspace.create({ name: form.value.name.trim(), description: form.value.description || undefined }); router.replace({ name: 'tasks', params: { workspaceId: created.id } }) } catch (error) { message.error(error instanceof Error ? error.message : '创建失败') } finally { creating.value = false } }
</script>
<template><main v-if="!empty" class="loading-page"><a-spin size="large" /><span>正在打开工作空间…</span></main><main v-else class="auth-page"><section class="auth-panel workspace-onboarding"><div class="onboarding-icon"><Layers3 :size="23"/></div><div class="auth-heading"><div class="eyebrow">FIRST WORKSPACE</div><h1>创建第一个工作空间</h1><p>工作空间用于隔离研究任务、知识库和团队成员。</p></div><a-form layout="vertical" @submit.prevent="createWorkspace"><a-form-item label="名称" required><a-input v-model:value="form.name" size="large" maxlength="64" placeholder="例如：产品研究组"/></a-form-item><a-form-item label="描述"><a-textarea v-model:value="form.description" :rows="3" maxlength="256" placeholder="可选，描述主要研究范围"/></a-form-item><a-button type="primary" size="large" block :loading="creating" html-type="submit"><Plus :size="16"/> 创建并进入</a-button></a-form></section></main></template>
