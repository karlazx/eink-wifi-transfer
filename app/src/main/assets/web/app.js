'use strict';
const $ = id => document.getElementById(id);
const queue = [];
let active = false, known = new Set();
const endpoint = name => '/api/file?name=' + encodeURIComponent(name);
const size = n => n < 1024 ? n + ' B' : n < 1048576 ? (n / 1024).toFixed(1) + ' KiB' : n < 1073741824 ? (n / 1048576).toFixed(1) + ' MiB' : (n / 1073741824).toFixed(2) + ' GiB';
function element(tag, text, cls) { const e = document.createElement(tag); if (text !== undefined) e.textContent = text; if (cls) e.className = cls; return e; }
async function api(url, options) {
  const response = await fetch(url, Object.assign({cache:'no-store'}, options));
  if (!response.ok) { let message = '请求失败（' + response.status + '）'; try { message = (await response.json()).error || message; } catch (_) {} throw new Error(message); }
  return response.json();
}
async function refresh() {
  try {
    const files = await api('/api/files');
    known = new Set(files.map(f => f.name)); $('files').replaceChildren(); $('count').textContent = '(' + files.length + ')';
    if (!files.length) $('files').append(element('li', '还没有接收文件，选择文件开始上传。', 'hint'));
    for (const file of files) {
      const li = element('li'), row = element('div', undefined, 'row'), detail = element('div', undefined, 'details');
      detail.append(element('div', file.name, 'name'), element('div', size(file.size), 'meta'));
      const actions = element('div', undefined, 'actions'), download = element('a', '下载', 'download'), del = element('button', '删除');
      download.href = endpoint(file.name); download.download = file.name;
      del.onclick = async () => {
        if (!confirm('删除“' + file.name + '”？文件将从阅读器的 EInkTransfer 目录移除。')) return;
        del.disabled = true;
        try { await api(endpoint(file.name), {method:'DELETE'}); await refresh(); } catch (e) { $('message').textContent = e.message; $('message').className = 'error'; }
        finally { del.disabled = false; }
      };
      actions.append(download, del); row.append(detail, actions); li.append(row); $('files').append(li);
    }
    $('message').textContent = '文件列表已更新'; $('message').className = ''; return files;
  } catch (e) {
    $('message').textContent = '无法连接阅读器或读取列表。请确认同一 Wi-Fi、打开阅读器传输界面后刷新。列表可能不是最新。';
    $('message').className = 'error'; throw e;
  }
}
function status(item, message, cls) { item.label.textContent = message; item.label.className = 'status ' + (cls || ''); }
$('picker').onchange = event => {
  for (const file of event.target.files) {
    const li = element('li'), row = element('div', undefined, 'row'), detail = element('div', undefined, 'details');
    const label = element('div', '等待', 'status'), cancel = element('button', '取消'), progress = element('progress'); progress.max = 100; progress.value = 0;
    detail.append(element('div', file.name, 'name'), element('div', size(file.size), 'meta'), label);
    row.append(detail, cancel); li.append(row, progress); $('queue').append(li);
    const item = {file, label, cancel, progress, state:'waiting'};
    if (file.size > 5368709120) { item.state = 'failed'; status(item, '超过单文件上限 5 GiB', 'error'); cancel.disabled = true; }
    else if (known.has(file.name) || queue.some(i => i.file.name === file.name && ['waiting','uploading'].includes(i.state))) {
      item.state = 'failed'; status(item, '文件已存在或已在队列中，未覆盖', 'error'); cancel.disabled = true;
    }
    cancel.onclick = () => {
      if (item.state === 'waiting') { item.state = 'cancelled'; status(item, '已取消'); cancel.disabled = true; }
      else if (item.xhr) { item.cancelled = true; cancel.disabled = true; status(item, '正在确认取消结果…'); item.xhr.abort(); }
    };
    queue.push(item);
  }
  $('queue-empty').hidden = true; event.target.value = ''; pump();
};
async function pump() {
  if (active) return; active = true;
  try {
    for (;;) {
      const item = queue.find(i => i.state === 'waiting'); if (!item) break;
      item.state = 'uploading'; status(item, '正在上传…');
      const result = await new Promise(resolve => {
        const xhr = new XMLHttpRequest(); item.xhr = xhr; xhr.open('PUT', endpoint(item.file.name)); xhr.timeout = 30 * 60 * 1000;
        xhr.upload.onprogress = e => {
          if (item.cancelled) return;
          if (e.lengthComputable) { item.progress.value = e.total === 0 ? 100 : Math.round(e.loaded / e.total * 100); status(item, e.loaded === e.total ? '正在保存…' : '上传中 ' + item.progress.value + '%'); }
        };
        xhr.onload = () => {
          if (xhr.status === 201) resolve({ok:true});
          else { let error = '上传失败（' + xhr.status + '）'; try { error = JSON.parse(xhr.responseText).error || error; } catch (_) {} resolve({error}); }
        };
        xhr.onerror = () => resolve({disconnected:true}); xhr.ontimeout = () => resolve({disconnected:true}); xhr.onabort = () => resolve({cancelled:true}); xhr.send(item.file);
      });
      item.xhr = null; item.cancel.disabled = true;
      if (result.ok) { item.state = 'success'; item.progress.value = 100; status(item, '上传成功', 'success'); known.add(item.file.name); }
      else if (result.cancelled) {
        // 先让对端处理断开；已完整提交的文件只报告完成，绝不回滚删除。
        await new Promise(r => setTimeout(r, 400));
        try {
          const files = await refresh();
          const done = files.some(f => f.name === item.file.name && f.size === item.file.size);
          item.state = done ? 'success' : 'cancelled'; status(item, done ? '文件已完成上传' : '已取消');
        } catch (_) { item.state = 'unknown'; status(item, '结果待确认，请恢复连接后刷新', 'error'); }
      } else { item.state = 'failed'; status(item, result.error || '连接中断，结果待确认，请恢复连接后刷新', 'error'); }
      if (result.disconnected || item.state === 'unknown') {
        for (const waiting of queue.filter(i => i.state === 'waiting')) { waiting.state = 'failed'; waiting.cancel.disabled = true; status(waiting, '连接已断开，请恢复后重新选择', 'error'); } break;
      }
      try { await refresh(); } catch (_) {}
    }
  } finally { active = false; }
}
$('refresh').onclick = () => refresh().catch(() => {});
refresh().catch(() => {});

// 全部文件采用独立只读接口，不复用接收目录的删除路径。
let storagePath = '', storageRequest = 0;
async function browseStorage(path) {
  const sequence = ++storageRequest;
  $('storage-message').textContent = '正在读取…';
  try {
    const files = await api('/api/storage?path=' + encodeURIComponent(path));
    if (sequence !== storageRequest) return;
    storagePath = path; $('storage-path').textContent = '共享存储 /' + path;
    $('storage-up').disabled = !path; $('storage-files').replaceChildren();
    for (const file of files) {
      const li = element('li'), row = element('div', undefined, 'row'), detail = element('div', undefined, 'details');
      detail.append(element('div', file.name, 'name'), element('div', file.directory ? '文件夹' : size(file.size), 'meta'));
      let action;
      if (file.directory) { action = element('button', '打开目录'); action.onclick = () => browseStorage(file.path); }
      else { action = element('a', '下载', 'download'); action.href = '/api/storage/file?path=' + encodeURIComponent(file.path); action.download = file.name; }
      row.append(detail, action); li.append(row); $('storage-files').append(li);
    }
    if (!files.length) $('storage-files').append(element('li', '此目录中没有可读取的文件。', 'hint'));
    $('storage-message').textContent = '只读 · ' + files.length + ' 项'; $('storage-message').className = '';
  } catch (e) {
    if (sequence !== storageRequest) return;
    $('storage-message').textContent = e.message + '。请检查连接或目录读取权限，当前列表可能不是最新。'; $('storage-message').className = 'error';
  }
}
function selectTab(all) {
  $('received-panel').hidden = all; $('all-panel').hidden = !all;
  $('tab-received').setAttribute('aria-selected', String(!all)); $('tab-all').setAttribute('aria-selected', String(all));
  if (all) browseStorage(storagePath);
}
$('tab-received').onclick = () => selectTab(false);
$('tab-all').onclick = () => selectTab(true);
$('storage-refresh').onclick = () => browseStorage(storagePath);
$('storage-up').onclick = () => browseStorage(storagePath.split('/').slice(0, -1).join('/'));
