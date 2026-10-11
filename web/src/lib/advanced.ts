import { ApiError, apiUrl, getSession } from './api';

export interface AiProvider { id: string; name: string; baseUrl: string; model: string; apiKeyConfigured?: boolean; apiKey?: string }
export interface AiPrompt { id: string; name: string; systemPrompt: string }
export interface AiConfig { providers: AiProvider[]; prompts: AiPrompt[]; defaultProviderId: string; defaultPromptId: string }
export interface AssistantSkill { id: string; name: string; instructions: string; tools: string[]; enabled: boolean }
export interface SkillsConfig { version: number; skills: AssistantSkill[] }
export interface TtsEngine { id: string; name: string; baseUrl: string; model: string; voice: string; voices?: { id: string; name: string }[]; roleVoices?: Record<string, string>; apiKeyConfigured?: boolean; apiKey?: string }
export interface TtsConfig { engines: TtsEngine[]; defaultEngineId: string }
export interface ChatMessage { role: 'user' | 'assistant' | 'system' | 'tool'; content: string }
export interface ToolResult { name: string; arguments: unknown; result: unknown }
export interface AssistantSession { id: string; title: string; bookUrl?: string; updatedAt: number; status?: string; messages?: ChatMessage[]; toolResults?: ToolResult[] }
export interface ReadingCount { seconds: number; characters: number; sessions: number }
export interface ReadingStats { days: (ReadingCount & { date: string })[]; books: (ReadingCount & { bookUrl: string; bookName?: string; author?: string; days?: (ReadingCount & { date: string })[] })[]; totalSeconds: number; totalCharacters: number; totalSessions: number; timezone: string }
export const assistantTools = ['bookshelf_list', 'book_sources_list', 'book_search', 'book_chapters', 'book_content', 'reading_stats'];
export const assistantToolLabels: Record<string, string> = { bookshelf_list: '读取我的书架', book_sources_list: '读取书源信息', book_search: '搜索网络书籍', book_chapters: '读取书籍目录', book_content: '读取章节正文', reading_stats: '读取阅读统计' };

/** Omitted credentials preserve server secrets; explicit empty strings clear them. */
export function credentialDraft<T extends { id: string; apiKey?: string }>(value: T, input: string, clear: boolean): T {
  const result = { ...value }; delete result.apiKey;
  if (clear || input.trim()) result.apiKey = clear ? '' : input.trim();
  return result;
}

export async function postEventStream(path: string, data: unknown, onEvent: (event: string, data: Record<string, unknown>) => void, signal?: AbortSignal): Promise<void> {
  const response = await fetch(apiUrl(path), { method: 'POST', credentials: 'include', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(data), signal });
  if (!response.ok) throw new ApiError(`请求失败（HTTP ${response.status}）`);
  if (!response.headers.get('content-type')?.includes('text/event-stream') || !response.body) throw new ApiError('服务器未返回实时事件流');
  const reader = response.body.getReader(); const decoder = new TextDecoder(); let buffer = ''; let ended = false;
  const dispatch = (block: string) => {
    let event = 'message'; const lines: string[] = [];
    for (const line of block.split('\n')) { if (line.startsWith('event:')) event = line.slice(6).trim(); if (line.startsWith('data:')) lines.push(line.slice(5).replace(/^ /, '')); }
    if (!lines.length) return;
    let value: Record<string, unknown>;
    try { value = JSON.parse(lines.join('\n')); if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error(); } catch { throw new ApiError('服务器返回的实时数据格式错误'); }
    if (event === 'error') throw new ApiError(String(value.errorMsg || '实时请求失败'));
    onEvent(event, value); if (event === 'end') ended = true;
  };
  try {
    while (!ended) {
      const chunk = await reader.read(); buffer += decoder.decode(chunk.value, { stream: !chunk.done });
      // Normalize only complete lines, preserving a CR split across transport chunks.
      let boundary: number;
      while ((boundary = buffer.search(/\r?\n\r?\n/)) >= 0) { const delimiter = buffer.slice(boundary).match(/^\r?\n\r?\n/)![0]; dispatch(buffer.slice(0, boundary).replaceAll('\r\n', '\n')); buffer = buffer.slice(boundary + delimiter.length); if (ended) break; }
      if (chunk.done) { if (buffer.trim()) dispatch(buffer.replaceAll('\r\n', '\n')); break; }
    }
    if (!ended) throw new ApiError('实时连接在完成前中断，请重试');
  } finally { await reader.cancel().catch(() => {}); reader.releaseLock(); }
}

export function authenticatedResource(path: string): string {
  const url = new URL(path, new URL(apiUrl('')).origin); const session = getSession();
  for (const [key, value] of Object.entries({ accessToken: session.accessToken, secureKey: session.secureKey, userNS: session.userNS })) if (value) url.searchParams.set(key, value);
  return url.toString();
}

export async function mcpCall(method: string, params?: unknown): Promise<Record<string, unknown>> {
  const response = await fetch(apiUrl('mcp'), { method: 'POST', credentials: 'include', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ jsonrpc: '2.0', id: crypto.randomUUID(), method, ...(params ? { params } : {}) }) });
  if (!response.ok) throw new ApiError(`MCP 请求失败（HTTP ${response.status}）`);
  const value = await response.json(); if (value.error) throw new ApiError(value.error.message || 'MCP 请求失败');
  return value.result;
}
