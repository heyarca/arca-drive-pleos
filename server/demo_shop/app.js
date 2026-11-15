// 데모 스토어: 결제 버튼
const TOTAL = 63000;

function formatWon(n) {
  return n.toLocaleString('ko-KR') + '원';
}

function pay() {
  const status = document.getElementById('status');
  status.textContent = '결제 완료 · ' + formatWon(TOTAL) + ' (테스트 결제)';
  status.className = 'status done';
}

const payButton = document.getElementById('payButton');
if (payButton) {
  payButton.addEventListener('click', pay);
}
