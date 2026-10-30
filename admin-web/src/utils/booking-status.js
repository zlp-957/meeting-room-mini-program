/**
 * 预约状态的展示映射（管理端共用）
 *
 * 与后端 booking.status 保持一致：0待审批 1已通过 2已驳回 3已取消 4已违约
 */
export const BOOKING_STATUS_TEXT = {
  0: '待审批',
  1: '已通过',
  2: '已驳回',
  3: '已取消',
  4: '已违约'
}

const BOOKING_STATUS_TYPE = {
  0: 'warning',
  1: 'success',
  2: 'danger',
  3: 'info',
  4: 'danger'
}

export const statusText = (status) => BOOKING_STATUS_TEXT[status] || '未知'

export const statusType = (status) => BOOKING_STATUS_TYPE[status] || 'info'

/**
 * 签到状态展示映射（由后端下发数字，前端只做文案映射）
 *
 * 0 无需签到 / 1 未签到 / 2 已签到 / 3 超时未签到 / 4 已违约
 *
 * 注意「3 超时未签到」是**中间态**：已过签到截止但定时任务还没跑到，
 * 最多存在 60 秒就会变成「已违约」。用 danger 色是为了让管理员一眼看出
 * 这条马上要出事，而不是让它在列表里看起来一切正常。
 */
export const SIGN_STATE_TEXT = {
  0: '无需签到',
  1: '未签到',
  2: '已签到',
  3: '超时未签到',
  4: '已违约'
}

const SIGN_STATE_TYPE = {
  0: 'info',
  1: 'warning',
  2: 'success',
  3: 'danger',
  4: 'danger'
}

export const signStateText = (s) => SIGN_STATE_TEXT[s] || '-'

export const signStateType = (s) => SIGN_STATE_TYPE[s] || 'info'
