package com.ragagent.agent;

import com.ragagent.retrieval.HybridRetriever;
import com.ragagent.retrieval.RetrievalResult;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.util.List;
@Component 
public class SearchKnowledgeBaseTool {
    private final HybridRetriever retriever;

    public SearchKnowledgeBaseTool(HybridRetriever retriever) {
        this.retriever = retriever;
    }
    @Tool("在知识库中检索与问题相关的文档片段，返回带来源编号的内容。当需要事实依据、或用户询问知识库内容时调用。")
    public String searchKnowledgeBase(@P("检索关键词或问题，尽量使用完整明确的表述") String query)
    {
        List<RetrievalResult> results = retriever.search(query);
        if(results.isEmpty())
        {
            return "【未检索到相关内容】";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < results.size(); i++) {
            RetrievalResult r = results.get(i);
            sb.append('[').append(i + 1).append("] 来源：").append(r.sourceName()).append('\n')
                    .append(r.content()).append("\n\n");
        }
        return sb.toString();
    }
}
