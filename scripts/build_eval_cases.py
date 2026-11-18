#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成 eval_cases 种子 SQL，并逐条校验「标准答案能在语料里找到依据」。

为什么要有校验：评测集的前提是「标准答案确实在知识库里」。
一条题的答案如果语料里根本没有，context_recall 会把它算成检索漏召回，
分数被这堆无解题拉低，却看不出是题的问题——所以写题时就把关。

用法：
  python scripts/build_eval_cases.py            # 只校验
  python scripts/build_eval_cases.py --emit      # 校验后写出 seed_eval_cases.sql
"""
import argparse
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CORPUS_FILES = [
    ROOT / "README.md",
    Path(r"C:\Users\83556\Desktop\复习\AI Meetting.md"),
]

# (question, expected, 校验词) —— 校验词必须在语料里出现，otherwise 这条题就是无解题
CASES: list[tuple[str, str, str]] = []


def add(rows):
    CASES.extend(rows)


# ---------------------------------------------------------------- 项目一：RAG Agent（README）
add([
    ("知识库的向量库用的是哪个镜像？", "pgvector/pgvector:pg16", "pgvector/pgvector:pg16"),
    ("向量库容器映射到本机哪个端口？", "5433 端口", "5433"),
    ("分块向量建的是什么索引？", "HNSW 索引", "HNSW"),
    ("词法检索在 PostgreSQL 里靠什么实现？", "tsvector 与 ts_rank 全文检索", "ts_rank"),
    ("RRF 的全称是什么？", "Reciprocal Rank Fusion（倒数排名融合）", "Reciprocal Rank Fusion"),
    ("RRF 的平滑常数 rrf-k 取多少？", "60", "rrf-k: 60"),
    ("向量通道一次召回多少条？", "30 条", "vector-top-k: 30"),
    ("词法通道一次召回多少条？", "30 条", "bm25-top-k: 30"),
    ("融合后送进精排的候选有多少条？", "20 条", "candidate-top-k: 20"),
    ("精排之后留给大模型几条？", "5 条", "fusion-top-k: 5"),
    ("精排用的是哪个模型？", "gte-rerank-v2 交叉编码器", "gte-rerank-v2"),
    ("精排的超时时间配置成多少毫秒？", "3000 毫秒", "timeout-ms: 3000"),
    ("精排调用失败时链路会怎么走？", "降级回 RRF 顺序", "降级"),
    ("单个分块的目标字符上限是多少？", "600 字符", "max-chars: 600"),
    ("段落累积到多少字符才独立成块？", "120 字符", "min-chars: 120"),
    ("一个入库段最多装多少个分块？", "100 块", "segment-max-chunks: 100"),
    ("短期记忆窗口默认保留多少条消息？", "12 条", "max-messages: 12"),
    ("向量化用的是哪个模型？", "通义千问百炼 text-embedding-v3", "text-embedding-v3"),
    ("向量化模型输出多少维向量？", "1024 维", "1024"),
    ("text-embedding-v3 单次请求最多送多少条？", "25 条", "25 条"),
    ("535 个分块要分成几批向量化？", "22 批", "22 批"),
    ("应用启动后在本地哪个地址访问前端？", "http://localhost:8080", "8080"),
    ("后端用的 Spring Boot 是哪个版本线？", "Spring Boot 3.5", "Spring Boot 3.5"),
    ("项目使用的 JDK 版本是多少？", "Java 17", "Java 17"),
    ("LangChain4j 用的是哪个版本？", "1.20", "LangChain4j 1.20"),
    ("LangGraph4j 用的是哪个版本？", "1.9", "LangGraph4j 1.9"),
    ("PDF 文档用什么库解析？", "PDFBox 3.0", "PDFBox 3.0"),
    ("Word 文档用什么库解析？", "Apache POI 5.2", "Apache POI 5.2"),
    ("流式输出用的是哪种协议？", "SSE（Server-Sent Events）", "SSE"),
    ("拒答阈值是怎么定出来的？", "背景分布的 P90 分位数", "P90"),
    ("重标拒答阈值调哪个接口？", "POST /api/retrieval/calibrate", "/api/retrieval/calibrate"),
    ("导出评测数据调哪个接口？", "POST /api/eval/export", "/api/eval/export"),
    ("查看最近一次评测结果调哪个接口？", "GET /api/eval/report", "/api/eval/report"),
    ("离线评测引入的是哪个框架？", "RAGAS", "RAGAS"),
    ("RAGAS 一共测哪四个指标？", "忠实度、答案相关性、上下文精确率、上下文召回率", "上下文召回率"),
    ("上传文档支持哪几种格式？", "PDF、Word、TXT、Markdown", "Markdown"),
    ("文档入库为什么要走消息队列？", "按段拆分入库，一段失败只重跑该段", "段"),
    ("LangChain4j 的 base-url 需要自带 /v1 吗？", "需要，不自动补", "/v1"),
    ("只配置 chat-model 会缺哪个 Bean？", "StreamingChatModel", "StreamingChatModel"),
    ("建表顺序上有什么硬依赖？", "chunks 引用 documents 的外键", "外键"),
    ("不配 multipart 文件上限会怎样？", "默认只有 1MB，企业文档直接超限", "1MB"),
    ("Windows 下中文日志乱码怎么解决？", "显式指定 UTF-8 编码", "UTF-8"),
    ("中文是按什么粒度切分的？", "重叠二元组（bigram）", "二元组"),
    ("查询端怎么过滤没有区分度的噪声词？", "用虚词停用表过滤噪声二元组", "停用"),
    ("ReAct 推理-行动循环由什么驱动？", "LangGraph4j", "LangGraph4j"),
    ("答案的引用溯源是怎么呈现的？", "逐条标注来源文档与原文片段", "原文片段"),
    ("两段式拒答看的是哪两个通道？", "向量相似度与词法命中强度", "词法命中强度"),
    ("词法通道的命中强度阈值是多少？", "0.05", "0.05"),
    ("相似度阈值的兜底值写的是多少？", "0.45", "0.45"),
    ("会话摘要是什么时候做的？", "每轮问答结束后异步做，不阻塞主链路", "异步"),
    ("为什么入库要按段提交事务？", "一次写几百块会拖长单事务", "事务"),
    ("删除一份文档调哪个接口？", "DELETE /api/documents/{id}", "DELETE"),
    ("查询某个会话的历史消息调哪个接口？", "GET /api/conversations/{id}/messages", "/messages"),
    ("密钥是怎么进到应用里的？", "启动瞬间以环境变量注入，不写进配置文件", "环境变量"),
    ("评测用例存在哪张表？", "eval_cases 表", "eval_cases"),
])

# ---------------------------------------------------------------- 项目二：技术栈与初始化
add([
    ("AI 面试官平台的后端用的是哪个 Spring Boot 版本？", "3.3.5", "3.3.5"),
    ("AI 面试官平台接入大模型用的是哪个框架？", "Spring AI 1.0.0-M6", "1.0.0-M6"),
    ("追问链用的规则引擎是什么？", "LiteFlow 2.12.4", "2.12.4"),
    ("前端用的是哪个框架？", "React 18", "React 18"),
    ("简历文件用什么库解析出文本？", "Apache Tika 2.9.2", "Apache Tika 2.9.2"),
    ("用户密码用哪种算法加密？", "BCrypt", "BCrypt"),
    ("登录态用什么方式签发？", "JWT", "JWT"),
    ("数据库访问层用的是哪个框架？", "MyBatis-Plus", "MyBatis-Plus"),
    ("用户表叫什么名字？", "app_user", "app_user"),
    ("简历表叫什么名字？", "interview_resume", "interview_resume"),
    ("岗位表叫什么名字？", "interview_position", "interview_position"),
    ("面试会话表叫什么名字？", "interview_session", "interview_session"),
])

# ---------------------------------------------------------------- 项目二：注册登录
add([
    ("密码明文会存进数据库吗？", "不会，只保存 BCrypt 结果", "绝不保存明文密码"),
    ("签发和校验 JWT 用的是哪种密钥？", "HMAC 密钥", "HMAC"),
    ("签发 JWT 时会写入哪些时间信息？", "签发时间与过期时间", "过期时间"),
    ("前端拿到 Token 之后怎么用？", "存下来并在后续请求里携带", "携带"),
    ("重复的用户名怎么防住？", "靠数据库唯一约束兜底", "唯一"),
    ("用户注册时密码先做什么处理？", "先做 BCrypt 哈希再入库", "哈希"),
    ("JWT 解析和签发必须一致的东西是什么？", "同一把密钥", "同一把密钥"),
    ("服务重启后 JWT 还能用吗？", "能，密钥来源必须前后一致", "来源一致"),
    ("鉴权失败时接口怎么响应？", "由全局异常处理统一返回", "异常"),
    ("登录接口核对密码时比对的是什么？", "原始密码与库里的 BCrypt 哈希", "BCrypt"),
])

# ---------------------------------------------------------------- 项目二：简历解析
add([
    ("简历上传后先做什么？", "先解析成原始文本", "解析为原始文本"),
    ("技术技能是怎么从简历里抽出来的？", "调用 AI 提取成技能 JSON", "技能 JSON"),
    ("简历记录的解析状态用哪个值表示成功？", "PARSED", "PARSED"),
    ("简历表里除了文件位置还存什么？", "解析状态、原始解析文本和技能 JSON", "原始解析文本"),
    ("简历解析异常怎么处理？", "捕获解析异常并返回结果，不让接口直接崩", "解析异常"),
    ("Tika 在项目里负责什么？", "从 PDF、Word 等文件里提取文本", "提取文本"),
    ("解析出的技能给谁用？", "岗位匹配、AI 出题和报告生成", "岗位匹配"),
    ("简历是按用户区分存一份还是多份？", "按用户存，查询时取最近一份已解析的", "最近一份"),
    ("上传简历要传哪些信息？", "文件和当前用户", "文件"),
    ("简历原文保存在哪？", "落到文件存储，库里存位置", "文件位置"),
])

# ---------------------------------------------------------------- 项目二：岗位匹配
add([
    ("简历和岗位的匹配分是怎么算的？", "按岗位技能与 AI 提取的简历技能计算", "按岗位技能"),
    ("匹配分用什么类型保存？", "BigDecimal，按百分比表示", "BigDecimal"),
    ("匹配分 85.5 是什么含义？", "百分比形式的匹配度", "85.5"),
    ("岗位列表查询靠什么实现分页？", "MyBatis-Plus 的 BaseMapper 自带能力", "分页"),
    ("岗位实体类在哪个包下？", "infrastructure/mysql", "infrastructure/mysql"),
    ("岗位查询接口返回什么？", "岗位信息与匹配分", "匹配分"),
    ("匹配分是在什么时候算出来的？", "创建会话后异步准备阶段", "异步准备"),
    ("岗位和简历谁先谁后？", "先有岗位列表，再按简历算匹配", "岗位"),
])

# ---------------------------------------------------------------- 项目二：状态机与快照
add([
    ("面试生命周期一共有几个状态？", "7 个", "ENDED"),
    ("面试结束对应的状态是哪个？", "ENDED", "ENDED"),
    ("初始状态叫什么？", "INIT", "INIT"),
    ("AI 正在打分时的状态叫什么？", "JUDGING", "JUDGING"),
    ("用户正在作答时的状态叫什么？", "ANSWERING", "ANSWERING"),
    ("状态机的转移表用什么结构保存？", "EnumMap", "EnumMap"),
    ("推动状态变化的东西叫什么？", "事件（InterviewEvent）", "InterviewEvent"),
    ("用户点开始按钮触发哪个事件？", "START", "START"),
    ("用户提交答案触发哪个事件？", "ANSWER_SUBMITTED", "ANSWER_SUBMITTED"),
    ("倒计时结束强制交卷触发哪个事件？", "TIMEOUT", "TIMEOUT"),
    ("快照对象为什么设计成不可变？", "避免创建后被外部代码偷偷修改", "不可变"),
    ("快照里的 version 字段有什么用？", "并发更新控制，写回时比对", "version"),
    ("processedRequestIds 解决什么问题？", "客户端重试同一请求时识别为幂等", "processedRequestIds"),
    ("快照的读取顺序是什么？", "Redis 热缓存 → MongoDB 冷存储 → 创建 INIT", "Redis 热缓存"),
    ("快照在 Redis 里的 key 长什么样？", "snapshot:{sessionId}", "snapshot:{sessionId}"),
    ("快照热数据的 TTL 是多少？", "30 分钟", "30 分钟"),
    ("MongoDB 命中后对 Redis 做什么？", "异步回填热缓存", "异步回填"),
    ("快照写回 Mongo 用什么命令保证原子？", "findAndModify", "findAndModify"),
    ("写回时 version 对不上会怎样？", "拒绝写入并重新加载", "拒绝写入"),
    ("Redis 不可用时本地请求合并靠什么容器承载？", "ConcurrentHashMap", "ConcurrentHashMap"),
    ("本地请求合并的结果多久过期？", "5 分钟", "5 分钟"),
    ("本地缓存多长时间清理一次？", "每 10 分钟", "每 10 分钟"),
])

# ---------------------------------------------------------------- 项目二：出题与 Single-flight
add([
    ("预生成的题目以什么状态落库？", "PRE_GENERATED", "PRE_GENERATED"),
    ("预生成题被真正取用时改成什么状态？", "ACTIVE", "ACTIVE"),
    ("为什么要先预生成下一题？", "占住题号，避免并发取题拿到还没准备好的题", "占住题号"),
    ("取题接口优先走哪条路径？", "先取预生成题，没有再回退请求合并", "请求合并"),
    ("题目在会话里靠哪个字段排序？", "sequenceNo（题号）", "sequenceNo"),
    ("Single-flight 把一次请求建模成几个状态？", "五个", "TAKEN_OVER"),
    ("抢锁和查缓存为什么能原子完成？", "用 Lua 脚本打包成一次 Redis 调用", "Lua"),
    ("没抢到锁的请求怎么等结果？", "轮询缓存，最多 30 秒", "最多轮询 30 秒"),
    ("等待超时后请求做什么？", "把任务登记给接管服务，到期重跑", "接管"),
    ("Fencing Token 防的是什么？", "旧结果覆盖新结果", "Fencing"),
    ("写缓存失败会不会让整个请求失败？", "不会，跳过缓存继续返回", "skip cache"),
    ("AI 调用的统一门面类是哪个？", "InterviewAiFacade", "InterviewAiFacade"),
    ("下一题预生成服务的类名是什么？", "NextQuestionPreGeneratorService", "NextQuestionPreGeneratorService"),
    ("判断这次失败值不值得重试的类叫什么？", "FailureClassifier", "FailureClassifier"),
    ("表达「已登记、稍后重试」的异常叫什么？", "RetryableSingleflightException", "RetryableSingleflightException"),
    ("接管服务的扫描间隔是多少？", "5 秒", "5 秒"),
    ("单飞状态里两个终态是哪两个？", "SUCCEEDED 与 FAILED", "SUCCEEDED"),
    ("等待方读到终态后怎么处理？", "成功就回放结果，失败立刻抛出", "回放"),
])

# ---------------------------------------------------------------- 项目二：评分与队列
add([
    ("追问任务的队列用了几个 key？", "三个：主队列、处理中队列、死信队列", "PROCESSING"),
    ("任务从主队列搬到处理中队列靠什么命令？", "LMOVE 原子搬运", "LMOVE"),
    ("为什么要有处理中队列？", "消息取出来还没完成时进程崩了，任务不会凭空消失", "PROCESSING"),
    ("死信队列里的任务会被自动消费吗？", "不会，留原文给人工排查", "死信"),
    ("同一个任务最多重试几次？", "3 次", "MAX_RETRY"),
    ("重投时哪些字段必须原样保留？", "followUp 等任务上下文原样保留", "原样保留"),
    ("反序列化失败的任务怎么处理？", "不重试，直接进死信", "反序列化"),
    ("一次评分 AI 调用通常要多久？", "3～10 秒", "3～10 秒"),
    ("答案的评分状态有哪三个？", "PENDING_JUDGE、JUDGED、JUDGE_FAILED", "PENDING_JUDGE"),
    ("评分成功后答案状态改成什么？", "JUDGED", "JUDGED"),
    ("答案表里保存的是哪些内容？", "答案正文、评分结果和评分反馈", "评分反馈"),
    ("题目状态 ACTIVE 表示什么？", "这道题可以作答", "可作答"),
    ("答案表里哪个字段记录补偿重试次数？", "retryCount", "retryCount"),
    ("答案的创建时间在补偿里有什么用？", "判断评分是否已经超时", "超时"),
    ("为什么不能只凭「超时」就重投？", "AI 长尾会被误判成崩溃，导致同题双评", "同题双评"),
    ("评分结果格式不对时怎么处理？", "把格式问题反馈给模型重新生成（格式自纠错）", "自纠错"),
    ("评分提示词里除了题目还带什么？", "评分维度、分数档位和 JSON 结构要求", "评分维度"),
    ("评分任务是谁在提交答案后发布的？", "AnswerCommandService 落库后发布", "AnswerCommandService"),
])

# ---------------------------------------------------------------- 项目二：追问与 LiteFlow
add([
    ("追问一共配了几条链？", "三条", "followUpBasicChain"),
    ("每条追问链由几个节点串行组成？", "三个", "THEN"),
    ("三条链共用的两个公共节点是什么？", "loadQuestionContext 与 persistFollowUp", "persistFollowUp"),
    ("按分数段区分的三个生成节点叫什么？", "generateBasicFollowUp / generateNormalFollowUp / generateDeepFollowUp", "generateDeepFollowUp"),
    ("低于 60 分走哪条追问链？", "基础追问链", "低于 60"),
    ("80 分以上走哪条追问链？", "深度追问链", "不低于 80"),
    ("追问内容存在哪张表？", "interview_follow_up", "interview_follow_up"),
    ("追问的唯一键是什么？", "uk_follow_up_session_question（session_id + question_id）", "uk_follow_up_session_question"),
    ("追问为什么要关联原题而不是当新题？", "追问是对同一道题的深挖，不算新的一题", "原题"),
    ("一道题最多有几条追问？", "一条", "最多一条"),
    ("没有追问时接口为什么返回 null 而不是 404？", "分数段决定不追问是正常业务分支，不是错误", "正常业务分支"),
    ("追问落库怎么保证重复执行不出两条？", "先查后插，唯一键兜底", "先查后插"),
])

# ---------------------------------------------------------------- 项目二：实时语音
add([
    ("实时语音识别用的是哪家服务？", "阿里云 NLS", "NLS"),
    ("NLS 是一整段返回还是分片返回？", "按分片（seg_id）边识别边回调", "seg_id"),
    ("同一个分片的多次回调怎么处理？", "用后到的覆盖先到的", "覆盖"),
    ("分片用什么容器按 seg_id 管理？", "TreeMap", "TreeMap"),
    ("最终文本只拼接哪些分片？", "只拼 ed=true 的连续已确认分片", "ed=true"),
    ("分片跳号时怎么办？", "停下等缺失分片补齐", "跳号"),
    ("临时结果要不要落库？", "不落库，只给前端做实时预览", "临时结果"),
    ("音频按什么规格送给 NLS？", "16k 单声道 PCM", "16k"),
    ("每帧音频加多长的帧头？", "4 字节", "4 字节"),
    ("浏览器采集的默认采样率通常是多少？", "48k，需要降采样到 16k", "48k"),
    ("NLS 的 AccessKey 放在哪？", "只在后端签发 Token，不下发浏览器", "AccessKey"),
    ("为什么不能把每次回调直接追加到字符串？", "会出现「我今今天去去公司」这种重复字", "覆盖而不是追加"),
])

# ---------------------------------------------------------------- 项目二：报告生成
add([
    ("报告有哪几个状态？", "GENERATING、COMPLETED、FAILED", "GENERATING"),
    ("报告正文包含几个固定章节？", "四个", "四个固定章节"),
    ("报告为什么不能同步等？", "10~30 秒的 AI 调用会被网关超时切断", "网关"),
    ("报告接口受理后立刻做什么？", "把记录置成 GENERATING 就返回", "GENERATING"),
    ("前端靠什么知道报告好了？", "轮询状态接口直到终态", "轮询"),
    ("一场面试最多有几份报告？", "一份，session_id 唯一约束", "唯一约束"),
])

# ---------------------------------------------------------------- 项目二：幂等与会话恢复
add([
    ("客户端重试同一个请求怎么防重复推进？", "用 processedRequestIds 直接短路", "processedRequestIds"),
    ("两个线程同时推进同一会话的状态会怎样？", "version 对不上的一方拒绝写入并重新加载", "version"),
    ("预生成题被并发取用怎么保证只有一次生效？", "用 CAS 把状态从 PRE_GENERATED 改成 ACTIVE", "CAS"),
    ("评分任务被重复投递会不会同题双评？", "不会，靠库里的唯一键保证幂等", "唯一键"),
])


def normalize(s: str) -> str:
    return re.sub(r"\s+", "", s)

def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--emit", action="store_true", help="校验通过后写出 seed_eval_cases.sql")
    args = ap.parse_args()

    corpus = "\n".join(p.read_text(encoding="utf-8", errors="ignore") for p in CORPUS_FILES if p.exists())
    flat = normalize(corpus)
    raw = corpus

    print(f"用例总数：{len(CASES)}")

    missing = []
    for q, expected, token in CASES:
        if normalize(token) not in flat:
            missing.append((q, expected, token))

    if missing:
        print(f"\n⚠️ {len(missing)} 条的标准答案在语料里找不到依据：")
        for q, e, t in missing:
            print(f"  - [{t}] {q} -> {e}")
    else:
        print("✅ 全部用例的标准答案都能在语料里找到依据")

    dup = len(CASES) - len(set(q for q, _, _ in CASES))
    if dup:
        print(f"⚠️ 有 {dup} 条问题重复")

    if args.emit and not missing:
        out = ROOT / "scripts" / "seed_eval_cases.sql"
        lines = [
            "-- 评测集种子数据：题目必须能从当前语料（README.md + 项目文档）里找到答案。",
            "-- 为什么不能用通用问题：评测的前提是「标准答案确实在知识库里」，",
            "-- 否则拒答和召回低都分不清是检索坏了还是题目本来就没答案。",
            "-- 本文件由 scripts/build_eval_cases.py 生成，改题请改脚本，不要手改 SQL。",
            "SET client_encoding = 'UTF8';",
            "",
            "TRUNCATE eval_cases RESTART IDENTITY;",
            "",
            "INSERT INTO eval_cases (question, expected) VALUES",
        ]
        rows = []
        for q, e, _ in CASES:
            qq = q.replace("'", "''")
            ee = e.replace("'", "''")
            rows.append(f"('{qq}', '{ee}')")
        lines.append(",\n".join(rows) + ";")
        out.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")
        print(f"已写出 {out}")


if __name__ == "__main__":
    main()
