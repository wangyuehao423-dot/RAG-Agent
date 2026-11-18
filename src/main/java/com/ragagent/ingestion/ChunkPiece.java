package com.ragagent.ingestion;

/**
 * 一个分块 + 它在原文中的位置。
 *
 * <p>{@code startLine} / {@code endLine} 是 1 基、闭区间，指向<b>正文</b>在原文里的行号范围，
 * 不含 {@link ChunkingService} 拼上去的标题路径前缀——那个前缀是分块时人为加的，
 * 原文里根本没有对应行，把它算进范围会让前端高亮到错误的位置。
 *
 * <p>为什么要记行号：回答里的引用来源要能「点一下跳过去」。
 * 只存块序号不行——块序号是分块结果的下标，跟原文件的行没有换算关系；
 * 而在前端拿块内容去全文里搜索定位，又会因为标题前缀、空行压缩、行首尾 strip 而对不上。
 * 分块时顺手把行号记下来是最便宜也最准的做法。
 */
public record ChunkPiece(String text, int startLine, int endLine) {
}
