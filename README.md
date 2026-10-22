# RAG Agent

企业知识库智能问答 Agent —— 基于 Spring Boot 3 + LangChain4j 的**深度 RAG** 实现：混合检索、ReAct 工具调用、引用溯源、效果评测。

## 核心特性

| 能力 | 说明 |
| --- | --- |
| **混合检索** | 向量检索（pgvector + HNSW）与 BM25 词法检索并行，用 RRF（Reciprocal Rank Fusion，倒数排名融合）合并两路结果 |
| **两段式拒答** | 余弦相似度与词法命中强度双阈值判定，避免「专有名词精确命中但语义分不高」被误判成「知识库没有」 |
| **ReAct Agent** | LangGraph4j 驱动的推理-行动循环，把知识库检索封装成工具由模型自主调用 |
| **引用溯源** | 答案逐条标注来源文档与原文片段，可点开核对 |
| **SSE 流式输出** | 前端逐字渲染，末帧返回 token 用量用于成本核算 |
| **短期记忆** | 按会话维护最近 N 轮对话上下文，落库可恢复 |
| **内置评测** | EvalRunner 跑固定问答集，输出召回率/拒答准确率等指标 |
| **中文分词** | 自实现 ChineseTokenizer，兼容 bigram 切分，喂给 PG 的 tsvector |

## 技术栈

- Java 17 / Spring Boot 3.5
- LangChain4j 1.20（对话模型 OpenAI 兼容协议）
- LangGraph4j 1.9（Agent 编排）
- PostgreSQL 16 + pgvector（向量库）
- PDFBox 3.0 / Apache POI 5.2（PDF、Word 解析）

## 快速开始

### 1. 启动向量库

```bash
docker compose up -d
```

拉起 `pgvector/pgvector:pg16`，映射到本机 `5433` 端口。建表脚本由 Spring Boot 启动时自动执行（`schema.sql`，幂等可重复运行）。

### 2. 填入密钥（只存本地）

密钥**不写进任何配置文件**，只在启动瞬间以环境变量注入：

```bash
copy .env.example .env        # Windows
cp .env.example .env          # macOS / Linux
```

然后编辑 `.env`：

```bash
DEEPSEEK_API_KEY=sk-xxx       # 对话模型
DASHSCOPE_API_KEY=sk-xxx      # 向量化模型（通义千问百炼 text-embedding-v3）
```

`.env` 已被 `.gitignore` 排除，不会进入仓库。

### 3. 启动应用

```bash
run.cmd        # Windows：读取 .env → 注入环境变量 → 启动
./run.sh       # macOS / Linux
```

脚本只做两件事：把 `.env` 里的键值导出成环境变量，再执行项目自带的 `mvnw`（Maven Wrapper，首次运行会自动下载 Maven，**不要求本机装过 Maven**）。启动日志里会打印 `[INFO] API keys injected from .env`。

不用脚本时（例如在 IDEA 里启动），把这两个变量填进「运行配置 → 环境变量」即可。

打开 http://localhost:8080 使用内置前端页面。

> `application.yml` 里写的是 `${DEEPSEEK_API_KEY}`，**没有默认值**。密钥没注入会直接启动失败并指向缺失的变量名——这是刻意设计，比拿占位符跑出 401 好排查得多。

## API

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/documents` | 上传文档（multipart，支持 PDF/Word/TXT/Markdown），自动解析、切块、向量化入库 |
| GET | `/api/documents` | 文档列表 |
| GET | `/api/documents/{id}/content` | 查看文档原文 |
| DELETE | `/api/documents/{id}` | 删除文档及其分块 |
| POST | `/api/chat` | 同步问答，返回答案 + 引用来源 |
| POST | `/api/chat/stream` | SSE 流式问答 |
| GET | `/api/conversations` | 会话列表 |
| GET | `/api/conversations/{id}/messages` | 历史消息 |
| DELETE | `/api/conversations/{id}` | 删除会话 |

## 关键参数

在 `src/main/resources/application.yml` 的 `app` 段下可调：

```yaml
app:
  retrieval:
    vector-top-k: 10        # 向量通道召回条数
    bm25-top-k: 10          # 词法通道召回条数
    fusion-top-k: 5         # RRF 融合后保留条数
    rrf-k: 60               # RRF 平滑常数
    similarity-threshold: 0.45   # 低于此值拒答（需按你的 embedding 模型重新标定）
    lexical-threshold: 0.05      # 词法命中强度下限
  chunking:
    max-chars: 600          # 单块目标上限
    min-chars: 120          # 累积到此长度才独立成块
  memory:
    max-messages: 12        # 短期记忆窗口
```

## 工程踩坑记录

代码注释里保留了几处实际踩过的坑，便于复现时避雷：

- **LangChain4j 不自动补 `/v1`**：`base-url` 必须自带 `/v1` 结尾，与 Spring AI 行为相反。
- **流式模型要单独配**：只配 `chat-model` 时不会创建 `StreamingChatModel` Bean，注入 ReActAgent 会报 `NoSuchBeanDefinitionException`。
- **建表顺序有依赖**：`chunks` 引用 `documents` 外键，被引用表必须先建；老库补列要在建唯一索引之前。
- **上传大小上限**：不配 `spring.servlet.multipart.max-file-size` 时默认仅 1MB，企业文档会直接超限。
- **Windows 下日志编码**：显式指定 UTF-8，否则 GBK 环境下中文日志乱码。

## 目录结构

```
src/main/java/com/ragagent/
├── agent/          # ReAct Agent 与知识库检索工具
├── config/         # 对话记忆等 Bean 配置
├── controller/     # REST 接口
├── dto/            # 请求/响应模型
├── eval/           # 评测执行器
├── exception/      # 全局异常处理
├── ingestion/      # 文档解析、切块、入库流水线
├── memory/         # 会话记忆持久化（JDBC）
├── observability/  # 链路追踪日志
├── repository/     # 数据访问
├── retrieval/      # 混合检索、RRF 融合、查询改写
└── util/           # 中文分词、模型调用保护
```

## License

MIT
