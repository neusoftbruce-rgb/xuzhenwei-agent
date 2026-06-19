// pages/chat/chat.js
const app = getApp();

Page({
  data: {
    mode: 'auto',
    messages: [],
    inputText: '',
    loading: false,
    recording: false,
    currentSessionId: null,
    sessions: [],
    drawerOpen: false,
    scrollTo: '',
  },

  onLoad() {
    this.loadSessions();
  },

  onShow() {
    this.loadSessions();
  },

  // ========== 会话管理 ==========
  async loadSessions() {
    try {
      const sessions = await app.request('/api/sessions?status=ACTIVE');
      this.setData({
        sessions: sessions.map(s => ({
          id: s.id,
          name: (s.name || '未命名').substring(0, 20),
          timeAgo: this.getRelativeTime(s.lastMessageAt || s.createdAt),
          active: s.id === this.data.currentSessionId
        }))
      });
    } catch(e) {}
  },

  getRelativeTime(ts) {
    if (!ts) return '';
    const diff = Date.now() - new Date(ts).getTime();
    const mins = Math.floor(diff / 60000);
    if (mins < 1) return '刚刚';
    if (mins < 60) return mins + '分钟前';
    const hrs = Math.floor(mins / 60);
    if (hrs < 24) return hrs + '小时前';
    const days = Math.floor(hrs / 24);
    if (days < 7) return days + '天前';
    return new Date(ts).toLocaleDateString('zh-CN');
  },

  newChat() {
    this.setData({
      currentSessionId: null, messages: [], inputText: '',
      drawerOpen: false
    });
  },

  async openSession(e) {
    const sid = e.currentTarget.dataset.id;
    try {
      const msgs = await app.request(`/api/sessions/${sid}/messages?page=0&size=100`);
      msgs.reverse();
      this.setData({
        currentSessionId: sid,
        messages: msgs.map(m => ({
          id: m.id,
          role: m.role === 'USER' ? 'user' : (m.role === 'SYSTEM' ? 'sys' : 'ai'),
          content: m.content || '',
          html: this.parseMarkdown(m.content || ''),
          exportable: m.role === 'ASSISTANT'
        })),
        drawerOpen: false,
        inputText: '',
        scrollTo: 'bottom'
      });
    } catch(e) { wx.showToast({ title: '加载失败', icon: 'none' }); }
  },

  // ========== 模式 ==========
  setMode(e) {
    const m = e.currentTarget.dataset.mode;
    this.setData({ mode: m });
  },

  // ========== 发送消息 ==========
  async send() {
    const text = this.data.inputText.trim();
    if (!text || this.data.loading) return;
    if (!app.globalData.token) { wx.showToast({ title: '请先登录', icon: 'none' }); return; }

    let sid = this.data.currentSessionId;
    if (!sid) {
      try {
        const s = await app.request('/api/sessions', 'POST', {
          name: text.substring(0, 50), topic: text.substring(0, 200)
        });
        sid = s.id;
        this.setData({ currentSessionId: sid });
      } catch(e) {}
    }

    const messages = [...this.data.messages, { id: Date.now(), role: 'user', content: text }];
    this.setData({ messages, inputText: '', loading: true, scrollTo: 'bottom' });

    const mode = this.data.mode;

    if (!mode) {
      // 自由对话
      await this.streamAI(text, '', sid);
    } else if (mode === 'auto') {
      // 智能推荐
      try {
        const rec = await app.request('/api/recommend/quick', 'POST', { message: text });
        const recCards = {
          id: Date.now(), role: 'recommend',
          keywords: rec.keywords || [],
          cards: rec.cards || [],
          _fullText: text
        };
        this.setData({ messages: [...this.data.messages, recCards], loading: false });
      } catch(e) {
        this.setData({ loading: false });
      }
    } else {
      // 指定模式
      await this.streamAI(text, mode, sid);
    }
  },

  // ========== SSE流式接收 ==========
  async streamAI(message, techniqueId, sessionId) {
    const msgId = Date.now();
    const messages = [...this.data.messages, { id: msgId, role: 'ai', html: '', exportable: true }];
    this.setData({ messages });

    const token = app.globalData.token;
    const that = this;

    const requestTask = wx.request({
      url: app.globalData.API_BASE + '/api/agent/think',
      method: 'POST',
      enableChunked: true,
      header: {
        'Content-Type': 'application/json',
        'Authorization': 'Bearer ' + token
      },
      data: { message, techniqueId, sessionId, deepThink: false },
      success() { /* 完整响应接收完成 */ },
      fail(err) {
        const msgs = that.data.messages;
        const idx = msgs.findIndex(m => m.id === msgId);
        if (idx >= 0) { msgs[idx].html = '<span style="color:#f05050">请求失败</span>'; that.setData({ messages, loading: false }); }
      }
    });

    // 监听分块 (基础库 2.20.1+)
    if (requestTask.onChunkReceived) {
      let rawText = '';
      requestTask.onChunkReceived((chunk) => {
        const text = that.ab2str(chunk.data);
        const lines = text.split('\n');
        for (const line of lines) {
          if (line.startsWith('data:')) {
            try {
              const event = JSON.parse(line.slice(5).trim());
              if (event.type === 'STEP_CONTENT' && event.content) {
                rawText += event.content;
                const msgs = that.data.messages;
                const idx = msgs.findIndex(m => m.id === msgId);
                if (idx >= 0) {
                  msgs[idx].html = that.parseMarkdown(rawText);
                  that.setData({ messages, scrollTo: 'bottom' });
                }
              }
            } catch(e) {}
          }
        }
      });
    } else {
      // 降级：不支持enableChunked，用普通请求
      this.setData({ loading: false });
      wx.showToast({ title: '请升级微信版本', icon: 'none' });
    }

    // 超时处理
    setTimeout(() => {
      const msgs = that.data.messages;
      const idx = msgs.findIndex(m => m.id === msgId);
      if (idx >= 0 && that.data.loading) {
        that.setData({ loading: false });
        // 刷新会话列表
        that.loadSessions();
      }
    }, 60000);
  },

  ab2str(buf) {
    return String.fromCharCode.apply(null, new Uint8Array(buf));
  },

  // ========== Markdown渲染(简易) ==========
  parseMarkdown(text) {
    if (!text) return '';
    return text
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/^### (.+)$/gm, '<h3>$1</h3>')
      .replace(/^## (.+)$/gm, '<h2>$1</h2>')
      .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
      .replace(/\n- (.+)/g, '\n· $1')
      .replace(/\n/g, '<br/>');
  },

  // ========== 语音 ==========
  startRecord() {
    const recorder = wx.getRecorderManager();
    recorder.start({ format: 'mp3', duration: 60000, sampleRate: 16000, numberOfChannels: 1 });
    this.setData({ recording: true });
    recorder.onError(() => { this.setData({ recording: false }); wx.showToast({ title: '录音失败', icon: 'none' }); });
  },

  stopRecord() {
    const recorder = wx.getRecorderManager();
    recorder.stop();
    this.setData({ recording: false });
    recorder.onStop((res) => {
      wx.showLoading({ title: '识别中...' });
      // 上传语音到后端识别
      wx.uploadFile({
        url: app.globalData.API_BASE + '/api/file/upload',
        filePath: res.tempFilePath,
        name: 'file',
        header: { 'Authorization': 'Bearer ' + app.globalData.token },
        success: (uploadRes) => {
          try {
            const data = JSON.parse(uploadRes.data);
            const text = data.text || '';
            this.setData({ inputText: text });
          } catch(e) {}
          wx.hideLoading();
        },
        fail: () => { wx.hideLoading(); wx.showToast({ title: '识别失败', icon: 'none' }); }
      });
    });
  },

  // ========== 推荐操作 ==========
  async executeRec() {
    const recMsg = this.data.messages.find(m => m.role === 'recommend');
    if (!recMsg || !recMsg.cards || !recMsg.cards.length) return;
    const topId = recMsg.cards[0].id;
    const text = recMsg._fullText;
    // 移除推荐卡片
    const messages = this.data.messages.filter(m => m.id !== recMsg.id);
    this.setData({ messages, loading: true });
    await this.streamAI(text, topId, this.data.currentSessionId);
  },

  async retryRec() {
    const messages = this.data.messages.filter(m => m.role !== 'recommend');
    this.setData({ messages });
    const text = this.data.inputText || '请重新推荐';
    try {
      const rec = await app.request('/api/recommend/quick', 'POST', { message: text, shuffle: 'true' });
      this.setData({
        messages: [...this.data.messages, { id: Date.now(), role: 'recommend', keywords: rec.keywords || [], cards: rec.cards || [], _fullText: text }]
      });
    } catch(e) {}
  },

  // ========== 导出 ==========
  async exportMsg(e) {
    const msgId = e.currentTarget.dataset.id;
    const msg = this.data.messages.find(m => m.id === msgId);
    if (!msg) return;
    try {
      const res = await app.request('/api/export', 'POST', {
        content: msg.content || '',
        format: 'WORD',
        title: '分析报告_' + new Date().toISOString().slice(0, 10),
        sessionId: this.data.currentSessionId
      });
      wx.showToast({ title: '导出成功', icon: 'success' });
    } catch(e) { wx.showToast({ title: '导出失败', icon: 'none' }); }
  },

  // ========== 抽屉 ==========
  openDrawer() { this.setData({ drawerOpen: true }); },
  closeDrawer() { this.setData({ drawerOpen: false }); },

  // ========== 输入 ==========
  onInput(e) { this.setData({ inputText: e.detail.value }); },
});
