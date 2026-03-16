const statusDot = document.getElementById('statusDot');
const statusBadge = document.getElementById('connectionStatus');
const deviceInfo = document.getElementById('deviceInfo');
const nowEl = document.getElementById('now');
const tailTagInput = document.getElementById('tailTagInput');
const contentInput = document.getElementById('contentInput');
const weiboImagePathsInput = document.getElementById('weiboImagePathsInput');
const imagePreviewContainer = document.getElementById('imagePreviewContainer');
const douyinTailTagInput = document.getElementById('douyinTailTagInput');
const selectAllDouyin = document.getElementById('selectAllDouyin');
const contentCheckboxes = document.querySelectorAll('.content-checkbox');

// 抖音文案模板数据
const douyinContentTemplates =[
    "前端收徒教学，涵盖html、css、js、vue3、uni app、node、react及小程序技术。为你定制个性化学习计划，适配入门或实习人群，提供简历指导、面试辅导，学习无时间限制，你想学的前端技术都会逐一教学。",
    "9年前端开发经验，0基础带徒弟入行。适配大学生、转行人士、兴趣爱好者及培训后难就业人群，提供学习路线规划、实战项目指导、简历优化、面试模拟及入职后的技术答疑指导。",
    "9年开发经验，0基础前端学员一对一教学。从零基础到独立做项目，学习路线清晰，问题当天解决不卡壳，核心覆盖vue3、react、uniapp等当下主流前端开发技术栈。",
    "前端高薪养成计划，9年实战经验打造情景式学习，模拟真实工作场景。培养代码思维与问题解决能力，提供个性化学习路径、真实项目实战、简历包装及面试核心考点辅导。",
    "前端全栈开发实战营，从零基础到独立接外包，覆盖HTML5、CSS3、JavaScript、Vue3、React、TypeScript、Node.js技术栈。师徒制教学，结合真实外包项目实战，边学技术边赚收益。",
    "前端技术专家成长计划，9年大型项目经验，助力突破技术瓶颈。涵盖工程化、性能优化、架构设计、微前端、跨端技术，提供一对一技术规划与专业代码审查服务。",
    "前端技术变现大师课，9年经验亲授多维度变现路径。涵盖高单价外包、SaaS工具开发、内容创作、在线教育、技术顾问，分享精准获客与项目报价核心技巧。",
    "前端面试必胜班，9年面试官授课，30天拿下高薪offer。解析高频面试题、算法考点、项目包装技巧，配套多轮模拟面试、简历优化及面试心理辅导。",
    "前端技术跃迁营，专为2-5年经验开发者设计。覆盖TypeScript高级应用、性能优化、跨端开发、前端测试、CI/CD实践，结合企业级项目挑战与难点研讨。",
    "零基础前端闪电入门，3个月就业计划，科学学习路径。从HTML/CSS/JS核心到Vue3/React实战，参与真实商业项目，配套每日计划、24小时答疑及就业辅导。",
    "前端技术私享顾问，9年大型项目经验一对一解决技术难题。涵盖项目架构设计、技术栈选型、代码优化、性能突破，及个性化技术培训，适配独立开发者与自由职业者。"
];

// 动态渲染抖音文案表格
function renderDouyinContentTable() {
  const tableBody = document.getElementById('douyinContentTableBody');
  if (!tableBody) return;
  
  // 清空表格内容
  tableBody.innerHTML = '';
  
  // 遍历文案模板数组，生成表格行
  douyinContentTemplates.forEach((template, index) => {
    const row = document.createElement('tr');
    row.innerHTML = `
      <td><input type="checkbox" class="content-checkbox" data-index="${index}" /></td>
      <td>${index + 1}</td>
      <td>${template}</td>
    `;
    tableBody.appendChild(row);
  });
}

const kuaishouContentInput = document.getElementById('kuaishouContentInput');
const toastRoot = document.getElementById('toast-root');

// 清理JSON中的末尾逗号（支持每行末尾有逗号的格式）
function cleanJsonTrailingCommas(jsonStr) {
  // 移除数组和对象中最后一个元素后的逗号
  return jsonStr.replace(/,\s*([\]\}])/g, '$1');
}

// 解析抖音文案数组（从表格中获取选中的文案）
function parseDouyinContentTemplates() {
  const selectedTemplates = [];
  document.querySelectorAll('.content-checkbox:checked').forEach(checkbox => {
    const index = parseInt(checkbox.dataset.index);
    if (index >= 0 && index < douyinContentTemplates.length) {
      selectedTemplates.push(douyinContentTemplates[index]);
    }
  });
  return selectedTemplates;
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
    
    // 抖音文案从固定数组加载，不需要从配置文件读取
    // 但可以根据配置文件中的数据更新选中状态
    if (data.douyinContentTemplates && Array.isArray(data.douyinContentTemplates)) {
      // 清除所有选中状态
      document.querySelectorAll('.content-checkbox').forEach(checkbox => {
        checkbox.checked = false;
      });
      
      // 选中配置文件中存在的文案
      data.douyinContentTemplates.forEach(template => {
        const index = douyinContentTemplates.indexOf(template);
        if (index >= 0) {
          const checkbox = document.querySelector(`.content-checkbox[data-index="${index}"]`);
          if (checkbox) {
            checkbox.checked = true;
          }
        }
      });
    }
    
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

// 动态渲染抖音文案表格
renderDouyinContentTable();

// 抖音全选/取消全选功能
if (selectAllDouyin) {
  selectAllDouyin.addEventListener('change', function() {
    document.querySelectorAll('.content-checkbox').forEach(checkbox => {
      checkbox.checked = this.checked;
    });
  });
}

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

// 抖音配置按钮事件监听器
if (document.getElementById('saveConfigDouyin')) {
  document.getElementById('saveConfigDouyin').addEventListener('click', async () => {
    currentPlatform = 'douyin';
    await saveConfig();
  });
}

if (document.getElementById('publishBtnDouyin')) {
  document.getElementById('publishBtnDouyin').addEventListener('click', async () => {
    currentPlatform = 'douyin';
    await publish();
  });
}

setInterval(() => {
  nowEl.textContent = new Date().toLocaleString();
}, 1000);

fetchStatus();
loadConfig();
setInterval(fetchStatus, 5000);
