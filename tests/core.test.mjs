import test from 'node:test';
import assert from 'node:assert/strict';
import {
  MAX_WEB_CHAT_BODY_BYTES,
  accumulator,
  buildLatestReminder,
  buildUserContentBlocks,
  citationPoints,
  estimateCostUsd,
  estimateInlineRequestBytes,
  formatServerToolEvent,
  normalizeToolRounds,
  safeUrl,
  sanitizeHistoryMessage,
  slugifyAppName,
  sourceList,
  supportsMultimodal,
  usageText,
  validateBaseUrl,
} from '../web/core.js';

test('tool round settings accept the expanded range and bound invalid input', () => {
  assert.equal(normalizeToolRounds(''), 50);
  assert.equal(normalizeToolRounds('bad'), 50);
  assert.equal(normalizeToolRounds(1), 1);
  assert.equal(normalizeToolRounds(120), 120);
  assert.equal(normalizeToolRounds(500), 500);
  assert.equal(normalizeToolRounds(501), 500);
  assert.equal(normalizeToolRounds(10.9), 10);
});

test('Work snapshots preserve commentary/tool ordering without duplicating final content', () => {
  const acc = accumulator();
  const tool = { round: 1, id: 'c1', name: 'calc', arguments: '{}', output: '20' };
  const first = { round: 1, content: '先计算。', thinking: '检查公式', local_tool_calls: [] };
  acc.push({ choices: [{ delta: { work_steps: [first] } }] });
  const beforeTool = acc.snapshot();
  first.local_tool_calls.push(tool);
  acc.push({ choices: [{ delta: { work_steps: [first], local_tool_calls: [tool] } }] });
  assert.equal(beforeTool.message.work_steps[0].local_tool_calls.length, 0);
  const final = { round: 2, content: '结果为 20。', local_tool_calls: [] };
  acc.push({ choices: [{ delta: { work_steps: [first, final] } }] });
  acc.push({ choices: [{ delta: { content: final.content }, finish_reason: 'stop' }] });
  acc.markDone();
  const { message } = acc.result();
  assert.deepEqual(message.work_steps.map(s => s.content), ['先计算。', '结果为 20。']);
  assert.equal(message.work_steps[0].local_tool_calls[0].id, 'c1');
  assert.equal(message.content, final.content);
  assert.equal(sanitizeHistoryMessage(message).work_steps, undefined);
});

test('tool-only partial Work output survives cancellation and retains final usage', () => {
  const acc = accumulator();
  const step = { round: 1, content: '', local_tool_calls: [{ id: 'c1', output: '20' }] };
  acc.push({ choices: [{ delta: { work_steps: [step] } }] });
  assert.throws(() => acc.push({
    error: { code: 'cancelled', message: 'Stopped' },
    usage: { prompt_tokens: 12 },
  }), err => {
    assert.equal(err.partial.work_steps[0].local_tool_calls[0].output, '20');
    assert.equal(err.usage.prompt_tokens, 12);
    return true;
  });
});

test('Work content is included in the existing response size budget', () => {
  const acc = accumulator();
  assert.throws(() => acc.push({ choices: [{ delta: {
    work_steps: [{ round: 1, content: 'x'.repeat(2 * 1024 * 1024) }],
  } }] }), err => err.code === 'size_limit');
});

test('safeUrl blocks javascript and credential URLs', () => {
  assert.equal(safeUrl('https://api-docs.deepseek.com/updates'), 'https://api-docs.deepseek.com/updates');
  assert.equal(safeUrl('javascript:alert(1)'), null);
  assert.equal(safeUrl('https://user:pass@example.com'), null);
});

test('buildLatestReminder formats date, Chinese weekday, platform, and language consistently with Android', () => {
  const d = new Date(2026, 10, 17, 10, 0, 0); // 2026-11-17 Tuesday
  assert.equal(buildLatestReminder(d), '2026-11-17,星期二,Local,Web,中文');
  assert.equal(buildLatestReminder(d, 'Android'), '2026-11-17,星期二,Local,Android,中文');
});

test('validateBaseUrl enforces HTTPS or loopback HTTP with port', () => {
  assert.equal(validateBaseUrl('https://api.deepseek.com/').url, 'https://api.deepseek.com');
  assert.equal(validateBaseUrl('http://127.0.0.1:8080/v1/').url, 'http://127.0.0.1:8080/v1');
  assert.equal(validateBaseUrl('http://localhost:3000').ok, true);
  assert.equal(validateBaseUrl('http://api.deepseek.com').ok, false);
  assert.equal(validateBaseUrl('http://127.0.0.1.evil.com:8080').ok, false);
  assert.equal(validateBaseUrl('https://user:pass@api.deepseek.com').ok, false);
  assert.equal(validateBaseUrl('https://api.deepseek.com?x=1').ok, false);
});

test('estimateInlineRequestBytes detects cumulative multi-image budget overflow', () => {
  const oneMbImage = { kind: 'image', dataUrl: 'data:image/jpeg;base64,' + 'A'.repeat(1_100_000) };
  const single = estimateInlineRequestBytes({ attachments: [oneMbImage], prompt: 'hi', history: [] });
  assert.ok(single < MAX_WEB_CHAT_BODY_BYTES);
  const pair = estimateInlineRequestBytes({ attachments: [oneMbImage, oneMbImage], prompt: 'hi', history: [] });
  assert.ok(pair > MAX_WEB_CHAT_BODY_BYTES);
});

test('slugifyAppName sanitizes app names for filenames', () => {
  assert.equal(slugifyAppName('My Harness App'), 'my-harness-app');
  assert.equal(slugifyAppName('澄明'), 'app');
});

test('sanitizeHistoryMessage strips response-only metadata and conditional server_tool_calls', () => {
  const msg = {
    role: 'assistant',
    content: 'answer',
    reasoning_content: 'hidden cot',
    annotations: [{ type: 'url_citation' }],
    local_tool_calls: [{ name: 'calc' }],
    server_tool_calls: [{ index: 0, calls: [] }],
  };
  const stripped = sanitizeHistoryMessage(msg, { keepServerToolCalls: false });
  assert.deepEqual(stripped, { role: 'assistant', content: 'answer' });

  const keptWeb = sanitizeHistoryMessage(msg, { keepServerToolCalls: true, keepReasoningContent: true });
  assert.equal(keptWeb.server_tool_calls.length, 1);
  assert.equal(keptWeb.reasoning_content, 'hidden cot');
  assert.equal(keptWeb.annotations, undefined);
});

test('formatServerToolEvent formats search and open_page events', () => {
  assert.equal(
    formatServerToolEvent({
      type: 'call_started',
      call: { action: { type: 'search', queries: ['DeepSeek V4.1', 'pricing'] } },
    }),
    '正在联网搜索：DeepSeek V4.1 · pricing'
  );
  assert.equal(
    formatServerToolEvent({
      type: 'call_started',
      call: { action: { type: 'open_page', url: 'https://api-docs.deepseek.com/' } },
    }),
    '正在读取页面：https://api-docs.deepseek.com/'
  );
});

test('citationPoints uses Unicode code points', () => {
  const msg = {
    content: '😀测试结果',
    annotations: [{ type: 'url_citation', url_citation: { url: 'https://example.com', title: 'Ex', start_index: 1, end_index: 5 } }],
  };
  const pts = citationPoints(msg);
  assert.equal(pts.length, 1);
  assert.equal(pts[0].number, 1);
});

test('accumulator merges indexed server_tool_calls and preserves reasoning across step_reasoning_done', () => {
  const acc = accumulator();
  acc.push({
    choices: [{
      delta: {
        reasoning_content: 'step 1',
        server_tool_calls: [{ index: 0, calls: [{ kind: 'search' }] }],
      },
      server_tool_event: { type: 'step_reasoning_done' },
    }],
  });
  acc.push({
    choices: [{
      delta: {
        reasoning_content: 'step 2',
        content: 'done',
        server_tool_calls: [{ index: 1, calls: [{ kind: 'read_link' }] }],
      },
      finish_reason: 'stop',
    }],
  });
  acc.markDone();
  const res = acc.result();
  assert.equal(res.message.server_tool_calls.length, 2);
  assert.equal(res.message.reasoning_content, 'step 1\nstep 2');
});

test('accumulator handles local_tool_calls and title', () => {
  const acc = accumulator();
  acc.push({
    choices: [{
      delta: {
        content: '结果是 14',
        title: '数学计算',
        local_tool_calls: [{ id: 'tc_1', name: 'calc', arguments: '{"expression":"2+3*4"}', result: '{"value":14}' }],
      },
      finish_reason: 'stop',
    }],
    usage: { prompt_tokens: 100, completion_tokens: 20 },
  });
  acc.markDone();
  const res = acc.result();
  assert.equal(res.title, '数学计算');
  assert.equal(res.message.local_tool_calls.length, 1);
  assert.equal(res.message.content, '结果是 14');
});

test('accumulator surfaces insufficient_system_resource clearly and keeps partial content', () => {
  const acc = accumulator();
  acc.push({
    choices: [{ delta: { content: 'partial' }, finish_reason: 'insufficient_system_resource' }],
  });
  acc.markDone();
  assert.throws(() => acc.result(), err => {
    assert.match(err.message, /推理资源暂时不足/);
    assert.equal(err.partial.content, 'partial');
    return true;
  });
});

test('accumulator preserves streamed content on content_filter', () => {
  const acc = accumulator();
  acc.push({
    choices: [{ delta: { reasoning_content: 'think', content: '已流出的正文' }, finish_reason: 'content_filter' }],
  });
  acc.markDone();
  assert.throws(() => acc.result(), err => {
    assert.equal(err.code, 'content_filter');
    assert.equal(err.partial.content, '已流出的正文');
    assert.equal(err.partial.reasoning_content, 'think');
    return true;
  });
});

test('supportsMultimodal and buildUserContentBlocks match model modalities', () => {
  assert.equal(supportsMultimodal('deepseek-flash'), true);
  assert.equal(supportsMultimodal('deepseek-v4-pro'), false);
  assert.equal(supportsMultimodal('custom-vl', { 'custom-vl': { input_modalities: ['text', 'image'] } }), true);
  assert.equal(supportsMultimodal('custom-txt', { 'custom-txt': { input_modalities: ['text'] } }), false);

  const blocks = buildUserContentBlocks('Analyze', [
    { kind: 'document', fileId: 'file_123' },
    { kind: 'image', dataUrl: 'data:image/png;base64,AAA' },
  ]);
  assert.equal(Array.isArray(blocks), true);
  assert.equal(blocks.length, 3);
  assert.deepEqual(blocks[0], { type: 'file', file_id: 'file_123' });
  assert.deepEqual(blocks[1], { type: 'image_url', image_url: { url: 'data:image/png;base64,AAA' } });
  assert.deepEqual(blocks[2], { type: 'text', text: 'Analyze' });
});

test('estimateCostUsd computes off-peak and peak rates', () => {
  const offPeak = new Date('2026-11-15T12:00:00Z'); // Sunday
  const peak = new Date('2026-11-16T02:00:00Z'); // Monday 02:00 UTC
  const u = { prompt_tokens: 1_000_000, prompt_cache_hit_tokens: 0, prompt_cache_miss_tokens: 1_000_000, completion_tokens: 1_000_000 };
  assert.ok(Math.abs(estimateCostUsd('deepseek-flash', u, offPeak) - 0.75) < 1e-9);
  assert.ok(Math.abs(estimateCostUsd('deepseek-flash', u, peak) - 1.5) < 1e-9);
});

test('sourceList deduplicates URLs', () => {
  const msg = {
    annotations: [
      { type: 'url_citation', url_citation: { url: 'https://a.example/1', title: 'A1' } },
      { type: 'url_citation', url_citation: { url: 'https://a.example/1', title: 'A1 dup' } },
    ],
  };
  assert.equal(sourceList(msg).length, 1);
});

test('usageText and estimateCostUsd separate actual context length from multi-round cumulative input', () => {
  const peak = new Date('2026-11-16T02:00:00Z'); // Monday 02:00 UTC
  const usage = {
    prompt_tokens: 1800,
    context_tokens: 2050,
    cumulative_prompt_tokens: 5200,
    completion_tokens: 450,
    prompt_cache_hit_tokens: 1600,
    prompt_cache_miss_tokens: 200,
    cumulative_cache_hit_tokens: 4000,
    cumulative_cache_miss_tokens: 1200,
  };
  const text = usageText(usage, 'deepseek-flash');
  assert.match(text, /上下文 2050 \(累计输入 5200\)/);
  assert.match(text, /输出 450 tokens/);

  const cost = estimateCostUsd('deepseek-flash', usage, peak);
  const expectedCost = (4000 * 0.006 + 1200 * 0.30 + 450 * 1.20) / 1_000_000;
  assert.ok(Math.abs(cost - expectedCost) < 1e-12);
});

