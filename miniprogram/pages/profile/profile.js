// pages/profile/profile.js
const app = getApp();

Page({
  data: {
    user: null,
    loggedIn: false
  },

  onShow() {
    this.setData({
      user: app.globalData.user,
      loggedIn: !!app.globalData.token
    });
  },

  async handleLogin() {
    wx.showLoading({ title: '登录中...' });
    try {
      const data = await app.login();
      this.setData({ user: data.user, loggedIn: true });
      wx.showToast({ title: '登录成功', icon: 'success' });
    } catch(e) {
      wx.showToast({ title: '登录失败', icon: 'none' });
    }
    wx.hideLoading();
  },

  handleLogout() {
    wx.showModal({
      title: '退出登录',
      content: '确定要退出吗？',
      success: (res) => {
        if (res.confirm) {
          wx.removeStorageSync('token');
          app.globalData.token = '';
          app.globalData.user = null;
          this.setData({ user: null, loggedIn: false });
        }
      }
    });
  },

  // 调试：无微信环境下的测试登录
  async testLogin() {
    try {
      const res = await app.request('/api/auth/test-login', 'POST');
      app.globalData.token = res.token;
      app.globalData.user = res.user;
      wx.setStorageSync('token', res.token);
      this.setData({ user: res.user, loggedIn: true });
      wx.showToast({ title: '测试登录成功', icon: 'success' });
    } catch(e) {
      wx.showToast({ title: '请确认后端已启动', icon: 'none' });
    }
  }
});
