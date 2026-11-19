// Dependency-free protocol layer shared by UI and Node tests.

export const DEFAULT_MAX_TOOL_ROUNDS = 50;
export const MAX_TOOL_ROUNDS = 500;
export const MAX_WEB_ATTACHMENTS = 5;
export const MAX_WEB_IMAGE_RAW_BYTES = 1400 * 1024;
export const MAX_WEB_DOC_RAW_BYTES = 1400 * 1024;
export const MAX_WEB_CHAT_BODY_BYTES = Math.floor(1.85 * 1024 * 1024);

export function normalizeToolRounds(value) {
  const n = Number(value);
  return Number.isFinite(n) && n > 0
    ? Math.max(1, Math.min(MAX_TOOL_ROUNDS, Math.floor(n)))
    : DEFAULT_MAX_TOOL_ROUNDS;
}

export const DEFAULT_CHAT_SYSTEM_PROMPT =
  '你是 Fluorine（DeepSeek 本地工作台），严谨、直接、重事实的中文技术与研究助手。优先给出可核验结论、关键依据、清晰推导与边界条件。数学公式请使用标准 LaTeX 语法（行内 $...$，独立公式块 $$...$$）。若证据不足或未联网核实，请明确说明不确定性，绝不编造来源、命令输出或实验数据。';

export const DEFAULT_WORK_SYSTEM_PROMPT =
  '你是 Fluorine（DeepSeek 本地工作台），具备自主规划、取证与本地执行能力的中文技术 Agent。没有固定死板的工作流步骤限制：请根据用户的具体目标，自行判断是否需要拆解计划、是否需要调用联网工具或当前已注入的本地工具（计算/统计/工作区读写/Git/Shell/设备控制）。简单问题直接回答或一步执行；复杂任务自主多轮调用工具验证后再汇总输出。数学公式请使用标准 LaTeX（$...$ 或 $$...$$）。';

export const DEFAULT_MODEL_CAPABILITIES = {
  'deepseek-flash': { id: 'deepseek-flash', input_modalities: ['text', 'image'] },
  'deepseek-v4-pro': { id: 'deepseek-v4-pro', input_modalities: ['text'] },
};

export function supportsMultimodal(modelId, discoveredCapabilities = {}) {
  const norm = String(modelId || '').trim();
  const info = discoveredCapabilities[norm] || DEFAULT_MODEL_CAPABILITIES[norm];
  if (info && Array.isArray(info.input_modalities) && info.input_modalities.length) {
    return info.input_modalities.some(m =>
      ['image', 'file', 'vision'].includes(String(m).toLowerCase())
    );
  }
  if (['deepseek-v4-pro', 'deepseek-reasoner', 'deepseek-chat'].includes(norm)) {
    return false;
  }
  return true;
}

export function buildUserContentBlocks(text, attachments = []) {
  const cleanText = String(text || '');
  if (!Array.isArray(attachments) || attachments.length === 0) {
    return cleanText;
  }
  const blocks = [];
  for (const att of attachments) {
    if (att?.fileId) {
      blocks.push({ type: 'file', file_id: att.fileId });
    } else if (att?.kind === 'image' && att?.dataUrl) {
      blocks.push({ type: 'image_url', image_url: { url: att.dataUrl } });
    }
  }
  if (cleanText.trim()) {
    blocks.push({ type: 'text', text: cleanText });
  }
  return blocks.length ? blocks : cleanText;
}

export function validateBaseUrl(raw) {
  const url = String(raw || '').trim().replace(/\/+$/, '');
  if (!url) return { ok: false, error: 'Base URL 不能为空' };
  if (url.length > 2048 || /[\u0000-\u0020\u007f?#@\\]/.test(url)) {
    return { ok: false, error: 'Base URL 包含非法字符（不允许凭据、查询参数、片段或空白字符）' };
  }
  let parsed;
  try {
    parsed = new URL(url);
  } catch {
    return { ok: false, error: 'Base URL 格式不正确' };
  }
  if (parsed.username || parsed.password || parsed.search || parsed.hash || parsed.pathname.includes('..')) {
    return { ok: false, error: 'Base URL 不允许包含凭据、查询参数、片段或 .. 路径' };
  }
  if (parsed.protocol === 'https:') {
    if (!parsed.hostname) return { ok: false, error: 'Base URL 缺少主机名' };
    return { ok: true, url };
  }
  if (parsed.protocol === 'http:') {
    const portNum = Number(parsed.port);
    if (
      (parsed.hostname === '127.0.0.1' || parsed.hostname === 'localhost') &&
      Number.isInteger(portNum) &&
      portNum >= 1 &&
      portNum <= 65535
    ) {
      return { ok: true, url };
    }
    return {
      ok: false,
      error: '明文 HTTP 仅允许本地回环地址 http://127.0.0.1:<port> 或 http://localhost:<port>',
    };
  }
  return {
    ok: false,
    error: 'Base URL 仅支持 https:// 或本地 http://127.0.0.1:<port> / http://localhost:<port>',
  };
}

export function estimateInlineRequestBytes({ attachments = [], prompt = '', history = [] } = {}) {
  const inlineImages = (attachments || [])
    .filter(a => a?.kind === 'image' && !a?.fileId && typeof a?.dataUrl === 'string')
    .reduce((sum, a) => sum + a.dataUrl.length, 0);
  const encoder = new TextEncoder();
  const promptBytes = encoder.encode(String(prompt || '')).length;
  const historyBytes = encoder.encode(JSON.stringify(history || [])).length;
  return inlineImages + promptBytes + historyBytes + 2048;
}

export function safeUrl(raw) {
  try {
    const u = new URL(raw);
    return ['http:', 'https:'].includes(u.protocol) && !u.username && !u.password ? u.href : null;
  } catch {
    return null;
  }
}

export function slugifyAppName(name = 'app') {
  const slug = String(name || 'app')
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9_-]+/g, '-')
    .replace(/^-+|-+$/g, '');
  return slug || 'app';
}

export function buildLatestReminder(date = new Date(), platform = 'Web') {
  const days = ['星期日', '星期一', '星期二', '星期三', '星期四', '星期五', '星期六'];
  const y = date.getFullYear();
  const m = String(date.getMonth() + 1).padStart(2, '0');
  const d = String(date.getDate()).padStart(2, '0');
  const weekday = days[date.getDay()];
  return `${y}-${m}-${d},${weekday},Local,${platform},中文`;
}

export function sanitizeHistoryMessage(
  msg,
  { keepServerToolCalls = false, keepReasoningContent = false } = {}
) {
  if (!msg || typeof msg !== 'object') return msg;
  const content = Array.isArray(msg.content)
    ? structuredClone(msg.content)
    : typeof msg.content === 'string'
      ? msg.content
      : '';
  const out = {
    role: msg.role,
    content,
  };
  if (
    msg.role === 'assistant' &&
    keepReasoningContent &&
    typeof msg.reasoning_content === 'string' &&
    msg.reasoning_content
  ) {
    out.reasoning_content = msg.reasoning_content;
  }
  if (
    msg.role === 'assistant' &&
    keepServerToolCalls &&
    Array.isArray(msg.server_tool_calls) &&
    msg.server_tool_calls.length
  ) {
    out.server_tool_calls = structuredClone(msg.server_tool_calls);
  }
  return out;
}

export function formatServerToolEvent(event) {
  if (!event || event.type !== 'call_started') return null;
  const action = event.call?.action || {};
  if (action.type === 'search' && Array.isArray(action.queries) && action.queries.length) {
    return `正在联网搜索：${action.queries.slice(0, 2).join(' · ')}`;
  }
  if ((action.type === 'open_page' || action.type === 'read_link') && action.url) {
    return `正在读取页面：${action.url}`;
  }
  if (action.type === 'find_in_page' && action.pattern) {
    return `正在页内查找：${action.pattern}`;
  }
  return `正在联网：${action.type || '检索'}`;
}

export function citationPoints(message) {
  const size = Array.from(message.content || '').length;
  return (message.annotations || [])
    .flatMap((a, index) => {
      const c = a.type === 'url_citation' ? a.url_citation : null;
      if (
        !c ||
        !Number.isInteger(c.start_index) ||
        !Number.isInteger(c.end_index) ||
        c.start_index < 0 ||
        c.start_index >= c.end_index ||
        c.end_index > size ||
        !safeUrl(c.url)
      ) {
        return [];
      }
      return [{ ...c, url: safeUrl(c.url), number: index + 1 }];
    })
    .sort((a, b) => a.end_index - b.end_index);
}

export function sourceList(message) {
  const found = new Map();
  const add = s => {
    const url = safeUrl(s?.url);
    if (url && !found.has(url)) found.set(url, { url, title: s.title || new URL(url).hostname });
  };
  for (const s of message.sources || []) add(s);
  for (const a of message.annotations || []) {
    if (a.type === 'url_citation') add(a.url_citation);
  }
  for (const step of message.server_tool_calls || []) {
    for (const call of step.calls || []) {
      for (const s of call.sources || []) add(s);
      if (call.page) add(call.page);
    }
  }
  return [...found.values()];
}

export function isPeakUtc(date = new Date()) {
  const day = date.getUTCDay(); // 0=Sun, 6=Sat
  if (day === 0 || day === 6) return false;
  const h = date.getUTCHours();
  return (h >= 1 && h < 4) || (h >= 6 && h < 10);
}

export function estimateCostUsd(model, u, date = new Date()) {
  if (!u) return null;
  const peak = isPeakUtc(date) ? 2 : 1;
  const rates =
    model === 'deepseek-v4-pro'
      ? { hit: 0.022 * peak, miss: 0.66 * peak, out: 1.98 * peak }
      : { hit: 0.003 * peak, miss: 0.15 * peak, out: 0.6 * peak };
  const prompt = u.cumulative_prompt_tokens ?? u.prompt_tokens ?? u.input_tokens ?? 0;
  const hit =
    u.cumulative_cache_hit_tokens ??
    u.prompt_cache_hit_tokens ??
    u.cache_read_input_tokens ??
    0;
  const miss =
    u.cumulative_cache_miss_tokens ??
    u.prompt_cache_miss_tokens ??
    Math.max(0, prompt - hit);
  const out = u.completion_tokens ?? u.output_tokens ?? 0;
  const tools = u.server_tool_use || {};
  const webCost =
    ((tools.tool_search ?? 0) + (tools.tool_image_search ?? 0) + (tools.tool_fetch ?? 0)) *
    0.00075;
  const tokenCost = (hit * rates.hit + miss * rates.miss + out * rates.out) / 1_000_000;
  return tokenCost + webCost;
}

function mergeServerToolCalls(existing, incoming) {
  if (!Array.isArray(existing)) return structuredClone(incoming);
  const byIndex = new Map();
  const unindexed = [];
  for (const item of existing) {
    if (Number.isInteger(item?.index)) byIndex.set(item.index, item);
    else unindexed.push(item);
  }
  for (const item of incoming) {
    if (Number.isInteger(item?.index)) byIndex.set(item.index, item);
    else unindexed.push(item);
  }
  const merged = [...byIndex.entries()]
    .sort((a, b) => a[0] - b[0])
    .map(([, v]) => v);
  return [...merged, ...unindexed];
}

export function accumulator(onUpdate = () => {}) {
  const message = { role: 'assistant', content: '', reasoning_content: '' };
  let finish = null;
  let usage = null;
  let title = null;
  let done = false;

  function makeIncompleteError(msgText, code = 'incomplete') {
    const error = new Error(msgText);
    error.code = code;
    error.finishReason = finish;
    error.partial = structuredClone(message);
    error.usage = usage;
    return error;
  }

  function push(chunk) {
    if (chunk.usage) usage = chunk.usage;
    if (chunk.error) {
      throw makeIncompleteError(
        chunk.error.message || '上游流式生成失败；已发生的用量仍可能计费。',
        chunk.error.code || 'upstream_stream_error'
      );
    }
    const choice = chunk.choices?.[0];
    if (!choice) {
      onUpdate({ message, usage, title, localEvent: chunk.local_tool_event });
      return;
    }
    const delta = choice.delta || {};
    if (typeof delta.content === 'string') message.content += delta.content;
    if (typeof delta.refusal === 'string' && delta.refusal) {
      message.content += (message.content ? '\n' : '') + delta.refusal;
    }
    if (typeof delta.reasoning_content === 'string') message.reasoning_content += delta.reasoning_content;
    if (typeof delta.title === 'string' && delta.title) title = delta.title;
    if (Array.isArray(delta.server_tool_calls)) {
      message.server_tool_calls = mergeServerToolCalls(
        message.server_tool_calls,
        delta.server_tool_calls
      );
    }
    if (Array.isArray(delta.annotations)) {
      message.annotations = [...(message.annotations || []), ...delta.annotations];
    }
    if (Array.isArray(delta.sources)) {
      message.sources = [...(message.sources || []), ...delta.sources];
    }
    if (Array.isArray(delta.work_steps)) {
      message.work_steps = structuredClone(delta.work_steps);
    }
    if (Array.isArray(delta.local_tool_calls)) {
      message.local_tool_calls = delta.local_tool_calls;
    }
    if (
      choice.server_tool_event?.type === 'step_reasoning_done' &&
      message.reasoning_content &&
      !message.reasoning_content.endsWith('\n')
    ) {
      message.reasoning_content += '\n';
    }
    if (choice.finish_reason) finish = choice.finish_reason;
    if (JSON.stringify(message).length > 2 * 1024 * 1024) {
      throw makeIncompleteError('单条回复过大，已停止接收。', 'size_limit');
    }
    onUpdate({
      message,
      usage,
      title,
      event: choice.server_tool_event,
      localEvent: chunk.local_tool_event,
      thinking: !!delta.reasoning_content,
    });
  }

  return {
    push,
    markDone() {
      done = true;
    },
    snapshot() {
      return { message: structuredClone(message), usage, title, finish };
    },
    result() {
      if (!done || !finish) {
        throw makeIncompleteError('事件流中断，回复未完成；已保留已流出的内容。', 'stream_interrupted');
      }
      if (finish === 'content_filter') {
        throw makeIncompleteError(
          '上游内容审查触发拒答（content_filter）；已保留已流出的正文。',
          'content_filter'
        );
      }
      if (finish !== 'stop') {
        const reasonMap = {
          length: '输出预算耗尽，当前回复未完成；已保留已流出的正文。',
          insufficient_system_resource: '上游推理资源暂时不足，请稍后手动重试。',
          aborted: '上游生成过程被中断；已保留已流出的正文。',
        };
        throw makeIncompleteError(reasonMap[finish] || `异常结束状态：${finish}`, finish);
      }
      if (!message.content.trim()) {
        throw makeIncompleteError('上游返回空正文（可能触发审核或输出为空）。', 'empty_response');
      }
      return { message: structuredClone(message), usage, title };
    },
  };
}

export async function readCompletion(response, onUpdate = () => {}) {
  if (!response.ok) {
    let msg = `请求失败 (${response.status})`;
    try {
      const v = await response.json();
      msg = v.error?.message || msg;
    } catch {}
    throw new Error(msg);
  }
  if (!response.headers.get('content-type')?.startsWith('text/event-stream')) {
    throw new Error('服务器没有返回 SSE 流。');
  }
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  const acc = accumulator(onUpdate);
  let buffer = '';
  let terminal = false;

  function processFrame(frame) {
    const data = frame
      .split(/\r?\n/)
      .filter(l => l.startsWith('data:'))
      .map(l => l.slice(5).replace(/^ /, ''))
      .join('\n');
    if (!data) return;
    if (data.trim() === '[DONE]') {
      acc.markDone();
      terminal = true;
      return;
    }
    let v;
    try {
      v = JSON.parse(data);
    } catch {
      const err = new Error('上游事件 JSON 格式错误。');
      err.partial = acc.snapshot().message;
      throw err;
    }
    acc.push(v);
  }

  try {
    while (!terminal) {
      const { done, value } = await reader.read();
      buffer += done ? decoder.decode() : decoder.decode(value, { stream: true });
      if (buffer.length > 4 * 1024 * 1024) {
        const err = new Error('单个事件过大。');
        err.partial = acc.snapshot().message;
        throw err;
      }
      let match;
      while ((match = /\r?\n\r?\n/.exec(buffer))) {
        const frame = buffer.slice(0, match.index);
        buffer = buffer.slice(match.index + match[0].length);
        processFrame(frame);
        if (terminal) break;
      }
      if (done) {
        if (buffer.trim()) processFrame(buffer);
        break;
      }
    }
    return acc.result();
  } catch (err) {
    if (!err.partial) {
      const snap = acc.snapshot();
      if (snap.message.content || snap.message.reasoning_content || snap.message.work_steps?.length) {
        err.partial = snap.message;
        err.usage = snap.usage;
      }
    }
    throw err;
  } finally {
    await reader.cancel().catch(() => {});
    reader.releaseLock();
  }
}

export function usageText(u, model = 'deepseek-flash') {
  if (!u) return '上游未返回完整用量';
  const prompt = u.prompt_tokens ?? u.input_tokens ?? '—';
  const out = u.completion_tokens ?? u.output_tokens ?? '—';
  const ctx =
    Number.isFinite(u.context_tokens) && u.context_tokens > 0
      ? ` · 上下文 ${u.context_tokens}${
          Number.isFinite(u.cumulative_prompt_tokens) &&
          Number.isFinite(prompt) &&
          u.cumulative_prompt_tokens > prompt
            ? ` (累计输入 ${u.cumulative_prompt_tokens})`
            : ''
        }`
      : '';
  const tools = u.server_tool_use || {};
  const cost = estimateCostUsd(model, u);
  const costStr = cost !== null ? ` · 约 $${cost.toFixed(5)}` : '';
  return `输入 ${prompt} · 输出 ${out} tokens${ctx} · 搜索 ${tools.tool_search ?? 0} · 读页 ${tools.tool_fetch ?? 0}${costStr}`;
}
