import { defineStore } from 'pinia'

export const useAdminStore = defineStore('admin', {
  state: () => ({
    adminInfo: JSON.parse(localStorage.getItem('admin_info') || 'null')
  }),
  actions: {
    setAdminInfo(info) {
      this.adminInfo = info
      localStorage.setItem('admin_info', JSON.stringify(info))
    },
    logout() {
      this.adminInfo = null
      localStorage.removeItem('admin_token')
      localStorage.removeItem('admin_info')
    }
  }
})
