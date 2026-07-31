# ai-chat v1 渐进式学习路线：先搭骨架，再理解完整系统

> 适用对象：第一次接手 `ai-chat/`，希望从最小实现开始，而不是一上来同时理解 LangChain、LangGraph、gRPC、PostgreSQL、Nacos 和多模态模型。
>
> 配套实作教程见 [`ai-chat-v1-from-minimum-to-current.md`](./ai-chat-v1-from-minimum-to-current.md)。现有 [`ai-chat-design.md`](./ai-chat-design.md) 适合看全景和亮点，[`ai-chat-learning-guide.md`](./ai-chat-learning-guide.md) 适合按当前目录阅读源码。

---

## 1. 先定边界：现在学的是 v1

当前 `ai-chat/` 是 v1 实现，核心形态是：

```text
gRPC / Chainlit 入口
        ↓
共享 Agent
        ↓
中间件：工具限制 → 动态 Prompt（含意图分类）→ 自动摘要
        ↓
LLM / 工具 / Checkpointer
```

v2 文档中的以下能力目前仍是**设计，代码中尚不存在**：

- `AgentRuntime` 和独立的 Pre/Post Hook 流水线；
- `ConversationLane` 的同会话串行、去抖合并、取消在飞请求；
- `SafetyReviewHook` 出站代码级安全审核；
- 主动消息调度、Redis 频控；
- ChatAgent 的多模型路由。

因此先完整理解 v1，再把 v2 当作“v1 暴露问题后的重构答案”。不要用 v2 的类名反推当前代码。

## 2. 学习方法：每层只增加一个主要变量

| 问题 | v1 的解决方案 |
|---|---|
| 怎么调用模型 | `ChatDeepSeek` |
| 怎么形成 Agent 循环 | `create_agent` |
| 怎么让不同 DH 有不同人设 | `@dynamic_prompt` + `UserContext` |
| 怎么记住历史 | LangGraph checkpointer + `thread_id` |
| 怎么判断当前消息该如何回应 | 关键词快路 + 门控 LLM 意图分类 |
| 怎么访问外部信息 | Agent tools |
| 怎么控制长对话成本 | `SummarizationMiddleware` |
| 怎么给其他服务调用 | gRPC servicer |
| 怎么取得 BH/DH 资料 | user-service gRPC client |
| 怎么持久化记忆 | PostgreSQL checkpointer |
| 怎么服务发现 | Nacos |
| 怎么看图并抵抗模型故障 | VisionAgent + SmartRouter |

渐进学习的原则是：**每一阶段只引入一个主要机制，其余依赖先用常量、内存或假实现替代。**

## 3. 推荐拆成 12 层

| 层级 | 新增能力 | 暂时不学 | 对应当前代码 |
|---|---|---|---|
| L0 | 单轮文本生成 | Agent、记忆、工具、服务化 | `core/llm.py` |
| L1 | 静态 Prompt + Agent | 动态人设、记忆 | `create_agent` 的最小用法 |
| L2 | 本地交互入口 | gRPC、外部服务 | `main.py` |
| L3 | 内存多轮记忆 | PostgreSQL | `InMemorySaver`、`thread_id` |
| L4 | 动态 DH/BH 人设 | 意图分类 | `UserContext`、`@dynamic_prompt` |
| L5 | 意图识别与策略注入 | 工具、摘要 | `intent_classifier.py` |
| L6 | 工具调用与调用上限 | 生产服务化 | `core/tools/` |
| L7 | 长对话摘要 | PostgreSQL | `SummarizationMiddleware` |
| L8 | Chat gRPC 接口 + 用户资料 | Nacos | `servicer.py`、`user_client.py` |
| L9 | PostgreSQL 持久记忆 | Nacos、Vision | `server/bootstrap.py` |
| L10 | 配置、反射、Nacos、停机 | Vision | `server/`、`core/nacos_client/` |
| L11 | Vision 单模型 → 结构化输出 → 多模型路由 | v2 | `vision_agent/`、`smart_router.py` |

这不是代码提交历史，而是为学习重新设计的依赖顺序。

---

## 4. 每层的学习任务

### L0：只完成“一问一答”

最小 AI Chat 只有：

```text
system 指令 + user 消息 → LLM → assistant 文本
```

只学习 `build_chat_llm()`、system/human message、`ainvoke()` 和结果消息。暂时忽略 Agent、记忆和服务化。

验收：能解释“没有 Agent 也能单轮回复；Agent 是在模型调用上叠加循环、工具和记忆”。

### L1：加入静态 Prompt 和 Agent

使用固定 `system_prompt`、`tools=[]`、`middleware=[]`、`checkpointer=None` 创建 Agent。

核心认识：Agent 不是另一种模型，而是围绕模型的执行图。没有工具时，它仍近似 `START → model → END`。

验收：能说明什么时候执行图才会出现 `model ⇄ tools` 循环。

### L2：加入本地 Chainlit 入口

读 `main.py` 时先只关注：

1. Agent 在模块加载时构建一次；
2. `@cl.on_message` 收消息；
3. 调 `agent.ainvoke(...)`；
4. 取最后一条 AI 消息并发送。

验收：能说明 Chainlit 只是入口/UI，不承担人设、记忆或模型编排。

### L3：加入内存多轮记忆

把 `checkpointer=None` 换成 `InMemorySaver`，调用时传：

```python
{"configurable": {"thread_id": thread_id}}
```

必须理解：

- Agent 不会天然记住历史；
- checkpointer 保存 LangGraph 状态；
- `thread_id` 是记忆分区键；
- `InMemorySaver` 重启即丢。

业务上的 BH-DH thread id 格式由调用方维护；当前 Chat servicer 只校验非空，不校验 `{min_id}_{max_id}`。

验收：用两个 thread id 交叉对话，解释为什么历史不会串。

### L4：加入动态 DH/BH 人设

新增：

- `UserInfo`：统一人物资料形状；
- `UserContext`：一次 invoke 的运行时上下文；
- `context_schema=UserContext`；
- `@dynamic_prompt`：每次模型调用前生成 system prompt；
- `format_user_info()` / `build_system_prompt()`。

关键方向：

```text
ChatRequest.from_user_id = BH（发消息的人）
ChatRequest.to_user_id   = DH（被发消息的人）

build_system_prompt.from_user = DH（AI 扮演的人）
build_system_prompt.to_user   = BH（AI 的对话对象）
```

Agent 的图结构稳定，变化放进 `UserContext`，因此一个共享 Agent 可以服务不同 DH。

`format_user_info()` 把稳定字段放前，把变化的 `current_time` 放后，以提高 Prompt 稳定前缀的缓存命中。

验收：同一个 Agent 连续注入两组 DH，解释为什么表现为两个角色但无需重建。

### L5：加入意图识别

分三步理解：

1. 关键词快路：强特征消息直接分类；
2. 门控：BH 轮数不超过 5 时，普通消息跳过分类；
3. LLM 分类：超过门槛后用独立、低温度、结构化模型分类。

分类生效链：

```text
IntentResult
  → build_intent_context()
  → {{INTENT_CONTEXT}}
  → system prompt
  → 聊天 LLM
```

实现状态：

- 已实现 9 种主意图和 3 种信号；
- 关键词规则只是简单子串匹配；
- v1 出站安全主要依赖 Prompt；
- v2 的 `SafetyReviewHook` 尚未实现。

验收：能分别解释前两轮普通问候、第一轮询问手机号、第十轮复杂普通消息的路径。

### L6：加入工具

执行图扩展为：

```text
model → 决定调用工具 → tools → 工具结果回到 model → 最终回复
```

| 工具 | 失败策略 |
|---|---|
| `get_weather` | 网络/解析失败返回友好文本 |
| `read_url` | HTTP 失败返回友好文本，正文最多 4000 字符 |
| `web_search` | 没有 `TAVILY_API_KEY` 时不注册 |

同时学习 `ToolCallLimitMiddleware(run_limit=10)`，避免一次 run 无限调用工具。

验收：能解释“返回错误文本”和“抛异常”的区别，以及为什么可选工具在构建期决定是否注册。

### L7：加入长对话摘要

当前参数：

- 累计 200 条消息触发；
- 保留最近 40 条原文；
- 老历史压缩成交友场景摘要。

必须区分：

- checkpointer 解决“历史存在哪里”；
- summarization 解决“历史太长怎么办”。

验收：能解释只加 PostgreSQL 为什么不能解决上下文长度和 token 成本。

### L8：加入 Chat gRPC 和真实用户资料

```text
ChatRequest
  → 参数校验
  → BatchGetProfile(BH, DH)
  → proto UserProfile 转 UserInfo
  → 计算所在地模糊时间
  → agent.ainvoke(context=UserContext(...))
  → ChatResponse(content)
```

当前行为：

- `thread_id` 不能为空；
- from/to 必须能转为整数；
- 两份资料通过一次 `BatchGetProfile` 获取；
- 返回资料按请求 id 顺序重排；
- 任一用户缺失会抛 `UserServiceError`；
- servicer 把资料查询和 Agent 调用中的异常统一映射为 `INTERNAL`。

对接不上时可用 fake `UserServiceClient` 返回固定 `UserInfo`，先验证协议映射。

验收：能从 `ChatAgentServicer.Chat()` 追到 LLM，再追回 `ChatResponse`。

### L9：把内存记忆换成 PostgreSQL

只替换 checkpointer：

```text
InMemorySaver → AsyncPostgresSaver
```

当前启动还会创建连接池、借出前探活、配置 TCP keepalive，并用 `setup()` 幂等建表。

当前风险：v1 没有把同一 `thread_id` 的并发请求串行化，checkpoint 可能竞态；这是 v2 `ConversationLane` 要解决的问题。

验收：说明持久化替换为什么发生在装配层而不是 Agent 业务逻辑层。

### L10：加入生产启动和服务治理

启动顺序：

```text
load_dotenv
  → Settings.from_env
  → 可选初始化 Nacos
  → build_chat_llm
  → PG pool / checkpointer
  → UserServiceClient
  → gRPC server
  → 注册 ChatAgent
  → 尝试注册 VisionAgent
  → reflection
  → 启动并注册 Nacos
```

Nacos 学习重点：

- 未配置 `NACOS_SERVER_ADDR` 时静态直连；
- Java 服务 gRPC 端口优先取 metadata 中的 `gRPC.port`；
- resolver 缓存并订阅地址变化；
- 只对 `UNAVAILABLE` / `DEADLINE_EXCEEDED` 重连重试一次；
- 业务错误不重试。

验收：分别解释服务注册和服务发现，以及 ai-chat 为什么两者都需要。

### L11：把 VisionAgent 当成独立支线

按四步学习：

1. **单模型、单图片、文本输出**：先只做 `Understand`；
2. **图片输入**：跟随 302、判断 MIME、转 base64，多图并发；
3. **结构化输出**：为 `ScoreFace` / `AnalyzeProfilePhoto` 绑定 Pydantic schema；
4. **SmartRouter**：轮询、请求内失败切换、连续错误熔断、fallback 层。

实现状态：

- Groq、Gemini、Z.ai 主池已实现；
- fallback 机制已实现；
- 实际 fallback 模型列表为空；
- Vision 构建失败时整个 servicer 跳过，Chat 保持可用。

验收：说明 Vision 为什么不用 checkpointer，以及 SmartRouter 为什么位于模型调用中间件。

---

## 5. 推荐阅读顺序

| 学习层 | 文件 |
|---|---|
| L0 | `core/llm.py` |
| L1–L3 | `main.py` |
| L4 | `core/models.py`、`chat_agent/prompts/utils.py`、`chat_agent/builder.py` |
| L5 | `chat_agent/intent_classifier.py`、`prompts/intent_classification.md` |
| L6 | `core/tools/`、`chat_agent/builder.py` |
| L7 | `prompts/dating_summary_prompt.md`、`chat_agent/builder.py` |
| L8 | `chat_agent/servicer.py`、`core/clients/user_client.py` |
| L9–L10 | `server/`、`core/config.py`、`core/nacos_client/` |
| L11 | `vision_agent/`、`core/middlewares/smart_router.py` |

专题文档在 `ai-chat/docs/`。建议完成对应层后再读，不要一开始全部读完。

## 6. 不强制运行时的验证方式

### A. 静态验收

- 能画数据流；
- 能指出入口、状态、外部副作用和错误出口；
- 能区分“已实现”“可选未启用”“仅设计”。

### B. 隔离验收

- fake LLM 返回固定消息；
- fake UserServiceClient 返回固定 BH/DH；
- 内存 checkpointer 代替 PostgreSQL；
- 假视觉模型代替真实 provider。

### C. 集成验收

- Chainlit 手动聊天；
- gRPC reflection / `grpcurl`；
- PostgreSQL 重启恢复；
- Nacos 地址变化；
- 视觉模型故障切换。

先完成 A 和 B 就足以学习代码；环境齐全后再做 C。

## 7. v1 实现状态总表

| 能力 | 状态 | 证据 |
|---|---|---|
| 文本聊天、动态人设 | 已实现 | `chat_agent/builder.py` |
| 内存 / PostgreSQL 记忆 | 已实现 | `main.py` / `server/bootstrap.py` |
| 意图快路与门控分类 | 已实现 | `intent_classifier.py` |
| 工具与自动摘要 | 已实现；搜索条件启用 | `core/tools/` / `builder.py` |
| Chat gRPC | 已实现 | `chat_agent/servicer.py` |
| Vision 三个 RPC | 已实现；整体可选注册 | `vision_agent/servicer.py` |
| Vision 多模型主池 | 已实现 | `build_vision_models()` |
| Vision fallback 模型 | 机制已实现，列表为空 | `build_vision_fallback_models()` |
| Nacos 注册/发现 | 已实现；按配置启用 | `core/nacos_client/` |
| 正式自动化测试 | 仓库中未发现 | 无 `tests/` |
| 同会话并发保护 | v1 未实现，v2 设计 | `ai-chat-v2-design.md` |
| 出站代码级安全审核 | v1 未实现，v2 设计 | `ai-chat-v2-design.md` |
| 主动消息 | v1 未实现，v2 设计 | `ai-chat-v2-design.md` |

## 8. 最终心智模型

```mermaid
flowchart LR
    A["L0 模型调用"] --> B["L1 Agent"]
    B --> C["L3 记忆"]
    C --> D["L4 动态人设"]
    D --> E["L5 意图策略"]
    E --> F["L6 工具"]
    F --> G["L7 摘要"]
    G --> H["L8 gRPC 与用户资料"]
    H --> I["L9 持久化"]
    I --> J["L10 服务治理"]
    J --> K["L11 Vision 支线"]
```

当你能回答“这一层解决什么问题、状态存在哪里、失败后发生什么、删掉这一层系统还剩什么”，就算真正掌握了这一层。
