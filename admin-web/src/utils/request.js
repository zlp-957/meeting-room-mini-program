import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '../router'

const request = axios.create({
  baseURL: '',
  timeout: 10000
})

// 请求拦截器
request.interceptors.request.use((config) => {
  const token = localStorage.getItem('admin_token')
  if (token) {
    config.headers.Authorization = token
  }
  return config
})

// 响应拦截器
request.interceptors.response.use(
  (response) => {
    const res = response.data
    if (res.code === 200) {
      return res.data
    }
    // 只有 401 才是「登录态失效」。403 是「已登录但没这个权限」，
    // 早期把 403 也当成掉线直接踢回登录页，管理员一遇到权限提示就被登出，体验极差。
    if (res.code === 401) {
      localStorage.removeItem('admin_token')
      router.push('/login')
      ElMessage.error(res.msg || '登录已过期，请重新登录')
      return Promise.reject(new Error(res.msg))
    }
    ElMessage.error(res.msg || '请求失败')
    return Promise.reject(new Error(res.msg))
  },
  (error) => {
    // 后端返回了 4xx/5xx 但没有统一响应体时，尽量把 body 里的提示透出来
    const body = error && error.response && error.response.data
    const msg = (body && body.msg) || '网络连接失败'
    ElMessage.error(msg)
    return Promise.reject(error)
  }
)

export default request
