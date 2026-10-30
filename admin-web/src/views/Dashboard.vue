<template>
  <div>
    <div class="page-title">数据概览</div>

    <!-- 统计卡片 -->
    <el-row :gutter="20">
      <el-col :span="6">
        <div class="stat-card" style="background: linear-gradient(135deg, #2563eb, #3b82f6);">
          <div class="num">{{ stats.roomCount ?? 0 }}</div>
          <div class="label">会议室总数</div>
          <el-icon class="icon"><OfficeBuilding /></el-icon>
        </div>
      </el-col>
      <el-col :span="6">
        <div class="stat-card" style="background: linear-gradient(135deg, #059669, #10b981);">
          <div class="num">{{ stats.userCount ?? 0 }}</div>
          <div class="label">注册用户</div>
          <el-icon class="icon"><User /></el-icon>
        </div>
      </el-col>
      <el-col :span="6">
        <div class="stat-card" style="background: linear-gradient(135deg, #7c3aed, #8b5cf6);">
          <div class="num">{{ stats.bookingCount ?? 0 }}</div>
          <div class="label">预约总数</div>
          <el-icon class="icon"><Calendar /></el-icon>
        </div>
      </el-col>
      <el-col :span="6">
        <div class="stat-card" style="background: linear-gradient(135deg, #d97706, #f59e0b);">
          <div class="num">{{ stats.pendingCount ?? 0 }}</div>
          <div class="label">待审批</div>
          <el-icon class="icon"><Clock /></el-icon>
        </div>
      </el-col>
    </el-row>

    <!-- 快捷入口 -->
    <el-card class="quick-card" shadow="never">
      <template #header>
        <span class="card-title">快捷操作</span>
      </template>
      <div class="quick-actions">
        <div class="quick-item" @click="$router.push('/rooms')">
          <div class="quick-icon blue"><el-icon><OfficeBuilding /></el-icon></div>
          <span>会议室管理</span>
        </div>
        <div class="quick-item" @click="$router.push('/bookings')">
          <div class="quick-icon green"><el-icon><Calendar /></el-icon></div>
          <span>预约管理</span>
        </div>
        <div class="quick-item" @click="$router.push('/users')">
          <div class="quick-icon orange"><el-icon><User /></el-icon></div>
          <span>用户管理</span>
        </div>
        <div class="quick-item" @click="$router.push('/announcements')">
          <div class="quick-icon purple"><el-icon><Bell /></el-icon></div>
          <span>公告管理</span>
        </div>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import request from '../utils/request'

const stats = ref({})

onMounted(async () => {
  try {
    stats.value = await request.get('/api/admin/stats')
  } catch (e) {}
})
</script>

<style scoped>
.card-title {
  font-size: 16px;
  font-weight: 600;
  color: #1a2233;
}

.quick-card {
  margin-top: 20px;
}

.quick-actions {
  display: flex;
  gap: 24px;
}

.quick-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  cursor: pointer;
  padding: 20px 30px;
  border-radius: 12px;
  transition: all 0.2s;
}

.quick-item:hover {
  background: #f8fafc;
}

.quick-icon {
  width: 48px;
  height: 48px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 24px;
}
.quick-icon.blue { background: #eff6ff; color: #2563eb; }
.quick-icon.green { background: #ecfdf5; color: #059669; }
.quick-icon.purple { background: #f5f3ff; color: #7c3aed; }
.quick-icon.orange { background: #fff7ed; color: #d97706; }

.quick-item span {
  font-size: 14px;
  color: #475569;
}
</style>
