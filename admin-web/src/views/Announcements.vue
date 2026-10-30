<template>
  <div>
    <div class="page-header">
      <div class="page-title">公告管理</div>
      <el-button type="primary" @click="openAdd">
        <el-icon style="margin-right:4px;"><Plus /></el-icon>发布公告
      </el-button>
    </div>

    <el-card shadow="never">
      <el-table :data="list" v-loading="loading" stripe>
        <el-table-column prop="id" label="ID" width="60" />
        <el-table-column prop="title" label="标题" min-width="200" />
        <el-table-column prop="createTime" label="发布时间" width="180" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">
              {{ row.status === 1 ? '上线' : '下线' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
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

    <el-dialog v-model="dialogVisible" :title="isEdit ? '编辑公告' : '发布公告'" width="600px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="80px">
        <el-form-item label="标题" prop="title">
          <el-input v-model="form.title" placeholder="请输入公告标题" />
        </el-form-item>
        <el-form-item label="内容" prop="content">
          <el-input
            v-model="form.content"
            type="textarea"
            :rows="8"
            placeholder="请输入公告内容"
          />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch
            v-model="form.status"
            :active-value="1"
            :inactive-value="0"
            active-text="上线"
            inactive-text="下线"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import request from '../utils/request'

const list = ref([])
const loading = ref(false)
const dialogVisible = ref(false)
const isEdit = ref(false)
const saving = ref(false)
const formRef = ref(null)
const page = ref(1)
const size = ref(10)
const total = ref(0)

/** 与后端 AnnouncementService.MAX_CONTENT_LENGTH 保持一致 */
const MAX_CONTENT_LENGTH = 20000

const form = reactive({
  id: null,
  title: '',
  content: '',
  status: 1
})

const rules = {
  title: [
    { required: true, message: '请输入标题', trigger: 'blur' },
    { max: 128, message: '标题不能超过 128 个字符', trigger: 'blur' }
  ],
  content: [
    { required: true, message: '请输入内容', trigger: 'blur' },
    {
      // 数据库列是 TEXT（约 1.6 万汉字），超长会直接抛 SQL 异常变成 500，这里先在前端拦住
      validator: (rule, value, callback) => {
        if (value && value.length > MAX_CONTENT_LENGTH) {
          callback(new Error(`内容不能超过 ${MAX_CONTENT_LENGTH} 个字符（当前 ${value.length} 个）`))
          return
        }
        callback()
      },
      trigger: 'blur'
    }
  ]
}

const loadData = async () => {
  loading.value = true
  try {
    const data = await request.get('/api/admin/announcement/page', {
      params: { page: page.value, size: size.value }
    })
    list.value = data.records || []
    total.value = data.total || 0
  } catch (e) {}
  loading.value = false
}

const openAdd = () => {
  isEdit.value = false
  Object.assign(form, { id: null, title: '', content: '', status: 1 })
  dialogVisible.value = true
}

const openEdit = (row) => {
  isEdit.value = true
  Object.assign(form, { ...row })
  dialogVisible.value = true
}

const handleSave = () => {
  formRef.value.validate(async (valid) => {
    if (!valid) return
    saving.value = true
    try {
      if (isEdit.value) {
        await request.put(`/api/admin/announcement/${form.id}`, form)
      } else {
        await request.post('/api/admin/announcement', form)
      }
      ElMessage.success('保存成功')
      dialogVisible.value = false
      // 新增后回到第一页，否则停在第 3 页看不到刚发的公告
      if (!isEdit.value) page.value = 1
      loadData()
    } catch (e) {}
    saving.value = false
  })
}

const handleDelete = (row) => {
  ElMessageBox.confirm(`确定删除公告「${row.title}」吗？`, '警告', {
    confirmButtonText: '删除',
    cancelButtonText: '取消',
    type: 'warning'
  }).then(async () => {
    await request.delete(`/api/admin/announcement/${row.id}`)
    ElMessage.success('删除成功')
    loadData()
  }).catch(() => {})
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
  margin-top: 20px;
  justify-content: flex-end;
}
</style>
