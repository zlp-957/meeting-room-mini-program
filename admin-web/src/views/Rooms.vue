<template>
  <div>
    <div class="page-header">
      <div class="page-title">会议室管理</div>
      <el-button type="primary" @click="openAdd">
        <el-icon style="margin-right:4px;"><Plus /></el-icon>新增会议室
      </el-button>
    </div>

    <!-- 搜索 -->
    <el-card shadow="never" style="margin-bottom:20px;">
      <el-input
        v-model="searchName"
        placeholder="按名称搜索"
        style="width:240px;"
        clearable
        @keyup.enter="loadData"
        @clear="loadData"
      >
        <template #prefix><el-icon><Search /></el-icon></template>
      </el-input>
      <el-button type="primary" style="margin-left:12px;" @click="loadData">搜索</el-button>
    </el-card>

    <!-- 表格 -->
    <el-card shadow="never">
      <el-table :data="list" v-loading="loading" stripe>
        <el-table-column prop="id" label="ID" width="60" />
        <el-table-column prop="name" label="名称" min-width="120" />
        <el-table-column prop="capacity" label="容量(人)" width="90">
          <template #default="{ row }">
            <el-tag size="small">{{ row.capacity }}人</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="location" label="位置" min-width="140" />
        <el-table-column label="开放时间" width="160">
          <template #default="{ row }">{{ row.openTime }} - {{ row.closeTime }}</template>
        </el-table-column>
        <!-- 配备设施统一写在备注里，列表直接展示便于管理员核对 -->
        <el-table-column prop="remark" label="备注" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">{{ row.remark || '-' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">
              {{ row.status === 1 ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="250" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="primary" plain @click="openSignCode(row)">签到码</el-button>
            <el-button size="small" @click="openEdit(row)">编辑</el-button>
            <el-button size="small" type="danger" @click="handleDelete(row)">删除</el-button>
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

    <!-- 新增/编辑弹窗 -->
    <el-dialog v-model="dialogVisible" :title="isEdit ? '编辑会议室' : '新增会议室'" width="500px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="名称" prop="name">
          <el-input v-model="form.name" placeholder="请输入会议室名称" />
        </el-form-item>
        <el-form-item label="容量" prop="capacity">
          <el-input-number v-model="form.capacity" :min="1" :max="100" />
        </el-form-item>
        <el-form-item label="位置">
          <el-input v-model="form.location" placeholder="如：A栋 3楼 301" />
        </el-form-item>
        <el-form-item label="开放时间">
          <el-time-picker
            v-model="openTime"
            is-range
            format="HH:mm"
            value-format="HH:mm:ss"
            range-separator="-"
            start-placeholder="开始"
            end-placeholder="结束"
          />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch
            v-model="form.status"
            :active-value="1"
            :inactive-value="0"
            active-text="启用"
            inactive-text="停用"
          />
        </el-form-item>
        <el-form-item label="备注">
          <el-input
            v-model="form.remark"
            type="textarea"
            :rows="3"
            maxlength="255"
            show-word-limit
            placeholder="如：小型会议室；配备：投影仪、白板、视频会议终端"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
      </template>
    </el-dialog>

    <!-- 签到码弹窗 -->
    <el-dialog v-model="signDialogVisible" title="会议室签到码" width="440px">
      <div class="qr-box">
        <div class="qr-room">{{ signInfo.roomName }}</div>
        <div class="qr-loc">{{ signInfo.location || '' }}</div>
        <img v-if="qrDataUrl" :src="qrDataUrl" class="qr-img" alt="签到二维码" />
        <div class="qr-code">{{ signInfo.signCode }}</div>
        <div class="qr-tip">
          请打印后张贴在会议室门口。用户需现场用小程序扫码签到，相册图片无效。<br />
          重新生成会使已张贴的旧二维码立即失效。
        </div>
      </div>
      <template #footer>
        <el-button @click="downloadQr">下载 PNG</el-button>
        <el-button @click="printQr">打印</el-button>
        <el-button type="danger" :loading="resetting" @click="resetSignCode">重新生成</el-button>
        <el-button type="primary" @click="signDialogVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Search } from '@element-plus/icons-vue'
import QRCode from 'qrcode'
import request from '../utils/request'

const list = ref([])
const loading = ref(false)
const searchName = ref('')
const page = ref(1)
const size = ref(10)
const total = ref(0)

const dialogVisible = ref(false)
const isEdit = ref(false)
const saving = ref(false)
const formRef = ref(null)
const openTime = ref([])

const form = reactive({
  id: null,
  name: '',
  capacity: 8,
  location: '',
  status: 1,
  remark: ''
})

const rules = {
  name: [{ required: true, message: '请输入名称', trigger: 'blur' }],
  capacity: [{ required: true, message: '请输入容量', trigger: 'blur' }]
}

const loadData = async () => {
  loading.value = true
  try {
    const data = await request.get('/api/admin/room/page', {
      params: { page: page.value, size: size.value, name: searchName.value || undefined }
    })
    list.value = data.records || []
    total.value = data.total || 0
  } catch (e) {}
  loading.value = false
}

const openAdd = () => {
  isEdit.value = false
  Object.assign(form, { id: null, name: '', capacity: 8, location: '', status: 1, remark: '' })
  openTime.value = []
  dialogVisible.value = true
}

const openEdit = (row) => {
  isEdit.value = true
  Object.assign(form, {
    id: row.id,
    name: row.name,
    capacity: row.capacity,
    location: row.location,
    status: row.status,
    remark: row.remark
  })
  // value-format 已经是 HH:mm:ss 字符串，可直接回显
  openTime.value = row.openTime && row.closeTime ? [row.openTime, row.closeTime] : []
  dialogVisible.value = true
}

const handleSave = () => {
  formRef.value.validate(async (valid) => {
    if (!valid) return
    saving.value = true
    try {
      const payload = { ...form }
      if (openTime.value && openTime.value.length === 2) {
        payload.openTime = openTime.value[0]
        payload.closeTime = openTime.value[1]
      }
      if (isEdit.value) {
        await request.put(`/api/admin/room/${form.id}`, payload)
      } else {
        await request.post('/api/admin/room', payload)
      }
      ElMessage.success('保存成功')
      dialogVisible.value = false
      loadData()
    } catch (e) {}
    saving.value = false
  })
}

const handleDelete = (row) => {
  ElMessageBox.confirm(`确定删除会议室「${row.name}」吗？`, '警告', {
    confirmButtonText: '删除',
    cancelButtonText: '取消',
    type: 'warning'
  }).then(async () => {
    await request.delete(`/api/admin/room/${row.id}`)
    ElMessage.success('删除成功')
    loadData()
  }).catch(() => {})
}

onMounted(() => {
  loadData()
})

// ---------------- 签到码 ----------------
const signDialogVisible = ref(false)
const signInfo = ref({})
const qrDataUrl = ref('')
const resetting = ref(false)

const QR_OPTIONS = { width: 480, margin: 2, errorCorrectionLevel: 'M' }

const openSignCode = async (row) => {
  try {
    // 后端负责拼 qrContent（格式只有 SignCodeUtil 一处定义），前端只渲染
    const data = await request.get(`/api/admin/room/sign-code/${row.id}`)
    signInfo.value = data
    qrDataUrl.value = await QRCode.toDataURL(data.qrContent, QR_OPTIONS)
    signDialogVisible.value = true
  } catch (e) {
    // request.js 已统一提示
  }
}

const downloadQr = () => {
  if (!qrDataUrl.value) return
  const a = document.createElement('a')
  a.href = qrDataUrl.value
  a.download = `签到码-${signInfo.value.roomName || signInfo.value.roomId}.png`
  a.click()
}

const printQr = () => {
  if (!qrDataUrl.value) return
  // 打印页必须带会议室名和位置：贴错门时管理员才能自检出来
  const w = window.open('', '_blank', 'width=520,height=760')
  if (!w) {
    ElMessage.warning('浏览器拦截了弹窗，请允许弹窗后重试，或改用「下载 PNG」')
    return
  }
  w.document.write(`
    <html><head><title>签到码 - ${signInfo.value.roomName || ''}</title><style>
      body{font-family:-apple-system,"PingFang SC","Microsoft YaHei",sans-serif;text-align:center;padding:32px}
      h1{font-size:22px;margin:0 0 6px}
      .loc{color:#555;font-size:13px;margin:0 0 20px}
      img{width:360px;height:360px}
      .tip{color:#333;font-size:13px;margin-top:16px;line-height:1.8}
    </style></head><body>
      <h1>${signInfo.value.roomName || ''}</h1>
      <p class="loc">${signInfo.value.location || ''}</p>
      <img src="${qrDataUrl.value}">
      <p class="tip">请打开小程序「我的预约 → 扫码签到」<br>扫描本二维码完成现场签到</p>
    </body></html>`)
  w.document.close()
  setTimeout(() => {
    w.focus()
    w.print()
  }, 300)
}

const resetSignCode = async () => {
  try {
    await ElMessageBox.confirm(
      '重新生成后，已张贴的旧二维码将立即失效，需要重新打印张贴。确定继续吗？',
      '重新生成签到码',
      { type: 'warning', confirmButtonText: '重新生成', cancelButtonText: '取消' }
    )
  } catch (e) {
    return
  }
  resetting.value = true
  try {
    const data = await request.post(`/api/admin/room/sign-code/${signInfo.value.roomId}/reset`)
    signInfo.value = data
    qrDataUrl.value = await QRCode.toDataURL(data.qrContent, QR_OPTIONS)
    ElMessage.success('已重新生成，请重新打印张贴')
  } catch (e) {
    // 同上
  } finally {
    resetting.value = false
  }
}
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
  margin-top: 20px;
  justify-content: flex-end;
}

.qr-box {
  text-align: center;
}

.qr-room {
  font-size: 18px;
  font-weight: 600;
  color: #1f2937;
}

.qr-loc {
  font-size: 13px;
  color: #64748b;
  margin-top: 4px;
}

.qr-img {
  width: 240px;
  height: 240px;
  margin: 16px 0 8px;
}

.qr-code {
  font-family: Consolas, Monaco, monospace;
  font-size: 12px;
  letter-spacing: 1px;
  color: #475569;
  word-break: break-all;
  padding: 0 12px;
}

.qr-tip {
  margin-top: 14px;
  font-size: 12px;
  color: #94a3b8;
  line-height: 1.7;
  text-align: left;
  background: #f8fafc;
  padding: 10px 12px;
  border-radius: 6px;
}
</style>
