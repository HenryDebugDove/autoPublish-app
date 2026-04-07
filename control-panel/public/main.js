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
  "前端教学，0基础从没写过代码也能学会。我会从最基础的标签开始讲，带你写第一个网页，慢慢过渡到JavaScript逻辑，再到Vue做项目。每学完一个小阶段就做个东西出来，比如登录页、购物车、后台管理。看得见成果才不会觉得枯燥，边做边学进步最快。",

  "前端收徒，专门收那些自学了一段时间还是懵的。你是不是看了好多视频，但让自己写个页面就无从下手？问题出在没有实战训练。我会带你从零开始敲一个完整的项目，每一步为啥这么写都给你讲清楚。项目做完你就有底气去投简历了，面试官问项目经历你也能说出东西来。",

  "0基础入门前端，我这边不搞一堆花里胡哨的课件。就实实在在告诉你：想找到工作需要学哪些，哪些可以暂时不碰。HTML、CSS、JS基础、Vue或者React选一个学透，再做两个项目，然后简历和面试题准备一下。按这个路子走，认真学两三个月就能出去试试了。",

  "前端教学，每天跟进不让你掉队。我会给你布置当天的学习任务，你看完视频把代码敲一遍发给我看。哪里写得不对我直接指出来，比你一个人闷头写效率高多了。每个周末做个阶段性小测试，看看你这周的东西是不是真掌握了。没掌握就停下来补，不赶进度。",

  "前端收徒，除了教技术还教你怎么接活。等你学完之后，我会告诉你上哪找一些简单的外包订单，比如企业官网、活动页面、小程序。你一边练手一边赚钱，不用等到找到工作才有收入。做过真实客户的项目之后，你的简历含金量也会高很多，面试官更喜欢这种。",

  "0基础带前端学员，适合那种报过培训班但还是找不到工作的。培训班的项目太模板化了，面试官一眼就能看出来。我带你做的项目是我自己接过或做过的真实业务，比如社区团购小程序、电商管理后台。做完之后简历上写的就是真实经历，面试时候聊细节你也不虚。",

  "前端教学，主打一个随问随答。你学的时候遇到任何问题，不管多基础，随时发消息问我。比如这个报错啥意思、这个效果咋实现、为啥我写的样式不生效。我都会当天回你，有时候直接写段代码给你看。不像有些地方问了半天没人理，学个习还憋一肚子气。",

  "前端收徒，从零开始把你带到能独立干活。我的带法分三步：第一步，给你学习路线和视频资源，你跟着学我盯进度；第二步，带你做两个完整项目，从搭环境到上线都走一遍；第三步，改简历、模拟面试、投简历技巧全教给你。三步走完你就不是小白了。",

  "0基础入门，别怕自己学历低或者年纪大。我带过的徒弟里有大专的、有转行的、有30多岁从零开始的。前端这行主要还是看你能不能干活，不是看你啥学校毕业的。只要你肯敲代码，遇到问题不放弃，我就有办法把你教会。之前带的一个大专生三个月就找到工作了。",

  "前端教学，教你用大白话理解技术概念。什么闭包、原型链、异步、响应式，我不用那种官方解释给你听，我拿生活中的例子打比方，让你一听就懂。懂了之后咱们再动手写，写的时候你就能理解为啥要这么写。很多人学不会不是因为笨，是因为没人给讲透。"
]

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
      <td>${template.length}</td>
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
