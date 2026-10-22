package com.ragagent.ingestion;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class ChunkingService {
    private final int maxChars;
    private final int minChars;

    public ChunkingService(@Value("${app.chunking.max-chars:600}") int maxChars,
                           @Value("${app.chunking.min-chars:120}") int minChars) {
        this.maxChars = maxChars;
        this.minChars = minChars;
    }

    public List<String> chunk(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> lines = List.of(text.split("\\R", -1));
        List<String> chunks = new ArrayList<>();
        String[] headingPath = new String[6];
        StringBuilder cur = new StringBuilder();
        StringBuilder curHeading = new StringBuilder();
        for (String raw : lines) {
            String line = raw.strip();
            int level = headingLevel(line);

            if (level > 0) {
                flush(chunks, cur, curHeading);
                updatePath(headingPath, level, line);
                curHeading.setLength(0);
                curHeading.append(buildPath(headingPath));
                continue;
            }
            if (line.isEmpty() && cur.length() >= minChars) {
                flush(chunks, cur, curHeading);
            }
            if (line.length() > maxChars) {
                flush(chunks, cur, curHeading);
                for (String seg : splitLong(line)) {
                    chunks.add(withHeading(curHeading.toString(), seg));
                }
                continue;
            }
            if (cur.length() > 0 && cur.length() + line.length() + 1 > maxChars) {
                flush(chunks, cur, curHeading);
            }
            if (cur.length() > 0) {
                cur.append('\n');
            }
            cur.append(line);
        }
        flush(chunks, cur, curHeading);
        return chunks.stream().map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    /** 将当前缓冲区落盘为一个 chunk（带标题前缀） */
    private void flush(List<String> chunks, StringBuilder cur, StringBuilder heading) {
        if (cur.length() == 0) {
            return;
        }
        chunks.add(withHeading(heading.toString(), cur.toString()));
        cur.setLength(0);
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
