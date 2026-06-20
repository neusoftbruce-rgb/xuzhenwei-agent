# TextPreprocessor — 文本预处理管道设计书 v1.0

> 设计日：2026-06-20
> 状态：待实现

---

## 一、背景与目标

### 1.1 现状问题

- `TechniqueRecommender.keywordMatch()` 使用硬编码关键词表（100+条 `keywordMap.put("xx", ...)`），维护成本高
- `DocumentIntakeService.countDomainKeywords()` 同样硬编码领域词
- 用户上传文件后，没有结构化的关键词提取，只是把原始文本拼到 Prompt 里
- 没有错别字检测能力

### 1.2 目标

在所有输入（文件上传 + 输入框文字）进入技法推荐引擎之前，插入一层**透明预处理管道**：

1. 本地：分词 + 频次统计 + 疑似错别字检测（0 token, <10ms）
2. AI：智谱一次性完成纠错 + 同义词合并 + 意图提取（~300 token, 1-2s）
3. 输出结构化 PreprocessResult，直接驱动技法推荐

核心原则：**下游不改动**，TechniqueRecommender 和 AgentEngine 保持现有逻辑，只是输入从"原始文字"替换为"清洗后的精炼文字"。

---

## 二、架构

```
用户输入(文件+文字)
       │
       ▼
┌──────────────────────────┐
│  TextPreprocessor (新增) │
│                          │
│  Phase 1: 本地分析        │  ← 0 token, <10ms
│  · 分词 → 频次 → Top-20  │
│  · 去停用词               │
│  · 编辑距离错别字检测     │
│                          │
│  Phase 2: 智谱精炼        │  ← ~300 token, 1-2s
│  · 确认/纠正错别字        │
│  · 合并同义词             │
│  · 提取意图+子问题        │
│                          │
│  输出: PreprocessResult   │
└──────────┬───────────────┘
           │
           ▼
┌──────────────────────────┐
│  TechniqueRecommender    │  ← 改动1行
│  用 correctedText +      │
│  intentAnalysis 匹配技法  │
└──────────┬───────────────┘
           │
           ▼
      技法推荐 → MECE → 智谱执行
```

---

## 三、数据结构

### 3.1 PreprocessResult

```java
public record PreprocessResult(
    List<RawKeyword> rawKeywords,     // 表1: 原始关键词
    String correctedText,             // 清洗纠错后文本
    IntentAnalysis intentAnalysis,    // 表2: AI深度分析
    long phase1Ms,                    // Phase1耗时
    long phase2Ms                     // Phase2耗时
) {}

public record RawKeyword(
    String word,           // 词
    int frequency,         // 频次
    double weight,         // 权重 0-1
    String source,         // "file" / "user_input"
    boolean suspectedTypo, // 疑似错别字
    String suggestion      // 纠错建议(null=无)
) {}

public record IntentAnalysis(
    String intent,           // "诊断分析"/"创意发想"/"方案打磨"/"执行落地"
    List<String> domains,    // ["农业技术","商业经营"]
    String coreQuestion,     // 核心问题一句话
    List<String> subQuestions, // 子问题列表
    int complexity           // 1-10
) {}
```

### 3.2 JSON 示例

```json
{
  "rawKeywords": [
    {"word":"高糖","freq":8,"weight":0.85,"source":"file","suspectedTypo":false,"suggestion":null},
    {"word":"冷棚","freq":5,"weight":0.72,"source":"file","suspectedTypo":false,"suggestion":null},
    {"word":"柿","freq":3,"weight":0.40,"source":"user_input","suspectedTypo":true,"suggestion":"西红柿"}
  ],
  "correctedText": "如何提高冷棚高糖西红柿的产量和糖度...",
  "intentAnalysis": {
    "intent": "诊断分析",
    "domains": ["农业技术","设施种植"],
    "coreQuestion": "如何提高冷棚高糖西红柿的产量和糖度",
    "subQuestions": ["品种选择","密度控制","水肥管理","温湿度调控"],
    "complexity": 7
  }
}
```

### 3.3 存储策略

- **不新建数据库表**
- PreprocessResult 序列化为 JSON，存入 `MessageRecord.metadata` 字段
- 技法推荐时可以直接从 metadata 反序列化复用，避免重复调用智谱

---

## 四、本地分析逻辑（Phase 1）

### 4.1 分词

用轻量级中文分词策略（不引入外部 NLP 库）：

1. 按标点符号切分：`，。！？、；：""''【】《》（）…—·\s`
2. 过滤单字词（无信息量）
3. 过滤长度>8的字词（可能是句子片段）

### 4.2 停用词表

内置中文停用词表（~50个）：
`的 了 是 我 你 他 她 它 们 这 那 吗 呢 吧 啊 哦 嗯 就 也 都 还 要 会 能 可以 应该 可能 已经 正在 一直 因为 所以 但是 虽然 如果 而且 或者 和 与 及 对 把 被 让 给 向 从 在 到 用 以 为`

### 4.3 频次统计与权重

```java
weight = min(1.0, frequency / maxFrequency + sourceBias);
// sourceBias: file=0.15, user_input=0.0
// 文件中的词比对话中的词有轻微加成
```

取 Top-20 按 weight 降序。

### 4.4 错别字检测

用**编辑距离**（Levenshtein）匹配常见中文词库：

1. 内置基础词库（~2000个农业+商业+通用中文常见词）
2. 对每个候选词，计算跟词库中相近长度词的编辑距离
3. 距离=0 → 正确
4. 距离=1-2 → `suspectedTypo=true`，取词库中距离最小的作为 `suggestion`
5. 距离≥3 → 可能是专有名词（品种名、地名等），保留原文

词库存储为 `src/main/resources/common_words.txt`，每行一个词，启动时加载。

---

## 五、智谱精炼逻辑（Phase 2）

### 5.1 触发条件

- 有文件上传 → 总是触发
- 仅文字输入 → 字数>20 或 `suspectedTypo` 数量>0 才触发
- 短问候（如"你好"）→ 跳过

### 5.2 Prompt 模板

```
你是一个文本预处理引擎。只返回JSON，不要任何解释。

输入文本：
{correctedText}

提取到的候选关键词（含疑似错别字标注）：
{rawKeywordsSummary}

请完成以下任务：
1. 纠错：suspectedTypo=true的词，确认是否真的是错别字，给出正确写法
2. 合并同义词：意思相近的词合并为统一表述
3. 意图提取：用户真正想解决什么问题？隐含意图是什么？

严格返回以下JSON格式：
{
  "correctedText": "纠错后的完整文本",
  "refinedKeywords": [{"word":"xx","weight":0.9}],
  "intent": "诊断分析|创意发想|方案打磨|执行落地",
  "domains": ["领域1"],
  "coreQuestion": "一句话核心问题",
  "subQuestions": ["子问题1"],
  "complexity": 7
}
```

### 5.3 模型配置

- 模型: `glm-5.2`
- maxTokens: 300
- temperature: 0.1
- 失败降级: 使用 Phase 1 本地结果，不阻塞主流程

---

## 六、与现有代码的衔接

### 6.1 TechniqueRecommender 改动

```java
// 旧
public RecommendationResult recommend(String userInput, boolean shuffle) {
    String input = userInput.toLowerCase();
    var analysis = decisionEngine.analyze(userInput);
    var matches = keywordMatch(input, shuffle);
    ...
}

// 新
public RecommendationResult recommend(String userInput, boolean shuffle,
                                       PreprocessResult preprocess) {
    // 用精炼文本替代原始输入做关键词匹配
    String input = (preprocess != null && preprocess.correctedText() != null)
        ? preprocess.correctedText().toLowerCase()
        : userInput.toLowerCase();
    // intentAnalysis 注入到 AnalysisContext
    var analysis = (preprocess != null)
        ? decisionEngine.analyzeWithIntent(userInput, preprocess.intentAnalysis())
        : decisionEngine.analyze(userInput);
    var matches = keywordMatch(input, shuffle);
    ...
}
```

### 6.2 调用时机

在 `AgentEngine.autoRecommendAndExecute()` 中，推荐之前调用预处理：

```java
private Flux<AgentEvent> autoRecommendAndExecute(String conversationId, String userMessage) {
    // v3.4: 文本预处理管道
    PreprocessResult preprocess = textPreprocessor.process(userMessage, pendingFileContent);
    // 存入会话元数据
    conversationManager.setMetadata(conversationId, "preprocess", preprocess);
    
    var recommendation = techniqueRecommender.recommend(userMessage, false, preprocess);
    ...
}
```

### 6.3 文件上传衔接

前端 `send()` 中，文件内容已经在 `fileContent` 变量中。传到后端时拼到 message 前面：

```
文件内容:\n{fileContent}\n---\n用户问题:\n{userMessage}
```

TextPreprocessor 把这个拼接后的文本当做输入，`RawKeyword.source` 字段区分来源。

---

## 七、前端展示

### 7.1 预处理卡片

在推荐卡片之前插入一个可折叠的预处理结果卡片：

```
🔍 文本预处理                           [展开▼]
✅ 提取关键词 18个 · 疑似错别字 2个
✅ 智谱精炼 · 农业技术·诊断分析
📊 核心问题：如何提高冷棚西红柿的糖度
```

默认3秒后自动折叠，用户可手动展开看关键词详情。

### 7.2 SSE 事件

新增 SSE 事件类型：
```java
PREPROCESS_START   → 前端显示"预处理中..."
PREPROCESS_PHASE1  → 显示"✅ 提取关键词 N个"
PREPROCESS_PHASE2  → 显示"✅ 智谱精炼完成"
PREPROCESS_RESULT  → 附带 PreprocessResult JSON，前端渲染卡片
```

---

## 八、不做什么

- ❌ 不引入外部 NLP 分词库（如 jieba）— 用标点切分+词库匹配即可
- ❌ 不新建数据库表 — 用 MessageRecord.metadata JSON 字段
- ❌ 不修改 56条技法的 Prompt 模板
- ❌ 不修改 MECE 引擎、技法执行器
- ❌ 不增加对外部 API 的依赖（只用已有的智谱）

---

## 九、新增/修改文件清单

| 文件 | 改动 | 说明 |
|------|------|------|
| `TextPreprocessor.java` | 🆕 | 核心预处理服务 |
| `common_words.txt` | 🆕 | 中文基础词库(~2000词) |
| `TechniqueRecommender.java` | 🔧 | recommend()新增preprocess参数 |
| `RecommendationDecisionEngine.java` | 🔧 | 新增analyzeWithIntent()方法 |
| `AgentEngine.java` | 🔧 | 推荐前调用preprocessor |
| `AgentEvent.java` | 🔧 | 新增PREPROCESS_*事件类型 |
| `index.html` | 🔧 | 预处理卡片渲染 + SSE事件处理 |
