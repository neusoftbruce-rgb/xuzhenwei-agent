# TextPreprocessor 文本预处理管道 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在所有用户输入进入技法推荐引擎之前，插入一层透明预处理管道（本地分词+错别字检测 → 智谱精炼）

**Architecture:** 新增 TextPreprocessor 服务（本地分词编辑距离 + 智谱单次调用做纠错合并意图），结果通过 PreprocessResult JSON 传给 TechniqueRecommender。不新建DB表，存 MessageRecord.metadata。

**Tech Stack:** Java 17, Spring Boot 3.4, 智谱 GLM-5.2, Vanilla JS

---

### Task 1: 创建中文基础词库

**Files:**
- Create: `src/main/resources/common_words.txt`

- [ ] **Step 1: 写入词库文件**

词库包含农业、商业、通用中文常见词，每行一个，按类别分组：

```
# ====== 农业 ======
农业
种植
大棚
温室
冷棚
土壤
肥料
病害
虫害
酵素菌
微生物
有机
品种
密度
产量
糖度
水肥
灌溉
温湿度
育苗
定植
采收
轮作
休耕
西红柿
番茄
黄瓜
辣椒
茄子
西瓜
甜瓜
草莓
葡萄
苹果
桃子
水稻
小麦
玉米
大豆
# ====== 商业 ======
营销
品牌
定价
成本
利润
收入
获客
渠道
市场
竞争
差异化
商业模式
融资
投资
预算
财务
销售
客户
用户
产品
服务
体验
口碑
复购
转化
推广
广告
# ====== 管理 ======
战略
规划
执行
团队
组织
人才
培训
考核
绩效
流程
标准
质量
效率
创新
改革
转型
目标
计划
方案
决策
# ====== 技术 ======
人工智能
数字化
自动化
系统
平台
数据
算法
模型
软件
硬件
网络
安全
# ====== 通用高频词 ======
问题
方法
结果
分析
研究
调查
设计
开发
测试
优化
改进
提升
降低
增加
减少
评估
对比
选择
建议
方案
策略
趋势
未来
机会
风险
优势
劣势
关键
核心
根本
基础
前提
条件
因素
影响
作用
价值
意义
优势
特色
亮点
差异
```

- [ ] **Step 2: 验证文件**

```bash
wc -l src/main/resources/common_words.txt
# Expected: ~150 lines
```

- [ ] **Step 3: 提交**

```bash
git add src/main/resources/common_words.txt
git commit -m "feat: 添加中文基础词库 common_words.txt (~150词，农业/商业/管理/技术/通用)"
```

---

### Task 2: 创建 TextPreprocessor.java 核心服务

**Files:**
- Create: `src/main/java/com/xuzhenwei/agent/agent/TextPreprocessor.java`

- [ ] **Step 1: 创建 PreprocessResult 数据类**

```java
package com.xuzhenwei.agent.agent;

import java.util.List;

/**
 * 文本预处理管道输出 — v3.4
 *
 * <p>rawKeywords=表1(原始关键词), correctedText=清洗后文本,
 * intentAnalysis=表2(AI深度分析), 三张逻辑表用JSON三个key替代。</p>
 */
public record PreprocessResult(
    List<RawKeyword> rawKeywords,
    String correctedText,
    IntentAnalysis intentAnalysis,
    long phase1Ms,
    long phase2Ms
) {
    public record RawKeyword(
        String word,
        int frequency,
        double weight,
        String source,       // "file" | "user_input"
        boolean suspectedTypo,
        String suggestion    // null = no typo suspected
    ) {}

    public record IntentAnalysis(
        String intent,           // "诊断分析"|"创意发想"|"方案打磨"|"执行落地"
        List<String> domains,    // ["农业技术","商业经营"]
        String coreQuestion,
        List<String> subQuestions,
        int complexity           // 1-10
    ) {}
}
```

- [ ] **Step 2: 停用词表 + 编辑距离方法**

```java
private static final Set<String> STOP_WORDS = Set.of(
    "的","了","是","我","你","他","她","它","们","这","那","吗","呢","吧","啊","哦","嗯",
    "就","也","都","还","要","会","能","可以","应该","可能","已经","正在","一直",
    "因为","所以","但是","虽然","如果","而且","或者","和","与","及",
    "对","把","被","让","给","向","从","在","到","用","以","为",
    "很","太","更","最","很","非常","比较","有点","不","没","别","没有",
    "一个","一种","这个","那个","什么","怎么","为什么","怎么样","多少",
    "上","下","中","里","外","前","后","左","右","请","帮","让","告诉","说"
);

/** 编辑距离（Levenshtein） */
private static int editDistance(String a, String b) {
    int m = a.length(), n = b.length();
    int[] prev = new int[n + 1], curr = new int[n + 1];
    for (int j = 0; j <= n; j++) prev[j] = j;
    for (int i = 1; i <= m; i++) {
        curr[0] = i;
        for (int j = 1; j <= n; j++) {
            curr[j] = (a.charAt(i - 1) == b.charAt(j - 1))
                ? prev[j - 1]
                : 1 + Math.min(Math.min(prev[j], curr[j - 1]), prev[j - 1]);
        }
        int[] tmp = prev; prev = curr; curr = tmp;
    }
    return prev[n];
}
```

- [ ] **Step 3: Phase 1 分词 + 频次统计**

```java
/** Phase 1: 本地分析 — 分词+频次+错别字检测 (0 token, <10ms) */
List<RawKeyword> extractKeywords(String combinedText, String fileContent, String userInput) {
    // 1. 按标点切分
    String[] words = combinedText.split("[，。！？、；：\"\"''【】《》（）…—·\\s\n\r]+");
    
    // 2. 统计频次
    Map<String, Integer> freq = new LinkedHashMap<>();
    for (String w : words) {
        w = w.trim();
        if (w.isEmpty() || w.length() == 1 || w.length() > 8 || STOP_WORDS.contains(w)) continue;
        freq.merge(w, 1, Integer::sum);
    }
    
    if (freq.isEmpty()) return List.of();
    
    int maxFreq = freq.values().stream().mapToInt(Integer::intValue).max().orElse(1);
    
    // 3. 按频次降序取 Top-20，计算权重
    List<RawKeyword> result = new ArrayList<>();
    int rank = 0;
    for (var e : freq.entrySet()) {
        double sourceBias = isFromFile(e.getKey(), fileContent) ? 0.15 : 0.0;
        double weight = Math.min(1.0, (double) e.getValue() / maxFreq + sourceBias);
        
        // 4. 错别字检测
        TypoCheck typo = checkTypo(e.getKey());
        
        result.add(new RawKeyword(e.getKey(), e.getValue(), weight,
            isFromFile(e.getKey(), fileContent) ? "file" : "user_input",
            typo.isTypo, typo.suggestion));
        
        if (++rank >= 20) break;
    }
    
    result.sort((a, b) -> Double.compare(b.weight, a.weight));
    return result;
}

private boolean isFromFile(String word, String fileContent) {
    return fileContent != null && fileContent.contains(word);
}

private TypoCheck checkTypo(String word) {
    if (word.length() < 2 || commonWords.contains(word)) return new TypoCheck(false, null);
    // 找编辑距离最近且≤2的词
    String best = null;
    int bestDist = 99;
    for (String dict : commonWords) {
        if (Math.abs(dict.length() - word.length()) > 2) continue;
        int d = editDistance(word, dict);
        if (d < bestDist) { bestDist = d; best = dict; }
    }
    return (bestDist <= 2 && best != null)
        ? new TypoCheck(true, best) : new TypoCheck(false, null);
}

private record TypoCheck(boolean isTypo, String suggestion) {}
```

- [ ] **Step 4: Phase 2 智谱精炼**

```java
/** Phase 2: 智谱精炼 — 纠错+合并同义词+意图提取 (~300 token) */
private ZhipuRefined zhipuRefine(String text, List<RawKeyword> keywords) {
    StringBuilder kwSummary = new StringBuilder();
    for (var kw : keywords) {
        kwSummary.append(kw.word()).append("(权重").append(String.format("%.2f", kw.weight())).append(")");
        if (kw.suspectedTypo()) kwSummary.append("[疑似错别字→").append(kw.suggestion()).append("]");
        kwSummary.append(", ");
    }
    
    String prompt = """
        你是一个文本预处理引擎。只返回JSON，不要任何解释。
        
        输入文本：
        %s
        
        候选关键词：
        %s
        
        请完成：
        1. 纠错：确认疑似错别字，给出正确写法
        2. 合并同义词：意思相近的词合并为统一表述
        3. 意图提取：用户真正想解决什么？隐含意图？
        
        严格返回JSON：
        {"correctedText":"纠错后完整文本","refinedKeywords":[{"word":"xx","weight":0.9}],
         "intent":"诊断分析","domains":["领域"],"coreQuestion":"问题","subQuestions":["子问题"],"complexity":7}
        """.formatted(text.length() > 800 ? text.substring(0, 800) + "..." : text, kwSummary.toString());
    
    String response = chatClient.prompt()
        .user(prompt)
        .options(OpenAiChatOptions.builder().model("glm-5.2").maxTokens(300).temperature(0.1).build())
        .call().content();
    
    return parseZhipuResponse(response);
}
```

- [ ] **Step 5: 主入口 process() + 降级逻辑**

```java
public PreprocessResult process(String userInput, String fileContent) {
    String combined = (fileContent != null ? fileContent + "\n---\n" : "") + userInput;
    
    // Phase 1
    long t1 = System.currentTimeMillis();
    List<RawKeyword> rawKeywords = extractKeywords(combined, fileContent, userInput);
    long phase1Ms = System.currentTimeMillis() - t1;
    
    // 判断是否跳过 Phase 2
    if (shouldSkipPhase2(userInput, fileContent, rawKeywords)) {
        return localOnlyResult(rawKeywords, combined, phase1Ms);
    }
    
    // Phase 2
    long t2 = System.currentTimeMillis();
    try {
        ZhipuRefined refined = zhipuRefine(combined, rawKeywords);
        long phase2Ms = System.currentTimeMillis() - t2;
        return new PreprocessResult(rawKeywords, refined.correctedText,
            refined.toIntentAnalysis(), phase1Ms, phase2Ms);
    } catch (Exception e) {
        log.warn("智谱精炼失败，降级本地: {}", e.getMessage());
        return localOnlyResult(rawKeywords, combined, phase1Ms);
    }
}

private boolean shouldSkipPhase2(String userInput, String fileContent, List<RawKeyword> keywords) {
    boolean hasFile = fileContent != null && !fileContent.isBlank();
    boolean hasTypos = keywords.stream().anyMatch(RawKeyword::suspectedTypo);
    int textLen = (userInput != null ? userInput.length() : 0) + (fileContent != null ? fileContent.length() : 0);
    return !hasFile && !hasTypos && textLen <= 20;
}

private PreprocessResult localOnlyResult(List<RawKeyword> keywords, String text, long phase1Ms) {
    IntentAnalysis ia = new IntentAnalysis("通用分析", List.of("通用分析"), text, List.of(), 3);
    return new PreprocessResult(keywords, text, ia, phase1Ms, 0);
}
```

- [ ] **Step 6: 提交**

```bash
git add src/main/java/com/xuzhenwei/agent/agent/TextPreprocessor.java
git commit -m "feat: TextPreprocessor 文本预处理管道 v3.4

Phase1(本地): 分词+频次+编辑距离错别字检测, 0token
Phase2(智谱): 纠错+同义词合并+意图提取, ~300token
输出: PreprocessResult(含rawKeywords/correctedText/intentAnalysis)" 
```

---

### Task 3: 修改 AgentEvent — 新增预处理事件类型

**Files:**
- Modify: `src/main/java/com/xuzhenwei/agent/agent/AgentEvent.java`

- [ ] **Step 1: 新增事件类型和工厂方法**

在 `AgentEvent.EventType` 枚举中添加：

```java
PREPROCESS_START,
PREPROCESS_PHASE1,
PREPROCESS_PHASE2,
PREPROCESS_RESULT
```

新增工厂方法：

```java
public static AgentEvent preprocessStart() {
    return new AgentEvent(EventType.PREPROCESS_START, 0, "🔍 文本预处理中...", "preprocess", null);
}

public static AgentEvent preprocessPhase1(int keywordCount, int typoCount) {
    return new AgentEvent(EventType.PREPROCESS_PHASE1, 0,
        "✅ 提取关键词 %d个".formatted(keywordCount) + (typoCount > 0 ? " · 疑似错别字 %d个".formatted(typoCount) : ""),
        "preprocess", null);
}

public static AgentEvent preprocessPhase2(PreprocessResult.IntentAnalysis ia) {
    return new AgentEvent(EventType.PREPROCESS_PHASE2, 0,
        "✅ 智谱精炼 · %s · %s".formatted(ia.intent(), String.join("·", ia.domains())),
        "preprocess", null);
}

public static AgentEvent preprocessResult(String json) {
    return new AgentEvent(EventType.PREPROCESS_RESULT, 0, json, "preprocess", null);
}
```

- [ ] **Step 2: 提交**

```bash
git add src/main/java/com/xuzhenwei/agent/agent/AgentEvent.java
git commit -m "feat: AgentEvent 新增 PREPROCESS_* 事件类型 (start/phase1/phase2/result)"
```

---

### Task 4: 修改 RecommendationDecisionEngine — 支持注入意图分析

**Files:**
- Modify: `src/main/java/com/xuzhenwei/agent/technique/RecommendationDecisionEngine.java`

- [ ] **Step 1: 新增 analyzeWithIntent 方法**

```java
/**
 * 跟 analyze() 相同，但用预处理管道提供的意图信息，不重新推断。
 */
public AnalysisContext analyzeWithIntent(String userInput, PreprocessResult.IntentAnalysis intent) {
    IntentType intentType = switch (intent.intent()) {
        case "诊断分析" -> IntentType.DIAGNOSIS;
        case "创意发想" -> IntentType.IDEATION;
        case "方案打磨" -> IntentType.REFINEMENT;
        case "执行落地" -> IntentType.EXECUTION;
        default -> IntentType.DIAGNOSIS;
    };
    ComplexityLevel complexity = intent.complexity() >= 7 ? ComplexityLevel.COMPLEX_MULTI
        : intent.complexity() >= 5 ? ComplexityLevel.SINGLE_DOMAIN
        : ComplexityLevel.SIMPLE_FACTUAL;
    return new AnalysisContext(intentType, complexity, userInput.length(),
        intent.subQuestions().size(), userInput.length() > 200);
}
```

- [ ] **Step 2: 提交**

```bash
git add src/main/java/com/xuzhenwei/agent/technique/RecommendationDecisionEngine.java
git commit -m "feat: RecommendationDecisionEngine 新增 analyzeWithIntent()，接收预处理意图"
```

---

### Task 5: 修改 TechniqueRecommender — 接收 PreprocessResult

**Files:**
- Modify: `src/main/java/com/xuzhenwei/agent/technique/TechniqueRecommender.java`

- [ ] **Step 1: 新增重载方法**

在 `recommend(String userInput, boolean shuffle)` 基础上新增：

```java
/**
 * v3.4: 接收预处理结果，用精炼文本+意图分析替代原始输入做推荐。
 */
public RecommendationResult recommend(String userInput, boolean shuffle,
                                       PreprocessResult preprocess) {
    if (preprocess == null || preprocess.correctedText() == null) {
        return recommend(userInput, shuffle);
    }
    
    String input = preprocess.correctedText().toLowerCase();
    
    // 用预处理意图替代本地分析
    var analysis = (preprocess.intentAnalysis() != null)
        ? decisionEngine.analyzeWithIntent(userInput, preprocess.intentAnalysis())
        : decisionEngine.analyze(userInput);
    
    // 后续流程不变
    var domainMatch = domainAdvisor.matchBusiness(userInput);
    var matches = keywordMatch(input, shuffle);
    
    // 语义检索增强...
    // (后面跟原 recommend() 完全一样，只是 input/analysis 换成了预处理版本)
    
    // ... 保持原有逻辑 ...
}
```

- [ ] **Step 2: 提交**

```bash
git add src/main/java/com/xuzhenwei/agent/technique/TechniqueRecommender.java
git commit -m "feat: TechniqueRecommender 新增 recommend(input, shuffle, preprocess) 重载"
```

---

### Task 6: 修改 AgentEngine — 推荐前调用 TextPreprocessor

**Files:**
- Modify: `src/main/java/com/xuzhenwei/agent/agent/AgentEngine.java`

- [ ] **Step 1: 注入 TextPreprocessor**

```java
private final TextPreprocessor textPreprocessor;

public AgentEngine(..., TextPreprocessor textPreprocessor) {
    ...
    this.textPreprocessor = textPreprocessor;
}
```

- [ ] **Step 2: 修改 autoRecommendAndExecute**

在 `autoRecommendAndExecute()` 开头插入预处理：

```java
private Flux<AgentEvent> autoRecommendAndExecute(String conversationId, String userMessage) {
    // v3.4: 文本预处理管道
    String fileContent = conversationManager.getMetadata(conversationId, "pendingFileContent");
    PreprocessResult preprocess = textPreprocessor.process(userMessage, fileContent);
    conversationManager.setMetadata(conversationId, "preprocess", preprocess);
    
    // SSE: 发送预处理进度
    Flux<AgentEvent> preprocessEvents = Flux.just(
        AgentEvent.preprocessStart(),
        AgentEvent.preprocessPhase1(preprocess.rawKeywords().size(),
            (int) preprocess.rawKeywords().stream().filter(k -> k.suspectedTypo()).count()),
        AgentEvent.preprocessPhase2(preprocess.intentAnalysis()),
        AgentEvent.preprocessResult(toJson(preprocess))
    );
    
    var recommendation = techniqueRecommender.recommend(userMessage, false, preprocess);
    ...
    return preprocessEvents.concatWith(/* 原有推荐流 */);
}
```

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/xuzhenwei/agent/agent/AgentEngine.java
git commit -m "feat: AgentEngine 推荐前调用 TextPreprocessor 管道，SSE推送预处理进度"
```

---

### Task 7: 修改前端 index.html — 预处理卡片 + SSE事件

**Files:**
- Modify: `src/main/resources/static/index.html`

- [ ] **Step 1: CSS — 预处理卡片样式**

在样式区添加：

```css
.preprocess-card{background:rgba(124,92,252,.04);border:1px solid rgba(124,92,252,.2);border-radius:10px;padding:10px 14px;margin-bottom:8px;font-size:12px;animation:fadeIn .3s}
.preprocess-card .pp-header{display:flex;align-items:center;justify-content:space-between;cursor:pointer}
.preprocess-card .pp-title{color:var(--accent2);font-weight:600}
.preprocess-card .pp-body{display:none;margin-top:8px;padding-top:8px;border-top:1px solid var(--border)}
.preprocess-card.open .pp-body{display:block}
.pp-kw{display:inline-block;padding:2px 8px;margin:2px;border-radius:6px;background:rgba(124,92,252,.1);font-size:10px;color:var(--accent2)}
.pp-kw.typo{background:rgba(240,160,80,.15);color:var(--warn);text-decoration:line-through}
.pp-kw.typo::after{content:' → 'attr(data-fix);text-decoration:none;color:var(--accent3)}
```

- [ ] **Step 2: JavaScript — SSE事件处理**

在 `streamWithRetry` 的事件处理中添加：

```javascript
case 'PREPROCESS_PHASE1':
case 'PREPROCESS_PHASE2':
    // 更新或创建预处理卡片
    var ppCard = document.querySelector('.preprocess-card');
    if (!ppCard) {
        ppCard = document.createElement('div');
        ppCard.className = 'preprocess-card open';
        var chat = document.getElementById('chat');
        chat.insertBefore(ppCard, chat.lastElementChild);
        // 3秒后自动折叠
        setTimeout(function(){ ppCard.classList.remove('open'); }, 3000);
    }
    ppCard.querySelector('.pp-status').textContent = data.content;
    break;

case 'PREPROCESS_RESULT':
    // 渲染关键词列表
    try {
        var pp = JSON.parse(data.content);
        var kwHtml = '';
        pp.rawKeywords.forEach(function(k){
            var cls = k.suspectedTypo ? 'pp-kw typo' : 'pp-kw';
            var fix = k.suspectedTypo ? ' data-fix="'+k.suggestion+'"' : '';
            kwHtml += '<span class="'+cls+'"'+fix+'>'+k.word+'</span>';
        });
        var body = ppCard.querySelector('.pp-body');
        body.innerHTML = '<div style="margin-bottom:4px;color:var(--text2)">📊 '+pp.intentAnalysis.coreQuestion+'</div>'
            + '<div style="font-size:10px;color:var(--text2)">子问题: '+pp.intentAnalysis.subQuestions.join(' · ')+'</div>'
            + '<div style="margin-top:6px">'+kwHtml+'</div>';
    } catch(e) { console.log('preprocess result parse error', e); }
    break;
```

- [ ] **Step 3: 提交**

```bash
git add src/main/resources/static/index.html
git commit -m "feat: 前端预处理卡片渲染 + SSE PREPROCESS_* 事件处理"
```

---

### Task 8: 编译验证 + 最终提交

- [ ] **Step 1: 编译**

```bash
mvn compile -q
# Expected: BUILD SUCCESS
```

- [ ] **Step 2: 推送**

```bash
git push origin master
```
