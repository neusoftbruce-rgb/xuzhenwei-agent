package com.xuzhenwei.agent.api;

import com.xuzhenwei.agent.output.DocumentExportService;
import com.xuzhenwei.agent.session.SessionService;
import com.xuzhenwei.agent.session.entity.SessionOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 文档导出 API — Word / Excel / PPT
 */
@RestController
@RequestMapping("/api/export")
@CrossOrigin
public class ExportController {

    private static final Logger log = LoggerFactory.getLogger(ExportController.class);

    private final DocumentExportService exportService;
    private final SessionService sessionService;

    public ExportController(DocumentExportService exportService, SessionService sessionService) {
        this.exportService = exportService;
        this.sessionService = sessionService;
    }

    /**
     * 导出文档
     *
     * @param request { content, format: "WORD"/"EXCEL"/"PPT", title, techniqueLabel?, sessionId? }
     * @return 文件下载
     */
    @PostMapping
    public ResponseEntity<byte[]> export(@RequestBody Map<String, String> request) {
        try {
            String content = request.get("content");
            String format = request.getOrDefault("format", "WORD").toUpperCase();
            String title = request.getOrDefault("title", "徐振伟智能体分析报告");
            String techniqueLabel = request.getOrDefault("techniqueLabel", ""); // v2.1: 技法溯源
            String sessionId = request.get("sessionId"); // v2.2: 关联会话

            if (content == null || content.isBlank()) {
                return ResponseEntity.badRequest().body("内容为空".getBytes());
            }

            DocumentExportService.ExportFormat fmt = DocumentExportService.ExportFormat.valueOf(format);
            byte[] data = exportService.export(content, fmt, title, techniqueLabel);

            // v2.2: 自动保存为会话产出
            if (sessionId != null && !sessionId.isBlank()) {
                try {
                    SessionOutput.OutputFormat outputFormat = switch (fmt) {
                        case WORD -> SessionOutput.OutputFormat.WORD;
                        case EXCEL -> SessionOutput.OutputFormat.EXCEL;
                        case PPT -> SessionOutput.OutputFormat.PPT;
                    };
                    sessionService.saveOutput(sessionId, outputFormat, title, content, techniqueLabel);
                    log.debug("导出已保存为会话产出: session={}, title={}", sessionId, title);
                } catch (Exception e) {
                    log.warn("保存会话产出失败（不影响导出）: {}", e.getMessage());
                }
            }

            String filename;
            MediaType mediaType;
            switch (fmt) {
                case WORD:
                    filename = title + ".docx";
                    mediaType = MediaType.parseMediaType(
                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
                    break;
                case EXCEL:
                    filename = title + ".xlsx";
                    mediaType = MediaType.parseMediaType(
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
                    break;
                case PPT:
                    filename = title + ".pptx";
                    mediaType = MediaType.parseMediaType(
                            "application/vnd.openxmlformats-officedocument.presentationml.presentation");
                    break;
                default:
                    return ResponseEntity.badRequest().build();
            }

            return ResponseEntity.ok()
                    .contentType(mediaType)
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + java.net.URLEncoder.encode(filename, "UTF-8") + "\"")
                    .body(data);

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(("不支持的格式: " + request.get("format") + "，可选: WORD / EXCEL / PPT").getBytes());
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(("导出失败: " + e.getMessage()).getBytes());
        }
    }

    /** 获取支持的导出格式 */
    @GetMapping("/formats")
    public Map<String, Object> getFormats() {
        return Map.of("formats", List.of(
                Map.of("id", "WORD", "name", "Word 文档 (.docx)", "icon", "📄"),
                Map.of("id", "EXCEL", "name", "Excel 表格 (.xlsx)", "icon", "📊"),
                Map.of("id", "PPT", "name", "PPT 幻灯片 (.pptx)", "icon", "📽️")
        ));
    }
}
