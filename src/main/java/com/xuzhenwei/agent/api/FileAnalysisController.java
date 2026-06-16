package com.xuzhenwei.agent.api;

import com.xuzhenwei.agent.agent.AgentEvent;
import com.xuzhenwei.agent.agent.DeepThinkService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * 文件分析 API — 支持上传文件 + 读取本地路径 + 56技法分析
 */
@RestController
@RequestMapping("/api/file")
@CrossOrigin
public class FileAnalysisController {

    private static final Logger log = LoggerFactory.getLogger(FileAnalysisController.class);
    private final DeepThinkService deepThinkService;

    public FileAnalysisController(DeepThinkService deepThinkService) {
        this.deepThinkService = deepThinkService;
    }

    /**
     * 上传文件并深度分析
     */
    @PostMapping(value = "/upload", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<AgentEvent> uploadAndAnalyze(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "topic", defaultValue = "请分析这个文件") String topic) {

        return Flux.create(sink -> {
            try {
                String content = new String(file.getBytes(), StandardCharsets.UTF_8);
                String filename = file.getOriginalFilename();
                long size = file.getSize();

                sink.next(AgentEvent.stepStart(1,
                        "📄 读取文件: %s (%.1f KB)".formatted(filename, size / 1024.0), "file"));
                sink.next(AgentEvent.stepContent(1,
                        "文件内容已读取，共 %d 字。正在深度分析...\n\n".formatted(content.length()), "file"));

                analyzeContent(sink, content, topic, filename);

            } catch (IOException e) {
                log.error("文件读取失败", e);
                sink.next(AgentEvent.error("文件读取失败: " + e.getMessage()));
                sink.complete();
            }
        });
    }

    /**
     * 读取本地文件路径并深度分析
     */
    @PostMapping(value = "/read-path", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<AgentEvent> readPathAndAnalyze(@RequestBody Map<String, String> request) {
        String path = request.get("path");
        String topic = request.getOrDefault("topic", "请分析这个文件");

        return Flux.create(sink -> {
            try {
                Path filePath = Path.of(path);
                if (!Files.exists(filePath)) {
                    sink.next(AgentEvent.error("文件不存在: " + path));
                    sink.complete();
                    return;
                }

                String content = Files.readString(filePath, StandardCharsets.UTF_8);
                String filename = filePath.getFileName().toString();

                sink.next(AgentEvent.stepStart(1,
                        "📂 读取本地文件: %s".formatted(filename), "file"));
                sink.next(AgentEvent.stepContent(1,
                        "路径: %s\n大小: %.1f KB\n共 %d 字\n\n".formatted(
                                path, (double) Files.size(filePath) / 1024, content.length()), "file"));

                analyzeContent(sink, content, topic, filename);

            } catch (IOException e) {
                log.error("读取本地文件失败: {}", path, e);
                sink.next(AgentEvent.error("读取失败: " + e.getMessage()));
                sink.complete();
            }
        });
    }

    /**
     * 批量读取目录下所有文件并分析
     */
    @PostMapping(value = "/read-dir", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<AgentEvent> readDirAndAnalyze(@RequestBody Map<String, String> request) {
        String dirPath = request.get("path");
        String topic = request.getOrDefault("topic", "请综合分析这些文件");

        return Flux.create(sink -> {
            try {
                Path dir = Path.of(dirPath);
                if (!Files.isDirectory(dir)) {
                    sink.next(AgentEvent.error("不是有效目录: " + dirPath));
                    sink.complete();
                    return;
                }

                // 收集所有可读文本文件
                List<Path> files = new ArrayList<>();
                try (var stream = Files.list(dir)) {
                    files = stream
                            .filter(Files::isRegularFile)
                            .filter(f -> isReadableFile(f))
                            .sorted()
                            .toList();
                }

                sink.next(AgentEvent.stepStart(1,
                        "📁 扫描目录: %s (找到 %d 个文件)".formatted(dirPath, files.size()), "file"));

                // 读取所有文件内容
                StringBuilder allContent = new StringBuilder();
                for (Path f : files) {
                    try {
                        String content = Files.readString(f, StandardCharsets.UTF_8);
                        if (content.length() > 10000) {
                            content = content.substring(0, 10000) + "\n...(已截断，原文更长)";
                        }
                        allContent.append("\n--- ").append(f.getFileName()).append(" ---\n");
                        allContent.append(content).append("\n");
                    } catch (IOException e) {
                        allContent.append("\n--- ").append(f.getFileName()).append(" ---\n");
                        allContent.append("[读取失败: ").append(e.getMessage()).append("]\n");
                    }
                }

                sink.next(AgentEvent.stepContent(1,
                        "已读取 %d 个文件，共 %d 字。正在深度分析...\n\n".formatted(
                                files.size(), allContent.length()), "file"));

                analyzeContent(sink, allContent.toString(), topic, dirPath);

            } catch (IOException e) {
                log.error("读取目录失败: {}", dirPath, e);
                sink.next(AgentEvent.error("读取失败: " + e.getMessage()));
                sink.complete();
            }
        });
    }

    /**
     * 通用的深度分析流程
     */
    private void analyzeContent(
            reactor.core.publisher.FluxSink<AgentEvent> sink,
            String content, String topic, String sourceName) {

        try {
            // 构建分析提示词
            String analysisPrompt = """
                    请分析以下文件内容，并结合 56 种人机共想技法给出建议。

                    【文件来源】%s
                    【分析主题】%s

                    【文件内容】
                    %s

                    请从以下角度分析：
                    1. **核心信息提取** — 这个文件讲了什么？关键数据和结论是什么？
                    2. **问题诊断** — 从内容中能看出什么问题、挑战或机会？
                    3. **技法匹配** — 根据内容特点，推荐 3-5 条最适合分析这个问题的技法
                    4. **行动建议** — 基于分析结果，具体该做什么？

                    请用清晰的结构输出，关键结论加粗。
                    """.formatted(sourceName, topic, content);

            // 深度推理
            sink.next(AgentEvent.stepStart(2, "🧠 深度推理文件内容...", "deepthink-reasoning"));
            sink.next(AgentEvent.stepContent(2, "正在用 DeepSeek Reasoner 分析...\n\n", "deepthink-reasoning"));

            var result = deepThinkService.deepThink(analysisPrompt, null);

            // 推理过程
            sink.next(AgentEvent.stepContent(2, "### 📊 分析推理\n\n", "deepthink-reasoning"));
            String reasoning = result.reasoning();
            if (reasoning != null && reasoning.length() > 2500) {
                reasoning = reasoning.substring(0, 2500) + "\n\n...(推理较长已截断)";
            }
            for (String line : reasoning.split("\n")) {
                sink.next(AgentEvent.stepContent(2, line + "\n", "deepthink-reasoning"));
            }
            sink.next(AgentEvent.stepComplete(2, "deepthink-reasoning"));

            // 最终答案
            sink.next(AgentEvent.stepStart(3, "📝 生成分析报告...", "deepthink-final"));
            sink.next(AgentEvent.stepContent(3, "\n### 🎯 分析报告\n\n", "deepthink-final"));
            for (String line : result.finalAnswer().split("\n")) {
                sink.next(AgentEvent.stepContent(3, line + "\n", "deepthink-final"));
            }
            sink.next(AgentEvent.stepComplete(3, "deepthink-final"));

        } catch (Exception e) {
            log.error("分析异常", e);
            sink.next(AgentEvent.error("分析失败: " + e.getMessage()));
        }
        sink.complete();
    }

    private boolean isReadableFile(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        return name.endsWith(".md") || name.endsWith(".txt")
                || name.endsWith(".csv") || name.endsWith(".json")
                || name.endsWith(".yml") || name.endsWith(".yaml")
                || name.endsWith(".xml") || name.endsWith(".html")
                || name.endsWith(".java") || name.endsWith(".py")
                || name.endsWith(".js") || name.endsWith(".log");
    }
}
