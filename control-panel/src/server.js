const path = require('path');
const fs = require('fs');
const http = require('http');
const os = require('os');
const express = require('express');
const cors = require('cors');
const WebSocket = require('ws');

const app = express();
const PORT = process.env.PORT || 4001;
const HEARTBEAT_TIMEOUT = 15000; // 15 seconds
const ROOT_DIR = path.join(__dirname, '..');
const PUBLIC_DIR = path.join(ROOT_DIR, 'public');
const CONFIG_PATH = path.join(ROOT_DIR, 'data', 'config.json');

app.use(cors());
app.use(express.json({ limit: '1mb' }));
app.use(
  express.static(PUBLIC_DIR, {
    setHeaders: (res, filePath) => {
      if (filePath.endsWith('.html')) {
        res.set('Content-Type', 'text/html; charset=utf-8');
      } else if (filePath.endsWith('.css')) {
        res.set('Content-Type', 'text/css; charset=utf-8');
      } else if (filePath.endsWith('.js')) {
        res.set('Content-Type', 'application/javascript; charset=utf-8');
      }
    }
  })
);
app.get('/', (_req, res) => {
  res.set('Content-Type', 'text/html; charset=utf-8');
  res.sendFile(path.join(PUBLIC_DIR, 'index.html'));
});

const status = {
  lastHeartbeat: null,
  isConnected: false,
  deviceInfo: null
};

function readConfig() {
  try {
    const raw = fs.readFileSync(CONFIG_PATH, 'utf-8');
    return JSON.parse(raw);
  } catch (err) {
    console.error('Failed to read config file:', err.message);
    return { 
      tailTag: '', 
      contentTemplates: [],
      douyinTailTag: '',
      douyinContentTemplates: [],
      kuaishouContentTemplates: [],
      weiboImagePaths: []
    };
  }
}

function writeConfig(newConfig) {
  fs.writeFileSync(CONFIG_PATH, JSON.stringify(newConfig, null, 2), 'utf-8');
}

// 检查是否有真正活跃的 WebSocket 连接
function hasActiveClients() {
  for (const client of clients) {
    if (client.readyState === WebSocket.OPEN) {
      return true;
    }
  }
  return false;
}

app.get('/api/status', (_req, res) => {
  const now = Date.now();
  const heartbeatAlive = status.lastHeartbeat && now - status.lastHeartbeat < HEARTBEAT_TIMEOUT;
  // 同时检查：有活跃的 WebSocket 连接 + 心跳未超时
  const reallyConnected = hasActiveClients() && heartbeatAlive;
  
  res.json({
    connected: reallyConnected,
    lastHeartbeat: status.lastHeartbeat,
    deviceInfo: status.deviceInfo,
    activeClients: clients.size  // 添加活跃客户端数量用于调试
  });
});

app.get('/api/config', (_req, res) => {
  res.json(readConfig());
});

app.post('/api/config', (req, res) => {
  const { tailTag, contentTemplates, douyinTailTag, douyinContentTemplates, kuaishouContentTemplates, weiboImagePaths } = req.body || {};
  if (typeof tailTag !== 'string') {
    return res.status(400).json({ message: 'tailTag is required.' });
  }
  const config = { 
    tailTag, 
    contentTemplates: Array.isArray(contentTemplates) ? contentTemplates : [],
    douyinTailTag: douyinTailTag || '',
    douyinContentTemplates: Array.isArray(douyinContentTemplates) ? douyinContentTemplates : [],
    kuaishouContentTemplates: Array.isArray(kuaishouContentTemplates) ? kuaishouContentTemplates : [],
    weiboImagePaths: Array.isArray(weiboImagePaths) ? weiboImagePaths : []
  };
  writeConfig(config);
  res.json({ message: 'Configuration saved.', config });
});

app.post('/api/publish', (req, res) => {
  const config = readConfig();
  const platform = req.body?.platform || 'weibo'; // 默认微博
  
  let payload;
  if (platform === 'douyin') {
    // 抖音发布
    const douyinContentTemplates = req.body?.content ?? config.douyinContentTemplates;
    payload = {
      type: 'publish_douyin',
      douyinTailTag: config.douyinTailTag,
      douyinContentTemplates: Array.isArray(douyinContentTemplates) ? douyinContentTemplates : [douyinContentTemplates],
      timestamp: Date.now()
    };
  } else if (platform === 'kuaishou') {
    // 快手发布
    const kuaishouContentTemplates = req.body?.content ?? config.kuaishouContentTemplates;
    payload = {
      type: 'publish_kuaishou',
      kuaishouContentTemplates: Array.isArray(kuaishouContentTemplates) ? kuaishouContentTemplates : [kuaishouContentTemplates],
      timestamp: Date.now()
    };
  } else {
    // 微博发布（默认）
    // content 可能是数组或字符串
    const contentTemplates = req.body?.content ?? config.contentTemplates;
    payload = {
      type: 'publish',
      tailTag: config.tailTag,
      contentTemplates: Array.isArray(contentTemplates) ? contentTemplates : [contentTemplates],
      timestamp: Date.now()
    };
  }

  if (!broadcastToClients(payload)) {
    return res.status(503).json({ message: 'No connected devices were able to receive the publish command.' });
  }

  res.json({ 
    message: `${platform === 'douyin' ? '抖音' : platform === 'kuaishou' ? '快手' : '微博'}发布请求已广播到连接的设备`, 
    payload 
  });
});

const server = http.createServer(app);
const wss = new WebSocket.Server({ server, path: '/ws' });
const clients = new Set();

wss.on('connection', (ws) => {
  console.log('WebSocket client connected.');
  clients.add(ws);
  status.isConnected = true;
  status.lastHeartbeat = Date.now();

  ws.on('message', (message) => {
    try {
      const data = JSON.parse(message.toString());
      handleClientMessage(ws, data);
    } catch (err) {
      console.warn('Failed to parse incoming WebSocket payload:', err.message);
    }
  });

  ws.on('close', () => {
    clients.delete(ws);
    evaluateConnectionState();
  });

  ws.on('error', (err) => {
    console.error('WebSocket error:', err.message);
  });
});

function handleClientMessage(ws, data) {
  switch (data.type) {
    case 'heartbeat':
      ws.isAlive = true;
      status.lastHeartbeat = Date.now();
      status.deviceInfo = data.deviceInfo || null;
      break;
    case 'register':
      status.deviceInfo = data.deviceInfo || null;
      break;
    case 'ack':
      console.log('Publish acknowledgement:', data.message || 'No message');
      break;
    default:
      console.log('Received unhandled message from client:', data);
  }
}

function broadcastToClients(payload) {
  let delivered = false;
  clients.forEach((client) => {
    if (client.readyState === WebSocket.OPEN) {
      client.send(JSON.stringify(payload));
      delivered = true;
    }
  });
  return delivered;
}

function evaluateConnectionState() {
  if (!clients.size) {
    status.isConnected = false;
    status.deviceInfo = null;
  }
}

setInterval(() => {
  clients.forEach((client) => {
    if (client.readyState !== WebSocket.OPEN) {
      clients.delete(client);
      return;
    }
    // 简化为仅依赖 ping/pong 与 lastHeartbeat，不再用 isAlive 标记强制断开
    client.ping();
  });

  const now = Date.now();
  if (!status.lastHeartbeat || now - status.lastHeartbeat > HEARTBEAT_TIMEOUT) {
    status.isConnected = false;
  } else {
    status.isConnected = true;
  }
}, 5000);

server.listen(PORT, () => {
  console.log(`Control panel server listening at http://localhost:${PORT}`);
  
  // 获取本地 IPv4 地址
  const networkInterfaces = os.networkInterfaces();
  const ipv4Addresses = [];
  
  Object.keys(networkInterfaces).forEach((interfaceName) => {
    networkInterfaces[interfaceName].forEach((iface) => {
      // 只获取 IPv4 且非内部地址
      if (iface.family === 'IPv4' && !iface.internal) {
        ipv4Addresses.push(iface.address);
      }
    });
  });
  
  if (ipv4Addresses.length > 0) {
    ipv4Addresses.forEach((ip) => {
      console.log(`                                http://${ip}:${PORT}/`);
    });
  }
});
