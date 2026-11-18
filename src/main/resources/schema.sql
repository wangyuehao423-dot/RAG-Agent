-- =============================================================
-- RAG Agent 数据库初始化脚本（幂等，可重复执行）
-- 由 Spring Boot 在启动时自动执行（spring.sql.init.mode=always）
-- 依赖：pgvector 扩展（docker 镜像 pgvector/pgvector 已内置）
-- =============================================================

CREATE EXTENSION IF NOT EXISTS vector;

-- 文档元信息
-- 注意：必须建在 chunks 之前。chunks 要引用它做外键，
-- 被引用的表必须先存在，否则 CREATE TABLE 会直接报错。
CREATE TABLE IF NOT EXISTS documents (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name         VARCHAR(255) NOT NULL,
    file_type    VARCHAR(20),
    content_hash VARCHAR(64),                       -- 文件内容 SHA-256，用于重复上传去重
    chunk_count  INT DEFAULT 0,
    status       VARCHAR(20) DEFAULT 'PROCESSING',  -- PROCESSING / DONE / FAILED
    error_msg    TEXT,                              -- 入库失败原因（status = FAILED 时有值）
    -- 解析后的纯文本全文。存它的唯一目的是「引用可回溯」：前端点开一条引用来源时，
    -- 要能把原文按行展示并定位到被引用的那几行。不存原文就只能拼 chunks，
    -- 而 chunks 带着标题前缀、丢了空行，拼出来的「原文」行号和真实文件对不上。
    raw_text     TEXT,
    created_at   TIMESTAMP DEFAULT now()
);

-- 同一份内容只允许入库一次（哈希唯一；旧数据哈希为 NULL 不受影响）
-- 注意：必须先补列再建索引。老库的 documents 表可能没有 content_hash 列，
-- CREATE UNIQUE INDEX 一旦先执行会报 "column content_hash does not exist"
-- 并中断整个初始化脚本（CREATE TABLE IF NOT EXISTS 不会给旧表补新列）。
ALTER TABLE documents ADD COLUMN IF NOT EXISTS content_hash VARCHAR(64);
ALTER TABLE documents ADD COLUMN IF NOT EXISTS error_msg TEXT;
ALTER TABLE documents ALTER COLUMN status SET DEFAULT 'PROCESSING';
CREATE UNIQUE INDEX IF NOT EXISTS documents_content_hash_idx ON documents (content_hash);

-- 分段入库的进度列：
--   segment_total = 这篇文档拆了几段（解析完才知道，所以默认 0）
--   segment_done  = 已完成几段（段完成时原子自增）
-- 收敛判断就是 segment_done >= segment_total，靠自增的原子性保证「只有一次成立」，
-- 而不是靠「最后一条消息是谁」——后者在并发消费下根本不可靠。
ALTER TABLE documents ADD COLUMN IF NOT EXISTS segment_total INT DEFAULT 0;
ALTER TABLE documents ADD COLUMN IF NOT EXISTS segment_done INT DEFAULT 0;

-- 段表：大文件分段入库的重做单元
--   唯一键 (document_id, seq)：同一段被重复投递时不会重复登记，消费前查状态即可跳过
--   chunk_start / chunk_end：本段在全文中的下标区间，保证 chunk_index 全局连续
-- 粒度降到「段」的理由：一份 100MB 文档拆成几十段，一段失败只重投这一段，
-- 已经成功的段不重跑；文档级重做单元太粗，一个错字就要跑完整篇。
CREATE TABLE IF NOT EXISTS document_segments (
    id          BIGSERIAL PRIMARY KEY,
    document_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    seq         INT  NOT NULL,
    chunk_start INT  NOT NULL DEFAULT 0,
    chunk_end   INT  NOT NULL DEFAULT 0,
    chunk_count INT  NOT NULL DEFAULT 0,
    status      VARCHAR(20) NOT NULL DEFAULT 'PENDING',   -- PENDING / DONE / FAILED
    error_msg   TEXT,
    created_at  TIMESTAMP DEFAULT now(),
    updated_at  TIMESTAMP DEFAULT now(),
    CONSTRAINT document_segments_doc_seq_uk UNIQUE (document_id, seq)
);

CREATE INDEX IF NOT EXISTS document_segments_doc_idx ON document_segments (document_id);

-- 知识库分块表：同时承担「向量检索」与「词法检索(BM25 语义)」两条通道
CREATE TABLE IF NOT EXISTS chunks (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    -- 外键 + 级联删除：删文档时由数据库自动清掉它的全部分块。
    -- 不写外键而是靠应用层手写两条 DELETE，漏一条就会留下「文档没了、分块还在」的孤儿数据，
    -- 这些孤儿分块仍然会被向量检索命中，变成永远答不准的幽灵来源。
    document_id  UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    chunk_index  INT  NOT NULL,
    content      TEXT NOT NULL,
    content_tsv  TSVECTOR,                     -- 中文 bigram 分词后的全文检索列
    embedding    VECTOR(1024),                 -- 与 embedding 模型维度对齐（text-embedding-v3 默认 1024）
    source_name  VARCHAR(255),                 -- 来源文档名
    -- 这一块内容在 raw_text 里的起止行号（1 基，闭区间）。分块时顺手记下，
    -- 前端据此把原文滚到对应位置并高亮。老数据这两列为空，前端会提示「未保留原文」。
    start_line   INT,
    end_line     INT,
    created_at   TIMESTAMP DEFAULT now()
);

-- HNSW 向量索引（余弦距离）
CREATE INDEX IF NOT EXISTS chunks_embedding_idx ON chunks USING hnsw (embedding vector_cosine_ops);
-- GIN 全文检索索引
CREATE INDEX IF NOT EXISTS chunks_tsv_idx ON chunks USING gin (content_tsv);

-- 会话（会话 ID 允许客户端自定义字符串，故用 VARCHAR 而非 UUID）
CREATE TABLE IF NOT EXISTS conversations (
    id         VARCHAR(128) PRIMARY KEY,
    title      VARCHAR(255),
    created_at TIMESTAMP DEFAULT now()
);

-- 消息留痕（持久化短期记忆；seq 保证同一事务内批量写入的消息也有确定顺序）
CREATE TABLE IF NOT EXISTS messages (
    seq             BIGSERIAL,
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id VARCHAR(128) NOT NULL,
    role            VARCHAR(20) NOT NULL,
    content         TEXT,
    created_at      TIMESTAMP DEFAULT now()
);

-- 兼容旧版本表结构（旧版 conversation_id / conversations.id 为 UUID 类型）。
-- 注意：这里刻意使用普通 ALTER，而不是 DO $$ ... $$ 匿名块——
-- Spring Boot 的 SQL 脚本初始化器（ScriptUtils）按分号切分语句，不识别 PostgreSQL 的
-- dollar-quoting，DO 块会被从中间切断并报 "Unterminated dollar quote"。
-- 列已是目标类型时该语句为空操作（仅有一次极小的表重写），消息表数据量很小，可忽略。
ALTER TABLE conversations ALTER COLUMN id TYPE VARCHAR(128) USING id::varchar;
ALTER TABLE messages ALTER COLUMN conversation_id TYPE VARCHAR(128) USING conversation_id::varchar;
ALTER TABLE messages ADD COLUMN IF NOT EXISTS seq BIGSERIAL;

-- 幂等添加外键：先 DROP IF EXISTS 再 ADD，重复执行不会报「约束已存在」。
-- 这样新库、旧库都能补上级联删除约束。
-- 注意：若库里已有孤儿数据（document_id 指向不存在的文档），ADD CONSTRAINT 会失败，
-- 需要先手工清理孤儿行；本项目从建库起就带外键，不会出现这种情况。
ALTER TABLE chunks DROP CONSTRAINT IF EXISTS chunks_document_id_fkey;
ALTER TABLE chunks ADD CONSTRAINT chunks_document_id_fkey
    FOREIGN KEY (document_id) REFERENCES documents(id) ON DELETE CASCADE;

-- 记忆按会话读取时按 seq 排序，建立联合索引
CREATE INDEX IF NOT EXISTS messages_conv_seq_idx ON messages (conversation_id, seq);

-- 评测用例（评测集可在库里动态维护：直接 INSERT/DELETE 即可，无需改代码重启）
CREATE TABLE IF NOT EXISTS eval_cases (
    id        BIGSERIAL PRIMARY KEY,
    question  TEXT NOT NULL,
    expected  TEXT
);

-- 拒答阈值标定结果：阈值不是常量，是「背景分布的分位数在当前语料上的落点」
--   value            这次标定算出来的数值
--   sample_size      参与标定的背景查询条数（样本太少时 P90 不稳）
--   percentile       分位点，固定 90
--   corpus_fingerprint 语料指纹（文档数 + 分块数）：语料变了指纹就变，
--                    用来发现「换了知识库但没重标阈值」这种情况
-- 为什么把阈值落库而不是写配置文件：写死一个绝对数，换一版知识库就得人工改一次；
-- 落库 + 标定脚本重跑，阈值跟着语料自动走，代码一个字都不用动。
CREATE TABLE IF NOT EXISTS retrieval_thresholds (
    metric             VARCHAR(32) PRIMARY KEY,   -- similarity / lexical
    value              DOUBLE PRECISION NOT NULL,
    sample_size        INT,
    percentile         INT DEFAULT 90,
    corpus_fingerprint VARCHAR(64),
    calibrated_at      TIMESTAMP DEFAULT now()
);

-- 评测运行记录：每次 RAGAS 评测落一行，形成可比对的基线
-- 为什么要有这张表：「改完变好了没有」不能靠感觉，只能跟上一行比。
CREATE TABLE IF NOT EXISTS eval_runs (
    id              BIGSERIAL PRIMARY KEY,
    mode            VARCHAR(20) NOT NULL,          -- export / ragas
    baseline_id     BIGINT,                        -- 本次对比的基线行
    case_count      INT,
    faithfulness         DOUBLE PRECISION,
    answer_relevancy     DOUBLE PRECISION,
    context_precision    DOUBLE PRECISION,
    context_recall       DOUBLE PRECISION,
    note            TEXT,
    created_at      TIMESTAMP DEFAULT now()
);

-- 仅当评测表为空时写入初始用例（幂等；不使用 DO 块以避免被脚本初始化器按分号切坏）
INSERT INTO eval_cases(question, expected)
SELECT v.question, v.expected
FROM (VALUES
    ('年假有几天？', '带薪年假'),
    ('差旅住宿在一线城市的标准是多少？', '北上广深'),
    ('病假需要提供什么证明？', '二级甲等'),
    ('调休需要在多长时间内使用完毕？', '3 个月'),
    ('机密文件可以外发吗？', '禁止')
) AS v(question, expected)
WHERE NOT EXISTS (SELECT 1 FROM eval_cases);