// 在隔离 DOM 与可控网络中测试真实网页脚本的队列和取消逻辑。
const test = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');

class Element {
  constructor(tag) { this.tag = tag; this.children = []; this.textContent = ''; this.disabled = false; }
  append(...children) { this.children.push(...children); }
  replaceChildren(...children) { this.children = children; }
  setAttribute(name, value) { this[name] = value; }
}
function setup() {
  const ids = Object.fromEntries(['picker', 'queue-empty', 'queue', 'count', 'files', 'message', 'refresh', 'storage-message', 'storage-path', 'storage-up', 'storage-files', 'received-panel', 'all-panel', 'tab-received', 'tab-all', 'storage-refresh'].map(id => [id, new Element(id)]));
  const files = new Map(), requests = [];
  let held = false, disconnected = false, deleteRequests = 0;
  const ctx = vm.createContext({
    document: {getElementById: id => ids[id], createElement: tag => new Element(tag)},
    setTimeout: (fn, _delay) => setTimeout(fn, 0),
    confirm: () => false,
    fetch: async (url, options) => {
      if (disconnected) throw new Error('offline');
      if (options.method === 'DELETE') { deleteRequests++; files.delete(decodeURIComponent(url.split('name=')[1])); }
      return {ok:true, json:async () => url === '/api/files' ? [...files].map(([name, size]) => ({name, size})) : url.startsWith('/api/storage?') ? [{name:'图书',path:'图书',directory:true,size:0},{name:'只读.txt',path:'只读.txt',directory:false,size:3}] : {ok:true}};
    },
    XMLHttpRequest: class {
      constructor() { this.upload = {}; requests.push(this); }
      open(method, url) { this.url = url; }
      send(file) { this.file = file; if (!held) setTimeout(() => this.complete(), 0); }
      complete() { if (this.aborted) return; this.upload.onprogress({lengthComputable:true, loaded:this.file.size, total:this.file.size}); files.set(this.file.name, this.file.size); this.status = 201; this.onload(); }
      abort() { this.aborted = true; this.onabort(); }
    }
  });
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../app/src/main/assets/web/app.js'), 'utf8'), ctx);
  return {
    ctx, ids, files, requests,
    pick: samples => ids.picker.onchange({target: {files:samples, value:'selected'}}),
    queue: () => vm.runInContext('queue', ctx),
    hold: value => held = value,
    offline: value => disconnected = value,
    deleted: () => deleteRequests
  };
}
const tick = () => new Promise(resolve => setTimeout(resolve, 25));

test('任意类型、多选串行、零字节、文件名作为文本渲染', async () => {
  const app = setup(); await tick();
  const samples = [{name:'<img onerror=alert(1)>.apk',size:12}, {name:'中文 空格 % # & +.unknown',size:8}, {name:'无扩展名',size:0}];
  app.pick(samples); await tick();
  assert.deepEqual(Array.from(app.queue(), item => item.state), ['success','success','success']);
  assert.equal(app.requests.length, 3);
  assert.equal(app.ids.queue.children[0].children[0].children[0].children[0].textContent, samples[0].name);
});

test('取消等待项和进行中项后继续下一项', async () => {
  const app = setup(); await tick(); app.hold(true);
  app.pick([{name:'first',size:65536}, {name:'waiting',size:1}, {name:'next',size:1}]);
  app.queue()[1].cancel.onclick(); app.hold(false); app.queue()[0].cancel.onclick(); await tick();
  assert.deepEqual(Array.from(app.queue(), item => item.state), ['cancelled','cancelled','success']);
});

test('取消和提交竞态保留已完成文件', async () => {
  const app = setup(); await tick(); app.hold(true);
  app.pick([{name:'committed',size:8}]); app.files.set('committed',8); app.queue()[0].cancel.onclick(); await tick();
  assert.equal(app.queue()[0].state,'success'); assert.equal(app.files.get('committed'),8); assert.equal(app.deleted(),0);
});

test('同名和同队列重名拒绝，不覆盖', async () => {
  const app = setup(); app.files.set('existing',7); await tick(); app.hold(true);
  app.pick([{name:'existing',size:3}, {name:'pending',size:3}, {name:'pending',size:4}]);
  assert.deepEqual(Array.from(app.queue(), item => item.state), ['failed','uploading','failed']);
  assert.equal(app.files.get('existing'),7); app.hold(false); app.requests[0].complete(); await tick();
});

test('连接中断停止剩余队列并提示恢复', async () => {
  const app = setup(); await tick(); app.hold(true);
  app.pick([{name:'one',size:3}, {name:'two',size:4}]); app.offline(true); app.requests[0].onerror(); await tick();
  assert.deepEqual(Array.from(app.queue(), item => item.state), ['failed','failed']);
  assert.match(app.queue()[1].label.textContent,/连接已断开/);
});

test('取消删除确认不发出 DELETE 请求', async () => {
  const app = setup(); app.files.set('keep',5); await tick();
  const deleteButton = app.ids.files.children[0].children[0].children[1].children[1];
  await deleteButton.onclick(); assert.equal(app.deleted(),0); assert.equal(app.files.get('keep'),5);
});

test('全部文件 tab 仅目录和下载，没有删除操作', async () => {
  const app = setup(); await tick(); app.ids['tab-all'].onclick(); await tick();
  assert.equal(app.ids['all-panel'].hidden, false);
  assert.equal(app.ids['received-panel'].hidden, true);
  assert.equal(app.ids['tab-all']['aria-selected'], 'true');
  const rows = app.ids['storage-files'].children;
  assert.equal(rows[0].children[0].children[1].textContent, '打开目录');
  const download = rows[1].children[0].children[1];
  assert.equal(download.tag,'a'); assert.equal(download.textContent,'下载');
  assert.match(download.href,/^\/api\/storage\/file\?path=/);
  assert.equal(app.deleted(),0);
  app.ids['tab-received'].onclick(); assert.equal(app.ids['all-panel'].hidden,true);
});

test('5 GiB 前端阈值：3 GiB 元数据允许，超过 5 GiB 拒绝', async () => {
  const app = setup(); await tick();
  app.pick([{name:'three-gib-size-only',size:3221225472},{name:'over-limit-size-only',size:5368709121}]); await tick();
  assert.deepEqual(Array.from(app.queue(), item => item.state), ['success','failed']);
  assert.match(app.queue()[1].label.textContent,/5 GiB/);
});
