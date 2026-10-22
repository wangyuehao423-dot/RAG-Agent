package com.ragagent.dto;

import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 流式问答结果：引用来源 + 是否拒答 + token 流 + 结束后回填的结果载体。
 *
 * @param sources 显式检索得到的引用来源（流式开始前的初值）
 * @param refused 是否在流式开始前就因「低置信度」触发拒答
 * @param tokens  流式 token 流
 * @param outcome 流结束后才最终确定的结果（模型主动拒答与否、真实 token 用量、最终引用列表）
 */
public record StreamedAnswer(List<Source> sources, boolean refused,
                             Flux<String> tokens, StreamOutcome outcome) {
}
