<template>
  <div>
    <div class="page-title">预约管理</div>

    <!-- 筛选 -->
    <el-card shadow="never" style="margin-bottom:20px;">
      <div class="filter-row">
        <el-input
          v-model="filterKeyword"
          placeholder="会议主题 / 预约人昵称 / 手机号"
          clearable
          style="width:240px;"
          @keyup.enter="handleSearch"
          @clear="handleSearch"
        />
        <el-select v-model="filterStatus" placeholder="预约状态" clearable style="width:140px;margin-left:12px;" @change="handleSearch">
          <el-option label="待审批" :value="0" />
          <el-option label="已通过" :value="1" />
          <el-option label="已驳回" :value="2" />
          <el-option label="已取消" :value="3" />
          <el-option label="已违约" :value="4" />
        </el-select>
        <!-- 会议室筛选：定位某间房的排期，不必再在整表里翻页 -->
        <el-select
          v-model="filterRoomId"
          placeholder="会议室"
          clearable
          style="width:150px;margin-left:12px;"
          @change="handleSearch"
        >
          <el-option v-for="r in rooms" :key="r.id" :label="r.name" :value="r.id" />
        </el-select>
        <el-select
          v-model="filterSignState"
          placeholder="签到状态"
          clearable
          style="width:140px;margin-left:12px;"
          @change="handleSearch"
        >
          <el-option label="无需签到" :value="0" />
          <el-option label="未签到" :value="1" />
          <el-option label="已签到" :value="2" />
          <el-option label="超时未签到" :value="3" />
          <el-option label="已违约" :value="4" />
        </el-select>
        <el-button type="primary" style="margin-left:12px;" @click="handleSearch">查询</el-button>
        <el-button style="margin-left:8px;" @click="resetFilter">重置</el-button>
        <span class="rule-tip">
          签到窗口为「会议开始前 15 分钟 ~ 开始后 15 分钟」，需现场扫描会议室门前的二维码；
          超时未签到自动判定违约并释放该时段。会议开始前可对审核结果改判（通过 ⇄ 驳回），
          也可代用户取消以释放被误占的时段。
        </span>
      </div>
    </el-card>

    <!-- 表格 -->
    <el-card shadow="never">
      <el-table :data="list" v-loading="loading" stripe>
        <el-table-column prop="id" label="ID" width="60" />
        <el-table-column prop="subject" label="会议主题" min-width="140">
          <template #default="{ row }">{{ row.subject || '未填写' }}</template>
        </el-table-column>
        <!-- P2-6：直接展示会议室名与预约人，审核无需再对照 ID -->
        <el-table-column prop="roomName" label="会议室" width="130">
          <template #default="{ row }">{{ row.roomName || ('#' + row.roomId) }}</template>
        </el-table-column>
        <el-table-column prop="userName" label="预约人" width="120">
          <template #default="{ row }">{{ row.userName || '-' }}</template>
        </el-table-column>
        <el-table-column label="预约时间" min-width="200">
          <template #default="{ row }">
            <div>{{ row.startTime }}</div>
            <div style="color:#94a3b8;font-size:12px;">至 {{ row.endTime }}</div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="签到状态" width="110">
          <template #default="{ row }">
            <el-tag :type="signStateType(row.signState)" size="small">{{ signStateText(row.signState) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="签到时间" width="160">
          <template #default="{ row }">{{ row.checkInTime || '-' }}</template>
        </el-table-column>
        <el-table-column prop="rejectReason" label="驳回原因" min-width="120">
          <template #default="{ row }">{{ row.rejectReason || '-' }}</template>
        </el-table-column>
        <!-- 按钮显隐由后端下发的 canApprove / canReject / canCancelByAdmin 决定，前端不再自行比较时间 -->
        <el-table-column label="操作" width="250" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="row.canApprove"
              size="small"
              type="success"
              :loading="actingId === row.id"
              @click="approve(row)"
            >{{ row.status === 2 ? '改判通过' : '通过' }}</el-button>
            <el-button
              v-if="row.canReject"
              size="small"
              type="danger"
              :loading="actingId === row.id"
              @click="reject(row)"
            >{{ row.status === 1 ? '改判驳回' : '驳回' }}</el-button>
            <!-- 管理员兜底取消：用户侧「开始前 2 小时不可取消」之外唯一能释放时段的通道 -->
            <el-button
              v-if="row.canCancelByAdmin"
              size="small"
              type="warning"
              :loading="actingId === row.id"
              @click="cancelBooking(row)"
            >取消预约</el-button>
            <span v-if="!row.canApprove && !row.canReject && !row.canCancelByAdmin" class="no-action">-</span>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        class="pagination"
        v-model:current-page="page"
        v-model:page-size="size"
        :total="total"
        :page-sizes="[10, 20, 50]"
        layout="total, sizes, prev, pager, next"
        @size-change="loadData"
        @current-change="loadData"
      />
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '../utils/request'
import { statusText, statusType, signStateText, signStateType } from '../utils/booking-status'

const list = ref([])
const loading = ref(false)
const filterStatus = ref(null)
const filterSignState = ref(null)
const filterKeyword = ref('')
const filterRoomId = ref(null)
const rooms = ref([])
const page = ref(1)
const size = ref(10)
const total = ref(0)
/** 正在提交的预约 ID，用于按钮 loading 防止连点重复提交 */
const actingId = ref(null)

const loadRooms = async () => {
  try {
    const data = await request.get('/api/admin/room/page', { params: { page: 1, size: 100 } })
    rooms.value = data.records || []
  } catch (e) {
    // request.js 已统一提示
  }
}

const loadData = async () => {
  loading.value = true
  try {
    const data = await request.get('/api/admin/booking/page', {
      params: {
        page: page.value,
        size: size.value,
        status: filterStatus.value != null ? filterStatus.value : undefined,
        signState: filterSignState.value != null ? filterSignState.value : undefined,
        roomId: filterRoomId.value != null ? filterRoomId.value : undefined,
        keyword: filterKeyword.value.trim() || undefined
      }
    })
    list.value = data.records || []
    total.value = data.total || 0
  } catch (e) {
    // request.js 已统一弹出错误提示
  } finally {
    loading.value = false
  }
}

/** 条件变化后从第一页重新查，否则会停在第 5 页却只剩 1 页数据，表现为「查询没结果」 */
const handleSearch = () => {
  page.value = 1
  loadData()
}

const resetFilter = () => {
  filterKeyword.value = ''
  filterStatus.value = null
  filterSignState.value = null
  filterRoomId.value = null
  page.value = 1
  loadData()
}

const approve = async (row) => {
  const flip = row.status === 2
  try {
    await ElMessageBox.confirm(
      flip ? '该预约曾被打回，确定改判为「已通过」吗？' : '确定通过该预约吗？',
      flip ? '改判通过' : '确认通过',
      { confirmButtonText: '通过', cancelButtonText: '取消', type: flip ? 'warning' : 'success' }
    )
  } catch (e) {
    return
  }
  actingId.value = row.id
  try {
    await request.post(`/api/admin/booking/approve/${row.id}`)
    ElMessage.success(flip ? '已改判为通过' : '已通过')
  } catch (e) {
    // 会议已开始 / 时段冲突等业务错误已由 request.js 提示
  } finally {
    actingId.value = null
    // 失败也刷新，避免管理员对着已过期的按钮反复点击
    loadData()
  }
}

const reject = async (row) => {
  const flip = row.status === 1
  let reason
  try {
    const res = await ElMessageBox.prompt(
      flip ? '该预约已通过，改判为驳回后预约人会看到驳回原因。' : '请输入驳回原因，该原因会展示给预约人。',
      flip ? '改判驳回' : '驳回预约',
      {
        confirmButtonText: '驳回',
        cancelButtonText: '取消',
        inputPlaceholder: '驳回原因（必填，最多 255 字）',
        inputValidator: (val) => {
          if (!val || !val.trim()) return '请输入驳回原因'
          if (val.trim().length > 255) return '驳回原因不能超过 255 个字符'
          return true
        }
      }
    )
    reason = res.value.trim()
  } catch (e) {
    return
  }
  actingId.value = row.id
  try {
    await request.post(`/api/admin/booking/reject/${row.id}`, { reason })
    ElMessage.success(flip ? '已改判为驳回' : '已驳回')
  } catch (e) {
    // 同上
  } finally {
    actingId.value = null
    loadData()
  }
}

/**
 * 代用户取消预约
 *
 * 用户侧受「开始前 2 小时不可取消」限制，管理员需要一条兜底通道释放被误占的会议室，
 * 取消会给预约人推送站内通知并带上原因。
 */
const cancelBooking = async (row) => {
  let reason
  try {
    const res = await ElMessageBox.prompt(
      `将取消「${row.subject || '未填写主题'}」（${row.startTime} ~ ${row.endTime}），该时段会释放给其他人预约。`,
      '取消预约',
      {
        confirmButtonText: '确定取消',
        cancelButtonText: '返回',
        inputPlaceholder: '取消原因（选填，会展示给预约人）',
        inputValidator: (val) => {
          if (val && val.trim().length > 200) return '原因不能超过 200 个字符'
          return true
        }
      }
    )
    reason = (res.value || '').trim()
  } catch (e) {
    return
  }
  actingId.value = row.id
  try {
    await request.post(`/api/admin/booking/cancel/${row.id}`, { reason })
    ElMessage.success('已取消该预约')
  } catch (e) {
    // request.js 已提示（会议已开始 / 状态已变化等）
  } finally {
    actingId.value = null
    loadData()
  }
}

onMounted(() => {
  loadRooms()
  loadData()
})
</script>

<style scoped>
.filter-row {
  display: flex;
  align-items: center;
}

.rule-tip {
  margin-left: 16px;
  font-size: 12px;
  color: #94a3b8;
  line-height: 1.5;
}

.no-action {
  color: #c0c4cc;
}

.pagination {
  margin-top: 20px;
  justify-content: flex-end;
}
</style>
