package com.ragagent.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 中文分词器：用于 PostgreSQL 全文检索（词法检索通道）。
 *
 * <p>PostgreSQL 自带的 'simple' / 'english' 分词器无法切分中文（中文没有空格边界），
 * 若直接 to_tsvector('simple', '世界美好') 会把整句当成一个 token，词法检索失效。
 *
 * <p>解决方案：在应用层用「重叠二元组（bigram）」切分中文，例如
 * "世界美好" → "世界 界美 美好"，再交给 PostgreSQL 的 tsvector/tsquery，
 * 这样无需额外安装中文分词扩展（如 pg_jieba / zhparser）即可获得可用的词法检索。
 *
 * <p>ASCII 单词按原词保留（小写化），数字与英文混排也能被正确切分。
 */
public final class ChineseTokenizer {

    private ChineseTokenizer() {
    }

    /** 是否为 CJK 汉字（统一表意文字 + 扩展A + 兼容表意文字） */
    public static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF)   // 基本区
                || (c >= 0x3400 && c <= 0x4DBF) // 扩展A
                || (c >= 0xF900 && c <= 0xFAFF); // 兼容区
    }

    /** 切分成 token 列表（中文重叠 bigram + ASCII 单词） */
    public static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return tokens;
        }
        StringBuilder cjk = new StringBuilder();
        StringBuilder ascii = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (isCjk(c)) {
                flushAscii(ascii, tokens);
                cjk.append(c);
            } else if (Character.isLetterOrDigit(c)) {
                flushCjk(cjk, tokens);
                ascii.append(c);
            } else {
                flushCjk(cjk, tokens);
                flushAscii(ascii, tokens);
            }
        }
        flushCjk(cjk, tokens);
        flushAscii(ascii, tokens);
        return tokens;
    }

    /** 转为 to_tsvector('simple', ?) 的输入（空格分隔） */
    public static String toText(String text) {
        return String.join(" ", tokenize(text));
    }

    /**
     * 转为 to_tsquery('simple', ?) 的输入（用 | 连接，任一命中即可）。
     *
     * <p><b>这里用 {@code |}（OR）而不是 {@code &}（AND），是本类最关键的一处修正。</b>
     * 中文 bigram 是「重叠切分」，同一个词的相邻二字会被重复切出来。
     * 如果查询用 AND 连接，语义就变成了「这一段必须**原样连续出现**问题里的所有二元组」，
     * 等价于要求「整句原文出现在同一块里」——对任何正常长度的问题都命中 0 条，
     * 词法通道等于不存在。OR 连接 + ts_rank 排序才是 bigram 检索的正确用法：
     * 命中的二元组越多，ts_rank 越高，靠前的结果越相关。
     */
    public static String toTsQuery(String text) {
        List<String> tokens = tokenize(text);
        if (tokens.isEmpty()) {
            return null;
        }
        // 去重 + 过滤停用二元组：OR 语义下，噪声 token 会把大量无关片段捞回来，
        // 先滤掉「的标」「可以」「什么」这类没有区分度的高频二元组。
        List<String> kept = tokens.stream()
                .distinct()
                .filter(t -> !isStopBigram(t))
                .toList();
        if (kept.isEmpty()) {
            return null;
        }
        return String.join(" | ", kept);
    }

    /** 停用二元组：由虚词或高频功能词构成、几乎不具备区分度的中文二元组 */
    private static final Set<String> STOP_BIGRAMS = Set.of(
            "可以", "什么", "怎么", "我们", "你们", "他们", "这个", "那个",
            "哪些", "哪个", "是否", "需要", "应该", "以及", "或者", "但是",
            "因为", "所以", "如果", "如何", "怎样", "多少", "多久", "没有");

    /** 高频虚词单字：只要二元组里含其中一个字就丢弃 */
    private static final String STOP_CHARS = "的了着和与及或在是为对把被这那吗呢吧啊呀么很就都还也又";

    /**
     * 判断一个 token 是否为应过滤的停用二元组。
     *
     * <p>只对「长度 2 且是中文」的 token 生效；ASCII 单词（英文、数字型号、编号）
     * 一律保留——它们恰恰是区分度最高、词法通道最该发挥作用的部分。
     */
    private static boolean isStopBigram(String token) {
        if (token.length() != 2) {
            return false;
        }
        if (STOP_BIGRAMS.contains(token)) {
            return true;
        }
        return STOP_CHARS.indexOf(token.charAt(0)) >= 0
                || STOP_CHARS.indexOf(token.charAt(1)) >= 0;
    }

    private static void flushCjk(StringBuilder cjk, List<String> tokens) {
        if (cjk.length() == 0) {
            return;
        }
        String s = cjk.toString();
        if (s.length() == 1) {
            tokens.add(s);
        } else {
            for (int i = 0; i < s.length() - 1; i++) {
                tokens.add(s.substring(i, i + 2));
            }
        }
        cjk.setLength(0);
    }

    private static void flushAscii(StringBuilder ascii, List<String> tokens) {
        if (ascii.length() == 0) {
            return;
        }
        tokens.add(ascii.toString().toLowerCase());
        ascii.setLength(0);
    }
}