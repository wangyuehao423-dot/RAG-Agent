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