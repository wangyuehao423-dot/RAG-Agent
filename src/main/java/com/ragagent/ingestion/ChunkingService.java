package com.ragagent.ingestion;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 按「标题路径 + 段落累积」切块，同时记录每块正文在原文中的起止行号。
 *
 * <h3>行号是怎么来的（也是这里最容易错的地方）</h3>
 * 遍历行时维护两个游标：{@code bodyStart} 在缓冲区从空变为非空的那一刻记下当前行号，
 * {@code bodyEnd} 每 append 一行就更新。落块时用它们作为范围。
 * 空行不参与正文（{@code cur.length() == 0} 时空行被完全丢弃），所以连续空行不会把范围撑大。
 *
 * <h3>为什么范围不含标题前缀</h3>
 * 每块正文前面会拼一段「H1 &gt; H2」的路径，那是给人看上下文用的，
 * 原文里并不存在对应行。范围只覆盖正文，前端高亮才不会偏。
 */
@Service
public class ChunkingService {
    private final int maxChars;
    private final int minChars;

    public ChunkingService(@Value("${app.chunking.max-chars:600}") int maxChars,
                           @Value("${app.chunking.min-chars:120}") int minChars) {
        this.maxChars = maxChars;
        this.minChars = minChars;
    }

    public List<ChunkPiece> chunk(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String[] lines = text.split("\\R", -1);
        List<ChunkPiece> out = new ArrayList<>();
        String[] headingPath = new String[6];
        StringBuilder cur = new StringBuilder();
        String curHeading = "";
        int bodyStart = 0;
        int bodyEnd = 0;

        for (int i = 0; i < lines.length; i++) {
            int lineNo = i + 1;
            String line = lines[i].strip();
            int level = headingLevel(line);

            if (level > 0) {
                if (cur.length() > 0) {
                    out.add(new ChunkPiece(withHeading(curHeading, cur.toString()), bodyStart, bodyEnd));
                    cur.setLength(0);
                }
                updatePath(headingPath, level, line);
                curHeading = buildPath(headingPath);
                continue;
            }
            if (line.isEmpty() && cur.length() >= minChars) {
                out.add(new ChunkPiece(withHeading(curHeading, cur.toString()), bodyStart, bodyEnd));
                cur.setLength(0);
            }
            if (line.length() > maxChars) {
                if (cur.length() > 0) {
                    out.add(new ChunkPiece(withHeading(curHeading, cur.toString()), bodyStart, bodyEnd));
                    cur.setLength(0);
                }
                for (String seg : splitLong(line)) {
                    // 超长单行被拆成多块：它们都来自同一行，范围就是这一行
                    out.add(new ChunkPiece(withHeading(curHeading, seg), lineNo, lineNo));
                }
                continue;
            }
            if (cur.length() > 0 && cur.length() + line.length() + 1 > maxChars) {
                out.add(new ChunkPiece(withHeading(curHeading, cur.toString()), bodyStart, bodyEnd));
                cur.setLength(0);
            }
            if (cur.length() == 0) {
                bodyStart = lineNo;      // 正文第一行：范围从这里开始
            } else {
                cur.append('\n');
            }
            cur.append(line);
            bodyEnd = lineNo;            // 每纳入一行就推进范围末尾
        }
        if (cur.length() > 0) {
            out.add(new ChunkPiece(withHeading(curHeading, cur.toString()), bodyStart, bodyEnd));
        }
        return out.stream()
                .map(p -> new ChunkPiece(p.text().strip(), p.startLine(), p.endLine()))
                .filter(p -> !p.text().isEmpty())
                .toList();
    }

    private String withHeading(String heading, String body) {
        if (heading == null || heading.isBlank()) {
            return body;
        }
        return heading + "\n" + body;
    }

    /** 识别 Markdown 标题，返回层级（1-6）；非标题返回 0 */
    private int headingLevel(String line) {
        if (line.isEmpty() || line.charAt(0) != '#') {
            return 0;
        }
        int n = 0;
        while (n < line.length() && line.charAt(n) == '#') {
            n++;
        }
        if (n > 6 || n >= line.length() || line.charAt(n) != ' ') {
            return 0;
        }
        return n;
    }

    /** 更新标题路径：设置当前层级，并清空更深的层级 */
    private void updatePath(String[] path, int level, String line) {
        path[level - 1] = line;
        for (int i = level; i < path.length; i++) {
            path[i] = null;
        }
    }

    /** 拼出形如「H1 > H2 > H3」的标题前缀 */
    private String buildPath(String[] path) {
        StringBuilder sb = new StringBuilder();
        for (String p : path) {
            if (p == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(" > ");
            }
            sb.append(p);
        }
        return sb.toString();
    }

    /** 超长段落按中英文句末标点切分，尽量保证每段不超过 maxChars */
    private List<String> splitLong(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String piece : line.split("(?<=[。！？；!?;])")) {
            if (cur.length() > 0 && cur.length() + piece.length() > maxChars) {
                out.add(cur.toString());
                cur.setLength(0);
            }
            cur.append(piece);
        }
        if (cur.length() > 0) {
            out.add(cur.toString());
        }
        return out;
    }
}
