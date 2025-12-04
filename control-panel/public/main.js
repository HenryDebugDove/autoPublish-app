const statusBadge = document.getElementById('connectionStatus');
const statusText = document.getElementById('statusText');
const heartbeatText = document.getElementById('heartbeatText');
const deviceInfo = document.getElementById('deviceInfo');
const nowEl = document.getElementById('now');
const tailTagInput = document.getElementById('tailTagInput');
const contentInput = document.getElementById('contentInput');

const refreshBtn = document.getElementById('refreshStatus');
const saveConfigBtn = document.getElementById('saveConfig');
const publishBtn = document.getElementById('publishBtn');

function formatTime(timestamp) {
  if (!timestamp) return '-';
  const date = new Date(timestamp);
  return date.toLocaleString();
}

function setStatusUI(connected) {
  statusBadge.classList.toggle('status-connected', connected);
  statusBadge.classList.toggle('status-disconnected', !connected);
  statusBadge.textContent = connected ? '已连接' : '未连接';
  statusText.textContent = connected ? '已连接' : '未连接';
}

async function fetchStatus() {
  try {
    const res = await fetch('/api/status');
    const data = await res.json();
    const connected = Boolean(data.connected);
    setStatusUI(connected);
    heartbeatText.textContent = formatTime(data.lastHeartbeat);
    deviceInfo.textContent = data.deviceInfo ? JSON.stringify(data.deviceInfo) : '-';
  } catch (err) {
    console.error(err);
    setStatusUI(false);
    heartbeatText.textContent = '-';
  }
}

async function loadConfig() {
  try {
    const res = await fetch('/api/config');
    const data = await res.json();
    tailTagInput.value = data.tailTag || '';
    contentInput.value = data.contentTemplate || '';
  } catch (err) {
    console.error('加载配置失败', err);
  }
}

async function saveConfig() {
  const body = {
    tailTag: tailTagInput.value.trim(),
    contentTemplate: contentInput.value
  };
  try {
    const res = await fetch('/api/config', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    });
    const data = await res.json();
    alert(data.message || '配置已保存');
  } catch (err) {
    alert('保存失败: ' + err.message);
  }
}

async function publish() {
  try {
    const res = await fetch('/api/publish', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ content: contentInput.value })
    });
    const data = await res.json();
    if (!res.ok) {
      throw new Error(data.message || '发布失败');
    }
    alert('发布指令已发送');
  } catch (err) {
    alert(err.message);
  }
}

saveConfigBtn.addEventListener('click', saveConfig);
publishBtn.addEventListener('click', publish);
refreshBtn.addEventListener('click', fetchStatus);

setInterval(() => {
  nowEl.textContent = new Date().toLocaleString();
}, 1000);

fetchStatus();
loadConfig();
setInterval(fetchStatus, 5000);
