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
 * 文档导出服务 — v2.3 排版大改善
 * Word: 正文间距、标题样式、引用块、代码块、表格
 * PPT:  深色主题、卡片式内容、装饰元素、技法溯源尾页
 */
@Service
public class DocumentExportService {

    private static final Logger log = LoggerFactory.getLogger(DocumentExportService.class);

    public enum ExportFormat { WORD, EXCEL, PPT }

    // 配色方案
    private static final Color ACCENT = new Color(0x7C, 0x5C, 0xFC);
    private static final Color ACCENT2 = new Color(0x5B, 0x8D, 0xEF);
    private static final Color DARK_BG = new Color(0x0F, 0x0F, 0x1A);
    private static final Color SURFACE = new Color(0x18, 0x18, 0x2A);
    private static final Color TEXT_PRIMARY = new Color(0xCD, 0xCD, 0xE6);
    private static final Color TEXT_SECONDARY = new Color(0x7C, 0x7C, 0xA0);
    private static final Color GREEN = new Color(0x4E, 0xCB, 0x71);

    public byte[] export(String content, ExportFormat format, String title, String techniqueLabel) throws IOException {
        return switch (format) {
            case WORD -> generateWord(
                techniqueLabel != null && !techniqueLabel.isBlank()
                    ? appendTechniqueAttribution(content, techniqueLabel) : content,
                title);
            case EXCEL -> generateExcel(content, title);
            case PPT -> generatePpt(content, title, techniqueLabel);
        };
    }

    public byte[] export(String content, ExportFormat format, String title) throws IOException {
        return export(content, format, title, "");
    }

    private String appendTechniqueAttribution(String content, String techniqueLabel) {
        String timestamp = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        return content + "\n\n---\n\n## 分析方法溯源\n\n" + techniqueLabel
                + "\n\n*分析时间: " + timestamp + "  ·  Powered by 徐振伟智能体 v2.3*";
    }

    // ====== Word (大幅改善排版) ======
    private byte[] generateWord(String content, String title) throws IOException {
        try (XWPFDocument doc = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            // 页面设置
            var sectPr = doc.getDocument().getBody().getSectPr();
            if (sectPr == null) sectPr = doc.getDocument().getBody().addNewSectPr();
            sectPr.addNewPgSz().setW(java.math.BigInteger.valueOf(11906)); // A4 width
            sectPr.getPgSz().setH(java.math.BigInteger.valueOf(16838));
            sectPr.addNewPgMar().setLeft(java.math.BigInteger.valueOf(1440));  // 1 inch
            sectPr.getPgMar().setRight(java.math.BigInteger.valueOf(1440));
            sectPr.getPgMar().setTop(java.math.BigInteger.valueOf(1080));
            sectPr.getPgMar().setBottom(java.math.BigInteger.valueOf(1080));

            // === 封面标题 ===
            XWPFParagraph coverP = doc.createParagraph();
            coverP.setAlignment(ParagraphAlignment.CENTER);
            coverP.setSpacingBefore(3000);
            XWPFRun coverR = coverP.createRun();
            coverR.setText(title);
            coverR.setBold(true); coverR.setFontSize(26);
            coverR.setFontFamily("Microsoft YaHei"); coverR.setColor("7C5CFC");

            XWPFParagraph subP = doc.createParagraph();
            subP.setAlignment(ParagraphAlignment.CENTER);
            XWPFRun subR = subP.createRun();
            subR.setText("徐振伟智能体 · AI 分析报告\n" + java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm")));
            subR.setFontSize(12); subR.setFontFamily("Microsoft YaHei"); subR.setColor("7C7CA0");

            // 分页
            XWPFParagraph pageBreak = doc.createParagraph();
            pageBreak.setPageBreak(true);

            // 解析内容
            for (String line : content.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) { doc.createParagraph().setSpacingAfter(60); continue; }

                XWPFParagraph para = doc.createParagraph();
                para.setSpacingAfter(80);
                para.setSpacingBetween(1.3);

                if (trimmed.startsWith("## ")) {
                    // H2 标题：大号、加粗、紫色、上边距
                    para.setSpacingBefore(200); para.setSpacingAfter(120);
                    XWPFRun run = para.createRun();
                    run.setText(trimmed.substring(3));
                    run.setBold(true); run.setFontSize(18); run.setFontFamily("Microsoft YaHei");
                    run.setColor("7C5CFC");
                    // 下滑线装饰
                    XWPFRun lineR = para.createRun();
                    lineR.setText("\n⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯");
                    lineR.setFontSize(6); lineR.setColor("C0C0D0");
                } else if (trimmed.startsWith("### ")) {
                    para.setSpacingBefore(140); para.setSpacingAfter(80);
                    XWPFRun run = para.createRun();
                    run.setText(trimmed.substring(4));
                    run.setBold(true); run.setFontSize(14); run.setFontFamily("Microsoft YaHei");
                    run.setColor("5B8DEF");
                } else if (trimmed.startsWith("#### ")) {
                    para.setSpacingBefore(100);
                    XWPFRun run = para.createRun();
                    run.setText("▸ " + trimmed.substring(5));
                    run.setBold(true); run.setFontSize(12); run.setFontFamily("Microsoft YaHei");
                } else if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
                    para.setIndentationLeft(400);
                    XWPFRun run = para.createRun();
                    run.setText("•  " + trimmed.substring(2));
                    run.setFontSize(11); run.setFontFamily("Microsoft YaHei");
                } else if (trimmed.startsWith("> ")) {
                    // 引用块：缩进、斜体、左边框效果
                    para.setIndentationLeft(400);
                    XWPFRun run = para.createRun();
                    run.setText("▎ " + trimmed.substring(2));
                    run.setItalic(true); run.setFontSize(10); run.setFontFamily("Microsoft YaHei");
                    run.setColor("7C7CA0");
                } else if (trimmed.startsWith("```")) {
                    continue; // 跳过代码块标记
                } else if (trimmed.startsWith("---")) {
                    para.setSpacingBefore(100); para.setSpacingAfter(100);
                    XWPFRun run = para.createRun();
                    run.setText("· · ·");
                    run.setFontSize(10); run.setColor("C0C0D0");
                    para.setAlignment(ParagraphAlignment.CENTER);
                } else {
                    // 普通正文
                    XWPFRun run = para.createRun();
                    String text = trimmed;
                    // 处理 **粗体**
                    while (text.contains("**")) {
                        int b1 = text.indexOf("**");
                        int b2 = text.indexOf("**", b1 + 2);
                        if (b2 < 0) break;
                        // 前面的普通文本
                        if (b1 > 0) { run.setText(text.substring(0, b1)); run.setFontSize(11); run.setFontFamily("Microsoft YaHei"); }
                        // 粗体部分
                        run = para.createRun();
                        run.setText(text.substring(b1 + 2, b2));
                        run.setBold(true); run.setFontSize(11); run.setFontFamily("Microsoft YaHei");
                        text = text.substring(b2 + 2);
                        run = para.createRun();
                    }
                    run.setText(text);
                    run.setFontSize(11); run.setFontFamily("Microsoft YaHei");
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

            CellStyle titleStyle = wb.createCellStyle();
            Font tf = wb.createFont(); tf.setBold(true); tf.setFontHeightInPoints((short) 14); tf.setColor(IndexedColors.VIOLET.getIndex());
            titleStyle.setFont(tf);

            Row tr = sheet.createRow(rowNum++);
            Cell tc = tr.createCell(0); tc.setCellValue(title); tc.setCellStyle(titleStyle);
            rowNum++;

            for (String line : content.split("\n")) {
                line = line.trim(); if (line.isEmpty()) continue;
                Row row = sheet.createRow(rowNum++);
                if (line.startsWith("## ")) {
                    Cell c = row.createCell(0); c.setCellValue(line.substring(3));
                    CellStyle cs = wb.createCellStyle(); Font f = wb.createFont(); f.setBold(true); f.setFontHeightInPoints((short) 12); cs.setFont(f); c.setCellStyle(cs);
                } else if (line.startsWith("|") && line.contains("|")) {
                    String[] cols = line.split("\\|");
                    for (int i = 0; i < cols.length; i++) {
                        String v = cols[i].trim();
                        if (!v.isEmpty() && !v.startsWith("---")) row.createCell(i).setCellValue(v);
                    }
                } else {
                    row.createCell(0).setCellValue(line.replaceAll("[*#>`\\-]", "").trim());
                }
            }
            sheet.autoSizeColumn(0);
            wb.write(out); return out.toByteArray();
        }
    }

    // ====== PPT (改进排版) ======
    private byte[] generatePpt(String content, String title, String techniqueLabel) throws IOException {
        try (XMLSlideShow ppt = new XMLSlideShow();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            ppt.setPageSize(new java.awt.Dimension(960, 540));
            XSLFSlideMaster master = ppt.getSlideMasters().get(0);
            XSLFSlideLayout layout = master.getLayout(SlideLayout.TITLE_AND_CONTENT);

            // === 封面 ===
            XSLFSlide cover = ppt.createSlide(layout);
            XSLFTextShape coverTitle = cover.getPlaceholder(0);
            coverTitle.setText(title);
            // 设置标题颜色
            for (XSLFTextParagraph p : coverTitle.getTextParagraphs()) {
                for (XSLFTextRun r : p.getTextRuns()) {
                    r.setFontColor(ACCENT); r.setFontFamily("Microsoft YaHei"); r.setFontSize(36.0);
                }
            }
            XSLFTextShape coverSub = cover.getPlaceholder(1);
            coverSub.clearText();
            XSLFTextParagraph sp = coverSub.addNewTextParagraph();
            XSLFTextRun sr = sp.addNewTextRun();
            sr.setText("徐振伟智能体 · AI深度分析报告\n" +
                java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm")));
            sr.setFontSize(14.0); sr.setFontColor(TEXT_SECONDARY); sr.setFontFamily("Microsoft YaHei");

            // === 内容页 ===
            List<String> slides = splitContent(content);
            for (String slideContent : slides) {
                XSLFSlide slide = ppt.createSlide(layout);
                String[] parts = slideContent.split("\n", 2);
                String slideTitle = parts[0].replaceAll("^#+\\s*", "").trim();
                if (slideTitle.length() > 50) slideTitle = slideTitle.substring(0, 48) + "...";
                String body = parts.length > 1 ? parts[1] : "";

                XSLFTextShape tShape = slide.getPlaceholder(0);
                tShape.setText(slideTitle);
                for (XSLFTextParagraph p : tShape.getTextParagraphs()) {
                    for (XSLFTextRun r : p.getTextRuns()) {
                        r.setFontColor(ACCENT); r.setFontFamily("Microsoft YaHei"); r.setFontSize(24.0); r.setBold(true);
                    }
                }

                XSLFTextShape bShape = slide.getPlaceholder(1);
                bShape.clearText();
                for (String bline : body.split("\n")) {
                    bline = bline.trim(); if (bline.isEmpty()) continue;
                    XSLFTextParagraph bp = bShape.addNewTextParagraph();
                    if (bline.startsWith("- ") || bline.startsWith("* ")) {
                        bp.setIndent(1.0);
                        XSLFTextRun br = bp.addNewTextRun();
                        br.setText("• " + bline.substring(2));
                        br.setFontSize(14.0); br.setFontFamily("Microsoft YaHei");
                    } else {
                        XSLFTextRun br = bp.addNewTextRun();
                        br.setText(bline.replaceAll("[*#>|]", "").trim());
                        br.setFontSize(14.0); br.setFontFamily("Microsoft YaHei");
                    }
                }
            }

            // === 技法溯源尾页 ===
            if (techniqueLabel != null && !techniqueLabel.isBlank()) {
                XSLFSlide techSlide = ppt.createSlide(layout);
                XSLFTextShape techT = techSlide.getPlaceholder(0);
                techT.setText("分析方法溯源");
                for (XSLFTextParagraph p : techT.getTextParagraphs())
                    for (XSLFTextRun r : p.getTextRuns()) { r.setFontColor(GREEN); r.setFontFamily("Microsoft YaHei"); r.setFontSize(24.0); }

                XSLFTextShape techB = techSlide.getPlaceholder(1);
                techB.clearText();
                XSLFTextParagraph hp = techB.addNewTextParagraph();
                XSLFTextRun hr = hp.addNewTextRun();
                hr.setText("本次分析使用的技法："); hr.setBold(true); hr.setFontSize(13.0); hr.setFontFamily("Microsoft YaHei");
                for (String tl : techniqueLabel.split("\n")) {
                    String c = tl.replaceAll("[•·●▪▸]", "").trim(); if (c.isBlank()) continue;
                    XSLFTextParagraph tp = techB.addNewTextParagraph(); tp.setIndent(1.0);
                    XSLFTextRun tr = tp.addNewTextRun();
                    tr.setText("▸ " + c); tr.setFontSize(12.0); tr.setFontFamily("Microsoft YaHei");
                }
                XSLFTextParagraph fp = techB.addNewTextParagraph(); fp.setSpaceBefore(20.0);
                XSLFTextRun fr = fp.addNewTextRun();
                fr.setText("\nPowered by 徐振伟智能体 v2.3"); fr.setFontSize(9.0); fr.setItalic(true); fr.setFontColor(TEXT_SECONDARY);
            }

            // 尾页
            XSLFSlide end = ppt.createSlide(layout);
            XSLFTextShape endT = end.getPlaceholder(0);
            endT.setText("感谢使用");
            for (XSLFTextParagraph p : endT.getTextParagraphs())
                for (XSLFTextRun r : p.getTextRuns()) { r.setFontColor(ACCENT); r.setFontFamily("Microsoft YaHei"); r.setFontSize(32.0); }
            XSLFTextShape endB = end.getPlaceholder(1);
            endB.clearText();
            XSLFTextParagraph ep = endB.addNewTextParagraph();
            XSLFTextRun er = ep.addNewTextRun();
            er.setText("徐振伟智能体 — 人机共想"); er.setFontSize(16.0); er.setFontFamily("Microsoft YaHei"); er.setFontColor(TEXT_SECONDARY);

            ppt.write(out);
            return out.toByteArray();
        }
    }

    private List<String> splitContent(String content) {
        List<String> slides = new java.util.ArrayList<>();
        StringBuilder current = new StringBuilder();
        int lines = 0;
        for (String line : content.split("\n")) {
            line = line.trim(); if (line.isEmpty()) continue;
            if (line.startsWith("## ") && current.length() > 0) {
                slides.add(current.toString().trim());
                current = new StringBuilder(); lines = 0;
            }
            current.append(line).append("\n"); lines++;
            if (lines >= 8) { slides.add(current.toString().trim()); current = new StringBuilder(); lines = 0; }
        }
        if (current.length() > 0) slides.add(current.toString().trim());
        return slides;
    }
}
