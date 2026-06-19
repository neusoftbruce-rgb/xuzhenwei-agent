// pages/techniques/techniques.js
const app = getApp();

Page({
  data: {
    tabs: ['全部', '快速产生灵感', '打磨完善创意', '落地执行创意', '找到思考切入点', '配方套餐'],
    activeTab: 0,
    techniques: [],
    filtered: [],
    loading: true
  },

  onLoad() { this.loadTechniques(); },

  async loadTechniques() {
    try {
      const all = await app.request('/api/techniques');
      this.setData({ techniques: all, loading: false });
      this.filterTab(0);
    } catch(e) {
      this.setData({ loading: false });
    }
  },

  filterTab(idx) {
    this.setData({ activeTab: idx });
    const all = this.data.techniques;
    if (idx === 0) {
      this.setData({ filtered: all });
      return;
    }
    const cat = this.data.tabs[idx];
    if (cat === '配方套餐') {
      // 配方列表硬编码（小程序端简化，不从API取）
      this.setData({ filtered: this.getRecipes() });
      return;
    }
    this.setData({
      filtered: all.filter(t => (t.category || '').indexOf(cat) >= 0)
    });
  },

  getRecipes() {
    return [
      { id: 'recipe:深度诊断套餐', name: '深度诊断套餐', description: 'SWOT + 烦恼分析 + 假说验证 + 验证挑剔', category: '配方套餐' },
      { id: 'recipe:农业品牌套餐', name: '农业品牌套餐', description: '跨界联想 + 虚拟专家 + 品牌定位 + 内容策略', category: '配方套餐' },
      { id: 'recipe:创业验证套餐', name: '创业验证套餐', description: '10倍目标 + 具体化 + 财务预测 + 逆向思维', category: '配方套餐' },
      { id: 'recipe:农场诊断：把脉开方跟诊', name: '农场诊断', description: '虚拟专家 + SWOT + 6W3H + 行动计划 + 独学TIPS', category: '配方套餐' },
      { id: 'recipe:农技推广：发现翻译传播', name: '农技推广', description: '预测未来 + 独学输出 + 传播策略', category: '配方套餐' },
      { id: 'recipe:课程开发：策划验证迭代', name: '课程开发', description: '6W3H + 假说验证 + 行动计划 + 定价 + 独学', category: '配方套餐' },
      { id: 'recipe:农业品牌：洞察定位内容', name: '农业品牌', description: '用户洞察 + 定位 + 内容策略 + 跨界联想', category: '配方套餐' },
      { id: 'recipe:学员养成：学练考用闭环', name: '学员养成', description: '独学超具体化 + 独学输出深化 + 独学机制化 + 行动计划', category: '配方套餐' },
    ];
  },

  selectTechnique(e) {
    const tech = e.currentTarget.dataset.tech;
    // 切换到对话页并设置模式
    wx.switchTab({ url: '/pages/chat/chat' });
    // 延迟设置模式（等chat页面onShow）
    setTimeout(() => {
      const pages = getCurrentPages();
      const chatPage = pages.find(p => p.route === 'pages/chat/chat');
      if (chatPage) {
        chatPage.setData({ mode: tech.id });
      }
    }, 300);
  }
});
