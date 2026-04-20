<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from "vue";

type Conversation = { id: string; title?: string | null; updatedAt?: string };
type Message = { id: string; role: "user" | "assistant" | "system"; content: string; createdAt?: string };
type RagDoc = {
  id: string;
  filename: string;
  sourceType: string;
  sourceUrl: string | null;
  status: string;
  createdAt: number;
  chunkCount: number;
  errorMessage?: string | null;
};

type Me = { id: string; githubLogin: string; name?: string | null; avatarUrl?: string | null };

const conversations = ref<Conversation[]>([]);
const activeConversationId = ref<string | null>(null);
const messages = ref<Message[]>([]);
const input = ref("");
const isStreaming = ref(false);
const errorMsg = ref<string | null>(null);
const isLoading = ref(false);

const token = ref<string | null>(localStorage.getItem("hal1000_token"));
const me = ref<Me | null>(null);

const ragAvailable = ref<boolean | null>(null);
const ragDocs = ref<RagDoc[]>([]);
const ragUrl = ref("");
const isRagLoading = ref(false);

const canSend = computed(() => input.value.trim().length > 0 && !isStreaming.value && !!activeConversationId.value);

function genClientId(): string {
  // Avoid `crypto.randomUUID()` because on some remote deployments the page is not a secure context
  // (`window.isSecureContext === false`), where `crypto.randomUUID` can be undefined.
  const g: any = globalThis as any;

  const cryptoObj: any = g?.crypto;
  if (cryptoObj && typeof cryptoObj.randomUUID === "function") {
    return cryptoObj.randomUUID();
  }

  // RFC4122 v4: only when `getRandomValues` exists.
  if (cryptoObj && typeof cryptoObj.getRandomValues === "function") {
    const buf = new Uint8Array(16);
    cryptoObj.getRandomValues(buf);
    buf[6] = (buf[6] & 0x0f) | 0x40;
    buf[8] = (buf[8] & 0x3f) | 0x80;
    const hex = Array.from(buf)
      .map((b) => b.toString(16).padStart(2, "0"))
      .join("");
    return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
  }

  // Last resort: prevents runtime crash even on very old/locked-down browsers.
  return `id_${Math.random().toString(16).slice(2)}_${Date.now().toString(16)}`;
}

async function api<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(path, {
    ...init,
    headers: {
      "Content-Type": "application/json",
      ...(token.value ? { Authorization: `Bearer ${token.value}` } : {}),
      ...(init?.headers || {}),
    },
  });
  if (!res.ok) {
    throw new Error(`${res.status} ${res.statusText}`);
  }
  return (await res.json()) as T;
}

function startGitHubLogin() {
  window.location.href = "/api/auth/github/start";
}

function maybeConsumeTokenFromUrl() {
  const url = new URL(window.location.href);
  const t = url.searchParams.get("token");
  if (t && t.trim().length > 0) {
    token.value = t;
    localStorage.setItem("hal1000_token", t);
    url.searchParams.delete("token");
    window.history.replaceState({}, "", url.toString());
  }
}

async function loadMe() {
  if (!token.value) {
    me.value = null;
    return;
  }
  try {
    me.value = await api<Me>("/api/auth/me");
  } catch {
    // token invalid/expired
    token.value = null;
    me.value = null;
    localStorage.removeItem("hal1000_token");
  }
}

async function loadConversations() {
  isLoading.value = true;
  errorMsg.value = null;
  try {
    conversations.value = await api<Conversation[]>("/api/conversations");
    if (!activeConversationId.value && conversations.value.length > 0) {
      activeConversationId.value = conversations.value[0].id;
      await loadMessages();
      await loadRagDocuments();
    }
  } catch (e: any) {
    if ((e?.message ?? "").includes("401")) {
      errorMsg.value = `请先登录（GitHub）`;
    } else {
      errorMsg.value = `加载会话失败：${e?.message ?? String(e)}`;
    }
  } finally {
    isLoading.value = false;
  }
}

async function createConversation() {
  isLoading.value = true;
  errorMsg.value = null;
  try {
    const convo = await api<Conversation>("/api/conversations", { method: "POST", body: JSON.stringify({}) });
    conversations.value = [convo, ...conversations.value];
    activeConversationId.value = convo.id;
    messages.value = [];
    await loadRagDocuments();
  } catch (e: any) {
    errorMsg.value = `新建会话失败：${e?.message ?? String(e)}`;
  } finally {
    isLoading.value = false;
  }
}

async function loadMessages() {
  if (!activeConversationId.value) return;
  messages.value = await api<Message[]>(`/api/conversations/${activeConversationId.value}/messages`);
  await nextTick();
  scrollToBottom();
}

async function loadRagDocuments() {
  if (!activeConversationId.value) return;
  isRagLoading.value = true;
  try {
    const res = await fetch(`/api/conversations/${activeConversationId.value}/documents`, {
      headers: token.value ? { Authorization: `Bearer ${token.value}` } : undefined,
    });
    if (res.status === 404) {
      ragAvailable.value = false;
      ragDocs.value = [];
      return;
    }
    if (!res.ok) throw new Error(`${res.status} ${res.statusText}`);
    ragAvailable.value = true;
    ragDocs.value = (await res.json()) as RagDoc[];
  } catch {
    ragAvailable.value = false;
    ragDocs.value = [];
  } finally {
    isRagLoading.value = false;
  }
}

async function uploadRagFile(ev: Event) {
  const el = ev.target as HTMLInputElement;
  const file = el.files?.[0];
  if (!file || !activeConversationId.value || ragAvailable.value !== true) return;
  errorMsg.value = null;
  isRagLoading.value = true;
  try {
    const fd = new FormData();
    fd.append("file", file);
    const res = await fetch(`/api/conversations/${activeConversationId.value}/documents`, {
      method: "POST",
      headers: token.value ? { Authorization: `Bearer ${token.value}` } : undefined,
      body: fd,
    });
    if (!res.ok) {
      const t = await res.text();
      throw new Error(t || `${res.status}`);
    }
    await loadRagDocuments();
    // Fire-and-forget status refresh while background indexing runs.
    setTimeout(loadRagDocuments, 1500);
    setTimeout(loadRagDocuments, 4000);
    setTimeout(loadRagDocuments, 9000);
  } catch (e: any) {
    errorMsg.value = `上传知识库失败：${e?.message ?? String(e)}`;
  } finally {
    el.value = "";
    isRagLoading.value = false;
  }
}

async function submitRagUrl() {
  const url = ragUrl.value.trim();
  if (!url || !activeConversationId.value || ragAvailable.value !== true) return;
  errorMsg.value = null;
  isRagLoading.value = true;
  try {
    const res = await fetch(`/api/conversations/${activeConversationId.value}/documents/from-url`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        ...(token.value ? { Authorization: `Bearer ${token.value}` } : {}),
      },
      body: JSON.stringify({ url }),
    });
    if (!res.ok) {
      const t = await res.text();
      throw new Error(t || `${res.status}`);
    }
    ragUrl.value = "";
    await loadRagDocuments();
    setTimeout(loadRagDocuments, 1500);
    setTimeout(loadRagDocuments, 4000);
    setTimeout(loadRagDocuments, 9000);
  } catch (e: any) {
    errorMsg.value = `URL 入库失败：${e?.message ?? String(e)}`;
  } finally {
    isRagLoading.value = false;
  }
}

async function deleteRagDoc(id: string) {
  if (!activeConversationId.value || ragAvailable.value !== true) return;
  errorMsg.value = null;
  isRagLoading.value = true;
  try {
    const res = await fetch(`/api/conversations/${activeConversationId.value}/documents/${id}`, {
      method: "DELETE",
      headers: token.value ? { Authorization: `Bearer ${token.value}` } : undefined,
    });
    if (!res.ok) throw new Error(`${res.status} ${res.statusText}`);
    await loadRagDocuments();
  } catch (e: any) {
    errorMsg.value = `删除文档失败：${e?.message ?? String(e)}`;
  } finally {
    isRagLoading.value = false;
  }
}

function scrollToBottom() {
  const el = document.getElementById("msgEnd");
  try {
    // When messages are many, repeated smooth scrolling can be janky; use auto.
    el?.scrollIntoView({ behavior: "auto", block: "end" });
  } catch {
    // ignore
  }
}

async function send() {
  errorMsg.value = null;
  if (!canSend.value || !activeConversationId.value) return;

  const content = input.value.trim();
  input.value = "";

  // optimistic append user msg
  messages.value.push({ id: genClientId(), role: "user", content });
  const assistantMsg: Message = { id: genClientId(), role: "assistant", content: "" };
  messages.value.push(assistantMsg);
  await nextTick();
  scrollToBottom();

  isStreaming.value = true;
  try {
    const res = await fetch(`/api/conversations/${activeConversationId.value}/stream`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        ...(token.value ? { Authorization: `Bearer ${token.value}` } : {}),
      },
      body: JSON.stringify({ content }),
    });
    if (!res.ok || !res.body) throw new Error(`${res.status} ${res.statusText}`);

    const reader = res.body.getReader();
    const decoder = new TextDecoder("utf-8");
    let buffer = "";
    let shouldStop = false;

    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });

      // minimal SSE parser: split by double newline
      const parts = buffer.split("\n\n");
      buffer = parts.pop() || "";
      for (const part of parts) {
        const lines = part.split("\n");
        const dataLines = lines.filter((l) => l.startsWith("data:")).map((l) => l.slice(5).trimStart());
        if (dataLines.length === 0) continue;
        const data = dataLines.join("\n");
        if (data === "[DONE]") continue;
        try {
          const evt = JSON.parse(data) as { type: string; delta?: string; error?: string };
          if (evt.type === "message_delta" && evt.delta) {
            assistantMsg.content += evt.delta;
            await nextTick();
            scrollToBottom();
          } else if (evt.type === "error") {
            errorMsg.value = evt.error || "stream error";
            shouldStop = true;
            try {
              await reader.cancel();
            } catch {}
            break;
          }
        } catch {
          // ignore non-JSON chunks
        }
      }
      if (shouldStop) break;
    }
  } catch (e: any) {
    errorMsg.value = e?.message ?? String(e);
  } finally {
    isStreaming.value = false;
    await loadConversations();
  }
}

onMounted(async () => {
  maybeConsumeTokenFromUrl();
  await loadMe();
  await loadConversations();
  if (!activeConversationId.value) {
    await createConversation();
  }
  await loadRagDocuments();
});
</script>

<template>
  <div class="layout">
    <aside class="sidebar">
      <div class="sidebarHeader">
        <div class="brand">HAL1000</div>
        <button class="btn" @click="createConversation" :disabled="!token">新建会话</button>
      </div>
      <div class="authRow">
        <div v-if="me" class="me">
          <img v-if="me.avatarUrl" class="avatar" :src="me.avatarUrl" alt="avatar" />
          <div class="meText">
            <div class="meLogin">{{ me.githubLogin }}</div>
            <div class="meName" v-if="me.name">{{ me.name }}</div>
          </div>
        </div>
        <button v-else class="btn" @click="startGitHubLogin">GitHub 登录</button>
      </div>
      <div class="list">
        <button
          v-for="c in conversations"
          :key="c.id"
          class="listItem"
          :class="{ active: c.id === activeConversationId }"
          @click="
            activeConversationId = c.id;
            loadMessages();
            loadRagDocuments();
          "
        >
          <div class="title">{{ c.title || c.id.slice(0, 8) }}</div>
        </button>
      </div>

      <div v-if="ragAvailable === true" class="ragPanel">
        <div class="ragTitle">知识库</div>
        <p class="ragHint">支持 .txt / .md / .pdf；URL 若为 PDF 或 HTML 会提取正文。</p>
        <label class="ragFileWrap">
          <span class="ragFileBtn">{{ isRagLoading ? "处理中…" : "上传文档" }}</span>
          <input
            class="ragFileInput"
            type="file"
            accept=".txt,.md,.pdf,text/plain,text/markdown,application/pdf"
            :disabled="isRagLoading"
            @change="uploadRagFile"
          />
        </label>
        <div class="ragUrlRow">
          <input v-model="ragUrl" class="ragUrlInput" type="url" placeholder="https://…" :disabled="isRagLoading" />
          <button type="button" class="btn ragUrlBtn" :disabled="isRagLoading || !ragUrl.trim()" @click="submitRagUrl">添加 URL</button>
        </div>
        <div v-if="ragDocs.length === 0" class="ragEmpty">暂无文档</div>
        <ul v-else class="ragList">
          <li v-for="d in ragDocs" :key="d.id" class="ragItem">
            <div class="ragItemMain">
              <div class="ragName">{{ d.filename }}</div>
              <div class="ragMeta">
                {{ d.status }} · {{ d.chunkCount }} 块 · {{ d.sourceType }}
                <span v-if="d.status === 'failed' && d.errorMessage"> · {{ d.errorMessage }}</span>
              </div>
            </div>
            <button type="button" class="btn ragDel" :disabled="isRagLoading" @click="deleteRagDoc(d.id)">删除</button>
          </li>
        </ul>
      </div>
    </aside>

    <main class="main">
      <header class="header">
        <div class="headerTitle">Chat</div>
        <div class="headerMeta" v-if="activeConversationId">{{ activeConversationId }}</div>
      </header>

      <section class="messages">
        <div v-for="m in messages" :key="m.id" class="msg" :class="m.role">
          <div class="role">{{ m.role }}</div>
          <div class="bubble">{{ m.content }}</div>
        </div>
        <div id="msgEnd"></div>
      </section>

      <footer class="composer">
        <div class="error" v-if="errorMsg">{{ errorMsg }}</div>
        <div class="row">
          <textarea v-model="input" class="input" placeholder="输入你的问题…" :disabled="isStreaming"></textarea>
          <button class="btn primary" :disabled="!canSend || isLoading" @click="send">
            {{ isStreaming ? "生成中…" : isLoading ? "处理中…" : "发送" }}
          </button>
        </div>
      </footer>
    </main>
  </div>
</template>

<style scoped>
.layout {
  display: grid;
  grid-template-columns: 280px 1fr;
  height: 100vh;
  background: #0b0f19;
  color: #e6e8ef;
  font-family: ui-sans-serif, system-ui, -apple-system, Segoe UI, Roboto, Helvetica, Arial;
}
.sidebar {
  border-right: 1px solid rgba(255, 255, 255, 0.08);
  padding: 12px;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  min-height: 0;
}
.sidebarHeader {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 12px;
}
.authRow {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 12px;
}
.me {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.avatar {
  width: 22px;
  height: 22px;
  border-radius: 999px;
  border: 1px solid rgba(255, 255, 255, 0.12);
}
.meText {
  min-width: 0;
}
.meLogin {
  font-size: 12px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.meName {
  font-size: 11px;
  opacity: 0.6;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.brand {
  font-weight: 700;
  letter-spacing: 0.4px;
}
.list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  overflow: auto;
  min-height: 0;
}
.listItem {
  text-align: left;
  background: rgba(255, 255, 255, 0.04);
  border: 1px solid rgba(255, 255, 255, 0.06);
  color: #e6e8ef;
  padding: 10px;
  border-radius: 10px;
  cursor: pointer;
}
.listItem.active {
  border-color: rgba(99, 102, 241, 0.7);
  background: rgba(99, 102, 241, 0.15);
}
.title {
  font-size: 13px;
  opacity: 0.9;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.main {
  display: grid;
  grid-template-rows: auto 1fr auto;
  min-width: 0;
  min-height: 0;
}
.header {
  padding: 12px 16px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
}
.headerTitle {
  font-weight: 600;
}
.headerMeta {
  font-size: 12px;
  opacity: 0.6;
  margin-top: 4px;
  overflow: hidden;
  text-overflow: ellipsis;
}
.messages {
  padding: 16px;
  overflow: auto;
  min-height: 0;
}
.msg {
  display: grid;
  grid-template-columns: 92px 1fr;
  gap: 10px;
  margin-bottom: 12px;
  align-items: start;
}
.role {
  font-size: 12px;
  opacity: 0.65;
  text-transform: uppercase;
}
.bubble {
  background: rgba(255, 255, 255, 0.04);
  border: 1px solid rgba(255, 255, 255, 0.06);
  padding: 10px 12px;
  border-radius: 12px;
  white-space: pre-wrap;
  word-break: break-word;
}
.composer {
  position: sticky;
  bottom: 0;
  border-top: 1px solid rgba(255, 255, 255, 0.08);
  padding: 12px 16px;
  background: #0b0f19;
  z-index: 5;
}
.row {
  display: grid;
  grid-template-columns: 1fr 120px;
  gap: 10px;
  align-items: stretch;
}
.input {
  width: 100%;
  height: 64px;
  resize: none;
  border-radius: 12px;
  padding: 10px 12px;
  border: 1px solid rgba(255, 255, 255, 0.12);
  background: rgba(0, 0, 0, 0.25);
  color: #e6e8ef;
  outline: none;
  min-width: 0;
}
.btn {
  border-radius: 10px;
  padding: 10px 12px;
  border: 1px solid rgba(255, 255, 255, 0.12);
  background: rgba(255, 255, 255, 0.06);
  color: #e6e8ef;
  cursor: pointer;
  z-index: 6;
}
.btn.primary {
  border-color: rgba(99, 102, 241, 0.7);
  background: rgba(99, 102, 241, 0.35);
}
.btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.error {
  margin-bottom: 8px;
  color: #fca5a5;
  font-size: 12px;
}
.ragPanel {
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid rgba(255, 255, 255, 0.08);
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-height: 0;
  flex-shrink: 0;
  max-height: 38vh;
  overflow: auto;
}
.ragTitle {
  font-weight: 600;
  font-size: 13px;
}
.ragHint {
  margin: 0;
  font-size: 11px;
  opacity: 0.65;
  line-height: 1.35;
}
.ragFileWrap {
  position: relative;
  display: inline-block;
}
.ragFileBtn {
  display: inline-block;
  font-size: 12px;
  padding: 8px 10px;
  border-radius: 8px;
  border: 1px solid rgba(255, 255, 255, 0.12);
  background: rgba(255, 255, 255, 0.06);
  cursor: pointer;
}
.ragFileInput {
  position: absolute;
  inset: 0;
  opacity: 0;
  cursor: pointer;
}
.ragUrlRow {
  display: flex;
  gap: 6px;
  align-items: center;
}
.ragUrlInput {
  flex: 1;
  min-width: 0;
  font-size: 12px;
  padding: 6px 8px;
  border-radius: 8px;
  border: 1px solid rgba(255, 255, 255, 0.12);
  background: rgba(0, 0, 0, 0.25);
  color: #e6e8ef;
}
.ragUrlBtn {
  flex-shrink: 0;
  padding: 6px 8px;
  font-size: 12px;
}
.ragEmpty {
  font-size: 12px;
  opacity: 0.55;
}
.ragList {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.ragItem {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
  padding: 8px;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.03);
  border: 1px solid rgba(255, 255, 255, 0.06);
}
.ragItemMain {
  min-width: 0;
}
.ragName {
  font-size: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ragMeta {
  font-size: 11px;
  opacity: 0.55;
  margin-top: 2px;
}
.ragDel {
  flex-shrink: 0;
  padding: 4px 8px;
  font-size: 11px;
}
</style>

