package com.xuzhenwei.agent.api;

import com.xuzhenwei.agent.agent.*;
import com.xuzhenwei.agent.session.SessionService;
import com.xuzhenwei.agent.agent.ConversationManager;
import com.xuzhenwei.agent.session.entity.Session;
import com.xuzhenwei.agent.technique.TechniqueRegistry;
import com.xuzhenwei.agent.technique.TechniqueExecutor;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Agent 核心 API — v2.3: 频率限制 + 错误分类
 */
@RestController
@RequestMapping("/api/agent")
@CrossOrigin
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private final AgentEngine agentEngine;
    private final DeepThinkService deepThinkService;
    private final DomainAdvisor domainAdvisor;
    private final TokenSaver tokenSaver;
    private final TechniqueRegistry techniqueRegistry;
    private final TechniqueExecutor techniqueExecutor;
    private final SessionService sessionService;
    private final ConversationManager conversationManager;
    private final RateLimiter rateLimiter;
    private final ErrorClassifier errorClassifier;
    private final ConsultingAnalysisService consultingService;
    private final BatchAnalysisService batchAnalysisService;
    private final DocumentIntakeService documentIntakeService;
    private final FollowupService followupService;
    private final QueryDecompositionService decompositionService;

    public AgentController(AgentEngine agentEngine,
                           DeepThinkService deepThinkService,
                           DomainAdvisor domainAdvisor,
                           TokenSaver tokenSaver,
                           TechniqueRegistry techniqueRegistry,
                           TechniqueExecutor techniqueExecutor,
                           SessionService sessionService,
                           ConversationManager conversationManager,
                           RateLimiter rateLimiter,
                           ErrorClassifier errorClassifier,
                           ConsultingAnalysisService consultingService,
                           BatchAnalysisService batchAnalysisService,
                           DocumentIntakeService documentIntakeService,
                           FollowupService followupService,
                           QueryDecompositionService decompositionService) {
        this.agentEngine = agentEngine;
        this.deepThinkService = deepThinkService;
        this.domainAdvisor = domainAdvisor;
        this.tokenSaver = tokenSaver;
        this.techniqueRegistry = techniqueRegistry;
        this.techniqueExecutor = techniqueExecutor;
        this.sessionService = sessionService;
        this.conversationManager = conversationManager;
        this.rateLimiter = rateLimiter;
        this.errorClassifier = errorClassifier;
        this.consultingService = consultingService;
        this.batchAnalysisService = batchAnalysisService;
        this.documentIntakeService = documentIntakeService;
        this.followupService = followupService;
        this.decompositionService = decompositionService;
    }

    @PostMapping(value = "/think", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<AgentEvent> think(@RequestBody ThinkRequest request,
                                   HttpServletRequest httpRequest) {
        // ---- 频率限制 (v2.3) ----
        String clientId = getClientId(httpRequest, request);
        boolean isDeep = request.deepThink() != null && request.deepThink();
        if (!rateLimiter.tryAcquire(clientId, isDeep)) {
            long retryAfter = rateLimiter.getRetryAfterSeconds(clientId, isDeep);
            return Flux.just(AgentEvent.error(
                    "⚠️ 请求太频繁，请 " + retryAfter + " 秒后重试 (每IP每分钟" +
                    (isDeep ? "10" : "20") + "次)"));
        }

        // ---- 解析会话ID ----
        String sessionId = resolveSessionId(request);
        String conversationId = sessionId;

        String message = request.message();

        // ---- 智能判断是否需要深度思考 ----
        boolean useDeepThink;
        if (request.deepThink() != null) {
            useDeepThink = request.deepThink();
        } else {
            useDeepThink = tokenSaver.shouldDeepThink(message);
        }

        // ---- 压缩 Prompt ----
        String compressed = tokenSaver.compressPrompt(message);

        // ---- 执行技法 ----
        var techniqueId = request.techniqueId();

        // 记录技法到会话
        if (techniqueId != null && !techniqueId.isBlank() && !"auto".equals(techniqueId)) {
            sessionService.appendTechnique(sessionId, techniqueId);
        }

        // 如果有技法ID，显示使用的技法信息
        if (techniqueId != null && !techniqueId.isBlank()) {
            String techLabel;
            if (techniqueId.startsWith("recipe:")) {
                techLabel = "使用配方: " + techniqueId.substring(7);
            } else {
                var tech = techniqueRegistry.get(techniqueId);
                techLabel = tech.map(t -> "使用技法: [%s] %s".formatted(t.getId(), t.getName()))
                        .orElse("使用技法: " + techniqueId);
            }

            Flux<AgentEvent> header = Flux.just(
                    AgentEvent.stepContent(0, "📌 " + techLabel + "\n\n", "tech-label")
            );

            // 配方模式（v2.3: recipe也支持deepThink）
            if (techniqueId.startsWith("recipe:")) {
                if (useDeepThink) {
                    return header.concatWith(deepThink(compressed, conversationId))
                            .concatWith(agentEngine.think(conversationId, compressed, techniqueId))
                            .onErrorResume(e -> Flux.just(toErrorEvent(e)));
                }
                return header.concatWith(agentEngine.think(conversationId, compressed, techniqueId))
                        .onErrorResume(e -> Flux.just(toErrorEvent(e)));
            }

            // 深度思考 + 技法合并
            if (useDeepThink) {
                return header.concatWith(deepTechniqueThink(compressed, techniqueId, conversationId))
                        .onErrorResume(e -> Flux.just(toErrorEvent(e)));
            }
            return header.concatWith(agentEngine.think(conversationId, compressed, techniqueId))
                    .onErrorResume(e -> Flux.just(toErrorEvent(e)));
        }

        if (useDeepThink) {
            return deepThink(compressed, conversationId);
        }

        return agentEngine.think(conversationId, compressed, null)
                .onErrorResume(e -> Flux.just(toErrorEvent(e)));
    }

    @PostMapping(value = "/deep-think", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<AgentEvent> deepThinkEndpoint(@RequestBody ThinkRequest request) {
        String sessionId = resolveSessionId(request);
        return deepThink(request.message(), sessionId);
    }

    /**
     * 解析会话ID：优先用请求中的 sessionId，其次 projectId，最后自动创建
     */
    private String resolveSessionId(ThinkRequest request) {
        // 1. 显式传入 sessionId
        if (request.sessionId() != null && !request.sessionId().isBlank()) {
            return request.sessionId();
        }

        // 2. 兼容旧的 projectId 字段
        if (request.projectId() != null && !request.projectId().isBlank()) {
            // 检查是否已有对应会话
            var sessions = sessionService.listByProject(request.projectId());
            if (!sessions.isEmpty()) {
                return sessions.get(0).getId();
            }
        }

        // 3. 自动创建新会话
        String firstMessage = request.message();
        Session session = sessionService.createSession(
                firstMessage != null ? firstMessage.substring(0, Math.min(firstMessage.length(), 50)) : "新会话",
                firstMessage != null ? firstMessage.substring(0, Math.min(firstMessage.length(), 200)) : null,
                request.projectId()
        );
        log.info("自动创建会话: {} ({})", session.getId(), session.getName());
        return session.getId();
    }

    private Flux<AgentEvent> deepThink(String message, String conversationId) {
        return Flux.create(sink -> {
            try {
                long start = System.currentTimeMillis();

                String context = "";
                var matched = domainAdvisor.matchBusiness(message);
                if (matched.isPresent()) {
                    context = domainAdvisor.buildDomainContext();
                }

                sink.next(AgentEvent.stepStart(1, "🧠 深度推理中...", "deepthink-reasoning"));
                sink.next(AgentEvent.stepContent(1, "智能判断需要深度分析，使用推理模型...\n\n", "deepthink-reasoning"));

                var result = deepThinkService.deepThink(message, context);

                // 推理过程（截断）
                sink.next(AgentEvent.stepContent(1, "### 推理过程\n\n", "deepthink-reasoning"));
                String reasoning = result.reasoning();
                if (reasoning != null) {
                    if (reasoning.length() > 1500) reasoning = reasoning.substring(0, 1500) + "\n\n...(推理过程较长，已截断)";
                    for (String line : reasoning.split("\n")) {
                        sink.next(AgentEvent.stepContent(1, line + "\n", "deepthink-reasoning"));
                    }
                }
                sink.next(AgentEvent.stepComplete(1, "deepthink-reasoning"));

                // 最终答案
                sink.next(AgentEvent.stepStart(2, "📝 最终答案", "deepthink-final"));
                String finalAnswer = result.finalAnswer();
                if (finalAnswer != null) {
                    for (String line : finalAnswer.split("\n")) {
                        sink.next(AgentEvent.stepContent(2, line + "\n", "deepthink-final"));
                    }
                }
                sink.next(AgentEvent.stepContent(2,
                        "\n\n*⏱ 耗时 %.1f 秒 · 🧠 深度推理模式*".formatted((System.currentTimeMillis() - start) / 1000.0),
                        "deepthink-final"));
                sink.next(AgentEvent.stepComplete(2, "deepthink-final"));
                sink.complete();

            } catch (Exception e) {
                log.error("深度思考异常", e);
                sink.next(AgentEvent.error("深度思考异常: " + e.getMessage()));
                sink.complete();
            }
        });
    }

    // ====== v2.5: 智能预分析 + 自动路由 API ======

    /**
     * 预分析 — 判断内容复杂度，推荐技法数量(1-9)，不执行分析
     * Token消耗: 0(本地) 或 ~200(轻量模型确认)
     */
    @PostMapping("/intake")
    public Map<String, Object> intake(@RequestBody Map<String, String> body) {
        String content = body.getOrDefault("content", "");
        boolean useModel = "true".equals(body.getOrDefault("useModel", "true"));

        DocumentIntakeService.IntakeResult result;
        if (useModel) {
            result = documentIntakeService.analyze(content);
        } else {
            result = documentIntakeService.localOnly(content);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("complexity", result.complexity());
        response.put("complexityLabel", result.complexityLabel());
        response.put("recommendedTechCount", result.recommendedTechCount());
        response.put("suggestedTechniqueIds", result.suggestedTechIds());
        response.put("domains", result.domains());
        response.put("reasoning", result.reasoning());
        response.put("estimatedTokens", result.estimatedTokens());

        // 附赠技法详情
        List<Map<String, String>> techDetails = result.suggestedTechIds().stream()
                .map(id -> techniqueRegistry.get(id))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .map(t -> Map.of("id", t.getId(), "name", t.getName(), "desc", t.getDescription()))
                .collect(java.util.stream.Collectors.toList());
        response.put("techniqueDetails", techDetails);

        return response;
    }

    // ====== v2.5: 批量分析 API ======

    /**
     * 批量执行多个技法，结果逐个显示不覆盖，最后自动汇总
     */
    @PostMapping(value = "/batch", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<AgentEvent> batchAnalyze(@RequestBody Map<String, Object> body) {
        String message = (String) body.get("message");
        @SuppressWarnings("unchecked")
        List<String> techniqueIds = (List<String>) body.get("techniqueIds");
        String sessionId = (String) body.getOrDefault("sessionId", "batch");

        if (message == null || message.isBlank()) {
            return Flux.just(AgentEvent.error("请输入要分析的问题"));
        }
        if (techniqueIds == null || techniqueIds.isEmpty()) {
            return Flux.just(AgentEvent.error("请至少选择一个技法"));
        }

        log.info("批量分析: {} 个技法, 问题: {}", techniqueIds.size(),
                message.substring(0, Math.min(50, message.length())));
        return batchAnalysisService.executeBatch(message, techniqueIds, sessionId)
                .onErrorResume(e -> Flux.just(toErrorEvent(e)));
    }

    // ====== v2.4: 咨询式分析 API ======

    /**
     * 全景快扫 — 用快速模型生成结构化MECE分析
     */
    @PostMapping(value = "/consulting/scan", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<AgentEvent> consultingScan(@RequestBody ThinkRequest request) {
        String message = request.message();
        if (message == null || message.isBlank()) {
            return Flux.just(AgentEvent.error("请输入要分析的问题"));
        }
        String conversationId = resolveSessionId(request);
        return consultingService.quickScan(message, conversationId)
                .onErrorResume(e -> Flux.just(toErrorEvent(e)));
    }

    /**
     * 按需深挖 — 对指定维度深度分析
     */
    @PostMapping(value = "/consulting/deep-dive", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<AgentEvent> consultingDeepDive(@RequestBody Map<String, String> body) {
        String question = body.get("question");
        String dimension = body.get("dimension");
        String findings = body.getOrDefault("findings", "");
        if (question == null || dimension == null) {
            return Flux.just(AgentEvent.error("请提供原始问题和分析维度"));
        }
        return consultingService.deepDive(question, dimension, findings)
                .onErrorResume(e -> Flux.just(toErrorEvent(e)));
    }

    @GetMapping("/techniques-summary")
    public Map<String, String> techniquesSummary() {
        return Map.of("summary", agentEngine.getTechniquesSummary());
    }

    private Flux<AgentEvent> deepTechniqueThink(String message, String techniqueId, String conversationId) {
        // FIX-08: 注入对话历史上下文，让技法执行感知多轮对话
        String enrichedMessage = message;
        var matched = domainAdvisor.matchBusiness(message);
        if (matched.isPresent()) {
            enrichedMessage = message + "\n\n【领域背景】\n" + domainAdvisor.buildDomainContext();
        }
        // 注入历史对话上下文（最近3轮）
        String history = conversationManager.formatHistory(conversationId);
        if (history != null && !history.isBlank()) {
            enrichedMessage = history + "\n\n【当前问题】\n" + enrichedMessage;
        }
        return techniqueExecutor.execute(techniqueId, enrichedMessage, conversationId);
    }

    /**
     * v2.3: 错误分类 → 用户友好提示
     */
    private AgentEvent toErrorEvent(Throwable e) {
        var classified = errorClassifier.classify(e);
        String icon = classified.type().getIcon();
        String msg = icon + " " + classified.userFriendlyMessage();
        log.warn("{} | 分类={} | 详情={}", msg, classified.type(), classified.technicalDetail());
        return AgentEvent.error(msg);
    }

    /**
     * v3.0 方法7: 智能追问 — 根据对话上下文生成追问建议
     */
    @PostMapping("/followup")
    public Map<String, Object> followup(@RequestBody Map<String, String> body) {
        String question = body.getOrDefault("question", "");
        String response = body.getOrDefault("response", "");

        var suggestions = followupService.generate(question, response);

        List<Map<String, String>> qs = new ArrayList<>();
        for (var fq : suggestions.questions()) {
            qs.add(Map.of("text", fq.text(), "action", fq.quickAction()));
        }

        return Map.of(
                "questions", qs,
                "fromModel", suggestions.fromModel()
        );
    }

    /**
     * v3.0 方法4: 查询拆解 — 判断是否需要拆解+执行拆解
     */
    @PostMapping("/decompose")
    public Map<String, Object> decompose(@RequestBody Map<String, String> body) {
        String message = body.getOrDefault("message", "");
        var result = decompositionService.analyze(message);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("shouldDecompose", result.shouldDecompose());
        response.put("reasoning", result.reasoning());

        if (result.hasSubQuestions()) {
            List<Map<String, Object>> subs = new ArrayList<>();
            for (var sq : result.subQuestions()) {
                Map<String, Object> sub = new LinkedHashMap<>();
                sub.put("index", sq.index());
                sub.put("question", sq.question());
                sub.put("focus", sq.focus());
                subs.add(sub);
            }
            response.put("subQuestions", subs);
        }

        return response;
    }

    /**
     * v3.0 方法9: 检查是否有相似问题的缓存
     */
    @PostMapping("/cache-check")
    public Map<String, Object> cacheCheck(@RequestBody Map<String, String> body) {
        String message = body.getOrDefault("message", "");
        var cached = conversationManager.findSimilar(message);

        if (cached != null && System.currentTimeMillis() - cached.timestamp() < 30 * 60 * 1000) {
            return Map.of(
                    "hit", true,
                    "originalQuestion", cached.userQuestion(),
                    "techniqueId", cached.techniqueId() != null ? cached.techniqueId() : "",
                    "techniqueName", cached.techniqueName() != null ? cached.techniqueName() : "",
                    "summary", cached.responseSummary()
            );
        }
        return Map.of("hit", false);
    }

    /**
     * v2.3: 用户反馈（👍/👎）
     */
    @PostMapping("/feedback")
    public Map<String, String> feedback(@RequestBody Map<String, String> body) {
        String sessionId = body.get("sessionId");
        String rating = body.getOrDefault("rating", "unknown");
        String message = body.getOrDefault("message", "");
        log.info("用户反馈: session={}, rating={}, msg={}", sessionId, rating, message);
        return Map.of("status", "ok", "message", "感谢反馈！");
    }

    /**
     * 获取客户端标识（优先sessionId，fallback到IP）
     */
    private String getClientId(HttpServletRequest httpRequest, ThinkRequest request) {
        // 优先用 sessionId
        if (request.sessionId() != null && !request.sessionId().isBlank()) {
            return request.sessionId();
        }
        // fallback: X-Forwarded-For → remoteAddr
        String xff = httpRequest.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return httpRequest.getRemoteAddr();
    }

    /**
     * Think 请求体 — v2.2: 支持会话持久化
     */
    public record ThinkRequest(
            String projectId,              // 兼容旧版：项目ID
            String sessionId,              // v2.2: 会话ID
            String message,                // 用户消息
            String techniqueId,            // 技法ID
            Boolean deepThink              // 是否深度思考
    ) {}
}
