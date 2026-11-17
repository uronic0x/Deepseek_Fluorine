import {
  readCompletion,
  citationPoints,
  sourceList,
  usageText,
  buildLatestReminder,
  formatServerToolEvent,
  sanitizeHistoryMessage,
  slugifyAppName,
} from './core.js';

const $ = id => document.getElementById(id);

const appMeta = {
  appName: document.body?.dataset?.appName || 'app',
  displayName: document.body?.dataset?.displayName || document.body?.dataset?.appName || 'App',
  subtitle: document.body?.dataset?.subtitle || 'DeepSeek 本地工作台',
  runtime: null,
};

let mode = 'chat';
let history = [];
let reports = [];
let controller = null;
let busy = false;

function status(t) {
  $('status').textContent = t;
}

function keyState() {
  $('keyState').textContent = $('key').value.trim() ? '已填 Key' : '未连接';
}

function applyAppMeta() {
  $('brandName').textContent = appMeta.displayName;
  if (!history.length) {
    $('convTitle').textContent = appMeta.subtitle;
  }
  document.title = `${appMeta.displayName} · ${appMeta.subtitle}`;
}

async function loadMeta() {
  try {
    const r = await fetch('/api/meta');
    if (!r.ok) return;
    const m = await r.json();
    if (m.app_name) appMeta.appName = m.app_name;
    if (m.display_name) appMeta.displayName = m.display_name;
    if (m.subtitle) appMeta.subtitle = m.subtitle;
    appMeta.runtime = m.runtime || null;
    applyAppMeta();
  } catch {}
}

applyAppMeta();
loadMeta();

$('settingsOpen').onclick = () => $('settings').showModal();
$('key').addEventListener('input', keyState);

function syncWorkToggles() {
  const isWork = mode === 'work';
  $('localToolsWrap').hidden = !isWork;
  $('deviceToolsWrap').hidden = !isWork || !$('localTools').checked;
}

function setMode(m) {
  if (busy) return;
  mode = m;
  $('chatMode').setAttribute('aria-pressed', String(m === 'chat'));
  $('workMode').setAttribute('aria-pressed', String(m === 'work'));
  syncWorkToggles();
  $('modeHint').textContent =
    m === 'work'
      ? '计划 → 联网取证（可选）→ 本地工具/设备执行 → 审查'
      : '由模型按需搜索';
}

$('chatMode').onclick = () => setMode('chat');
$('workMode').onclick = () => setMode('work');
$('localTools').onchange = syncWorkToggles;

$('model').onchange = () => {
  if ($('model').value !== 'deepseek-flash') {
    $('web').checked = false;
    status('Pro 已关闭官方联网（仍可使用 Work 本地工具与设备能力）。');
  }
};

$('web').onchange = () => {
  if ($('web').checked && $('model').value !== 'deepseek-flash') {
    $('web').checked = false;
    status('官方联网请在设置中选择 Flash。');
  }
};

function lock(value) {
  busy = value;
  for (const id of [
    'send',
    'newChat',
    'chatMode',
    'workMode',
    'web',
    'localTools',
    'deviceTools',
    'settingsOpen',
  ]) {
    $(id).disabled = value;
  }
  $('stop').hidden = !value;
}

function message(role, text = '') {
  $('welcome').hidden = true;
  const box = document.createElement('article');
  box.className = 'message ' + role;
  const label = document.createElement('div');
  label.className = 'label';
  label.textContent = role === 'user' ? 'YOU' : role === 'error' ? '未完成' : appMeta.displayName;
  const answer = document.createElement('div');
  answer.className = 'answer';
  answer.textContent = text;
  box.append(label, answer);
  $('messages').append(box);
  return { box, answer };
}

function renderAnswer(target, raw) {
  const chars = Array.from(raw.content || '');
  let start = 0;
  target.replaceChildren();
  for (const c of citationPoints(raw)) {
    if (c.end_index > start) {
      target.append(document.createTextNode(chars.slice(start, c.end_index).join('')));
      start = c.end_index;
    }
    const a = document.createElement('a');
    a.className = 'citation';
    a.href = c.url;
    a.target = '_blank';
    a.rel = 'noopener noreferrer';
    a.title = c.title || c.url;
    a.textContent = `[${c.number}]`;
    target.append(a);
  }
  target.append(document.createTextNode(chars.slice(start).join('')));
}

function clipText(s, max = 72) {
  return s.length > max ? s.slice(0, max - 1) + '…' : s;
}

function extras(box, raw, usage, model) {
  if (raw.reasoning_content?.trim()) {
    const det = document.createElement('details');
    det.className = 'thinking';
    const sum = document.createElement('summary');
    sum.textContent = '思考过程';
    const pre = document.createElement('pre');
    pre.textContent = raw.reasoning_content.trim();
    det.append(sum, pre);
    box.insertBefore(det, box.querySelector('.answer'));
  }
  if (raw.local_tool_calls?.length) {
    const group = document.createElement('div');
    group.className = 'sources';
    group.setAttribute('aria-label', '本地工具调用');
    for (const tc of raw.local_tool_calls) {
      const tag = document.createElement('span');
      tag.className = 'tool-badge';
      tag.title = `${tc.name}(${tc.arguments}) → ${tc.result}`;
      tag.textContent = clipText(`${tc.name}(${tc.arguments}) → ${tc.result}`);
      group.append(tag);
    }
    box.append(group);
  }
  const sources = sourceList(raw);
  if (sources.length) {
    const group = document.createElement('div');
    group.className = 'sources';
    group.setAttribute('aria-label', '引用与调研来源');
    for (const [i, s] of sources.entries()) {
      const a = document.createElement('a');
      a.href = s.url;
      a.target = '_blank';
      a.rel = 'noopener noreferrer';
      a.textContent = `${i + 1}. ${s.title}`;
      group.append(a);
    }
    box.append(group);
  }
  const metaRow = document.createElement('div');
  metaRow.className = 'usageRow';
  const info = document.createElement('span');
  info.className = 'usage';
  info.textContent = usageText(usage, model);
  const copyBtn = document.createElement('button');
  copyBtn.type = 'button';
  copyBtn.className = 'quiet copyBtn';
  copyBtn.textContent = '复制';
  copyBtn.onclick = async () => {
    try {
      await navigator.clipboard.writeText(raw.content || '');
      copyBtn.textContent = '已复制';
      setTimeout(() => {
        copyBtn.textContent = '复制';
      }, 1500);
    } catch {
      status('剪贴板写入失败，请手动选择复制。');
    }
  };
  metaRow.append(info, copyBtn);
  box.append(metaRow);
}

async function run(stage, messages, config, target, localTools = false) {
  const progress = document.createElement('div');
  progress.className = 'progress';
  progress.textContent = '正在连接…';
  if (target) target.box.append(progress);
  try {
    const response = await fetch('/api/chat', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${config.key}`,
      },
      signal: controller.signal,
      body: JSON.stringify({
        stage,
        model: config.model,
        web: config.web,
        local_tools: localTools,
        device_tools: localTools && config.deviceTools,
        effort: config.effort,
        max_tokens: stage === 'plan' ? 1024 : stage === 'review' ? 2048 : config.budget,
        messages,
      }),
    });
    return await readCompletion(response, ({ message: raw, event, localEvent, thinking }) => {
      if (target) target.answer.textContent = raw.content;
      const webStatus = formatServerToolEvent(event);
      if (localEvent?.type === 'round_started') {
        progress.textContent = `本地执行第 ${localEvent.round}/${localEvent.max_rounds} 轮…`;
      } else if (localEvent?.type === 'call_started') {
        progress.textContent = `调用本地工具：${localEvent.name}(${clipText(localEvent.arguments || '', 48)})`;
        $('toolsText').textContent += `▶ ${localEvent.name}(${localEvent.arguments})\n`;
      } else if (localEvent?.type === 'call_completed') {
        progress.textContent = `已完成：${localEvent.name}`;
        $('toolsText').textContent += `✓ ${clipText(localEvent.result || '', 600)}\n\n`;
      } else if (webStatus) {
        progress.textContent = webStatus;
      } else if (thinking) {
        progress.textContent = '正在思考…';
      } else if (raw.content) {
        progress.textContent = '正在生成…';
      }
      if (stage === 'plan') $('planText').textContent = raw.content;
      if (stage === 'review') $('reviewText').textContent = raw.content;
    });
  } catch (error) {
    if (error.code === 'content_filter') {
      if (target) target.answer.replaceChildren();
      if (stage === 'plan') $('planText').textContent = '';
      if (stage === 'review') $('reviewText').textContent = '';
    }
    throw error;
  } finally {
    progress.remove();
  }
}

function step(n, text) {
  $('taskStatus').textContent = text;
  [...$('steps').children].forEach((el, i) => el.classList.toggle('active', i === n));
}

function currentPlatformLabel() {
  return appMeta.runtime?.is_android ? 'Android' : 'Web';
}

$('composer').onsubmit = async e => {
  e.preventDefault();
  if (busy) return;
  const prompt = $('prompt').value.trim();
  if (!prompt) return;
  const config = {
    key: $('key').value.trim(),
    model: $('model').value,
    web: $('web').checked,
    localTools: $('localTools').checked,
    deviceTools: $('deviceTools').checked,
    effort: $('effort').value,
    budget: Number($('budget').value),
  };
  if (!config.key) {
    $('settings').showModal();
    status('先填入 API Key，再发送。');
    return;
  }
  if (history.length > (mode === 'work' ? 105 : 114)) {
    status('会话已接近长度上限，请导出后新建会话。');
    return;
  }

  controller = new AbortController();
  lock(true);
  $('prompt').value = '';
  message('user', prompt);

  const sanitizedPrev = history.map(m =>
    m.role === 'assistant'
      ? sanitizeHistoryMessage(m, {
          keepServerToolCalls: mode === 'chat' && config.web && config.model === 'deepseek-flash',
        })
      : m
  );

  const pending = [
    ...sanitizedPrev,
    { role: 'latest_reminder', content: buildLatestReminder(new Date(), currentPlatformLabel()) },
    { role: 'user', content: prompt },
  ];

  let target = null;
  let executionCommitted = false;
  const report = {
    prompt,
    mode,
    model: config.model,
    effort: config.effort,
    web: config.web,
    localTools: config.localTools,
    deviceTools: config.deviceTools,
    plan: '',
    tools: '',
    review: '',
    answer: null,
    usage: [],
  };

  try {
    if (mode === 'work') {
      $('taskPanel').hidden = false;
      $('planText').textContent = '';
      $('toolsText').textContent = '';
      $('reviewText').textContent = '';
      $('planBox').open = true;

      step(0, '拆解目标');
      status('Work 1/4：生成计划（不联网）');
      const p = await run('plan', pending, config, null, false);
      report.plan = p.message.content;
      report.usage.push(p.usage);
      $('planText').textContent = report.plan;

      let executeHistory = [
        ...pending,
        sanitizeHistoryMessage(p.message),
        {
          role: 'user',
          content: '请按这份计划执行最初任务，交付结果；不要把计划当成已完成的事实。',
        },
      ];

      if (config.web && config.localTools && config.model === 'deepseek-flash') {
        step(1, '联网取证');
        status('Work 2/4：联网收集一手资料');
        const res = await run('research', executeHistory, config, null, false);
        report.usage.push(res.usage);
        // Strip server_tool_calls before transitioning into local custom-function execute loop
        executeHistory = [
          ...executeHistory,
          sanitizeHistoryMessage(res.message, { keepServerToolCalls: false }),
          {
            role: 'user',
            content:
              '请结合以上联网收集的事实，必要时调用本地计算或设备/工作区工具获取精确结果，生成最终交付正文。',
          },
        ];
      } else {
        step(1, config.web ? '并入执行阶段' : '跳过联网');
      }

      step(2, config.localTools ? '本地工具执行' : '执行与交付');
      status(config.localTools ? 'Work：本地工具执行与生成正文' : 'Work：生成交付正文');
      if (config.localTools) $('toolsBox').open = true;
      target = message('assistant');
      const result = await run('execute', executeHistory, config, target, config.localTools);
      report.answer = result.message;
      report.tools = $('toolsText').textContent;
      report.usage.push(result.usage);

      history = [
        ...executeHistory,
        sanitizeHistoryMessage(result.message, { keepServerToolCalls: false }),
      ];
      executionCommitted = true;
      renderAnswer(target.answer, result.message);
      extras(target.box, result.message, result.usage, config.model);

      step(3, '文本审查');
      status('Work：审查交付物（不独立联网）');
      const r = await run(
        'review',
        [
          ...history,
          {
            role: 'user',
            content:
              '请检查刚才的交付物，指出计算、工具结果、证据或完成度方面的缺口。不要假定计划已经全部完成。',
          },
        ],
        config,
        null,
        false
      );
      report.review = r.message.content;
      report.usage.push(r.usage);
      $('reviewText').textContent = report.review;
      $('reviewBox').open = true;
      step(-1, '已完成');
    } else {
      target = message('assistant');
      const result = await run('chat', pending, config, target, false);
      if (result.title) $('convTitle').textContent = result.title;
      report.answer = result.message;
      report.usage.push(result.usage);
      history = [
        ...pending,
        sanitizeHistoryMessage(result.message, {
          keepServerToolCalls: config.web && config.model === 'deepseek-flash',
        }),
      ];
      executionCommitted = true;
      renderAnswer(target.answer, result.message);
      extras(target.box, result.message, result.usage, config.model);
    }
    reports.push(report);
    status(mode === 'work' ? 'Work 完成 · 详细用量与记录见导出文件' : '完成 · 用量由上游返回');
  } catch (err) {
    const why =
      err.name === 'AbortError' ? '已停止本地接收；上游可能仍有已计费用量。' : err.message;
    if (target && !executionCommitted) {
      if (err.code === 'content_filter') target.answer.replaceChildren();
      target.box.classList.add('error');
      const note = document.createElement('p');
      note.textContent = '本轮未完成，未加入下一轮上下文。';
      target.box.append(note);
    }
    message('error', why);
    status(why);
    if (mode === 'work') step(-1, executionCommitted ? '执行完成，审查未完成' : '任务未完成');
    if (executionCommitted) {
      report.review += '\n审查未完成：' + why;
      reports.push(report);
      $('reviewText').textContent = report.review;
    } else {
      $('prompt').value = prompt;
    }
  } finally {
    controller = null;
    lock(false);
  }
};

$('stop').onclick = () => controller?.abort();

$('newChat').onclick = () => {
  if (busy) return;
  history = [];
  reports = [];
  $('convTitle').textContent = appMeta.subtitle;
  $('messages').replaceChildren();
  $('taskPanel').hidden = true;
  $('welcome').hidden = false;
  status('已新建会话 · 刷新页面会清除 Key');
};

$('discover').onclick = async () => {
  $('discover').disabled = true;
  const c = new AbortController();
  const timer = setTimeout(() => c.abort(), 20000);
  const lines = [];
  try {
    const metaResp = await fetch('/api/meta', { signal: c.signal });
    if (metaResp.ok) {
      const m = await metaResp.json();
      appMeta.runtime = m.runtime || null;
      const rt = m.runtime || {};
      lines.push(
        `本机 Harness：${rt.os || '?'}/${rt.arch || '?'} · Android=${!!rt.is_android} · Termux-API=${!!rt.termux_api_ready} · ADB=${!!rt.adb_ready} · Shizuku=${!!rt.shizuku_ready}`
      );
      lines.push(`工作区：${m.workspace || '-'}`);
    }
    const key = $('key').value.trim();
    if (!key) {
      lines.push('提示：尚未填写 API Key，跳过上游模型检测。');
      $('modelInfo').textContent = lines.join('\n');
      return;
    }
    const r = await fetch('/api/models', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${key}`,
      },
      body: '{}',
      signal: c.signal,
    });
    const v = await r.json();
    if (!r.ok) throw new Error(v.error?.message || `HTTP ${r.status}`);
    lines.push('Key 检测成功。上游返回：');
    for (const m of v.data || []) {
      lines.push(`${m.id} · context ${m.context_window ?? '1M'} · output ${m.max_output_tokens ?? '384K'}`);
    }
    $('modelInfo').textContent = lines.join('\n');
  } catch (err) {
    lines.push(err.name === 'AbortError' ? '检测超时，请稍后手动重试。' : err.message);
    $('modelInfo').textContent = lines.join('\n');
  } finally {
    clearTimeout(timer);
    $('discover').disabled = false;
  }
};

$('export').onclick = () => {
  if (!reports.length) {
    status('尚无已完成的回复可导出。');
    return;
  }
  const text = reports
    .map(
      r =>
        `# ${r.prompt}\n\n模式：${r.mode} | 模型：${r.model} | effort：${r.effort} | 联网：${r.web} | 本地工具：${r.localTools} | 设备/Shell：${r.deviceTools}\n` +
        (r.plan ? `\n## 计划\n\n${r.plan}\n` : '') +
        (r.tools ? `\n## 本地工具记录\n\n\`\`\`\n${r.tools.trim()}\n\`\`\`\n` : '') +
        `\n## 交付物\n\n${r.answer?.content || ''}\n` +
        `\n## 来源\n\n${sourceList(r.answer || {})
          .map(s => `- [${s.title.replace(/[\[\]]/g, '')}](${s.url})`)
          .join('\n')}\n` +
        (r.review ? `\n## 文本审查（非独立联网核查）\n\n${r.review}\n` : '') +
        `\n## 用量\n\n${r.usage.map((u, i) => `- 第 ${i + 1} 次：${usageText(u, r.model)}`).join('\n')}\n`
    )
    .join('\n---\n\n');
  const url = URL.createObjectURL(new Blob([text], { type: 'text/markdown;charset=utf-8' }));
  const a = document.createElement('a');
  a.href = url;
  a.download = `${slugifyAppName(appMeta.appName)}-report.md`;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
};
