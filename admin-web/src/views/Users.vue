<template>
  <div>
    <div class="page-header">
      <div class="page-title">用户管理</div>
      <el-input
        v-model="keyword"
        placeholder="搜索昵称 / 手机号"
        clearable
        style="width: 260px;"
        @keyup.enter="handleSearch"
        @clear="handleSearch"
      >
        <template #append>
          <el-button @click="handleSearch">搜索</el-button>
        </template>
      </el-input>
    </div>

    <el-card shadow="never">
      <el-table :data="list" v-loading="loading" stripe>
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="nickname" label="昵称" min-width="140">
          <template #default="{ row }">{{ row.nickname || '-' }}</template>
        </el-table-column>
        <el-table-column prop="phone" label="手机号" width="150">
          <template #default="{ row }">{{ row.phone || '-' }}</template>
        </el-table-column>
        <el-table-column label="连续违约" width="100">
          <template #default="{ row }">
            <el-tag :type="(row.violationCount || 0) > 0 ? 'danger' : 'info'" size="small">
              {{ row.violationCount || 0 }} 次
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="账号状态" width="110">
          <template #default="{ row }">
            <el-tag :type="row.status === 0 ? 'danger' : 'success'" size="small">
              {{ row.status === 0 ? '已禁用' : '正常' }}
            </el-tag>
          </template>
        </el-table-column>
        <!-- 封禁分两种：限时封禁只停预约权限（可登录），永久禁用直接停用账号 -->
        <el-table-column label="预约权限" width="200">
          <template #default="{ row }">
            <el-tag v-if="isPermanent(row)" type="info" size="small">永久禁用</el-tag>
            <el-tag v-else-if="isTimeBanned(row)" type="danger" size="small">
              封禁至 {{ row.bannedUntil }}<span v-if="row.banSource === 2">（人工）</span>
            </el-tag>
            <el-tag v-else type="success" size="small">正常</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="注册时间" width="180" />
        <el-table-column label="操作" width="250" fixed="right">
          <template #default="{ row }">
            <el-button size="small" @click="openDetail(row)">详情</el-button>
            <!-- 永久禁用与限时封禁是两套动作：前者账号级，后者权限级，不能共用一个按钮 -->
            <el-button
              v-if="isPermanent(row)"
              size="small"
              type="primary"
              @click="handleEnable(row)"
            >解除禁用</el-button>
            <el-button
              v-else-if="isTimeBanned(row)"
              size="small"
              type="warning"
              @click="handleUnban(row)"
            >解封</el-button>
            <el-button
              v-else
              size="small"
              type="danger"
              @click="openBan(row)"
            >封禁</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pagination">
        <el-pagination
          v-model:current-page="page"
          :page-size="size"
          :total="total"
          layout="total, prev, pager, next"
          @current-change="loadData"
        />
      </div>
    </el-card>

    <!-- 封禁弹窗 -->
    <el-dialog v-model="banVisible" :title="`封禁用户「${banTargetName}」`" width="440px">
      <el-radio-group v-model="banDays" class="ban-options">
        <el-radio :value="1">1 天</el-radio>
        <el-radio :value="7">7 天</el-radio>
        <el-radio :value="30">30 天</el-radio>
        <el-radio value="forever">永久禁用</el-radio>
      </el-radio-group>
      <div class="ban-tip">
        <p>限时封禁：仍可登录浏览，但无法新建或修改预约，到期后自动恢复。</p>
        <p>永久禁用：立即强制下线，且无法再登录，需在列表点「解除禁用」才能恢复。</p>
        <p>注意：封禁不会取消该用户已有的预约；已被永久禁用的账号不能再选限时封禁。</p>
      </div>
      <template #footer>
        <el-button @click="banVisible = false">取消</el-button>
        <el-button type="danger" :loading="banSubmitting" @click="submitBan">确定封禁</el-button>
      </template>
    </el-dialog>

    <!-- 用户详情抽屉 -->
    <el-drawer v-model="detailVisible" title="用户详情" size="560px">
      <div v-loading="detailLoading">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="昵称">{{ detail.nickname || '-' }}</el-descriptions-item>
          <el-descriptions-item label="手机号">{{ detail.phone || '-' }}</el-descriptions-item>
          <el-descriptions-item label="账号状态">
            <el-tag :type="detail.status === 0 ? 'danger' : 'success'" size="small">
              {{ detail.status === 0 ? '已禁用' : '正常' }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="预约权限">
            <el-tag v-if="detail.banType === 'permanent'" type="info" size="small">永久禁用</el-tag>
            <el-tag v-else-if="detail.banType === 'temporary'" type="danger" size="small">
              封禁至 {{ detail.bannedUntil }}（{{ detail.banSource === 2 ? '管理员人工' : '连续违约自动' }}）
            </el-tag>
            <el-tag v-else type="success" size="small">正常</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="连续违约">{{ detail.violationCount || 0 }} 次</el-descriptions-item>
          <el-descriptions-item label="注册时间">{{ detail.createTime || '-' }}</el-descriptions-item>
        </el-descriptions>

        <div class="section-title">预约统计</div>
        <el-descriptions :column="4" border size="small">
          <el-descriptions-item label="总数">{{ detail.total || 0 }}</el-descriptions-item>
          <el-descriptions-item label="未结束">{{ detail.upcoming || 0 }}</el-descriptions-item>
          <el-descriptions-item label="待审批">{{ detail.pending || 0 }}</el-descriptions-item>
          <el-descriptions-item label="已通过">{{ detail.approved || 0 }}</el-descriptions-item>
          <el-descriptions-item label="已驳回">{{ detail.rejected || 0 }}</el-descriptions-item>
          <el-descriptions-item label="已取消">{{ detail.canceled || 0 }}</el-descriptions-item>
          <el-descriptions-item label="已违约">{{ detail.violated || 0 }}</el-descriptions-item>
        </el-descriptions>

        <div class="section-title">最近预约（最多 10 条）</div>
        <el-table :data="detail.recentBookings || []" size="small" v-if="(detail.recentBookings || []).length">
          <el-table-column prop="subject" label="主题" min-width="110">
            <template #default="{ row }">{{ row.subject || '未填写' }}</template>
          </el-table-column>
          <el-table-column label="会议室" width="110">
            <template #default="{ row }">{{ row.roomName || ('#' + row.roomId) }}</template>
          </el-table-column>
          <el-table-column prop="startTime" label="开始时间" width="150" />
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag :type="statusType(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="驳回原因" min-width="110">
            <template #default="{ row }">{{ row.rejectReason || '-' }}</template>
          </el-table-column>
        </el-table>
        <el-empty v-else description="暂无预约记录" :image-size="70" />
      </div>
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '../utils/request'
import { statusText, statusType } from '../utils/booking-status'

const list = ref([])
const loading = ref(false)
const keyword = ref('')
const page = ref(1)
const size = 10
const total = ref(0)

const banVisible = ref(false)
const banTarget = ref(null)
const banDays = ref(7)
const banSubmitting = ref(false)
const banTargetName = computed(() => {
  const t = banTarget.value
  return t ? (t.nickname || t.phone || t.id) : ''
})

const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = reactive({ recentBookings: [] })

/** 限时封禁未到期（永久禁用看 permanentBan，不看 bannedUntil） */
const isTimeBanned = (row) => {
  if (!row.bannedUntil) return false
  return new Date(row.bannedUntil.replace(/-/g, '/')) > new Date()
}

/** 是否永久禁用：由独立标记决定，不再用 status 反推（status 会被限时封禁改动） */
const isPermanent = (row) => row.permanentBan === 1 || row.banType === 'permanent'

const loadData = async () => {
  loading.value = true
  try {
    const res = await request.get('/api/admin/user/page', {
      params: { page: page.value, size, keyword: keyword.value || undefined }
    })
    list.value = res.records || []
    total.value = res.total || 0
  } catch (e) {
    // request.js 已统一提示
  } finally {
    loading.value = false
  }
}

const handleSearch = () => {
  page.value = 1
  loadData()
}

const openBan = (row) => {
  banTarget.value = row
  banDays.value = 7
  banVisible.value = true
}

const submitBan = async () => {
  const row = banTarget.value
  if (!row) return
  const days = banDays.value === 'forever' ? null : banDays.value
  const label = days === null ? '永久禁用' : `${days} 天`
  try {
    await ElMessageBox.confirm(
      `确定对用户「${banTargetName.value}」执行「${label}」吗？该用户会被立即强制下线。`,
      '封禁确认',
      { confirmButtonText: '确定封禁', cancelButtonText: '取消', type: 'warning' }
    )
  } catch (e) {
    return
  }
  banSubmitting.value = true
  try {
    await request.post(`/api/admin/user/ban/${row.id}`, { days })
    ElMessage.success(days === null ? '已永久禁用' : `已封禁 ${days} 天`)
    banVisible.value = false
  } catch (e) {
    // 同上
  } finally {
    banSubmitting.value = false
    loadData()
  }
}

const handleUnban = (row) => {
  ElMessageBox.confirm(
    `确定解除用户「${row.nickname || row.phone || row.id}」的封禁吗？解封后该用户可立即登录并发起预约。`,
    '解封确认',
    { confirmButtonText: '解封', cancelButtonText: '取消', type: 'warning' }
  ).then(async () => {
    try {
      await request.post(`/api/admin/user/unban/${row.id}`)
      ElMessage.success('解封成功')
    } catch (e) {
      // 同上
    } finally {
      loadData()
    }
  }).catch(() => {})
}

/** 解除永久禁用（账号级动作，与「解封限时封禁」分开） */
const handleEnable = (row) => {
  ElMessageBox.confirm(
    `确定解除用户「${row.nickname || row.phone || row.id}」的永久禁用吗？解除后该账号可重新登录并预约，违约计数会清零。`,
    '解除禁用确认',
    { confirmButtonText: '解除禁用', cancelButtonText: '取消', type: 'warning' }
  ).then(async () => {
    try {
      await request.post(`/api/admin/user/enable/${row.id}`)
      ElMessage.success('已解除永久禁用')
    } catch (e) {
      // 同上
    } finally {
      loadData()
    }
  }).catch(() => {})
}

const openDetail = async (row) => {
  detailVisible.value = true
  detailLoading.value = true
  Object.assign(detail, {
    id: row.id,
    nickname: row.nickname,
    phone: row.phone,
    status: row.status,
    violationCount: row.violationCount,
    bannedUntil: row.bannedUntil,
    createTime: row.createTime,
    permanentBan: row.permanentBan,
    banSource: row.banSource,
    banType: isPermanent(row) ? 'permanent' : (isTimeBanned(row) ? 'temporary' : null),
    total: 0,
    pending: 0,
    approved: 0,
    rejected: 0,
    canceled: 0,
    violated: 0,
    upcoming: 0,
    recentBookings: []
  })
  try {
    const res = await request.get(`/api/admin/user/detail/${row.id}`)
    Object.assign(detail, res)
  } catch (e) {
    // 同上
  } finally {
    detailLoading.value = false
  }
}

onMounted(() => {
  loadData()
})
</script>

<style scoped>
.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 20px;
}
.page-header .page-title {
  margin-bottom: 0;
}
.pagination {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
}
.ban-options {
  display: flex;
  gap: 4px;
}
.ban-tip {
  margin-top: 16px;
  padding: 12px 14px;
  background: #f8fafc;
  border-radius: 6px;
  font-size: 12px;
  color: #64748b;
  line-height: 1.7;
}
.ban-tip p {
  margin: 0;
}
.section-title {
  margin: 20px 0 10px;
  font-size: 14px;
  font-weight: 600;
  color: #1f2937;
}
</style>
