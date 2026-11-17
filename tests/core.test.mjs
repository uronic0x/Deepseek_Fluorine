import test from 'node:test';
import assert from 'node:assert/strict';
import {
  accumulator,
  buildLatestReminder,
  citationPoints,
  estimateCostUsd,
  formatServerToolEvent,
  safeUrl,
  sanitizeHistoryMessage,
  slugifyAppName,
  sourceList,
} from '../web/core.js';

test('safeUrl blocks javascript and credential URLs', () => {
  assert.equal(safeUrl('https://api-docs.deepseek.com/updates'), 'https://api-docs.deepseek.com/updates');
  assert.equal(safeUrl('javascript:alert(1)'), null);
  assert.equal(safeUrl('https://user:pass@example.com'), null);
});

test('buildLatestReminder formats date, weekday, and platform', () => {
  const d = new Date(2026, 10, 17, 10, 0, 0); // 2026-11-17 Tuesday
  assert.equal(buildLatestReminder(d), '2026-11-17,Tuesday,Local,Web,Chinese');
  assert.equal(buildLatestReminder(d, 'Android'), '2026-11-17,Tuesday,Local,Android,Chinese');
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

  const keptWeb = sanitizeHistoryMessage(msg, { keepServerToolCalls: true });
  assert.equal(keptWeb.server_tool_calls.length, 1);
  assert.equal(keptWeb.reasoning_content, undefined);
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

test('accumulator surfaces insufficient_system_resource clearly', () => {
  const acc = accumulator();
  acc.push({
    choices: [{ delta: { content: 'partial' }, finish_reason: 'insufficient_system_resource' }],
  });
  acc.markDone();
  assert.throws(() => acc.result(), /推理资源暂时不足/);
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
