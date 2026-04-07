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
    "接编程项目，我干了9年了。Java、Python、Springboot、Vue这些技术栈都熟，管理系统、小程序、网站都能做。你要是想搞个什么系统，或者有个项目不知道怎么下手，直接找我。从后端到前端，从数据库到部署上线，我一条龙帮你弄明白，代码写得清楚，功能加得明白，不整虚的。",
    "接编程项目，靠谱的来。1. 技术方面：Java、Python、Springboot、Vue、Flask都行；2. 项目类型：web系统、小程序、安卓app、爬虫、图像处理都能搞；3. 服务内容：代码讲解、功能添加、远程调试、项目部署都包了。我一个人做，不转手，质量你放心，有啥不懂的直接问，给你讲透。",
    "接编程项目，又是一年。Python、Java、Springboot、Vue、Flask这些我都玩得转，从简单官网到复杂管理系统，从后端开发到前端页面，包括小程序、安卓app都没问题。你要是需要做深度学习、机器学习、大数据相关的，我也能接，代码写清楚，逻辑讲明白，不搞花架子。",
    "接编程项目，9年经验在这摆着。说说我能帮你啥：做网站（前端后端都能搞）、写小程序、开发安卓app、搞Python爬虫、做数据分析、弄图像处理。还有那种Springboot、Flask的项目，从零开始搭架子到上线运行，我都能一步步给你弄好，顺便把代码给你讲清楚。",
    "接编程项目，个人接单不是中介。我做Java开发、Python开发、安卓app、小程序、网站设计，还有机器学习、深度学习的项目也接过不少。技术栈就是Springboot、Vue、Flask这些。你要是写代码卡住了，或者项目做一半搞不定，找我聊聊，给你出出主意，帮你把功能加上。",
    "接编程项目，来简单说下我咋干活的：1. 先说技术——Java、Python、Springboot、Vue、Flask、爬虫、图像处理、大数据都行；2. 再说项目——管理系统、小程序、app、官网、游戏、可视化都能做；3. 最后说服务——代码指导、功能添加",
    "接编程项目，大白话跟你说。我会写Java、Python，Springboot和Vue做网站，Flask搞后端，还能做安卓app、小程序、爬虫、图像识别那些。你要做个管理系统、弄个官网、或者搞个数据可视化的大屏，我都能接。开发流程透明，做一步跟你讲一步",
    "接编程项目，9年了，啥类型都碰过。web系统、app应用、小程序、游戏、matlab编程、人机交互、大数据、图形学、可视化、云计算、算法实现……这些我都能搞。你是想做毕设还是自己练手，我都能带你做出来，代码讲到你懂为止，不藏着掖着，远程调试运行也方便。",
    "接编程项目，简单粗暴一点。你缺啥我补啥：缺后端，Java、Python、Springboot、Flask我都能写；缺前端，Vue给你整得明明白白；缺项目，管理系统、小程序、安卓app、官网都行；缺讲解，代码一行行给你说清楚。9年经验，一个人干，不转包，沟通顺畅，有问题随时说。",
    "接编程项目，来聊聊我能做啥。技术栈这块：Java、Python、Springboot、Vue、Flask、爬虫、图像处理、深度学习、机器学习都行。项目类型：管理系统、小程序、安卓app、网站设计、数据可视化、游戏开发、单片机都能搞。9年经验，服务内容就是代码指导、功能添加",
    "python教学，我干了9年了。说真的，报那种几千块的班真没必要，甩你一堆视频就不管了。我这边一对一，你哪儿卡住了随时问，从装环境开始讲，变量、循环、函数一步步带你写代码，不是光看不动手。学完能做爬虫、自动化办公、网站开发，想接单的话后期也能分单给你。",
    "python收徒，零基础完全ok。1. 先搭环境，教你配好Python和编辑器；2. 基础语法，变量、条件判断、循环、函数这些搞明白；3. 实战项目，爬虫抓数据、自动化操作Excel、做个简单网站；4. 进阶方向，数据分析、机器学习、web开发随便你选。一对一指导，有问题随时问，不走弯路。",
    "python教学，我就是那个帮你省钱的。机构收你几万块，其实就是给你看视频。我9年经验，带你从零开始写代码，遇到报错我帮你分析，卡住的地方我讲到你懂为止。办公自动化、爬虫、网页开发、人工智能这些都能教，想学什么方向你定，我陪你跑完全程。",
    "python收徒，来聊聊我为啥跟别人不一样。自学编程最怕啥？遇到问题没人问，一个小bug卡一两天。我这边一对一，你写代码我盯着，有问题立马解决。教的内容有爬虫、自动化脚本、网页搭建、AI接入，后期学成了还能带你接单，赚点零花钱。",
    "python零基础入门，9年程序员手把手带。不需要你有基础，只要肯动手敲代码就行。我教你的思路是：先学基础语法，再上手做项目。可以做数据分析可视化、写个爬虫、搞个自动化办公的小工具、或者搭个网站出来。学完你就能自己独立做东西了，不用再到处找教程。",
    "python教学，我这边主打一个实在。你想学啥方向我都能带：数据分析、机器学习、web开发、自动化测试、游戏开发、爬虫都行。学习路径给你规划好，资源给你准备好，有问题随时问。不像那些大课，群里几百号人根本轮不到你，我这儿就你一个，讲到你明白为止。",
    "python收徒，985程序员一对一指导。教的内容包括多线程爬虫、自动化操作手机和浏览器、协议逆向、前端后端搭建、服务器部署、AI接入方法。如果你是想学完接单的，后期我可以分单给你做。要求就一个：好学肯动手，懒的别来。",
    "python零基础到独立做项目，我带你一步步走。很多人学编程卡在哪儿？基础不牢就开始看框架，越学越懵。我这边先把基础语法讲透，变量、数据类型、函数、面向对象这些弄扎实了，再带你上框架做项目。Web开发、爬虫、数据分析，你想走哪个方向都行。",
    "python教学，我是认真带徒弟的那种。不是甩给你一堆视频就完事了，我陪你写代码、陪你调试、陪你上线。能教的内容有：环境搭建、基础语法、爬虫、办公自动化、数据分析可视化、机器学习入门、网页开发。9年经验，带过留学生也带过零基础小白，只要你肯学我就能教会。",
    "python收徒，简单说下我能帮你啥。想搞数据分析的，我教你pandas、matplotlib做可视化；想写爬虫的，我教requests、scrapy抓数据；想做网站的，我教Flask、Django搭后端；想搞自动化的，我教你脚本操作浏览器和手机。一对一指导，有问题随时问，学完能自己干活。",
    "前端教学，9年经验带0基础入行。很多人自学前端卡在哪儿？不知道从哪开始，学一堆用不上。我这边帮你规划路线，html、css、js先打基础，然后直接上vue或react做项目，边做边学。有问题当天解决，不让你卡壳。学完能独立做网站、小程序，简历和面试我也帮你搞定。",
    "前端收徒，零基础完全ok。1. 先学html+css，把页面结构样式搞明白；2. js基础，变量函数DOM操作这些；3. vue或react框架选一个，带你做2-3个真实项目；4. 小程序或uniapp再拓展一下。全程一对一，你写代码我盯着，有问题随时问，3个月左右能自己干活。",
    "前端教学，我就是那个帮你省钱的。培训机构收你一两万，其实就是甩视频给你看。我9年经验，一对一带着你，从零开始写代码，遇到报错我帮你分析，卡住的地方讲到你懂。教的内容有vue、react、小程序、uniapp，学完还帮你改简历、模拟面试，直到你找到工作。",
    "前端收徒，专门带0基础小白转行。前端这东西技术不难，难的是不知道怎么入门。我帮你把学习路径理清楚，今天学什么明天学什么安排得明明白白。你跟着视频学，我盯着进度，有疑问随时解答。学完能做管理系统、官网、小程序，面试题和简历我也帮你搞定。",
    "前端零基础入门，9年前端老司机手把手带。适合人群：大学生、想转行的、培训出来找不到工作的。我能帮你做这些：1. 定制学习路线；2. 带做实战项目；3. 改简历模拟面试；4. 整理高频面试题；5. 平时技术咨询；6. 入职后继续指导。全程陪跑，直到你能独立干活。",
    "前端教学，我这边主打一个实在。技术栈从html、css、js开始，到vue、react、uniapp、小程序、鸿蒙开发都能教。不管你是零基础还是想进阶，我都能带。学习计划按你情况来，有问题当天就解决，不拖不卡。学完简历面试一条龙服务，帮你顺利入行。",
    "前端收徒，9年经验带徒弟入行。跟机构不一样的是，我不甩视频给你就完事了。我每天跟进你的进度，今天学了什么、卡在哪儿了，我都知道。你写代码遇到bug，我帮你分析思路，教你怎么自己调试解决，而不是直接给你答案。这样学出来，你才是真的会写代码。",
    "前端教学，适合三类人：1. 完全零基础想转行的；2. 自学了好久还是懵的；3. 培训出来找不到工作的。我帮你做的事情很简单：规划学习路线、带做项目、改简历、模拟面试、入职后技术指导。9年经验，带过几十个学员，3个月左右能上岗，不信你试试。",
    "前端收徒，0基础到独立做项目。我不会让你天天对着视频发呆，而是带着你边学边写。先搭环境，写第一个页面，然后做小项目，慢慢上难度。vue、react、小程序、uniapp这些主流技术都会带你过一遍。学完能自己接单做网站、app，简历面试我也帮你搞定。",
    "前端教学，我干了9年前端，带过学生也带过实习生。说实话，自学前端最大的问题就是遇到bug没人问，一个小问题卡两天。我这边一对一，你写代码我陪着你，遇到问题马上解决，效率高太多了。学完还帮你改简历、模拟面试，让你顺利拿到offer。"
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
