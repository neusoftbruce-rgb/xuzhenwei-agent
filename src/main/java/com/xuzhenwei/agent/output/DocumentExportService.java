package com.xuzhenwei.agent.output;

import org.apache.poi.xslf.usermodel.*;
import org.apache.poi.xwpf.usermodel.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.*;
import java.util.List;

/**
 * 文档导出服务 — 生成 Word / Excel / PPT 文件
 *
 * <p>基于 Apache POI，支持：
 * <ul>
 *   <li>Word (.docx) — 标题 + 正文 + 列表</li>
 *   <li>Excel (.xlsx) — 表格结构化数据</li>
 *   <li>PPT (.pptx) — 自动分页幻灯片</li>
 * </ul>
 */
@Service
public class DocumentExportService {

    private static final Logger log = LoggerFactory.getLogger(DocumentExportService.class);

    public enum ExportFormat { WORD, EXCEL, PPT }

    /**
     * 导出文档
     *
     * @param content 内容（Markdown 格式文本）
     * @param format  导出格式
     * @param title   文档标题
     * @return 文件字节数组
     */
    public byte[] export(String content, ExportFormat format, String title) throws IOException {
        return switch (format) {
            case WORD -> generateWord(content, title);
            case EXCEL -> generateExcel(content, title);
            case PPT -> generatePpt(content, title);
        };
    }

    // ====== Word ======
    private byte[] generateWord(String content, String title) throws IOException {
        try (XWPFDocument doc = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            // 标题
            XWPFParagraph titlePara = doc.createParagraph();
            titlePara.setAlignment(ParagraphAlignment.CENTER);
            XWPFRun titleRun = titlePara.createRun();
            titleRun.setText(title);
            titleRun.setBold(true);
            titleRun.setFontSize(18);
            titleRun.setFontFamily("Microsoft YaHei");

            // 空行
            doc.createParagraph();

            // 解析 Markdown 内容为段落
            for (String line : content.split("\n")) {
                line = line.trim();
                if (line.isEmpty()) {
                    doc.createParagraph();
                    continue;
                }

                XWPFParagraph para = doc.createParagraph();
                XWPFRun run = para.createRun();
                run.setFontFamily("Microsoft YaHei");
                run.setFontSize(11);

                if (line.startsWith("## ")) {
                    para.setStyle("Heading2");
                    run.setText(line.substring(3).trim());
                    run.setBold(true);
                    run.setFontSize(14);
                } else if (line.startsWith("### ")) {
                    para.setStyle("Heading3");
                    run.setText(line.substring(4).trim());
                    run.setBold(true);
                    run.setFontSize(12);
                } else if (line.startsWith("- ") || line.startsWith("* ")) {
                    run.setText("• " + line.substring(2).trim());
                } else if (line.startsWith("> ")) {
                    run.setText(line.substring(2).trim());
                    run.setItalic(true);
                } else if (line.startsWith("**") && line.contains("**")) {
                    // 粗体处理
                    String text = line.replaceAll("\\*\\*(.*?)\\*\\*", "$1");
                    run.setText(text);
                    run.setBold(true);
                } else {
                    run.setText(line);
                }
            }

            doc.write(out);
            return out.toByteArray();
        }
    }

    // ====== Excel ======
    private byte[] generateExcel(String content, String title) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            Sheet sheet = wb.createSheet(title.substring(0, Math.min(31, title.length())));
            int rowNum = 0;

            // 标题行
            Row titleRow = sheet.createRow(rowNum++);
            Cell titleCell = titleRow.createCell(0);
            titleCell.setCellValue(title);
            CellStyle titleStyle = wb.createCellStyle();
            Font titleFont = wb.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 14);
            titleStyle.setFont(titleFont);
            titleCell.setCellStyle(titleStyle);

            rowNum++; // 空行

            // 解析内容
            String currentSection = "";
            for (String line : content.split("\n")) {
                line = line.trim();
                if (line.isEmpty()) continue;

                Row row = sheet.createRow(rowNum++);

                if (line.startsWith("## ")) {
                    currentSection = line.substring(3).trim();
                    Cell c = row.createCell(0);
                    c.setCellValue("【" + currentSection + "】");
                    CellStyle cs = wb.createCellStyle();
                    Font cf = wb.createFont();
                    cf.setBold(true);
                    cf.setFontHeightInPoints((short) 12);
                    cs.setFont(cf);
                    c.setCellStyle(cs);
                } else if (line.startsWith("### ")) {
                    Cell c = row.createCell(0);
                    c.setCellValue(line.substring(4).trim());
                    CellStyle cs = wb.createCellStyle();
                    Font cf = wb.createFont();
                    cf.setBold(true);
                    cs.setFont(cf);
                    c.setCellStyle(cs);
                } else if (line.startsWith("|") && line.contains("|")) {
                    // 表格行
                    String[] cols = line.split("\\|");
                    for (int i = 0; i < cols.length; i++) {
                        String val = cols[i].trim();
                        if (val.isEmpty() || val.startsWith("---") || val.startsWith(":--")) continue;
                        row.createCell(i).setCellValue(val);
                    }
                } else {
                    Cell c = row.createCell(0);
                    c.setCellValue(line.replaceAll("\\*\\*|#|>|-", "").trim());
                }
            }

            // 自动调整列宽
            sheet.autoSizeColumn(0);
            sheet.setColumnWidth(0, Math.min(sheet.getColumnWidth(0), 20000));

            wb.write(out);
            return out.toByteArray();
        }
    }

    // ====== PPT ======
    private byte[] generatePpt(String content, String title) throws IOException {
        try (XMLSlideShow ppt = new XMLSlideShow();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            // 获取幻灯片母版
            XSLFSlideMaster master = ppt.getSlideMasters().get(0);
            XSLFSlideLayout layout = master.getLayout(SlideLayout.TITLE_AND_CONTENT);

            // === 封面 ===
            XSLFSlide cover = ppt.createSlide(layout);
            XSLFTextShape coverTitle = cover.getPlaceholder(0);
            coverTitle.setText(title);
            XSLFTextShape coverSub = cover.getPlaceholder(1);
            coverSub.setText("徐振伟智能体 · AI分析报告\n生成时间: " + java.time.LocalDateTime.now().toString().replace("T", " ").substring(0, 16));

            // === 内容分页 ===
            List<String> slides = splitContent(content);

            for (String slideContent : slides) {
                XSLFSlide slide = ppt.createSlide(layout);

                // 标题（取第一行）
                String[] lines = slideContent.split("\n", 2);
                String slideTitle = lines[0].replaceAll("^#+\\s*", "").trim();
                if (slideTitle.length() > 60) slideTitle = slideTitle.substring(0, 60) + "...";

                XSLFTextShape titleShape = slide.getPlaceholder(0);
                titleShape.setText(slideTitle);

                // 内容
                String body = lines.length > 1 ? lines[1] : "";
                XSLFTextShape bodyShape = slide.getPlaceholder(1);
                bodyShape.clearText();
                XSLFTextParagraph para = bodyShape.addNewTextParagraph();
                XSLFTextRun run = para.addNewTextRun();
                run.setText(body.replaceAll("[*#>`|\\-]", "").trim());
                run.setFontSize(14.0);
            }

            ppt.write(out);
            return out.toByteArray();
        }
    }

    /**
     * 将长文本拆分为 PPT 页面（每页约 5-8 行）
     */
    private List<String> splitContent(String content) {
        List<String> slides = new java.util.ArrayList<>();
        StringBuilder current = new StringBuilder();
        int lines = 0;

        for (String line : content.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;

            // 遇到 ## 标题，新起一页
            if (line.startsWith("## ") && current.length() > 0) {
                slides.add(current.toString().trim());
                current = new StringBuilder();
                lines = 0;
            }

            current.append(line).append("\n");
            lines++;

            // 每页最多 10 行
            if (lines >= 10) {
                slides.add(current.toString().trim());
                current = new StringBuilder();
                lines = 0;
            }
        }

        if (current.length() > 0) {
            slides.add(current.toString().trim());
        }

        return slides;
    }
}
