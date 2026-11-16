// Dependency-free protocol layer shared by UI and Node tests.
export function safeUrl(raw) {
  try {
    const u = new URL(raw);
    return ['http:', 'https:'].includes(u.protocol) && !u.username && !u.password ? u.href : null;
  } catch {
    return null;
  }
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

  const prompt = u.prompt_tokens ?? u.input_tokens ?? 0;
  const hit = u.prompt_cache_hit_tokens ?? u.cache_read_input_tokens ?? 0;
  const miss = u.prompt_cache_miss_tokens ?? Math.max(0, prompt - hit);
  const out = u.completion_tokens ?? u.output_tokens ?? 0;
  const tools = u.server_tool_use || {};
  const webCost =
    ((tools.tool_search ?? 0) + (tools.tool_image_search ?? 0) + (tools.tool_fetch ?? 0)) *
    0.00075;

  const tokenCost = (hit * rates.hit + miss * rates.miss + out * rates.out) / 1_000_000;
  return tokenCost + webCost;
}

export function accumulator(onUpdate = () => {}) {
  const message = { role: 'assistant', content: '', reasoning_content: '' };
  let finish = null;
  let usage = null;
  let title = null;
  let done = false;

  function push(chunk) {
    if (chunk.error) throw new Error(chunk.error.message || '上游流式生成失败；已发生的用量仍可能计费。');
    if (chunk.usage) usage = chunk.usage;
    const choice = chunk.choices?.[0];
    if (!choice) {
      onUpdate({ message, usage, title, localEvent: chunk.local_tool_event });
      return;
    }
    const delta = choice.delta || {};
    if (typeof delta.content === 'string') message.content += delta.content;
    if (typeof delta.reasoning_content === 'string') message.reasoning_content += delta.reasoning_content;
    if (typeof delta.title === 'string' && delta.title) title = delta.title;
    for (const key of ['server_tool_calls', 'annotations', 'local_tool_calls']) {
      if (Array.isArray(delta[key])) message[key] = delta[key];
    }
    if (choice.server_tool_event?.type === 'step_reasoning_done') message.reasoning_content = '';
    if (choice.finish_reason) finish = choice.finish_reason;
    if (message.content.length + message.reasoning_content.length > 2 * 1024 * 1024) {
      throw new Error('单条回复过大，已停止接收。');
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
    result() {
      if (!done || !finish) throw new Error('事件流中断，回复未完成；请手动重试。');
      if (finish === 'content_filter') {
        const error = new Error('上游内容审查拒绝了本轮回复。');
        error.code = 'content_filter';
        throw error;
      }
      if (finish !== 'stop') {
        throw new Error(
          finish === 'length'
            ? '输出预算耗尽，当前回复未完成。请提高预算后重试。'
            : `异常结束状态：${finish}`
        );
      }
      if (!message.content.trim()) throw new Error('上游未返回正文；不会将思考内容冒充答案。');
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
      throw new Error('上游事件 JSON 格式错误。');
    }
    acc.push(v);
  }

  try {
    while (!terminal) {
      const { done, value } = await reader.read();
      buffer += done ? decoder.decode() : decoder.decode(value, { stream: true });
      if (buffer.length > 4 * 1024 * 1024) throw new Error('单个事件过大。');
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
  } finally {
    await reader.cancel().catch(() => {});
    reader.releaseLock();
  }
}

export function usageText(u, model = 'deepseek-flash') {
  if (!u) return '上游未返回完整用量';
  const tools = u.server_tool_use || {};
  const cost = estimateCostUsd(model, u);
  const costStr = cost !== null ? ` · 约 $${cost.toFixed(5)}` : '';
  return `输入 ${u.prompt_tokens ?? u.input_tokens ?? '—'} · 输出 ${u.completion_tokens ?? u.output_tokens ?? '—'} tokens · 搜索 ${tools.tool_search ?? 0} · 读页 ${tools.tool_fetch ?? 0}${costStr}`;
}
