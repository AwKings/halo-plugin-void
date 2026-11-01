<script setup lang="ts">
import { axiosInstance } from '@halo-dev/api-client'
import { onMounted, ref } from 'vue'

interface VoteItem {
  metadata: {
    name: string
    creationTimestamp: string
  }
  spec: {
    subjectType: string
    subjectName: string
    action: string
    ip?: string
    userAgent?: string
    title?: string
    url?: string
  }
}

const items = ref<VoteItem[]>([])
const total = ref(0)
const page = ref(1)
const size = 20
const loading = ref(false)
const error = ref('')

async function load() {
  loading.value = true
  error.value = ''
  try {
    const { data } = await axiosInstance.get(
      `/apis/console.api.void.halo.run/v1alpha1/activity?page=${page.value}&size=${size}`,
    )
    items.value = data.items || []
    total.value = data.total || 0
  } catch (e: any) {
    error.value = e?.response?.data?.detail || e?.message || '加载失败'
  } finally {
    loading.value = false
  }
}

function goto(target: number) {
  const max = Math.max(1, Math.ceil(total.value / size))
  if (target < 1 || target > max) return
  page.value = target
  load()
}

function formatTime(value?: string) {
  if (!value) return ''
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return value
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

function actionLabel(action?: string) {
  if (action === 'up') return '赞'
  if (action === 'down') return '踩'
  return action || ''
}

function subjectLabel(type?: string) {
  if (type === 'post') return '文章'
  if (type === 'comment') return '评论'
  return type || ''
}

onMounted(load)
</script>

<template>
  <div class="void-activity">
    <header class="void-activity__header">
      <div>
        <h1>访客互动</h1>
        <p>文章点赞与评论赞踩记录，共 {{ total }} 条。数据来自 VOID 增强插件。</p>
      </div>
      <button type="button" :disabled="loading" @click="load">刷新</button>
    </header>

    <p v-if="error" class="void-activity__error">{{ error }}</p>
    <p v-else-if="loading" class="void-activity__empty">正在加载…</p>
    <p v-else-if="items.length === 0" class="void-activity__empty">暂无互动记录。</p>

    <table v-else class="void-activity__table">
      <thead>
        <tr>
          <th>时间</th>
          <th>类型</th>
          <th>动作</th>
          <th>目标</th>
          <th>IP</th>
          <th>User-Agent</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="item in items" :key="item.metadata.name">
          <td class="nowrap">{{ formatTime(item.metadata.creationTimestamp) }}</td>
          <td>{{ subjectLabel(item.spec.subjectType) }}</td>
          <td>
            <span :class="['badge', item.spec.action === 'up' ? 'badge--up' : 'badge--down']">
              {{ actionLabel(item.spec.action) }}
            </span>
          </td>
          <td class="title-cell">
            <a v-if="item.spec.url" :href="item.spec.url" target="_blank" rel="noopener">
              {{ item.spec.title || item.spec.subjectName }}
            </a>
            <span v-else>{{ item.spec.title || item.spec.subjectName }}</span>
          </td>
          <td class="nowrap">{{ item.spec.ip || '-' }}</td>
          <td class="ua-cell" :title="item.spec.userAgent">{{ item.spec.userAgent || '-' }}</td>
        </tr>
      </tbody>
    </table>

    <footer v-if="total > size" class="void-activity__pager">
      <button type="button" :disabled="page <= 1" @click="goto(page - 1)">上一页</button>
      <span>第 {{ page }} / {{ Math.max(1, Math.ceil(total / size)) }} 页</span>
      <button type="button" :disabled="page >= Math.ceil(total / size)" @click="goto(page + 1)">
        下一页
      </button>
    </footer>
  </div>
</template>

<style scoped>
.void-activity {
  padding: 1.5rem;
}
.void-activity__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 1rem;
  margin-bottom: 1.25rem;
}
.void-activity__header h1 {
  margin: 0 0 0.35rem;
  font-size: 1.35rem;
  font-weight: 600;
}
.void-activity__header p {
  margin: 0;
  color: #6b7280;
  font-size: 0.85rem;
}
.void-activity__header button,
.void-activity__pager button {
  border: 1px solid #d1d5db;
  border-radius: 0.375rem;
  background: #fff;
  padding: 0.35rem 0.8rem;
  font-size: 0.85rem;
  cursor: pointer;
}
.void-activity__header button:disabled,
.void-activity__pager button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.void-activity__table {
  width: 100%;
  border-collapse: collapse;
  font-size: 0.85rem;
  background: #fff;
  border-radius: 0.5rem;
  overflow: hidden;
}
.void-activity__table th,
.void-activity__table td {
  padding: 0.6rem 0.75rem;
  border-bottom: 1px solid #eef0f3;
  text-align: left;
  vertical-align: top;
}
.void-activity__table th {
  background: #f9fafb;
  font-weight: 600;
  color: #374151;
}
.nowrap {
  white-space: nowrap;
}
.title-cell a {
  color: #2563eb;
  text-decoration: none;
}
.ua-cell {
  max-width: 22rem;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: #6b7280;
}
.badge {
  display: inline-block;
  padding: 0.05rem 0.45rem;
  border-radius: 999px;
  font-size: 0.75rem;
}
.badge--up {
  background: #ecfdf5;
  color: #047857;
}
.badge--down {
  background: #fef2f2;
  color: #b91c1c;
}
.void-activity__empty,
.void-activity__error {
  padding: 2rem;
  text-align: center;
  color: #6b7280;
  background: #fff;
  border-radius: 0.5rem;
}
.void-activity__error {
  color: #b91c1c;
}
.void-activity__pager {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  margin-top: 1rem;
  font-size: 0.85rem;
  color: #374151;
}
</style>
