<template>
  <el-container class="layout">
    <!-- 侧边栏 -->
    <el-aside width="220px" class="sidebar">
      <div class="logo">
        <img class="logo-mark" :src="logoUrl" alt="会议室预约系统" />
        <span class="logo-text">会议室预约系统</span>
      </div>
      <el-menu
        :default-active="activeMenu"
        class="menu"
        background-color="#1e293b"
        text-color="#94a3b8"
        active-text-color="#ffffff"
        router
      >
        <el-menu-item index="/dashboard">
          <el-icon><DataLine /></el-icon>
          <span>数据概览</span>
        </el-menu-item>
        <el-menu-item index="/rooms">
          <el-icon><OfficeBuilding /></el-icon>
          <span>会议室管理</span>
        </el-menu-item>
        <el-menu-item index="/bookings">
          <el-icon><Calendar /></el-icon>
          <span>预约管理</span>
        </el-menu-item>
        <el-menu-item index="/users">
          <el-icon><User /></el-icon>
          <span>用户管理</span>
        </el-menu-item>
        <el-menu-item index="/announcements">
          <el-icon><Bell /></el-icon>
          <span>公告管理</span>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <!-- 顶部栏 -->
      <el-header class="header">
        <div class="header-title">{{ pageTitle }}</div>
        <div class="header-right">
          <el-dropdown @command="handleCommand">
            <div class="admin-info">
              <el-avatar :size="32" class="admin-avatar">{{ avatarText }}</el-avatar>
              <span class="admin-name">{{ adminStore.adminInfo?.realName || '管理员' }}</span>
              <el-icon><ArrowDown /></el-icon>
            </div>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="logout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>

      <!-- 主内容 -->
      <el-main class="main-content">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox, ElMessage } from 'element-plus'
import { useAdminStore } from '../store/admin'
import request from '../utils/request'
import logoUrl from '../assets/logo.png'

const route = useRoute()
const router = useRouter()
const adminStore = useAdminStore()

const activeMenu = computed(() => route.path)
const pageTitle = computed(() => route.meta.title || '')

const avatarText = computed(() => {
  const name = adminStore.adminInfo?.realName || '管'
  return name.substring(0, 1)
})

const handleCommand = (command) => {
  if (command === 'logout') {
    ElMessageBox.confirm('确定要退出登录吗？', '提示', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    }).then(async () => {
      // P2-4：先通知后端注销 token，避免服务端会话仍然有效
      try {
        await request.post('/api/admin/auth/logout')
      } catch (e) {}
      adminStore.logout()
      ElMessage.success('已退出登录')
      router.push('/login')
    }).catch(() => {})
  }
}
</script>

<style scoped>
.layout {
  height: 100vh;
}

.sidebar {
  background: #1e293b;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.logo {
  height: 60px;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 20px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
}

.logo-mark {
  width: 32px;
  height: 32px;
  display: block;
  flex-shrink: 0;
  object-fit: contain;
  user-select: none;
  -webkit-user-drag: none;
}

.logo-text {
  color: #fff;
  font-size: 15px;
  font-weight: 600;
  white-space: nowrap;
}

.menu {
  border-right: none;
  flex: 1;
}

.menu :deep(.el-menu-item) {
  height: 50px;
  line-height: 50px;
}

.menu :deep(.el-menu-item.is-active) {
  background: rgba(59, 130, 246, 0.18) !important;
  border-left: 3px solid #3b82f6;
}

.menu :deep(.el-menu-item:hover) {
  background: rgba(255, 255, 255, 0.06) !important;
}

.header {
  background: #fff;
  border-bottom: 1px solid #e8ecf3;
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 60px;
  padding: 0 24px;
}

.header-title {
  font-size: 16px;
  font-weight: 600;
  color: #1a2233;
}

.header-right {
  display: flex;
  align-items: center;
}

.admin-info {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
}

.admin-avatar {
  background: #2563eb;
}

.admin-name {
  font-size: 14px;
  color: #475569;
}

.main-content {
  background: #f5f7fa;
  padding: 24px;
  overflow-y: auto;
}
</style>
