<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import message from "ant-design-vue/es/message";
import { ArrowLeft, BookOpen, FilePlus2, Sparkles } from "@lucide/vue";
import { knowledgeApi } from "@/api/knowledge";
import { researchTaskApi } from "@/api/researchTask";
import AppShell from "@/components/AppShell.vue";
import PageHeader from "@/components/PageHeader.vue";
import StatusTag from "@/components/StatusTag.vue";
import type { KnowledgeBase } from "@/types";
const route = useRoute();
const router = useRouter();
const workspaceId = computed(() => String(route.params.workspaceId));
const knowledgeBases = ref<KnowledgeBase[]>([]);
const selected = ref<string[]>([]);
const query = ref("");
const enableDataAnalysis = ref(false);
const loading = ref(false);
async function load() {
  try {
    knowledgeBases.value = await knowledgeApi.list(workspaceId.value);
  } catch (error) {
    message.error(error instanceof Error ? error.message : "知识库加载失败");
  }
}

/** 创建异步任务并进入详情页（须 await 路由，否则懒加载失败会被吞掉） */
async function submit() {
  if (!query.value.trim()) {
    message.warning("请先描述你想研究的问题");
    return;
  }
  loading.value = true;
  try {
    const accepted = await researchTaskApi.create(workspaceId.value, {
      query: query.value.trim(),
      knowledgeBaseIds: selected.value,
      enableDataAnalysis: enableDataAnalysis.value,
    });
    if (!accepted?.taskId) {
      throw new Error("创建成功但未返回 taskId");
    }
    await router.replace({
      name: "task-detail",
      params: { workspaceId: workspaceId.value, taskId: accepted.taskId },
    });
  } catch (error) {
    message.error(error instanceof Error ? error.message : "任务创建失败");
  } finally {
    loading.value = false;
  }
}

onMounted(load);
function toggleKnowledge(id: string, checked: boolean) {
  selected.value = checked
    ? [...selected.value, id]
    : selected.value.filter((item) => item !== id);
}
</script>
<template>
  <AppShell>
    <div class="page-content task-create-page">
      <PageHeader
        eyebrow="NEW RESEARCH"
        title="创建研究任务"
        description="描述一个清晰的问题，InsightHub 会规划并执行研究流程。"
      >
        <a-button @click="router.back()">
          <ArrowLeft :size="15" />
          返回任务列表
        </a-button>
      </PageHeader>
      <div class="create-grid">
        <section class="soft-panel create-main">
          <div class="panel-heading">
            <div>
              <h2>研究问题</h2>
              <p>越具体的问题，越容易得到可行动的结论。</p>
            </div>
            <Sparkles :size="18" class="panel-icon" />
          </div>
          <div class="create-form">
            <a-textarea
              v-model:value="query"
              :rows="8"
              :maxlength="2000"
              show-count
              placeholder="例如：分析 2025 年中国企业级 AI 知识库市场的主要趋势、竞争格局与进入机会。"
            />
            <div class="example-queries">
              <span>可以从这些角度开始：</span>
              <button
                @click="
                  query = '分析中国企业级 AI 知识库市场的主要趋势与竞争格局'
                "
              >
                市场趋势
              </button>
              <button
                @click="query = '比较三家主要竞品的产品定位、定价和客户评价'"
              >
                竞品比较
              </button>
              <button
                @click="query = '整理这个领域近一年的重要变化，并给出行动建议'"
              >
                行动建议
              </button>
            </div>
            <a-checkbox v-model:checked="enableDataAnalysis">
              生成分析产物（将使用受限 Sandbox，可能延长任务时间）
            </a-checkbox>
          </div>
        </section>
        <aside class="soft-panel knowledge-picker">
          <div class="panel-heading">
            <div>
              <h2>参考知识库</h2>
              <p>可选，帮助 Agent 使用你的内部资料。</p>
            </div>
            <BookOpen :size="18" class="panel-icon" />
          </div>
          <div v-if="knowledgeBases.length" class="kb-options">
            <label
              v-for="kb in knowledgeBases"
              :key="kb.id"
              class="kb-pick-row"
              :class="{
                selected: selected.includes(kb.id),
                disabled: kb.status !== 'ACTIVE',
              }"
            >
              <a-checkbox
                :checked="selected.includes(kb.id)"
                :disabled="kb.status !== 'ACTIVE'"
                @change="toggleKnowledge(kb.id, $event.target.checked)"
              ></a-checkbox>
              <div>
                <strong>{{ kb.name }}</strong>
                <span>
                  {{ kb.docCount }} 份文档 ·
                  <StatusTag
                    :status="kb.status === 'ACTIVE' ? 'INDEXED' : 'FAILED'"
                  />
                </span>
              </div>
            </label>
          </div>
          <div v-else class="empty-state compact">
            <BookOpen :size="24" />
            <h3>暂无知识库</h3>
            <p>可以稍后在知识库页面创建。</p>
          </div>
          <div class="create-footer">
            <a-button
              type="primary"
              size="large"
              block
              :loading="loading"
              @click="submit"
            >
              <FilePlus2 :size="16" />
              开始异步研究
            </a-button>
            <small>创建后会进入任务详情并接收实时执行动态</small>
          </div>
        </aside>
      </div>
    </div>
  </AppShell>
</template>
