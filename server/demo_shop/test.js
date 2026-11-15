// 결제 버튼 회귀 테스트 (DOM 없이 실행: node test.js)
// index.html 의 결제 버튼을 실제로 "클릭"했을 때 상태 문구가 결제 완료로 바뀌는지 확인한다.
const fs = require('fs');
const vm = require('vm');
const path = require('path');

const html = fs.readFileSync(path.join(__dirname, 'index.html'), 'utf8');
const ids = [...html.matchAll(/id="([^"]+)"/g)].map((m) => m[1]);
const payId = (html.match(/<button[^>]*id="([^"]+)"[^>]*class="pay"/) || [])[1];

const els = {};
for (const id of ids) {
  els[id] = { id, textContent: '', className: '', listeners: {},
    addEventListener(type, fn) { (this.listeners[type] ||= []).push(fn); } };
}
els.status.textContent = '결제 전';
const document = { getElementById: (id) => els[id] || null };

vm.runInNewContext(fs.readFileSync(path.join(__dirname, 'app.js'), 'utf8'), { document, console });

const clicks = (els[payId].listeners.click || []);
clicks.forEach((fn) => fn({ type: 'click' }));

if (clicks.length === 0) {
  console.log('FAIL 결제 버튼(#' + payId + ')에 클릭 핸들러가 연결되어 있지 않습니다.');
  process.exit(1);
}
if (!/결제 완료/.test(els.status.textContent)) {
  console.log('FAIL 클릭 후 상태 문구가 바뀌지 않았습니다: ' + els.status.textContent);
  process.exit(1);
}
console.log('PASS 결제 버튼 클릭 → "' + els.status.textContent + '"');
