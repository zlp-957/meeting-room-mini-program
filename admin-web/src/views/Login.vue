<template>
  <div class="login-page">
    <div class="login-container">
      <!-- 左侧品牌区 -->
      <div class="brand-panel">
        <div class="brand-logo">
          <img :src="logoUrl" alt="会议室预约系统" />
        </div>
        <h1 class="brand-title">会议室预约系统</h1>
        <p class="brand-sub">企业会议室资源数字化管理平台</p>
        <div class="brand-features">
          <div class="feature-item">
            <span class="feature-icon"><el-icon><Calendar /></el-icon></span>
            <span>预约管理</span>
          </div>
          <div class="feature-item">
            <span class="feature-icon"><el-icon><OfficeBuilding /></el-icon></span>
            <span>资源调度</span>
          </div>
          <div class="feature-item">
            <span class="feature-icon"><el-icon><DataLine /></el-icon></span>
            <span>数据统计</span>
          </div>
        </div>
      </div>

      <!-- 右侧登录表单 -->
      <div class="form-panel">
        <h2 class="form-title">管理员登录</h2>
        <p class="form-sub">请输入账号密码登录后台</p>

        <el-form ref="formRef" :model="form" :rules="rules" size="large" @keyup.enter="handleLogin">
          <el-form-item prop="username">
            <el-input
              v-model="form.username"
              placeholder="请输入用户名"
              :prefix-icon="User"
            />
          </el-form-item>
          <el-form-item prop="password">
            <el-input
              v-model="form.password"
              type="password"
              placeholder="请输入密码"
              :prefix-icon="Lock"
              show-password
            />
          </el-form-item>
          <el-form-item prop="captcha">
            <div class="captcha-row">
              <el-input
                v-model="form.captcha"
                placeholder="验证码"
                :prefix-icon="Key"
              />
              <div class="captcha-box" @click="refreshCaptcha">
                <img v-if="captchaImage" :src="captchaImage" class="captcha-img" alt="点击刷新验证码" />
                <span v-else class="captcha-text">加载中</span>
              </div>
            </div>
          </el-form-item>
          <el-form-item>
            <el-button
              type="primary"
              class="login-btn"
              :loading="loading"
              @click="handleLogin"
            >
              登 录
            </el-button>
          </el-form-item>
        </el-form>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { User, Lock, Key, Calendar, OfficeBuilding, DataLine } from '@element-plus/icons-vue'
import request from '../utils/request'
import { useAdminStore } from '../store/admin'
import logoUrl from '../assets/logo.png'

const router = useRouter()
const adminStore = useAdminStore()
const formRef = ref(null)
const loading = ref(false)
const captchaImage = ref('')
const captchaKey = ref('')

const form = reactive({
  username: '',
  password: '',
  captcha: '',
  captchaKey: ''
})

const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
  captcha: [{ required: true, message: '请输入验证码', trigger: 'blur' }]
}

const refreshCaptcha = async () => {
  try {
    const data = await request.get('/api/admin/auth/captcha')
    // P2-2：后端不再返回明文验证码，改为 Base64 图片
    captchaImage.value = data.captchaImage
    captchaKey.value = data.captchaKey
  } catch (e) {}
}

const handleLogin = () => {
  formRef.value.validate(async (valid) => {
    if (!valid) return
    loading.value = true
    try {
      form.captchaKey = captchaKey.value
      const data = await request.post('/api/admin/auth/login', form)
      localStorage.setItem('admin_token', data.token)
      adminStore.setAdminInfo(data.adminInfo)
      ElMessage.success('登录成功')
      router.push('/dashboard')
    } catch (e) {
      refreshCaptcha()
    } finally {
      loading.value = false
    }
  })
}

onMounted(() => {
  refreshCaptcha()
})
</script>

<style scoped>
.login-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #eef2f8;
  padding: 20px;
}

.login-container {
  display: flex;
  width: 880px;
  max-width: 100%;
  min-height: 520px;
  background: #fff;
  border-radius: 20px;
  overflow: hidden;
  box-shadow:
    0 1px 2px rgba(16, 24, 40, 0.04),
    0 8px 24px -6px rgba(16, 24, 40, 0.10),
    0 28px 64px -16px rgba(16, 24, 40, 0.18);
}

.brand-panel {
  flex: 1;
  background: #fff;
  color: #1f2937;
  padding: 50px 45px;
  display: flex;
  flex-direction: column;
  justify-content: center;
  border-right: 1px solid #edf0f5;
}

.brand-logo {
  width: 64px;
  height: 64px;
  margin-bottom: 26px;
}

.brand-logo img {
  width: 100%;
  height: 100%;
  display: block;
  object-fit: contain;
  user-select: none;
  -webkit-user-drag: none;
  filter: drop-shadow(0 8px 18px rgba(37, 99, 235, 0.22));
}

.brand-title {
  font-size: 26px;
  font-weight: 700;
  color: #101828;
  margin-bottom: 8px;
}

.brand-sub {
  font-size: 14px;
  color: #6c7486;
  margin-bottom: 0;
}

.brand-features {
  display: flex;
  flex-direction: column;
  gap: 14px;
  margin-top: 44px;
}

.feature-item {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 14px;
  color: #4b5563;
}

.feature-icon {
  width: 32px;
  height: 32px;
  border-radius: 9px;
  background: #f1f5ff;
  color: #2563eb;
  font-size: 16px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.form-panel {
  flex: 1;
  padding: 50px 45px;
  display: flex;
  flex-direction: column;
  justify-content: center;
}

.form-title {
  font-size: 24px;
  font-weight: 700;
  color: #1a2233;
  margin-bottom: 8px;
}

.form-sub {
  font-size: 14px;
  color: #6c7486;
  margin-bottom: 30px;
}

.captcha-row {
  display: flex;
  gap: 12px;
  width: 100%;
}

.captcha-box {
  width: 110px;
  height: 40px;
  border-radius: 8px;
  background: linear-gradient(135deg, #e0e7ff, #dbeafe);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  flex-shrink: 0;
  overflow: hidden;
}

.captcha-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.captcha-text {
  font-size: 20px;
  font-weight: 700;
  letter-spacing: 4px;
  color: #2563eb;
}

.login-btn {
  width: 100%;
  height: 44px;
  font-size: 16px;
  font-weight: 600;
  border-radius: 8px;
}
</style>