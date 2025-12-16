const statusDot = document.getElementById('statusDot');
const statusBadge = document.getElementById('connectionStatus');
const deviceInfo = document.getElementById('deviceInfo');
const nowEl = document.getElementById('now');
const tailTagInput = document.getElementById('tailTagInput');
const contentInput = document.getElementById('contentInput');
const weiboImagePathsInput = document.getElementById('weiboImagePathsInput');
const imagePreviewContainer = document.getElementById('imagePreviewContainer');
const douyinTailTagInput = document.getElementById('douyinTailTagInput');
const douyinContentInput = document.getElementById('douyinContentInput');

const kuaishouContentInput = document.getElementById('kuaishouContentInput');
const toastRoot = document.getElementById('toast-root');

// 清理JSON中的末尾逗号（支持每行末尾有逗号的格式）
function cleanJsonTrailingCommas(jsonStr) {
  // 移除数组和对象中最后一个元素后的逗号
  return jsonStr.replace(/,\s*([\]\}])/g, '$1');
}

// 解析抖音文案数组
function parseDouyinContentTemplates() {
  const contentText = douyinContentInput.value.trim();
  if (!contentText) return [];
  
  try {
    const cleanedJson = cleanJsonTrailingCommas(contentText);
    const parsed = JSON.parse(cleanedJson);
    if (Array.isArray(parsed)) {
      return parsed;
    }
    return [contentText];
  } catch (e) {
    // 如果不是JSON，则作为单条文案
    return [contentText];
  }
}

// 解析快手文案数组
function parseKuaishouContentTemplates() {
  const contentText = kuaishouContentInput.value.trim();
  if (!contentText) return [];
  
  try {
    const cleanedJson = cleanJsonTrailingCommas(contentText);
    const parsed = JSON.parse(cleanedJson);
    if (Array.isArray(parsed)) {
      return parsed;
    }
    return [contentText];
  } catch (e) {
    // 如果不是JSON，则作为单条文案
    return [contentText];
  }
}

const refreshBtn = document.getElementById('refreshStatus');
const saveConfigBtn = document.getElementById('saveConfig');
const publishBtn = document.getElementById('publishBtn');
const saveConfigBtnDouyin = document.getElementById('saveConfigDouyin');
const publishBtnDouyin = document.getElementById('publishBtnDouyin');
const saveConfigBtnKuaishou = document.getElementById('saveConfigKuaishou');
const publishBtnKuaishou = document.getElementById('publishBtnKuaishou');

// Tab switching
const tabButtons = document.querySelectorAll('.tab-btn');
const tabContents = document.querySelectorAll('.tab-content');
let currentPlatform = 'weibo'; // 默认是微博

tabButtons.forEach(btn => {
  btn.addEventListener('click', () => {
    const targetTab = btn.dataset.tab;
    
    // Remove active class from all tabs and contents
    tabButtons.forEach(b => b.classList.remove('active'));
    tabContents.forEach(c => c.classList.remove('active'));
    
    // Add active class to clicked tab and corresponding content
    btn.classList.add('active');
    document.getElementById(`${targetTab}-content`).classList.add('active');
    
    // 更新当前平台
    currentPlatform = targetTab;
    console.log('当前选中平台:', currentPlatform);
  });
});

function showToast(message, type = 'info') {
  console.log('showToast called:', message, type, 'toastRoot:', toastRoot);
  if (!toastRoot) {
    console.error('toast-root element not found!');
    return;
  }
  const el = document.createElement('div');
  el.className = `toast toast--${type}`;
  el.innerHTML = `
    <svg class="toast-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
      ${type === 'success'
        ? '<polyline points="20 6 9 17 4 12"></polyline>'
        : type === 'error'
        ? '<circle cx="12" cy="12" r="10"></circle><line x1="15" y1="9" x2="9" y2="15"></line><line x1="9" y1="9" x2="15" y2="15"></line>'
        : '<circle cx="12" cy="12" r="10"></circle><line x1="12" y1="16" x2="12" y2="12"></line><line x1="12" y1="8" x2="12.01" y2="8"></line>'}
    </svg>
    <div class="toast-message">${message}</div>
    <button class="toast-close" aria-label="关闭">
      &times;
    </button>
  `;

  const close = () => {
    if (!el.parentNode) return;
    el.style.opacity = '0';
    el.style.transform = 'translateX(20px)';
    setTimeout(() => {
      if (el.parentNode) {
        el.parentNode.removeChild(el);
      }
    }, 200);
  };

  el.querySelector('.toast-close')?.addEventListener('click', close);
  toastRoot.appendChild(el);
  console.log('Toast appended to DOM:', el);
  setTimeout(close, 3500);
}

function formatTime(timestamp) {
  if (!timestamp) return '-';
  const date = new Date(timestamp);
  return date.toLocaleString();
}


function setStatusUI(connected) {
  if (statusDot) {
    statusDot.classList.toggle('connected', connected);
  }
  if (statusBadge) {
    statusBadge.textContent = connected ? '已连接' : '未连接';
  }
}

// 格式化设备信息，只显示型号
function formatDeviceInfo(info) {
  if (!info) return '-';
  if (typeof info === 'string') return info;
  // 优先显示 model，否则显示 brand
  return info.model || info.brand || info.device || '-';
}

async function fetchStatus() {
  try {
    const res = await fetch('/api/status');
    const data = await res.json();
    const connected = Boolean(data.connected);
    setStatusUI(connected);
    if (deviceInfo) {
      deviceInfo.textContent = formatDeviceInfo(data.deviceInfo);
    }
  } catch (err) {
    console.error(err);
    setStatusUI(false);
    if (deviceInfo) {
      deviceInfo.textContent = '-';
    }
  }
}

async function loadConfig() {
  try {
    const res = await fetch('/api/config');
    const data = await res.json();
    tailTagInput.value = data.tailTag || '';
    
    // 加载微博文案内容（作为JSON数组显示）
    const contentTemplates = data.contentTemplates || [];
    contentInput.value = JSON.stringify(contentTemplates, null, 2);
    
    douyinTailTagInput.value = data.douyinTailTag || '';
    
    // 加载抖音文案内容（作为JSON数组显示）
    const douyinContentTemplates = data.douyinContentTemplates || [];
    douyinContentInput.value = JSON.stringify(douyinContentTemplates, null, 2);
    
    // 加载快手文案内容（作为JSON数组显示）
    const kuaishouContentTemplates = data.kuaishouContentTemplates || [];
    kuaishouContentInput.value = JSON.stringify(kuaishouContentTemplates, null, 2);
    
    // 加载微博图片路径（作为JSON数组显示）
    const imagePaths = data.weiboImagePaths || [];
    weiboImagePathsInput.value = JSON.stringify(imagePaths, null, 2);
    updateImagePreview(imagePaths);
  } catch (err) {
    console.error('加载配置失败', err);
    showToast('加载配置失败', 'error');
  }
}

async function saveConfig() {
  // 解析微博文案内容（支持JSON数组格式）
  let contentTemplates = [];
  const contentText = contentInput.value.trim();
  
  if (contentText) {
    try {
      // 尝试解析为JSON数组（支持末尾逗号）
      const cleanedJson = cleanJsonTrailingCommas(contentText);
      contentTemplates = JSON.parse(cleanedJson);
      if (!Array.isArray(contentTemplates)) {
        throw new Error('Not an array');
      }
    } catch (e) {
      // 如果不是JSON，则作为单条文案
      contentTemplates = [contentText];
    }
  }
  
  // 解析图片路径（支持JSON格式）
  let imagePaths = [];
  const imagePathsText = weiboImagePathsInput.value.trim();
  
  if (imagePathsText) {
    try {
      // 尝试解析为JSON数组（支持末尾逗号）
      const cleanedJson = cleanJsonTrailingCommas(imagePathsText);
      imagePaths = JSON.parse(cleanedJson);
      if (!Array.isArray(imagePaths)) {
        throw new Error('Not an array');
      }
    } catch (e) {
      // 如果不是JSON，则按行分割
      imagePaths = imagePathsText
        .split('\n')
        .map(line => line.trim())
        .filter(line => line.length > 0);
    }
  }
  
  const body = {
    tailTag: tailTagInput.value.trim(),
    contentTemplates: contentTemplates,
    douyinTailTag: douyinTailTagInput.value.trim(),
    douyinContentTemplates: parseDouyinContentTemplates(),
    kuaishouContentTemplates: parseKuaishouContentTemplates(),
    weiboImagePaths: imagePaths
  };
  try {
    const res = await fetch('/api/config', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    });
    const data = await res.json();
    showToast(data.message || '配置已保存', 'success');
    // 更新图片预览
    updateImagePreview(imagePaths);
  } catch (err) {
    showToast('保存失败: ' + err.message, 'error');
  }
}

async function publish() {
  try {
    const platform = currentPlatform; // 'weibo' 或 'douyin' 或 'kuaishou'
    let content;
    
    if (platform === 'weibo') {
      // 微博文案是数组，发布时传递整个数组
      const contentText = contentInput.value.trim();
      try {
        const cleanedJson = cleanJsonTrailingCommas(contentText);
        content = JSON.parse(cleanedJson);
        if (!Array.isArray(content)) {
          content = [contentText];
        }
      } catch (e) {
        content = [contentText];
      }
    } else if (platform === 'douyin') {
      // 抖音文案是数组，发布时传递整个数组
      content = parseDouyinContentTemplates();
    } else {
      // 快手文案也是数组，发布时传递整个数组
      content = parseKuaishouContentTemplates();
    }
    
    console.log('发布平台:', platform);
    console.log('发布内容:', content);
    
    const res = await fetch('/api/publish', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ 
        platform: platform,
        content: content 
      })
    });
    const data = await res.json();
    if (!res.ok) {
      throw new Error(data.message || '发布失败');
    }
    showToast(`${platform === 'weibo' ? '微博' : platform === 'douyin' ? '抖音' : '快手'}发布指令已发送`, 'success');
  } catch (err) {
    showToast(err.message, 'error');
  }
}

saveConfigBtn.addEventListener('click', saveConfig);
publishBtn.addEventListener('click', publish);
refreshBtn.addEventListener('click', fetchStatus);

// Douyin buttons use same save/publish functions
if (saveConfigBtnDouyin) {
  saveConfigBtnDouyin.addEventListener('click', saveConfig);
}
if (publishBtnDouyin) {
  publishBtnDouyin.addEventListener('click', publish);
}

// Kuaishou buttons use same save/publish functions
if (saveConfigBtnKuaishou) {
  saveConfigBtnKuaishou.addEventListener('click', saveConfig);
}
if (publishBtnKuaishou) {
  publishBtnKuaishou.addEventListener('click', publish);
}

// 图片预览功能
const IMAGE_BASE_URL = 'https://yxx-1251927313.image.myqcloud.com';

function updateImagePreview(imagePaths) {
  if (!imagePreviewContainer) return;
  
  imagePreviewContainer.innerHTML = '';
  
  if (!imagePaths || imagePaths.length === 0) {
    imagePreviewContainer.innerHTML = '<div class="image-preview-empty">暂无图片</div>';
    return;
  }
  
  imagePaths.forEach(path => {
    // 完整URL：基础URL + 路径（路径本身不含扩展名，自动添加）
    const fullUrl = `${IMAGE_BASE_URL}/${path}`;
    const item = document.createElement('div');
    item.className = 'image-preview-item';
    
    const img = document.createElement('img');
    img.src = fullUrl;
    img.alt = path;
    img.onerror = () => {
      img.style.display = 'none';
      item.innerHTML = '<div class="image-preview-empty">加载失败</div>';
    };
    
    item.appendChild(img);
    imagePreviewContainer.appendChild(item);
  });
}

// 监听输入框变化，实时预览
if (weiboImagePathsInput) {
  weiboImagePathsInput.addEventListener('input', () => {
    let imagePaths = [];
    const imagePathsText = weiboImagePathsInput.value.trim();
    
    if (imagePathsText) {
      try {
        // 尝试解析为JSON数组（支持末尾逗号）
        const cleanedJson = cleanJsonTrailingCommas(imagePathsText);
        imagePaths = JSON.parse(cleanedJson);
        if (!Array.isArray(imagePaths)) {
          throw new Error('Not an array');
        }
      } catch (e) {
        // 如果不是JSON，则按行分割
        imagePaths = imagePathsText
          .split('\n')
          .map(line => line.trim())
          .filter(line => line.length > 0);
      }
    }
    
    updateImagePreview(imagePaths);
  });
}

setInterval(() => {
  nowEl.textContent = new Date().toLocaleString();
}, 1000);

fetchStatus();
loadConfig();
setInterval(fetchStatus, 5000);
