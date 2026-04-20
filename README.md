# HAL1000

HAL1000 是一个 **单体 Spring Boot + Vue3** 的本地/自托管聊天系统，支持：

- **会话聊天 + SSE 流式输出**
- **SQLite 持久化**（Liquibase 管理表结构）
- **可选 RAG**：上传 `.txt/.md/.pdf` 或 URL → 分块 → 向量检索 → 以引用片段注入 system
- **GitHub 登录（JWT）**：以 GitHub 账号作为用户体系，按用户隔离会话与知识库数据

---

## 技术栈

- **Backend**：Java 17、Spring Boot 4、Spring MVC、Spring Data JPA、Liquibase、SQLite、WebFlux `WebClient`
- **Frontend**：Vue 3 + TypeScript + Vite

---

## 目录结构（核心）

- `src/main/java/com/genhao/hal1000/`：后端代码
  - `chat/`：会话、消息、SSE stream
  - `llm/`：Gemini / OpenAI-compat 流式网关
  - `rag/`：RAG 入库与检索（可选）
  - `auth/`：GitHub OAuth + JWT（必选，如果你启用了安全保护）
- `src/main/resources/`：`application.yaml`、Liquibase changelog、静态资源
- `frontend/`：Vue 前端
- `docs/CURRENT_FEATURES.md`：当前已实现功能与约束清单

---

## 快速开始（本地开发）

### 1) 准备环境

- Java 17
- Node.js 20+

### 2) 配置环境变量

```bash
cp .env.example .env
```

至少需要配置：

- `HAL1000_LLM_API_KEY`
- `HAL1000_AUTH_JWT_SECRET`
- GitHub OAuth（登录用）：
  - `HAL1000_GITHUB_CLIENT_ID`
  - `HAL1000_GITHUB_CLIENT_SECRET`
  - `HAL1000_AUTH_FRONTEND_REDIRECT_URL`（本地建议：`http://localhost:8080/` 或 `http://localhost:5173/`，看你用哪个入口访问）

### 3) 启动

```bash
./gradlew bootRun
```

访问：

- `http://localhost:8080/`

---

## Docker 运行

```bash
cp .env.example .env
# 编辑 .env，至少填 LLM key / JWT secret / GitHub OAuth
docker compose up -d --build
docker compose logs -f
```

- 数据库会持久化到 volume（容器内 `/data/hal1000.db`），见 [`docker-compose.yml`](docker-compose.yml)。
- Debian 12 部署步骤见 [`DEPLOY_DEBIAN12_DOCKER.md`](DEPLOY_DEBIAN12_DOCKER.md)。
- 反向代理与 TLS 见 [`REVERSE_PROXY.md`](REVERSE_PROXY.md)。

---

## 登录与鉴权（GitHub OAuth + JWT）

前端侧边栏点击 **GitHub 登录**：

1. 浏览器打开 `/api/auth/github/start`
2. GitHub 授权完成后回调 `/api/auth/github/callback`
3. 后端签发 JWT，并重定向回 `HAL1000_AUTH_FRONTEND_REDIRECT_URL?token=...`
4. 前端保存 token 到 `localStorage(hal1000_token)`，后续请求自动带 `Authorization: Bearer ...`

接口：

- `GET /api/auth/me`：返回当前用户信息

---

## 聊天 API（SSE）

- `POST /api/conversations`：创建会话
- `GET /api/conversations`：列出会话（按用户隔离）
- `GET /api/conversations/{id}/messages`：会话消息列表
- `POST /api/conversations/{id}/stream`：SSE 流式生成

未登录调用 `/api/**` 会返回 **401**。

---

## RAG（可选）

启用：`.env` 中设置 `HAL1000_RAG_ENABLED=true`。

### 入库方式

- 上传文件：`POST /api/conversations/{id}/documents`（multipart field：`file`）
- URL 入库：`POST /api/conversations/{id}/documents/from-url`
- 列表：`GET /api/conversations/{id}/documents`
- 删除：`DELETE /api/conversations/{id}/documents/{docId}`

### 异步索引（重要）

大文件（例如 50MB PDF）索引会很慢。现在入库是 **异步**：

- 上传/URL 会先返回 `status=processing`
- 后台完成后变为 `ready`，失败则为 `failed` 并记录 `errorMessage`

前端知识库列表会显示 `status / chunkCount / errorMessage`。

### 保护阈值（建议调整）

为避免超大 PDF 导致 OOM / 超时，提供护栏配置：

- `HAL1000_RAG_MAX_EXTRACT_CHARS`（默认 1,000,000）
- `HAL1000_RAG_MAX_CHUNKS`（默认 2000）

如果你需要“更快出结果”，优先把这两个值调小。

> PDF 仅支持抽取 **文本层**；扫描件/纯图片 PDF 需要 OCR（当前未实现）。

---

## 常见问题（Troubleshooting）

### 启动报：`HAL1000_AUTH_JWT_SECRET is required`

你启用了 JWT 相关组件但未配置 secret。给 `.env` 增加：

```bash
HAL1000_AUTH_JWT_SECRET=REPLACE_ME_WITH_LONG_RANDOM_SECRET
```

然后重启。

### 上传报：`MaxUploadSizeExceededException`

说明文件超过上传限制。相关配置在 [`application.yaml`](src/main/resources/application.yaml)：

- `spring.servlet.multipart.max-file-size`
- `spring.servlet.multipart.max-request-size`
- `server.tomcat.max-swallow-size`

以及反向代理（Nginx/Caddy）也可能有 body 限制。

---

## 变更记录 / 现状说明

更完整的“已实现功能与局限”请看 [`docs/CURRENT_FEATURES.md`](docs/CURRENT_FEATURES.md)。

