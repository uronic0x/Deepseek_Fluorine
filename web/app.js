import {
  DEFAULT_CHAT_SYSTEM_PROMPT,
  DEFAULT_WORK_SYSTEM_PROMPT,
  buildLatestReminder,
  buildUserContentBlocks,
  citationPoints,
  formatServerToolEvent,
  readCompletion,
  sanitizeHistoryMessage,
  slugifyAppName,
  sourceList,
  supportsMultimodal,
  usageText,
} from './core.js';

const $ = id => document.getElementById(id);

const appMeta = {
  appName: document.body?.dataset?.appName || 'app',
  displayName: document.body?.dataset?.displayName || document.body?.dataset?.appName || 'App',
  subtitle: document.body?.dataset?.subtitle || 'DeepSeek 本地工作台',
  runtime: null,
};

let mode = 'chat';
let controller = null;
let busy = false;
let useThinking = true;
let useWeb = true;
let drawerOpen = false;
let pdfPurpose = 'text_recognition';
let pendingAttachments = [];
let discoveredCapabilities = {};

// Separate Chat and Work sessions so mode switching never mixes turns in the same conversation.
const sessions = {
  chat: { history: [], reports: [], uiMessages: [], title: '' },
  work: { history: [], reports: [], uiMessages: [], title: '' },
};

function currentSession() {
  return sessions[mode];
}

function status(t) {
  $('status').textContent = t;
}

function keyState() {
  $('keyState').textContent = $('key').value.trim() ? '已填 Key' : '未连接';
}

function applyAppMeta() {
  $('brandName').textContent = appMeta.displayName;
  const sess = currentSession();
  $('convTitle').textContent = sess.title || appMeta.subtitle;
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

$('chatPrompt').value = DEFAULT_CHAT_SYSTEM_PROMPT;
$('workPrompt').value = DEFAULT_WORK_SYSTEM_PROMPT;
applyAppMeta();
loadMeta();

$('settingsOpen').onclick = () => $('settings').showModal();
$('key').addEventListener('input', keyState);
$('resetPromptsBtn').onclick = () => {
  $('chatPrompt').value = DEFAULT_CHAT_SYSTEM_PROMPT;
  $('workPrompt').value = DEFAULT_WORK_SYSTEM_PROMPT;
  status('已恢复默认 Chat / Work 系统提示词');
};

function currentModel() {
  return $('customModel').value.trim() || $('model').value;
}

function syncAttachmentCapabilities() {
  const model = currentModel();
  const supported = supportsMultimodal(model, discoveredCapabilities);
  $('attachToggle').classList.toggle('disabled', !supported);
  for (const id of ['pickCamera', 'pickPhoto', 'pickDocument']) {
    $(id).classList.toggle('disabled', !supported);
  }
  const notice = $('attachUnavailable');
  if (!supported) {
    notice.hidden = false;
    notice.textContent = `当前模型 (${model}) 不支持图片或文件上传；请切换为 deepseek-flash 或其他多模态模型。`;
  } else {
    notice.hidden = true;
    notice.textContent = '';
  }
  return supported;
}

function syncPills() {
  $('thinkToggle').setAttribute('aria-pressed', String(useThinking));
  $('webToggle').setAttribute('aria-pressed', String(useWeb));
}

function setMode(m) {
  if (busy || m === mode) return;
  mode = m;
  $('chatMode').setAttribute('aria-pressed', String(m === 'chat'));
  $('workMode').setAttribute('aria-pressed', String(m === 'work'));
  renderCurrentSession();
  status(`已切换至独立的 ${m === 'work' ? 'Work' : 'Chat'} 会话`);
}

$('chatMode').onclick = () => setMode('chat');
$('workMode').onclick = () => setMode('work');

$('thinkToggle').onclick = () => {
  if (busy) return;
  useThinking = !useThinking;
  syncPills();
};

$('webToggle').onclick = () => {
  if (busy) return;
  if (!useWeb && currentModel() === 'deepseek-v4-pro') {
    status('deepseek-v4-pro 默认不支持官方联网，可在模型中选择 deepseek-flash。');
    return;
  }
  useWeb = !useWeb;
  syncPills();
};

$('model').onchange = syncAttachmentCapabilities;
$('customModel').oninput = syncAttachmentCapabilities;

$('attachToggle').onclick = () => {
  if (!syncAttachmentCapabilities() && !drawerOpen) {
    status(`当前模型 (${currentModel()}) 暂不支持多模态上传`);
    drawerOpen = true;
  } else {
    drawerOpen = !drawerOpen;
  }
  $('attachmentDrawer').hidden = !drawerOpen;
  $('attachToggle').setAttribute('aria-expanded', String(drawerOpen));
  $('attachToggle').textContent = drawerOpen ? '×' : '＋';
};

function renderAttachmentStrip() {
  const strip = $('attachStrip');
  const addTile = $('addMoreTile');
  strip.replaceChildren(addTile);
  let hasDoc = false;
  for (const att of pendingAttachments) {
    if (att.kind === 'document') hasDoc = true;
    const chip = document.createElement('div');
    chip.className = 'attachChip';
    if (att.kind === 'image' && att.dataUrl) {
      const img = document.createElement('img');
      img.src = att.dataUrl;
      img.alt = att.name;
      chip.append(img);
    } else {
      const name = document.createElement('span');
      name.textContent = att.name.slice(0, 12);
      chip.append(name);
    }
    const rm = document.createElement('button');
    rm.type = 'button';
    rm.className = 'chipRemove';
    rm.textContent = '×';
    rm.onclick = () => {
      pendingAttachments = pendingAttachments.filter(x => x.id !== att.id);
      renderAttachmentStrip();
    };
    chip.append(rm);
    strip.append(chip);
  }
  $('pdfPurposeRow').hidden = !hasDoc;
}

document.querySelectorAll('#pdfPurposeRow button').forEach(btn => {
  btn.onclick = () => {
    pdfPurpose = btn.dataset.purpose;
    document.querySelectorAll('#pdfPurposeRow button').forEach(b => {
      b.setAttribute('aria-pressed', String(b === btn));
    });
  };
});

function guardMultimodalClick(inputId) {
  if (!syncAttachmentCapabilities()) {
    status(`当前模型 (${currentModel()}) 不支持文件或图片上传`);
    return;
  }
  $(inputId).click();
}

$('addMoreTile').onclick = () => guardMultimodalClick('photoInput');
$('pickCamera').onclick = () => guardMultimodalClick('cameraInput');
$('pickPhoto').onclick = () => guardMultimodalClick('photoInput');
$('pickDocument').onclick = () => guardMultimodalClick('docInput');

function readFileAsDataUrl(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result || ''));
    reader.onerror = () => reject(new Error('读取文件失败'));
    reader.readAsDataURL(file);
  });
}

async function handleSelectedImages(files) {
  for (const f of files) {
    if (f.size > 1500 * 1024) {
      status(`${f.name} 超过 Web 端单次请求体积限制，请压缩后再试`);
      continue;
    }
    const dataUrl = await readFileAsDataUrl(f);
    pendingAttachments.push({
      id: crypto.randomUUID(),
      kind: 'image',
      name: f.name,
      mimeType: f.type || 'image/jpeg',
      dataUrl,
    });
  }
  renderAttachmentStrip();
}

$('cameraInput').onchange = async e => {
  await handleSelectedImages([...(e.target.files || [])]);
  e.target.value = '';
};

$('photoInput').onchange = async e => {
  await handleSelectedImages([...(e.target.files || [])]);
  e.target.value = '';
};

$('docInput').onchange = async e => {
  const file = e.target.files?.[0];
  e.target.value = '';
  if (!file) return;
  if (file.size > 1400 * 1024) {
    status(`${file.name} 超过 1.4 MiB 限制`);
    return;
  }
  const dataUrl = await readFileAsDataUrl(file);
  const comma = dataUrl.indexOf(',');
  const base64 = comma >= 0 ? dataUrl.slice(comma + 1) : '';
  pendingAttachments.push({
    id: crypto.randomUUID(),
    kind: 'document',
    name: file.name,
    mimeType: file.type || 'application/octet-stream',
    dataBase64: base64,
    purpose: pdfPurpose,
  });
  renderAttachmentStrip();
};

function lock(value) {
  busy = value;
  for (const id of ['send', 'newChat', 'chatMode', 'workMode', 'settingsOpen', 'attachToggle']) {
    $(id).disabled = value;
  }
  $('stop').hidden = !value;
  $('send').hidden = value;
}

function appendMessageBox(role, text = '') {
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

function renderCurrentSession() {
  const sess = currentSession();
  $('messages').replaceChildren();
  $('taskPanel').hidden = mode !== 'work' || !sess.uiMessages.length;
  $('welcome').hidden = sess.uiMessages.length > 0;
  $('convTitle').textContent = sess.title || appMeta.subtitle;
  for (const item of sess.uiMessages) {
    const t = appendMessageBox(item.role, item.text || '');
    if (item.raw) {
      renderAnswer(t.answer, item.raw);
      extras(t.box, item.raw, item.usage, item.model);
    }
    if (item.warning) {
      const banner = document.createElement('div');
      banner.className = 'incompleteBanner';
      banner.textContent = item.warning;
      t.box.append(banner);
    }
  }
}

function readToolConfig() {
  const disabled = $('disabledTools')
    .value.split(',')
    .map(s => s.trim())
    .filter(Boolean);
  return {
    deterministic: $('toolDeterministic').checked,
    workspace_read: $('toolWorkspaceRead').checked,
    workspace_write: $('toolWorkspaceWrite').checked,
    git: $('toolGit').checked,
    shell_exec: $('toolShellExec').checked,
    device_control: $('toolDeviceControl').checked,
    disabled_tools: disabled,
  };
}

function currentPlatformLabel() {
  return appMeta.runtime?.is_android ? 'Android' : 'Web';
}

async function uploadPendingDocuments(config) {
  const resolved = [];
  for (const att of pendingAttachments) {
    if (att.kind !== 'document' || att.fileId) {
      resolved.push(att);
      continue;
    }
    status(`正在上传文档 ${att.name}…`);
    const resp = await fetch('/api/files', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${config.key}`,
      },
      signal: controller.signal,
      body: JSON.stringify({
        filename: att.name,
        mime_type: att.mimeType,
        purpose: att.purpose || pdfPurpose,
        data_base64: att.dataBase64,
        base_url: config.baseUrl,
      }),
    });
    const parsed = await resp.json();
    if (!resp.ok || !parsed.id) {
      throw new Error(parsed.error?.message || `上传文档失败: ${att.name}`);
    }
    resolved.push({ ...att, fileId: parsed.id });
  }
  return resolved;
}

$('composer').onsubmit = async e => {
  e.preventDefault();
  if (busy) return;
  const prompt = $('prompt').value.trim();
  if (!prompt && !pendingAttachments.length) return;

  const config = {
    key: $('key').value.trim(),
    baseUrl: $('baseUrl').value.trim(),
    model: currentModel(),
    web: useWeb,
    thinking: useThinking,
    effort: $('effort').value,
    budget: Number($('budget').value),
    maxRounds: Math.max(1, Math.min(50, Number($('maxRounds').value) || 20)),
    systemPrompt: (mode === 'work' ? $('workPrompt').value : $('chatPrompt').value).trim(),
    toolConfig: readToolConfig(),
  };

  if (!config.key) {
    $('settings').showModal();
    status('先填入 API Key，再发送。');
    return;
  }

  const sess = currentSession();
  controller = new AbortController();
  lock(true);

  let target = null;
  let attachmentsSnapshot = [...pendingAttachments];
  try {
    attachmentsSnapshot = await uploadPendingDocuments(config);
    pendingAttachments = [];
    renderAttachmentStrip();

    $('prompt').value = '';
    const userDisplay = attachmentsSnapshot.length
      ? `${prompt}${prompt ? '\n' : ''}[附件 ${attachmentsSnapshot.length} 个]`
      : prompt;
    appendMessageBox('user', userDisplay);
    sess.uiMessages.push({ role: 'user', text: userDisplay });

    const userContent = buildUserContentBlocks(prompt, attachmentsSnapshot);
    const keepRc = mode === 'work' || (config.web && config.model === 'deepseek-flash');
    const sanitizedPrev = sess.history.map(m =>
      m.role === 'assistant'
        ? sanitizeHistoryMessage(m, {
            keepServerToolCalls: mode === 'chat' && config.web && config.model === 'deepseek-flash',
            keepReasoningContent: keepRc,
          })
        : m
    );
    const pendingMsgs = [
      ...sanitizedPrev,
      { role: 'latest_reminder', content: buildLatestReminder(new Date(), currentPlatformLabel()) },
      { role: 'user', content: userContent },
    ];

    if (mode === 'work') {
      $('taskPanel').hidden = false;
      $('toolsText').textContent = '';
      $('taskStatus').textContent = '执行中…';
    }

    target = appendMessageBox('assistant');
    const progress = document.createElement('div');
    progress.className = 'progress';
    progress.textContent = '正在连接…';
    target.box.append(progress);

    try {
      const response = await fetch('/api/chat', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${config.key}`,
        },
        signal: controller.signal,
        body: JSON.stringify({
          stage: mode,
          model: config.model,
          base_url: config.baseUrl || undefined,
          use_web: config.web,
          use_thinking: config.thinking,
          local_tools: mode === 'work',
          tool_config: mode === 'work' ? config.toolConfig : undefined,
          max_rounds: config.maxRounds,
          system_prompt: config.systemPrompt || undefined,
          effort: config.effort,
          max_tokens: config.budget,
          messages: pendingMsgs,
        }),
      });

      const result = await readCompletion(
        response,
        ({ message: raw, event, localEvent, thinking }) => {
          target.answer.textContent = raw.content;
          const webStatus = formatServerToolEvent(event);
          if (localEvent?.type === 'round_started') {
            progress.textContent = `本地执行第 ${localEvent.round}/${localEvent.max_rounds} 轮…`;
            $('taskStatus').textContent = progress.textContent;
          } else if (localEvent?.type === 'call_started') {
            progress.textContent = `调用工具：${localEvent.name}(${clipText(localEvent.arguments || '', 48)})`;
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
        }
      );

      if (result.title) {
        sess.title = result.title;
        $('convTitle').textContent = result.title;
      }
      renderAnswer(target.answer, result.message);
      extras(target.box, result.message, result.usage, config.model);
      sess.uiMessages.push({
        role: 'assistant',
        raw: result.message,
        usage: result.usage,
        model: config.model,
      });
      sess.history = [
        ...pendingMsgs,
        sanitizeHistoryMessage(result.message, {
          keepServerToolCalls: mode === 'chat' && config.web && config.model === 'deepseek-flash',
          keepReasoningContent: mode === 'work',
        }),
      ];
      sess.reports.push({ prompt: userDisplay, mode, model: config.model, answer: result.message, usage: result.usage });
      if (mode === 'work') $('taskStatus').textContent = '已完成';
      status('完成 · 用量由上游返回');
    } finally {
      progress.remove();
    }
  } catch (err) {
    const why =
      err.name === 'AbortError' ? '已停止接收；已流出的内容已保留。' : err.message;
    // Non-destructive handling: NEVER wipe streamed content on content_filter, empty reply, or interruption.
    if (target) {
      const partial = err.partial;
      if (partial) {
        if (partial.content) renderAnswer(target.answer, partial);
        extras(target.box, partial, err.usage || null, config.model);
      }
      const banner = document.createElement('div');
      banner.className = 'incompleteBanner';
      banner.textContent = `⚠ ${why}`;
      target.box.append(banner);
      sess.uiMessages.push({
        role: 'assistant',
        raw: partial || { content: target.answer.textContent || '' },
        usage: err.usage || null,
        model: config.model,
        warning: `⚠ ${why}`,
      });
    } else {
      appendMessageBox('error', why);
      $('prompt').value = prompt;
      pendingAttachments = attachmentsSnapshot;
      renderAttachmentStrip();
    }
    if (mode === 'work') $('taskStatus').textContent = '未完成';
    status(why);
  } finally {
    controller = null;
    lock(false);
  }
};

$('stop').onclick = () => controller?.abort();

$('newChat').onclick = () => {
  if (busy) return;
  sessions[mode] = { history: [], reports: [], uiMessages: [], title: '' };
  renderCurrentSession();
  status(`已新建 ${mode === 'work' ? 'Work' : 'Chat'} 会话`);
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
    const nextCaps = {};
    for (const m of v.data || []) {
      if (m.id) nextCaps[m.id] = m;
      const mods = Array.isArray(m.input_modalities) ? m.input_modalities.join('/') : 'text';
      lines.push(`${m.id} · modalities=${mods} · context ${m.context_window ?? '1M'}`);
    }
    discoveredCapabilities = nextCaps;
    syncAttachmentCapabilities();
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
  const sess = currentSession();
  if (!sess.reports.length) {
    status('尚无已完成的回复可导出。');
    return;
  }
  const text = sess.reports
    .map(
      r =>
        `# ${r.prompt}\n\n模式：${r.mode} | 模型：${r.model}\n` +
        `\n## 交付物\n\n${r.answer?.content || ''}\n` +
        `\n## 用量\n\n- ${usageText(r.usage, r.model)}\n`
    )
    .join('\n---\n\n');
  const url = URL.createObjectURL(new Blob([text], { type: 'text/markdown;charset=utf-8' }));
  const a = document.createElement('a');
  a.href = url;
  a.download = `${slugifyAppName(appMeta.appName)}-${mode}-report.md`;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
};

syncPills();
syncAttachmentCapabilities();
