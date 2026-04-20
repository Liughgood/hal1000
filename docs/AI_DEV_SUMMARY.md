# HAL1000 AI 开发摘要（给后续 Agent/AI 上手用）

本文档是面向“让 AI/新同学快速进入代码”的**高密度摘要**：架构、关键数据模型、API、配置开关、常见坑点与扩展点。更完整的功能清单请同时参考 [`docs/CURRENT_FEATURES.md`](CURRENT_FEATURES.md)。

---

## 1. 系统架构一览

- **单体应用**：Spring Boot（后端 API + 静态资源）+ Vue3 SPA（编译产物放 `src/main/resources/static` 或由 Docker 构建拷贝进去）。
- **存储**：SQLite（JPA）+ Liquibase（changelog：`src/main/resources/db/changelog/db.changelog-master.yaml`）。
- **聊天**：`/api/conversations/{id}/stream` 用 **SSE** 进行 token/delta 级别的流式输出。
- **RAG（可选）**：
  - 文档入库：上传/URL → 抽取文本 → 分块 → embeddings → `rag_chunk`。
  - 检索：对用户 query 做 embedding → 余弦 Top‑K → 片段注入 system。
  - **索引为异步**：先返回 `processing`，后台完成后 `ready/failed`。
- **鉴权（GitHub 登录 + JWT）**：
  - GitHub OAuth 回调后签发 JWT。
  - `/api/**` 需要 `Authorization: Bearer <token>`（`/api/auth/**` 放行）。
  - 会话与知识库按用户隔离（`chat_conversation.user_id`）。

---

## 2. 关键代码入口（建议阅读顺序）

### 2.1 聊天（SSE）

- Controller：`src/main/java/com/genhao/hal1000/chat/web/ChatApiController.java`
- Service：`src/main/java/com/genhao/hal1000/chat/service/ChatService.java`
  - 关键点：会话归属校验 + `ContextAugmentor` 注入 extra system prompt + 调用 `LlmGateway.streamReply(...)`

### 2.2 LLM 网关（流式）

- 接口：`src/main/java/com/genhao/hal1000/llm/LlmGateway.java`
- Provider 选择：`src/main/java/com/genhao/hal1000/llm/LlmConfig.java`
  - `hal1000.llm.provider = gemini | openai`
- 实现：
  - Gemini：`src/main/java/com/genhao/hal1000/llm/GeminiAiStudioGateway.java`
  - OpenAI-compat：`src/main/java/com/genhao/hal1000/llm/OpenAiCompatGateway.java`

### 2.3 Auth（GitHub OAuth + JWT）

- 安全链：`src/main/java/com/genhao/hal1000/auth/SecurityConfig.java`
- JWT：
  - 解析 filter：`src/main/java/com/genhao/hal1000/auth/JwtAuthFilter.java`
  - 签发/校验：`src/main/java/com/genhao/hal1000/auth/JwtService.java`
- OAuth Controller：`src/main/java/com/genhao/hal1000/auth/AuthApiController.java`
- 获取当前用户：`src/main/java/com/genhao/hal1000/auth/CurrentUser.java`

### 2.4 RAG（入库/索引/检索）

- 开关配置：`src/main/java/com/genhao/hal1000/rag/RagProperties.java`
- 入库 API：`src/main/java/com/genhao/hal1000/rag/web/RagApiController.java`
- 入库服务：`src/main/java/com/genhao/hal1000/rag/RagIngestionService.java`
  - 关键点：**异步索引必须 afterCommit 触发**（避免事务未提交导致 worker 查不到 `rag_document` 从而永久 `processing`）
- 索引 worker：`src/main/java/com/genhao/hal1000/rag/RagIndexingWorker.java`（`@Async`）
- 检索注入：`src/main/java/com/genhao/hal1000/rag/VectorRagContextAugmentor.java`（`@Primary`，`hal1000.rag.enabled=true` 时启用）
- Embeddings：
  - 接口：`src/main/java/com/genhao/hal1000/rag/embedding/EmbeddingClient.java`
  - Gemini：`src/main/java/com/genhao/hal1000/rag/embedding/GeminiEmbeddingClient.java`
  - OpenAI：`src/main/java/com/genhao/hal1000/rag/embedding/OpenAiEmbeddingClient.java`
  - 装配：`src/main/java/com/genhao/hal1000/rag/RagEnabledConfiguration.java`
- 工具：
  - PDF 抽取：`src/main/java/com/genhao/hal1000/rag/util/PdfTextExtractor.java`（PDFBox）
  - 分块：`src/main/java/com/genhao/hal1000/rag/util/TextChunker.java`
  - embedding 编解码：`src/main/java/com/genhao/hal1000/rag/util/FloatEmbeddingCodec.java`

---

## 3. 数据模型（SQLite）

Liquibase：`src/main/resources/db/changelog/db.changelog-master.yaml`

### 3.1 Chat

- `chat_conversation`：`id`、`title`、`user_id`、`created_at`、`updated_at`
- `chat_message`：`id`、`conversation_id`、`role`、`content`、`model`、token 计数字段、`created_at`

### 3.2 RAG

- `rag_document`：`id`、`conversation_id`、`filename`、`source_type`、`source_url`、`mime_type`、`created_at`、`status`、`error_message`
- `rag_chunk`：`id`、`document_id`、`conversation_id`、`chunk_index`、`text`、`embedding_dim`、`embedding`（float32 小端 BLOB）

### 3.3 User

- `app_user`：`id`（UUID）、`github_id`（unique）、`github_login`（unique）、`name`、`avatar_url`、`created_at`、`updated_at`

---

## 4. HTTP API（核心）

### 4.1 Auth

- `GET /api/auth/github/start`：跳转 GitHub authorize（state 存 session）
- `GET /api/auth/github/callback`：code → token → user → JWT → redirect 到 `frontend-redirect-url?token=...`
- `GET /api/auth/me`：当前用户信息

### 4.2 Chat

- `POST /api/conversations`
- `GET /api/conversations`
- `GET /api/conversations/{id}/messages`
- `POST /api/conversations/{id}/stream`（SSE）
  - 服务端事件：`{"type":"message_delta","delta":"..."}`、`{"type":"error","error":"..."}`，最后 `[DONE]`

### 4.3 RAG（`hal1000.rag.enabled=true` 才存在）

- `POST /api/conversations/{id}/documents`（multipart `file`：`.txt/.md/.pdf`）
- `POST /api/conversations/{id}/documents/from-url`（JSON `{url}`）
- `GET /api/conversations/{id}/documents`（含 `status/chunkCount/errorMessage`）
- `DELETE /api/conversations/{id}/documents/{docId}`

---

## 5. 配置（环境变量）速查

### 5.1 必要（系统能跑）

- DB：`HAL1000_DB_PATH`（可选；默认 `./hal1000.db`）
- JWT：`HAL1000_AUTH_JWT_SECRET`（**必填**；否则启动时报 `HAL1000_AUTH_JWT_SECRET is required`）

### 5.2 GitHub OAuth（能登录）

- `HAL1000_GITHUB_CLIENT_ID`
- `HAL1000_GITHUB_CLIENT_SECRET`
- `HAL1000_AUTH_FRONTEND_REDIRECT_URL`（默认 `http://localhost:5173/`，也可用 `http://localhost:8080/`）

### 5.3 LLM（能聊天）

- `HAL1000_LLM_PROVIDER`：`gemini | openai`
- `HAL1000_LLM_BASE_URL`
- `HAL1000_LLM_API_KEY`
- `HAL1000_LLM_MODEL`
- `HAL1000_LLM_TIMEOUT_SECONDS`

### 5.4 RAG（可选）

- `HAL1000_RAG_ENABLED=true`
- `HAL1000_RAG_TOP_K`
- `HAL1000_RAG_MAX_PROMPT_CHARS`
- `HAL1000_RAG_CHUNK_SIZE_CHARS` / `HAL1000_RAG_CHUNK_OVERLAP_CHARS`
- `HAL1000_RAG_EMBEDDING_PROVIDER`：`openai | gemini`
- `HAL1000_RAG_EMBEDDING_MODEL`（Gemini 例：`gemini-embedding-001`）
- `HAL1000_RAG_EMBEDDING_BASE_URL` / `HAL1000_RAG_EMBEDDING_API_KEY`（可回退到 `HAL1000_LLM_*`）
- **护栏（大文件强烈建议调小）**：
  - `HAL1000_RAG_MAX_EXTRACT_CHARS`
  - `HAL1000_RAG_MAX_CHUNKS`

---

## 6. 已知坑点 / 注意事项（高优先级）

### 6.1 大 PDF “一直 processing”

如果异步 worker 在事务提交前启动，可能查不到 `rag_document`，导致 worker 直接退出，从而永久 `processing`。当前实现通过 **afterCommit** 触发避免该问题。

### 6.2 大 PDF 很慢/超时

即便异步，embedding 调用次数也会非常多。优先用 `MAX_EXTRACT_CHARS/MAX_CHUNKS` 控制规模；必要时提高 `HAL1000_LLM_TIMEOUT_SECONDS`。

### 6.3 200 OK 但 embeddings 仍失败（解析/空 body）

Gemini/OpenAI embeddings 请求体需要保证是真正 JSON（而不是 JsonNode 的 POJO 序列化）。实现中已将请求体先序列化为 String 再发送，避免出现 `array/bigDecimal/...` 这类字段。

### 6.4 “Access Denied but response committed”

多见于 SSE/异步响应：响应头已写出后才触发权限异常，日志会很红但不一定是本次请求。定位需对照浏览器 Network 的 401/403 请求。

---

## 7. 扩展点（后续迭代建议）

- **RAG**：
  - 引用来源结构化：在 SSE 中额外发送 sources JSON（或在消息表落引用）
  - OCR（扫描 PDF）
  - 全局知识库 / 跨会话共享
  - 更高效向量索引（SQLite-vec / 外置向量库）
- **Auth**：
  - GitHub org/team 限制登录
  - token 刷新/短期 token
- **Chat**：
  - 模型/温度 per-conversation
  - 停止生成、重试等交互增强

