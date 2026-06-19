// 徐振伟智能体 小程序 v1.0
App({
  globalData: {
    API_BASE: 'http://localhost:8080', // 开发环境；上线改为 https
    token: '',
    user: null,
    currentSessionId: null,
    mode: 'auto',
  },

  onLaunch() {
    // 检查本地缓存的token
    const token = wx.getStorageSync('token');
    if (token) {
      this.globalData.token = token;
      this.checkLogin();
    }
  },

  async checkLogin() {
    const token = this.globalData.token;
    if (!token) return;
    try {
      const res = await this.request('/api/auth/me', 'GET');
      this.globalData.user = res;
    } catch(e) {
      // token过期，清除
      wx.removeStorageSync('token');
      this.globalData.token = '';
    }
  },

  // 微信登录
  async login() {
    return new Promise((resolve, reject) => {
      wx.login({
        success: async (loginRes) => {
          try {
            const data = await this.request('/api/auth/wechat-login', 'POST', {
              code: loginRes.code
            });
            this.globalData.token = data.token;
            this.globalData.user = data.user;
            wx.setStorageSync('token', data.token);
            resolve(data);
          } catch(e) { reject(e); }
        },
        fail: reject
      });
    });
  },

  // 封装的网络请求
  request(path, method = 'GET', data = {}) {
    const token = this.globalData.token;
    const base = this.globalData.API_BASE;
    return new Promise((resolve, reject) => {
      wx.request({
        url: base + path,
        method,
        data,
        header: {
          'Content-Type': 'application/json',
          'Authorization': token ? 'Bearer ' + token : ''
        },
        success(res) {
          if (res.statusCode >= 200 && res.statusCode < 300) {
            resolve(res.data);
          } else {
            reject(res.data);
          }
        },
        fail(err) { reject(err); }
      });
    });
  }
});
