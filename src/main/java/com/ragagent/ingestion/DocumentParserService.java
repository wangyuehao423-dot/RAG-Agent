package com.ragagent.ingestion;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 文档解析器：按文件扩展名分发到对应解析实现。
 * 支持 txt / md / pdf / docx。
 */
@Service
public class DocumentParserService {

    private static final Logger log = LoggerFactory.getLogger(DocumentParserService.class);

    /** GB18030 是 GBK 的超集，覆盖绝大多数中文 Windows 环境产生的文本文件 */
    private static final Charset GB18030 = Charset.forName("GB18030");

    public String parse(String filename, byte[] content) {
        String ext = extension(filename);
        return switch (ext) {
            case "txt", "md", "markdown" -> decodeText(content);
            case "pdf" -> parsePdf(content);
            case "docx" -> parseDocx(content);
            default -> throw new IllegalArgumentException("不支持的文件类型: " + ext);
        };
    }

    /**
     * 解码纯文本：优先 UTF-8 严格解码，失败则回退 GB18030。
     *
     * <p>不能直接 new String(content, UTF_8)。中文 Windows 上大量 txt 是
     * GBK/GB18030 编码（记事本保存时的默认编码就是它），
     * 用 UTF-8 硬解会把汉字整段变成替换字符（U+FFFD，看起来像一串「？」）。
     *
     * <p>更麻烦的是它**不会报错**：接口照样返回 200、status 照样是 DONE，
     * 用户以为传成功了，实际库里存的是一堆乱码。
     * 而这些乱码还会被向量化、被检索、被当作引用喂给模型，
     * 等于往知识库里静默注入了一条永远答不准的垃圾数据。
     *
     * <p>这里不引入任何第三方依赖：用 CharsetDecoder 的 REPORT 模式
     * 严格解码 UTF-8，遇到非法字节序列会抛 CharacterCodingException，
     * 捕获后按 GB18030 重新解码。
     */
    private String decodeText(byte[] content) {
        try {
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            return decoder.decode(ByteBuffer.wrap(content)).toString();
        } catch (CharacterCodingException e) {
            log.warn("文件不是合法 UTF-8，按 GB18030 重新解码");
            return new String(content, GB18030);
        }
    }

    private String parsePdf(byte[] content) {
        try (PDDocument doc = Loader.loadPDF(content)) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(doc);
        } catch (IOException e) {
            throw new RuntimeException("PDF 解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 解析 docx，并把 Word 的标题样式「翻译」成 Markdown 的 # 标记。
     *
     * <p>为什么要翻译，而不是直接 extractor.getText()？
     * 因为分块器（ChunkingService）是按 Markdown 标题层级切分的，
     * 它只认行首的 #。
     * 如果直接取纯文本，Word 里明明有一级、二级标题，
     * 到了分块器眼里也只是一堆普通段落 ——
     * 于是「按标题层级语义分块 + 标题路径前缀」这套机制
     * 对最常见的 Word 文档直接失效，退化成按空行切段落。
     *
     * <p>所以在这里做一次「结构归一化」：
     * 遍历段落 -> 读它的样式名 -> 是 Heading N 就补 N 个 #。
     * 分块器一行都不用改，只是喂给它的文本已经带上了结构标记。
     */
    private String parseDocx(byte[] content) {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(content))) {
            StringBuilder sb = new StringBuilder();
            for (XWPFParagraph p : doc.getParagraphs()) {
                String text = p.getText();
                if (text == null || text.isBlank()) {
                    sb.append('\n');
                    continue;
                }
                int level = headingLevel(p);
                if (level > 0) {
                    sb.append("#".repeat(level)).append(' ').append(text.strip()).append('\n');
                } else {
                    sb.append(text.strip()).append('\n');
                }
            }
            return sb.toString();
        } catch (IOException e) {
            throw new RuntimeException("DOCX 解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 读 Word 段落的样式名，把标题映射成 1~6 级（即 Markdown 的 # 数量）。
     *
     * <p>英文版 Word 的样式 ID 形如 Heading1 / Heading2；
     * 中文界面下样式 ID 仍常是 heading 1（带个空格）。
     * 这里用「去掉空格、转小写后以 heading 开头」来判断，
     * 末尾数字就是级别。取不到样式、或不是标题样式，一律返回 0（当正文）。
     */
    private static int headingLevel(XWPFParagraph p) {
        String style = p.getStyle();
        if (style == null) {
            return 0;
        }
        String normalized = style.replace(" ", "").toLowerCase(Locale.ROOT);
        if (!normalized.startsWith("heading") || normalized.length() <= 7) {
            return 0;
        }
        char c = normalized.charAt(7);
        return (c >= '1' && c <= '6') ? c - '0' : 0;
    }

    static String extension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "";
        }
        return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }
}
