/* ============================================================================
   town.js — 「AI Pocket Chat」世界系统 · 小镇屏网页渲染引擎
   契约：网页小镇前端施工契约 v1.0（数据驱动 + 桥协议）
   铁律：零网络请求 · 零硬编码城市布局（一切几何来自 init(townJson)）· 背景不闪白
   美术：温暖绘本 · 黄昏燃灯第一优先（canvas 程序化手绘，无外部资源）
   行数豁免：本文件为外部 AI 按契约交付的整体产物，机审验收后「原样入库」（契约 §8）、
   由前端承包方整文件维护——不按仓库行数标准强拆（拆分会破坏其交付/返修工作流）。
   ========================================================================= */
'use strict';
(function () {

/* ================= 0. 桥（页面 → App） ================= */
var BR = window.AndroidBridge || null;
function bridge(name, a, b) {
  if (window.__tlog) window.__tlog.push({ n: name, a: a === undefined ? null : a });
  try {
    if (BR && typeof BR[name] === 'function') {
      if (a === undefined) BR[name]();
      else if (b === undefined) BR[name](a);
      else BR[name](a, b);
    } else {
      /* 浏览器 / mock 环境降级：console.log */
      var show = (a === undefined) ? '' : (typeof a === 'string' ? a : JSON.stringify(a));
      if (b !== undefined) show += ' ' + b;
      console.log('[town→app]', name, show);
    }
  } catch (e) { console.error('[town→app]', name, e); }
}
addEventListener('error', function (e) {
  if (window.__townErrors) window.__townErrors.push(String((e && e.message) || (e && e.type) || 'error'));
  if (e && e.message) bridge('onError', String(e.message) + ' @' + (e.lineno || '?'));
  else if (e && e.target && e.target.tagName) bridge('onError', '资源加载失败: ' + String(e.target.tagName));
  var vv = document.getElementById('veil'); if (vv) vv.style.opacity = 0;
}, true);
addEventListener('unhandledrejection', function (e) {
  if (window.__townErrors) window.__townErrors.push('promise: ' + String((e && e.reason) || e));
  bridge('onError', 'promise: ' + String((e && e.reason) || e));
});

/* ================= 1. 工具 ================= */
/* mock 自测模式（?mock=1）：桥回调落 __tlog、错误落 __townErrors、姿态镜像 __lastPose，
   供自动化自测读取；App 真机路径完全不触碰这些钩子 */
var MOCK_MODE = /[?&]mock=1/.test(location.search);
if (MOCK_MODE) {
  var _dg = document.createElement('div');
  _dg.id = 'towndiag'; _dg.style.display = 'none';
  document.documentElement.appendChild(_dg);
  window.__tlog = [];        /* 桥回调流水（自动化自测读） */
  window.__townErrors = [];  /* 运行期错误流水 */
  window.__lastPose = null;  /* 最新姿态镜像 */
}
var PI = Math.PI;
function clamp(v, a, b) { return v < a ? a : (v > b ? b : v); }
function lerp(a, b, t) { return a + (b - a) * t; }
function smooth01(t) { t = clamp(t, 0, 1); return t * t * (3 - 2 * t); }
function easeOutCubic(t) { t = clamp(t, 0, 1); return 1 - Math.pow(1 - t, 3); }
function easeInOutCubic(t) { t = clamp(t, 0, 1); return t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2; }
/* 与窗火错峰同族的确定性哈希（装饰随机用，保证重建稳定） */
function hashXZ(x, z) {
  var s = Math.sin(Math.floor(x) * 12.9898 + Math.floor(z) * 78.233) * 43758.5453;
  return s - Math.floor(s);
}
function r4(v) { return Math.round(v * 10000) / 10000; }
function col3(a) { return new THREE.Color(clamp(a[0], 0, 1), clamp(a[1], 0, 1), clamp(a[2], 0, 1)); }

/* ================= 2. 渲染基座 ================= */
var renderer = new THREE.WebGLRenderer({ antialias: true });
renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2)); /* 契约：pixelRatio 上限 2 */
renderer.setSize(window.innerWidth, window.innerHeight);
renderer.outputEncoding = THREE.sRGBEncoding;
renderer.toneMapping = THREE.ACESFilmicToneMapping;
renderer.toneMappingExposure = 1.05;
renderer.shadowMap.enabled = true;
renderer.shadowMap.type = THREE.PCFSoftShadowMap;
renderer.domElement.id = 'gl';
document.body.appendChild(renderer.domElement);

/* GL 上下文丢失（显存吃紧 / GPU 进程重启）是唯一不抛 JS 错误的死法：three r147 内部只是停渲染，
   页面照常跑帧、控制台零错，用户看到的却是一屏黑。这里走契约 §2.2 既有的逃生口告诉 App，让它
   回落原生渲染器；浏览器若愿意恢复（three 自带 restored 重建），本页顺势自愈继续画。 */
var ctxLost = false;
renderer.domElement.addEventListener('webglcontextlost', function () {
  ctxLost = true;
  if (window.__townErrors) window.__townErrors.push('webgl context lost');
  bridge('onError', 'WebGL 上下文丢失');
}, false);
renderer.domElement.addEventListener('webglcontextrestored', function () {
  ctxLost = false; dirty = true;
}, false);

var scene = new THREE.Scene();
scene.fog = new THREE.Fog(0xE8B490, 34, 150);
/* 契约 §5：竖向 FOV = 0.85 rad（≈48.7°）——与 App 原生渲染器 TownMath.FOV 同值 */
var FOV_RAD = 0.85;
var camera = new THREE.PerspectiveCamera(FOV_RAD * 180 / PI, window.innerWidth / window.innerHeight, 0.1, 600);

var hemi = new THREE.HemisphereLight(0xD9A8B8, 0x8A6852, 0.75); scene.add(hemi);
var sun = new THREE.DirectionalLight(0xFFB070, 1.15);
sun.castShadow = true;
sun.shadow.mapSize.set(2048, 2048);
sun.shadow.camera.left = -30; sun.shadow.camera.right = 30;
sun.shadow.camera.top = 30; sun.shadow.camera.bottom = -30;
sun.shadow.camera.near = 5; sun.shadow.camera.far = 140;
sun.shadow.bias = -0.0004; sun.shadow.normalBias = 0.03;
scene.add(sun); scene.add(sun.target);

/* ================= 3. 程序化贴图（canvas 手绘水彩） ================= */
function cnv(w, h) { var c = document.createElement('canvas'); c.width = w; c.height = h; return [c, c.getContext('2d')]; }
function grain(x, w, h, n, alpha) {
  for (var i = 0; i < n; i++) {
    x.fillStyle = 'rgba(60,45,30,' + (Math.random() * alpha).toFixed(3) + ')';
    x.fillRect(Math.random() * w, Math.random() * h, 1 + Math.random() * 1.6, 1 + Math.random() * 1.6);
  }
}
function blotch(x, w, h, colors, count, rmin, rmax, alpha) {
  for (var i = 0; i < count; i++) {
    var cx = Math.random() * w, cy = Math.random() * h, r = rmin + Math.random() * (rmax - rmin);
    var col = colors[Math.floor(Math.random() * colors.length)];
    /* W-4：径向渐变改同心盘叠加（CanvasGradient 同任务内会静默失效·PITFALLS 1d·天空同配方） */
    glowStack(x, cx, cy, r, r, function () { return col; }, function (k) { return lerp(alpha, 0, k); }, 6);
  }
}
function T(c, repX, repY) {
  var cv = (c && c.length === 2 && c[0] && c[0].getContext) ? c[0] : c; /* cnv() 返回 [canvas, ctx] */
  var t = new THREE.CanvasTexture(cv);
  t.encoding = THREE.sRGBEncoding;
  t.anisotropy = Math.min(8, renderer.capabilities.getMaxAnisotropy());
  if (repX) { t.wrapS = t.wrapT = THREE.RepeatWrapping; t.repeat.set(repX, repY || repX); }
  return t;
}

/* 陶瓦鱼鳞瓦 */
var roofTex = (function () {
  var S = 512, c = cnv(S, S), x = c[1];
  x.fillStyle = '#D97B4C'; x.fillRect(0, 0, S, S);
  blotch(x, S, S, ['205,110,70', '225,140,95', '195,100,64'], 18, 40, 150, 0.12);
  var cols = ['#DD7E4C', '#E58F5A', '#CE6C40', '#DA8452', '#C4653C', '#DD7E4C'];
  var rows = 12, rh = S / rows, rw = 44;
  for (var r = 0; r < rows; r++) {
    var off = (r % 2) ? rw / 2 : 0;
    for (var i = -1; i < S / rw + 1; i++) {
      var cx = i * rw + off + rw / 2, cy = r * rh;
      x.fillStyle = cols[Math.floor(Math.random() * cols.length)];
      x.beginPath(); x.arc(cx, cy, rw / 2, 0, PI); x.fill();
      x.strokeStyle = 'rgba(120,56,34,.75)'; x.lineWidth = 2.5;
      x.beginPath(); x.arc(cx, cy, rw / 2, 0, PI); x.stroke();
      x.strokeStyle = 'rgba(255,225,190,.25)'; x.lineWidth = 1.5;
      x.beginPath(); x.arc(cx, cy - 2, rw / 2 - 4, 0.2 * PI, 0.8 * PI); x.stroke();
    }
  }
  /* 行间明暗 + 上下渐变：让鱼鳞瓦有瓦垄的体积感 */
  for (var rr2 = 0; rr2 < rows; rr2++) {
    x.fillStyle = rr2 % 2 ? 'rgba(70,30,16,.06)' : 'rgba(255,235,210,.05)';
    x.fillRect(0, rr2 * rh, S, rh);
  }
  /* W-4：上下渐变改逐行插值纯色（原 rgba(255,240,220,.10)→rgba(70,32,18,.12)） */
  for (var rgr = 0; rgr < S; rgr++) {
    var rgk = rgr / (S - 1);
    x.fillStyle = 'rgba(' + Math.round(lerp(255, 70, rgk)) + ',' + Math.round(lerp(240, 32, rgk)) + ',' +
      Math.round(lerp(220, 18, rgk)) + ',' + lerp(0.10, 0.12, rgk).toFixed(3) + ')';
    x.fillRect(0, rgr, S, 1);
  }
  grain(x, S, S, 1600, 0.06);
  return T(c, 2, 1);
})();
/* 抹灰墙（水彩晕染·近白，吃数据 tint；风化竖痕 + 底部动森式墙裙带） */
var wallTex = (function () {
  var S = 512, c = cnv(S, S), x = c[1];
  x.fillStyle = '#F2E9D6'; x.fillRect(0, 0, S, S);
  blotch(x, S, S, ['236,224,200', '240,230,208', '228,212,182', '232,218,192'], 22, 40, 170, 0.22);
  for (var si = 0; si < 22; si++) { /* 风化竖痕 */
    var sx2 = Math.random() * S, sw = 2 + Math.random() * 5, sh2 = 40 + Math.random() * 120;
    x.fillStyle = 'rgba(140,108,66,' + (0.03 + Math.random() * 0.05).toFixed(3) + ')';
    x.fillRect(sx2, Math.random() * (S - sh2), sw, sh2);
  }
  x.fillStyle = 'rgba(150,118,76,.10)'; x.fillRect(0, S * 0.86, S, S * 0.14);
  x.fillStyle = 'rgba(120,92,56,.16)'; x.fillRect(0, S * 0.86, S, 3);
  /* W-4：底部阴影带改逐行插值（0.7S..S 渐入 alpha 0→0.14·带外原本就是 0） */
  for (var shr = Math.floor(S * 0.7); shr < S; shr++) {
    x.fillStyle = 'rgba(120,90,50,' + (0.14 * (shr - S * 0.7) / (S * 0.3)).toFixed(3) + ')';
    x.fillRect(0, shr, S, 1);
  }
  grain(x, S, S, 1400, 0.05);
  return T(c, 1, 1);
})();
/* 草地（近白底·吃数据 ground tint；ACNH 式三级有机斑驳：大晕染→中斑块→细碎笔触+点缀） */
var groundTex = (function () {
  var S = 1024, c = cnv(S, S), x = c[1];
  x.fillStyle = '#F1F0DC'; x.fillRect(0, 0, S, S);
  blotch(x, S, S, ['214,226,196', '232,238,212', '222,232,200', '240,242,222'], 70, 60, 170, 0.16);
  blotch(x, S, S, ['206,220,182', '236,234,204', '224,230,194', '246,244,226'], 100, 24, 64, 0.15);
  x.strokeStyle = 'rgba(96,126,66,.13)'; x.lineWidth = 1.6;
  for (var i = 0; i < 2200; i++) {
    var gx = Math.random() * S, gy = Math.random() * S;
    x.beginPath(); x.moveTo(gx, gy); x.lineTo(gx + (Math.random() * 4 - 2), gy - 3 - Math.random() * 4); x.stroke();
  }
  x.strokeStyle = 'rgba(246,248,232,.16)'; x.lineWidth = 1.4;
  for (i = 0; i < 1100; i++) {
    gx = Math.random() * S; gy = Math.random() * S;
    x.beginPath(); x.moveTo(gx, gy); x.lineTo(gx + (Math.random() * 3 - 1.5), gy - 2 - Math.random() * 3); x.stroke();
  }
  for (i = 0; i < 150; i++) {
    var dx = Math.random() * S, dy = Math.random() * S, rr = 1.6 + Math.random() * 1.8;
    x.fillStyle = Math.random() < 0.5 ? 'rgba(248,246,232,.2)' : 'rgba(178,198,146,.2)';
    x.beginPath(); x.arc(dx, dy, rr, 0, PI * 2); x.fill();
  }
  grain(x, S, S, 2800, 0.04);
  return T(c, 5, 5);
})();
/* 树冠（对比加强，供双色调树冠用） */
var leavesTex = (function () {
  var S = 512, c = cnv(S, S), x = c[1];
  x.fillStyle = '#F2F2E2'; x.fillRect(0, 0, S, S);
  blotch(x, S, S, ['220,230,190', '240,244,216', '202,218,172', '246,248,228', '212,226,182'], 95, 22, 100, 0.3);
  grain(x, S, S, 1600, 0.06);
  return T(c, 1, 1);
})();
/* 中性斑驳（litBox 台面/广场/塘面用·近白灰，吃数据 col tint） */
var mottleTex = (function () {
  var S = 512, c = cnv(S, S), x = c[1];
  x.fillStyle = '#E9E6DA'; x.fillRect(0, 0, S, S);
  blotch(x, S, S, ['222,218,204', '236,234,224', '214,210,194', '240,238,228'], 80, 26, 110, 0.2);
  grain(x, S, S, 1800, 0.05);
  return T(c, 1, 1);
})();
/* 接触阴影（Townscaper 式软 AO 盘，垫在物体脚下） */
var aoTex = (function () {
  var S = 128, c = cnv(S, S), x = c[1];
  /* W-4：径向渐变改同心盘（原 .55@0 → .24@0.55 → 0@1） */
  glowStack(x, S / 2, S / 2, S / 2, S / 2, function () { return '42,28,16'; },
    function (k) { return k < 0.55 ? lerp(0.55, 0.24, k / 0.55) : lerp(0.24, 0, (k - 0.55) / 0.45); }, 10);
  return T(c);
})();
/* 木门 */
var doorTex = (function () {
  var W = 128, H = 192, c = cnv(W, H), x = c[1];
  x.fillStyle = '#A87850'; x.fillRect(0, 0, W, H);
  x.strokeStyle = 'rgba(110,74,48,.8)'; x.lineWidth = 3;
  for (var i = 1; i < 4; i++) { x.beginPath(); x.moveTo(i * W / 4, 0); x.lineTo(i * W / 4, H); x.stroke(); }
  x.strokeStyle = '#6E4A30'; x.lineWidth = 8;
  x.beginPath(); x.moveTo(4, H - 4); x.lineTo(4, 50); x.quadraticCurveTo(4, 6, W / 2, 6);
  x.quadraticCurveTo(W - 4, 6, W - 4, 50); x.lineTo(W - 4, H - 4); x.closePath(); x.stroke();
  x.fillStyle = '#E8C57E'; x.beginPath(); x.arc(W - 30, 110, 6, 0, PI * 2); x.fill();
  return T(c);
})();
/* 暖光晕（灯/萤火/烟共用底图） */
var glowTex = (function () {
  var S = 128, c = cnv(S, S), x = c[1];
  /* W-4：径向渐变改同心盘（原 1@0 → .45@0.4 → 0@1·色温芯暖缘冷两段） */
  glowStack(x, S / 2, S / 2, S / 2, S / 2,
    function (k) { return k < 0.4 ? '255,214,140' : '255,187,108'; },
    function (k) { return k < 0.4 ? lerp(1, 0.45, k / 0.4) : lerp(0.45, 0, (k - 0.4) / 0.6); }, 12);
  return T(c);
})();
/* 云朵 / 树叶粒子底图 */
var puffTex = (function () {
  var S = 256, c = cnv(S, S), x = c[1];
  for (var i = 0; i < 9; i++) {
    var cx = S * 0.2 + Math.random() * S * 0.6, cy = S * 0.4 + Math.random() * S * 0.28, r = 26 + Math.random() * 46;
    /* W-4：径向渐变改同心盘（原 .9@0 → 0@1） */
    glowStack(x, cx, cy, r, r, function () { return '255,253,247'; }, function (k) { return lerp(0.9, 0, k); }, 8);
  }
  return T(c);
})();
var leafTex = (function () {
  var W = 32, H = 24, c = cnv(W, H), x = c[1];
  x.fillStyle = 'rgba(143,191,106,.95)';
  x.beginPath(); x.ellipse(W / 2, H / 2, 13, 8, 0.35, 0, PI * 2); x.fill();
  x.strokeStyle = 'rgba(90,130,60,.8)'; x.lineWidth = 2;
  x.beginPath(); x.moveTo(5, H - 6); x.quadraticCurveTo(W / 2, H / 2, W - 5, 6); x.stroke();
  return T(c);
})();
/* ================= 3b. 背景幕坐标系（天空 + 远山共用） =================
   契约相机恒俯视（pitch 0.28..1.25·竖向 FOV 0.85rad），天空不是头顶穹顶而是**地平线附近的背景幕**：
   着陆姿态（pitch .36 / dist 30）下可见天区只有仰角 −8°…+4° 这一条窄带。所以
   ① 天空贴图不按整球均分，而是整张铺进 SKY_BAND_TOP..SKY_BAND_BOT（球面 UV 重映射·带外钳边缘行），
      带内纵向分辨率是均分球的 ~3.4 倍，渐变与装饰全落在玩家真看得见的地方（旧版把渐变压进窄带
      再拿末停色糊满地平线以下 → 白天档发白，现在整条渐变直接铺在可见带上，不需要那层补偿）；
   ② 远山环带压在地面盘外沿之上一点点，山脊线落在屏高 ~20%（参照图 town_r3_composition_1830 同构图）。 */
var SKY_BAND_TOP = 0.35;   /* 贴图第 0 行的仰角（rad·≈+20°） */
var SKY_BAND_BOT = -0.61;  /* 贴图末行的仰角（rad·≈−35°） */
var SKY_TEX_W = 1024, SKY_TEX_H = 512;
/* 带内「纵向 px/rad ÷ 横向 px/rad」：装饰要画成圆的，就得在这个系数下纵向拉伸 */
var SKY_AY = (SKY_TEX_H / (SKY_BAND_TOP - SKY_BAND_BOT)) / (SKY_TEX_W / (2 * PI));
/* 仰角(rad) → 贴图行（0..H·带外钳住） */
function skyRowOf(elev) { return clamp((SKY_BAND_TOP - elev) / (SKY_BAND_TOP - SKY_BAND_BOT), 0, 1) * SKY_TEX_H; }
/* 七停渐变的落位：着陆姿态屏顶≈+3.8°、远山脊线≈−5°。末停色（地平线暖色）压到脊线上，
   让暖光正好衬在远山之上；再往下全被远山/地面挡住，所以不必给它留画面。 */
var SKY_GRAD_TOP = 0.09, SKY_GRAD_BOT = -0.085;
function rgbStr(a) { return 'rgb(' + Math.round(a[0] * 255) + ',' + Math.round(a[1] * 255) + ',' + Math.round(a[2] * 255) + ')'; }
/* ⚠ 天空画布全面戒掉 CanvasGradient（R2 复审实锤的环境 bug）：GPU 加速的 2D canvas 上，
   同一段 JS 任务里只有前几次渐变填充真的落笔，之后的渐变填充**静默变透明**——参数合法、
   状态正常、零报错（探针实录：四次同参渐变填充，第一次回读有色，后三次全 [0,0,0,0]）。
   材质纹理的渐变排在任务前段侥幸存活，天空排在末段必中招 ⇒ 黑带。纯色填充不受影响，
   所以：渐变带=逐行插值纯色；光晕=同心环按精确合成公式叠加。视觉与原渐变等价。 */
function stopColorAt(list, t) {
  var lo = list[0], hi = list[list.length - 1];
  if (t <= lo.pos) return lo.rgb;
  if (t >= hi.pos) return hi.rgb;
  for (var i = 1; i < list.length; i++) {
    if (list[i].pos >= t) { hi = list[i]; lo = list[i - 1]; break; }
  }
  var k = (t - lo.pos) / Math.max(1e-6, hi.pos - lo.pos);
  return [lerp(lo.rgb[0], hi.rgb[0], k), lerp(lo.rgb[1], hi.rgb[1], k), lerp(lo.rgb[2], hi.rgb[2], k)];
}
/* 同心椭圆盘从外向内叠加逼近径向渐变。aFn(k)=半径占比 k 处的目标累计透明度（aFn(1)=0），
   每层用精确合成公式 p=(aIn−aOut)/(1−aOut) ⇒ 叠完在任意半径处的总透明度=aFn 目标值 */
function glowStack(x, cx, cy, r, ry, colFn, aFn, rings) {
  for (var i = rings; i >= 1; i--) {
    var aOut = aFn(i / rings), aIn = aFn((i - 1) / rings);
    var p = (aIn - aOut) / Math.max(0.02, 1 - aOut);
    if (p <= 0.003) continue;
    x.fillStyle = 'rgba(' + colFn((i - 1) / rings) + ',' + Math.min(1, p).toFixed(3) + ')';
    x.beginPath(); x.ellipse(cx, cy, Math.max(0.01, r * i / rings), Math.max(0.01, ry * i / rings), 0, 0, PI * 2); x.fill();
  }
}

/* 远山剪影（透明底·灰阶水彩，吃每档 hillC tint）。两道山脊：后浅前浓；剖面先算再**归一化**到
   [peakRow, peakRow+band] 的行区间 ⇒「贴图第 peakRow 行 = 最高峰」恒成立，山脊在屏幕上的落点
   由 hillRings 的 半径/山脚/环带顶 直接算得出来。
   频率：环带一圈 360°，但着陆姿态一眼只看得见约 28° —— 所以 ① 贴图横向 repeat rep 次（一张只铺
   360/rep 度 ⇒ 放大倍率降到 ~2.7×，山脊边缘不糊）；② 剖面用 cyc 个整周期正弦（整数 ⇒ 首尾无缝），
   cyc 取到「可见 28° 内看得见三四个峰」。三带 rep 互质错开，转镜头也看不出重复。 */
function makeHillTex(cyc, rough, peakRow, band, rep) {
  var W = 1024, H = 256, c = cnv(W, H), x = c[1], N = 384;
  x.clearRect(0, 0, W, H);
  for (var layer = 0; layer < 2; layer++) {
    var prof = [], i, a;
    for (i = 0; i <= N; i++) {
      a = i / N * PI * 2;
      prof.push(-Math.sin(a * cyc + layer * 2.4) - 0.28 * Math.sin(a * (cyc + 3) + layer)
        + (rough ? 0.12 * Math.sin(a * cyc * 3 + layer * 5) : 0));
    }
    var lo = Math.min.apply(null, prof), hi = Math.max.apply(null, prof), span = Math.max(1e-3, hi - lo);
    /* 后层（浅）占上 3/4 段，前层（浓）整体下压 0.3 段 —— 后脊探出前脊之上 = 空气透视 */
    var y0 = H * (peakRow + (layer === 0 ? 0 : band * 0.30));
    var y1 = H * (peakRow + band * (layer === 0 ? 0.75 : 1.0));
    x.beginPath(); x.moveTo(0, H);
    for (i = 0; i <= N; i++) x.lineTo(i / N * W, y0 + (prof[i] - lo) / span * (y1 - y0));
    x.lineTo(W, H); x.closePath();
    /* 墨色浓度：贴图不是纯白而是压暗的灰——远山要衬得住天光才有剪影感（tint 由 PHASES 的 hill1-3 给） */
    x.fillStyle = layer === 0 ? 'rgba(166,166,166,.66)' : 'rgba(128,128,128,1)';
    x.fill();
  }
  grain(x, W, H, 900, 0.05);
  var t = T(c);
  t.wrapS = THREE.RepeatWrapping; t.wrapT = THREE.ClampToEdgeWrapping; t.repeat.set(rep, 1);
  return t;
}
/* [峰频, 糙化, 峰行, 脊带高, 横向重复]——与下面 hillRings 的 半径/山脚/环带顶 成对调（远→近） */
var hillTexes = [makeHillTex(7, false, 0.05, 0.271, 6), makeHillTex(5, true, 0.05, 0.346, 5), makeHillTex(4, false, 0.05, 0.427, 4)];

/* ================= 4. 天空背景幕（双层交叉溶解） =================
   stops: [{pos,rgb}] 顶→底；deco: 'day' | 'dusk' | 'night'。渐变铺在 SKY_GRAD_TOP..SKY_GRAD_BOT
   的仰角区间（= 可见天带），带外钳首/末停色；装饰在纵向拉伸 SKY_AY 的坐标系里画（画圆得圆）。 */
function buildSkyTex(stops, deco) {
  var W = SKY_TEX_W, H = SKY_TEX_H, c = cnv(W, H), x = c[1];
  var list = (stops && stops.length) ? stops : [{ pos: 0, rgb: [0.31, 0.5, 0.75] }, { pos: 1, rgb: [0.95, 0.93, 0.87] }];
  var gTop = skyRowOf(SKY_GRAD_TOP), gBot = skyRowOf(SKY_GRAD_BOT);
  /* 渐变带 = 逐行插值纯色（不用 CanvasGradient·原因见 stopColorAt 上方注释） */
  x.fillStyle = rgbStr(list[0].rgb); x.fillRect(0, 0, W, Math.floor(gTop) + 1);             /* 带顶以上：钳天顶色 */
  for (var row = Math.floor(gTop); row <= Math.ceil(gBot); row++) {
    x.fillStyle = rgbStr(stopColorAt(list, clamp((row - gTop) / Math.max(1e-6, gBot - gTop), 0, 1)));
    x.fillRect(0, row, W, 1);
  }
  x.fillStyle = rgbStr(list[list.length - 1].rgb); x.fillRect(0, Math.ceil(gBot), W, H - Math.ceil(gBot));  /* 地平线以下：钳末停色（基本全被远山/地面挡住） */

  /* ---- 装饰：进入「纵向已按 SKY_AY 拉伸」的坐标系，eY(仰角°) 给该系里的 y ---- */
  function eY(deg) { return skyRowOf(deg * PI / 180) / SKY_AY; }
  function cloud(cx, cy, s, tint, hi) {
    for (var w = -1; w <= 1; w++) { /* 左右各补一份 ⇒ 球面 u=0/1 接缝处的云不被切断 */
      for (var i = 0, n = 6; i < n; i++) {
        var ox = (Math.random() * 3 - 1.5) * s, oy = (Math.random() * 0.5 - 0.25) * s * 0.4, r = (0.45 + Math.random() * 0.55) * s;
        x.fillStyle = tint; x.beginPath(); x.ellipse(cx + ox + w * W, cy + oy + r * 0.28, r, r * 0.62, 0, 0, PI * 2); x.fill();
      }
      for (i = 0; i < n; i++) {
        ox = (Math.random() * 2.6 - 1.3) * s; oy = (Math.random() * 0.35 - 0.3) * s; r = (0.35 + Math.random() * 0.45) * s;
        x.fillStyle = hi; x.beginPath(); x.ellipse(cx + ox + w * W, cy + oy, r, r * 0.5, 0, 0, PI * 2); x.fill();
      }
    }
  }
  x.save(); x.scale(1, SKY_AY);
  if (deco === 'dusk') {
    var sx0 = W * 0.24, sy0 = eY(-2.2), sr = 96;
    /* 落日光晕：同心环叠加（原径向渐变 0:.95 → 0.35:.5 → 1:0·色温由芯向外变冷） */
    glowStack(x, sx0, sy0, sr, sr,
      function (k) { return k < 0.35 ? rgbStr(stopColorAt([{pos:0,rgb:[1,0.925,0.745]},{pos:0.35,rgb:[1,0.769,0.51]}], k)).slice(4, -1) : '255,180,118'; },
      function (k) { return k >= 1 ? 0 : (k < 0.35 ? lerp(0.95, 0.5, k / 0.35) : lerp(0.5, 0, (k - 0.35) / 0.65)); }, 12);
    for (var i = 0; i < 13; i++) {
      var nearSun = Math.random() < 0.55;
      cloud(nearSun ? W * 0.24 + (Math.random() * 460 - 230) : Math.random() * W, eY(0.3 + Math.random() * 2.7), 4.5 + Math.random() * 6,
        nearSun ? 'rgba(255,186,140,.55)' : 'rgba(210,146,156,.46)',
        nearSun ? 'rgba(255,228,200,.62)' : 'rgba(238,186,178,.55)');
    }
  } else if (deco === 'day') {
    for (i = 0; i < 16; i++) cloud(Math.random() * W, eY(0.5 + Math.random() * 3.0), 4 + Math.random() * 5.5, 'rgba(255,255,255,.75)', 'rgba(255,255,255,.9)');
  } else { /* night：银河 + 月 + 星（全部落在可见带里） */
    x.save(); x.translate(W * 0.4, eY(1.9)); x.rotate(-0.16);
    for (i = 0; i < 520; i++) {
      var bx = Math.random() * 1240 - 620, by = (Math.random() * 46 - 23) * (1 - Math.abs(bx) / 900);
      x.fillStyle = 'rgba(226,232,248,' + (0.03 + Math.random() * 0.10).toFixed(3) + ')';
      x.beginPath(); x.arc(bx, by, 0.5 + Math.random() * 1.4, 0, PI * 2); x.fill();
    }
    x.restore();
    var mx = W * 0.76, my = eY(2.6), mr = 9;
    /* 月晕：同心环叠加（原径向渐变 0:.5 → 1:0） */
    glowStack(x, mx, my, mr * 4.2, mr * 4.2,
      function () { return '245,230,180'; },
      function (k) { return lerp(0.5, 0, k); }, 8);
    x.fillStyle = '#F5E6B4'; x.beginPath(); x.arc(mx, my, mr, 0, PI * 2); x.fill();
    x.fillStyle = '#1B2444'; x.beginPath(); x.arc(mx - mr * 0.5, my - mr * 0.27, mr * 0.88, 0, PI * 2); x.fill();
    /* 一圈只画 5 颗五角主星（横向 ~11× 放大 ⇒ 中号星必糊；大而少才立得住），其余全是细星点 */
    for (i = 0; i < 5; i++) star5(x, (i + 0.35 + Math.random() * 0.3) / 5 * W, eY(-1.2 + Math.random() * 5.2), 7 + Math.random() * 4);
    /* 细星点画成软芯（三层同心盘）而不是硬圆：贴图横向要放大 ~11×，硬边亚像素圆会糊成小方块 */
    for (i = 0; i < 340; i++) {
      var stx = Math.random() * W, sty = eY(-3.6 + Math.random() * 7.6), str = 0.9 + Math.random() * 0.9;
      var sa = 0.42 + Math.random() * 0.5;
      glowStack(x, stx, sty, str, str,
        function (k) { return k < 0.45 ? '244,240,222' : '236,232,214'; },
        function (k) { return k >= 1 ? 0 : (k < 0.45 ? lerp(sa, sa * 0.55, k / 0.45) : lerp(sa * 0.55, 0, (k - 0.45) / 0.55)); }, 3);
    }
  }
  x.restore();
  grain(x, W, H, 2600, 0.04);
  return T(c);
}
/* 天空**不吃** AI 画层：tex_sky_dusk/night.webp 是含山林地景的整幅风景、右缘还有一道竖接缝，
   球面投影后地景碎块会糊到画面顶部；且素材横向要铺满一圈必然被拉伸，星月这类硬装饰会明显压扁。
   → 黄昏 / 深夜天空一律走上面的程序化手绘版（渐变 + 落日 / 银河月星，逐档为可见带量身画）。
   两张素材已从 world_web 移除（App 侧 res/world_town_sky_* 是另一条线，不受影响）。 */
function star5(x, cx, cy, r) {
  x.save(); x.translate(cx, cy); x.beginPath();
  for (var i = 0; i < 10; i++) {
    var rad = i % 2 === 0 ? r : r * 0.45, a = -PI / 2 + i * PI / 5;
    if (i === 0) x.moveTo(Math.cos(a) * rad, Math.sin(a) * rad); else x.lineTo(Math.cos(a) * rad, Math.sin(a) * rad);
  }
  x.closePath(); x.fillStyle = 'rgba(245,230,180,.8)'; x.fill(); x.restore();
}
/* 黄昏 / 深夜固定美术天（白天那张由数据 stops 现烤） */
var duskStops = [[0, '#4A5688'], [0.4, '#7E6FA0'], [0.56, '#B48AA0'], [0.7, '#E0A48E'], [0.85, '#FFD2A2'], [1, '#FFE8C4']]
  .map(function (s) { var n = parseInt(s[1].slice(1), 16); return { pos: s[0], rgb: [(n >> 16 & 255) / 255, (n >> 8 & 255) / 255, (n & 255) / 255] }; });
var nightStops = [[0, '#131B38'], [0.55, '#233059'], [0.8, '#334274'], [1, '#48598C']]
  .map(function (s) { var n = parseInt(s[1].slice(1), 16); return { pos: s[0], rgb: [(n >> 16 & 255) / 255, (n >> 8 & 255) / 255, (n & 255) / 255] }; });
var skyTexDay = buildSkyTex(null, 'day');
var skyTexDusk = buildSkyTex(duskStops, 'dusk');
var skyTexNight = buildSkyTex(nightStops, 'night');

/* 天空球：整圈 40 段 × 纵向 96 段（纵向要密，UV 重映射是逐顶点的，段太粗渐变会折） */
var skyGeo = new THREE.SphereGeometry(260, 40, 96);
(function remapSkyBandUV() {
  /* 把整张贴图铺进 SKY_BAND_TOP..SKY_BAND_BOT 的仰角带，带外钳到首/末行（wrapT 默认 ClampToEdge）。
     球仍是整球 ⇒ 任何俯仰 / 缩放下都不会露出没天空的洞。 */
  var pos = skyGeo.attributes.position, uv = skyGeo.attributes.uv, span = SKY_BAND_TOP - SKY_BAND_BOT;
  for (var i = 0; i < pos.count; i++) {
    var elev = Math.atan2(pos.getY(i), Math.hypot(pos.getX(i), pos.getZ(i)));
    uv.setY(i, 1 - clamp((SKY_BAND_TOP - elev) / span, 0, 1)); /* uv.y=1 ↔ 贴图首行 ↔ 带顶 */
  }
  uv.needsUpdate = true;
})();
function skyMat(map) {
  return new THREE.MeshBasicMaterial({ map: map, side: THREE.BackSide, fog: false, transparent: true, depthWrite: false });
}
var skyFront = new THREE.Mesh(skyGeo, skyMat(skyTexDusk));
var skyBack = new THREE.Mesh(skyGeo, skyMat(skyTexDusk));
skyBack.material.opacity = 0; skyBack.visible = false;
skyFront.renderOrder = -9; skyBack.renderOrder = -8;
skyFront.frustumCulled = skyBack.frustumCulled = false;
scene.add(skyFront); scene.add(skyBack);
var skyFrontName = 'dusk', skyFading = 0;
function setSkyTarget(name, instant) {
  if (name === skyFrontName) return;
  if (flags.reduceMotion || instant) {
    skyFront.material.map = skyTexByName(name); skyFrontName = name;
    skyBack.visible = false; skyFading = 0; return;
  }
  if (skyFading <= 0) {
    skyBack.material.map = skyTexByName(name);
    skyBack.material.opacity = 0; skyBack.visible = true; skyFading = 0.0001;
  }
}
function skyTexByName(n) { return n === 'day' ? skyTexDay : (n === 'night' ? skyTexNight : skyTexDusk); }
function tickSky(dt) {
  if (skyFading > 0) {
    skyFading = Math.min(1, skyFading + dt / 1.6);
    skyBack.material.opacity = smooth01(skyFading);
    if (skyFading >= 1) {
      skyFrontName = skyBack.material.map === skyTexDay ? 'day' : (skyBack.material.map === skyTexNight ? 'night' : 'dusk');
      var tmp = skyFront; skyFront = skyBack; skyBack = tmp;
      /* 画序跟着角色走：淡入的那层（skyBack）必须画在旧层之上，否则整层被不透明的旧天挡住，
         表现为「前 1.5s 纹丝不动、到点啪一下跳档」——只在交替的那一次犯，最藏得住 */
      skyFront.renderOrder = -9; skyBack.renderOrder = -8;
      skyBack.visible = false; skyBack.material.opacity = 0; skyFading = 0;
    }
  }
}

/* ================= 5. 远山三带（背景幕下缘·压在地面盘外沿之上） =================
   表项 = [半径, 山脚 y, 环带顶 y, 剖面贴图]，顺序 远→近。山脊峰 = 环带顶下 5%（贴图峰行）：
   着陆姿态（pitch .36/dist 30/FOV .85）下三带峰线落在屏高 ~18.5% / 19.3% / 20.2%，脊谷 ~21% /
   22.3% / 23.6%，而地面盘 r=48（外圈缓丘 0..1.8 高）的外沿落在 ~24–26% —— 合起来就是
   「天空 + 远山 = 画面上部约 1/4~1/3」的层叠背景幕（参照图 town_r3_composition_1830 同构图）。
   山脚一律沉到地面盘外沿视线之下（远带更低）⇒ 任何脊谷处都不会露出山脚与地面之间的缝。
   远→近的顺序还顺带对上两件事：hillRings[0..2] 依次吃 applyAmbience 的 hill1(最浅)→hill3(最深)，
   renderOrder 依次 −7→−5 ⇒ 近的画在远的之上（空气透视对）。 */
var hillRings = [];
[[76, -5.0, 3.20, 0], [63, -3.2, 3.589, 1], [52, -1.6, 3.884, 2]].forEach(function (d, i) {
  var hh = d[2] - d[1];
  var m = new THREE.Mesh(
    new THREE.CylinderGeometry(d[0], d[0], hh, 64, 1, true),
    new THREE.MeshBasicMaterial({ map: hillTexes[d[3]], transparent: true, side: THREE.BackSide, fog: false, depthWrite: false }));
  m.position.y = d[1] + hh / 2;
  m.renderOrder = -7 + i;
  m.frustumCulled = false;
  scene.add(m); hillRings.push(m);
});

/* ================= 6. 地面 ================= */
var groundMat = new THREE.MeshStandardMaterial({ map: groundTex, roughness: 1, color: 0x9DB470, vertexColors: true });
var groundAiOn = false; /* AI 地表贴图加载成功后，地面吃贴图原色（ground tint 仅作参考） */
var groundMesh = (function () {
  var geo = new THREE.RingGeometry(0.001, 48, 64, 22);
  geo.rotateX(-PI / 2);
  var pos = geo.attributes.position;
  var colors = new Float32Array(pos.count * 3);
  for (var i = 0; i < pos.count; i++) {
    var vx = pos.getX(i), vz = pos.getZ(i);
    var r = Math.hypot(vx, vz);
    /* 动森式缓丘外圈（r≥26 起伏，城内保持平整——数据物体可到 ±21） */
    var rise = smooth01((r - 26) / 16);
    var a = Math.atan2(vz, vx);
    var y = rise * (0.75 + 0.5 * Math.sin(a * 3 + 1.3) + 0.3 * Math.sin(a * 7 + 0.5) + 0.25 * Math.sin(vx * 0.31) * Math.cos(vz * 0.27));
    pos.setY(i, Math.max(-0.3, y));
    /* 草地水彩色斑 */
    var nz = Math.sin(vx * 0.41 + 1.7) * Math.cos(vz * 0.35 + 0.6) + 0.5 * Math.sin((vx + vz) * 0.17);
    var k = 0.93 + 0.07 * clamp(nz, -1, 1);
    colors[i * 3] = k * 0.99; colors[i * 3 + 1] = k; colors[i * 3 + 2] = k * 0.94;
  }
  geo.setAttribute('color', new THREE.BufferAttribute(colors, 3));
  geo.computeVertexNormals();
  var m = new THREE.Mesh(geo, groundMat);
  m.receiveShadow = true;
  return m;
})();
scene.add(groundMesh);

/* ================= 7. 水（WEST_RIVER / EAST_SEA） ================= */
var waterMesh = null;
var waterUni = {
  uTime: { value: 0 }, uCol: { value: new THREE.Color(0xD9A8B4) }, uFogC: { value: new THREE.Color(0xE8B490) },
  uGlow: { value: 0 }, uStreakX: { value: -19.5 }, uMinX: { value: -24 }, uMaxX: { value: -15 }
};
function setupWater(kind) {
  if (waterMesh) { scene.remove(waterMesh); waterMesh.geometry.dispose(); waterMesh.material.dispose(); waterMesh = null; }
  if (kind !== 'WEST_RIVER' && kind !== 'EAST_SEA') return;
  var geo, minX, maxX, streakX;
  if (kind === 'WEST_RIVER') { geo = new THREE.PlaneGeometry(9, 150); minX = -24; maxX = -15; streakX = -19.5; }
  else { geo = new THREE.PlaneGeometry(92, 150); minX = 13; maxX = 105; streakX = 20; }
  var mat = new THREE.ShaderMaterial({
    uniforms: waterUni,
    vertexShader: [
      'varying vec3 vW;',
      'void main(){',
      '  vec4 wp = modelMatrix * vec4(position,1.0); vW = wp.xyz;',
      '  gl_Position = projectionMatrix * viewMatrix * wp; }'
    ].join('\n'),
    fragmentShader: [
      'uniform float uTime, uGlow, uStreakX, uMinX, uMaxX;',
      'uniform vec3 uCol, uFogC;',
      'varying vec3 vW;',
      'void main(){',
      '  float rip = sin(vW.x*2.3 + uTime*1.5)*sin(vW.z*2.9 - uTime*1.2);',
      '  float rip2 = sin(vW.x*5.1 - uTime*1.1)*sin(vW.z*6.3 + uTime*1.7);',
      '  vec3 c = uCol * (0.93 + 0.05*rip + 0.03*rip2);',
      '  c += vec3(0.92,0.96,1.0) * pow(max(0.0, rip2), 2.0) * 0.2 * (1.0 - uGlow*0.45);', /* 日间碎光 */
      '  float d = min(vW.x - uMinX, uMaxX - vW.x);',
      '  float shore = smoothstep(0.0, 1.4, d);',
      '  c = mix(c*0.5, c, 0.35 + 0.65*shore);', /* 岸线压暗成湿泥色 */
      '  float foam = smoothstep(1.0, 0.12, d) * (0.55 + 0.45*sin(d*6.0 - uTime*2.0));',
      '  c = mix(c, vec3(0.97, 0.96, 0.9), foam * 0.42);', /* 动森式岸线呼吸白沫 */
      '  float col = exp(-pow((vW.x - uStreakX)/1.1, 2.0));',
      '  float sparkle = 0.5 + 0.5*sin(vW.z*2.6 - uTime*1.6);',
      '  c += vec3(1.0,0.85,0.6) * col * sparkle * uGlow * 0.22;', /* 月光/灯火倒影光带 */
      '  float fogF = smoothstep(40.0, 130.0, distance(vW, cameraPosition));',
      '  c = mix(c, uFogC, fogF);',
      '  gl_FragColor = vec4(c, 1.0); }'
    ].join('\n')
  });
  waterMesh = new THREE.Mesh(geo, mat);
  waterMesh.rotation.x = -PI / 2;
  waterMesh.position.set((minX + maxX) / 2, 0.035, 0);
  waterUni.uMinX.value = minX; waterUni.uMaxX.value = maxX; waterUni.uStreakX.value = streakX;
  scene.add(waterMesh);
}

/* ================= 8. 合并桶（几何合批 · 控 draw call） ================= */
var _m4 = new THREE.Matrix4(), _q = new THREE.Quaternion(), _v3 = new THREE.Vector3(), _s3 = new THREE.Vector3();
var _m3 = new THREE.Matrix3(), _tv = new THREE.Vector3(), _tn = new THREE.Vector3();
function M(px, py, pz, sx, sy, sz, rx, ry, rz) {
  _q.setFromEuler(new THREE.Euler(rx || 0, ry || 0, rz || 0));
  return _m4.compose(_v3.set(px, py, pz), _q, _s3.set(sx, sy === undefined ? 1 : sy, sz === undefined ? 1 : sz)).clone();
}
function Bucket(name, pickable) {
  this.name = name; this.pickable = !!pickable;
  this.pos = []; this.nrm = []; this.col = []; this.uv = []; this.idx = []; this.ranges = []; this.mesh = null;
}
Bucket.prototype.push = function (geo, m, color, meta) {
  var p = geo.attributes.position, n = geo.attributes.normal, uv = geo.attributes.uv;
  var base = this.pos.length / 3, triStart = this.idx.length / 3;
  _m3.getNormalMatrix(m);
  for (var i = 0; i < p.count; i++) {
    _tv.fromBufferAttribute(p, i).applyMatrix4(m);
    this.pos.push(_tv.x, _tv.y, _tv.z);
    _tn.fromBufferAttribute(n, i).applyMatrix3(_m3).normalize();
    this.nrm.push(_tn.x, _tn.y, _tn.z);
    this.col.push(color.r, color.g, color.b);
    if (uv) this.uv.push(uv.getX(i), uv.getY(i)); else this.uv.push(0, 0);
  }
  if (geo.index) { for (i = 0; i < geo.index.count; i++) this.idx.push(base + geo.index.getX(i)); }
  else { for (i = 0; i < p.count; i++) this.idx.push(base + i); }
  if (this.pickable && meta) this.ranges.push({ s: triStart, e: this.idx.length / 3, meta: meta });
};
var buckets = {};
function bucket(name) { return buckets[name]; }
var stdMat = function (map) { return new THREE.MeshStandardMaterial({ map: map || null, roughness: 1, vertexColors: true }); };
var BUCKET_MATS = {
  plaster: stdMat(wallTex), roof: stdMat(roofTex), leaves: stdMat(leavesTex),
  stone: stdMat(null), door: stdMat(doorTex), plain: stdMat(null), lit: stdMat(mottleTex)
};
/* 可拾取桶（建筑墙体/屋顶/通用盒）记录三角区间，供点选映射地点 */
buckets.plaster = new Bucket('plaster', true);
buckets.roof = new Bucket('roof', true);
buckets.leaves = new Bucket('leaves', false);
buckets.stone = new Bucket('stone', true);
buckets.door = new Bucket('door', false);
buckets.plain = new Bucket('plain', true);
buckets.lit = new Bucket('lit', true);

/* ---- AI 贴图静默升级（世界图/ 素材经裁水印/无缝化/降饱和产出的 tex_*.webp·全本地）。
   程序化贴图始终先打底；webp 加载成功才替换，失败静默保留兜底（file:// 直开也不白屏） ---- */
(function upgradeAiTextures() {
  var al = Math.min(8, renderer.capabilities.getMaxAnisotropy());
  function up(url, apply) {
    new THREE.TextureLoader().load(url, function (t) {
      t.encoding = THREE.sRGBEncoding; t.anisotropy = al;
      t.wrapS = t.wrapT = THREE.RepeatWrapping;
      apply(t);
      dirty = true;
    }, undefined, function () { console.log('[town] AI 贴图缺失，程序化兜底:', url); });
  }
  up('tex_ground.webp', function (t) {
    t.repeat.set(5, 5);
    groundMat.map = t; groundMat.color.set(0xffffff); groundMat.needsUpdate = true;
    groundAiOn = true;
  });
  up('tex_roof.webp', function (t) {
    t.repeat.set(2, 1);
    BUCKET_MATS.roof.map = t; BUCKET_MATS.roof.needsUpdate = true;
  });
  up('tex_wall.webp', function (t) {
    BUCKET_MATS.plaster.map = t; BUCKET_MATS.plaster.needsUpdate = true;
  });
  up('tex_stone.webp', function (t) {
    t.repeat.set(3, 3);
    BUCKET_MATS.lit.map = t; BUCKET_MATS.lit.needsUpdate = true;
  });
  up('tex_leaf.webp', function (t) {
    BUCKET_MATS.leaves.map = t; BUCKET_MATS.leaves.needsUpdate = true;
  });
})();
/* 常用几何模板（unit，靠矩阵变形） */
var G = {
  box: new THREE.BoxGeometry(1, 1, 1),
  dome: new THREE.SphereGeometry(1, 24, 12, 0, PI * 2, 0, PI / 2),
  sphere: new THREE.SphereGeometry(1, 12, 9),
  cyl: new THREE.CylinderGeometry(1, 1, 1, 8),
  cone: new THREE.ConeGeometry(1, 1, 9),
  door: new THREE.PlaneGeometry(0.85, 1.35),
  win: new THREE.PlaneGeometry(1, 1)
};
/* 双坡屋顶（unit：x,z 半宽 0.5，脊高 1，脊沿 z 轴；绕向外法线手算） */
G.gable = (function () {
  var hx = 0.5, hz = 0.5, pk = 1;
  var v = [[-hx, 0, -hz], [hx, 0, -hz], [hx, 0, hz], [-hx, 0, hz], [0, pk, -hz], [0, pk, hz]];
  var idx = [
    1, 4, 5, 1, 5, 2,  /* +x 坡 */
    0, 3, 5, 0, 5, 4,  /* -x 坡 */
    0, 1, 4,           /* -z 山墙 */
    2, 5, 3            /* +z 山墙 */
  ];
  var pos = [], uvs = [];
  v.forEach(function (p) { pos.push(p[0], p[1], p[2]); uvs.push(p[0] + 0.5, p[2] + 0.5); });
  var geo = new THREE.BufferGeometry();
  geo.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
  geo.setAttribute('uv', new THREE.Float32BufferAttribute(uvs, 2));
  geo.setIndex(idx);
  geo.computeVertexNormals();
  return geo;
})();
/* 四坡锥顶（unit：矩形底半宽 0.5，顶高 1） */
G.pyramid = (function () {
  var hx = 0.5, hz = 0.5;
  var v = [[-hx, 0, -hz], [hx, 0, -hz], [hx, 0, hz], [-hx, 0, hz], [0, 1, 0]];
  var idx = [2, 4, 3, 1, 4, 0, 1, 4, 2, 3, 4, 0];
  var pos = [], uvs = [];
  v.forEach(function (p) { pos.push(p[0], p[1], p[2]); uvs.push(p[0] + 0.5, p[2] + 0.5); });
  var geo = new THREE.BufferGeometry();
  geo.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
  geo.setAttribute('uv', new THREE.Float32BufferAttribute(uvs, 2));
  geo.setIndex(idx);
  geo.computeVertexNormals();
  return geo;
})();

/* ================= 9. 错峰着色器（契约公式逐字） =================
   key = fract(sin(dot(floor((worldXZ+37.7)/1.7), vec2(12.9898,78.233)))*43758.5453)
   on  = clamp((duskSec - key*12.0)/0.9, 0, 1) * lampT
---------------------------------------------------------------- */
/* 错峰 key（契约 §4 逐字）——窗火 / 灯头 / 灯晕点 / 灯下光池 四处共用这一行 GLSL 源码。
   为什么四处都必须在 GPU 上算、CPU 一律不掺和：key = fract(sin(点积) * 43758.5453)，
   sin 的自变量最大到 ~3400，而 GPU 是 float32、JS 是 float64 —— 同一条公式在两边算出的 key
   会完全跑偏（实测同一盏灯 CPU 0.130 / GPU 0.980 ⇒ 点亮时刻差 10.2s，几乎是整个 12s 错峰窗）。
   所以灯晕 / 光池不再从 CPU 传 key，只传灯位 XZ，key 一律由这行同源代码在着色器里算出来。 */
function staggerKeyGLSL(xz) {
  return 'fract(sin(dot(floor((' + xz + ' + 37.7) / 1.7), vec2(12.9898, 78.233))) * 43758.5453)';
}
var STAGGER_VS = [
  'varying vec2 vUv; varying vec3 vCol; varying float vKey;',
  'void main(){',
  '  vUv = uv; vCol = vec3(1.0);',
  '  vec4 wp; vec2 wxz;',
  '  #ifdef USE_INSTANCING',
  '    wp = modelMatrix * instanceMatrix * vec4(position, 1.0);',
  '    wxz = (modelMatrix * instanceMatrix * vec4(0.0, 0.0, 0.0, 1.0)).xz;',
  '    #ifdef USE_INSTANCING_COLOR',
  '      vCol = instanceColor;',
  '    #endif',
  '  #else',
  '    wp = modelMatrix * vec4(position, 1.0);',
  '    wxz = modelMatrix[3].xz;',
  '  #endif',
  '  vKey = ' + staggerKeyGLSL('wxz') + ';',
  '  gl_Position = projectionMatrix * viewMatrix * wp;',
  '}'
].join('\n');
var WIN_FS = [
  'uniform float uDuskSec, uLampT, uTime, uIdle;',
  'varying vec2 vUv; varying vec3 vCol; varying float vKey;',
  'void main(){',
  '  float on = clamp((uDuskSec - vKey * 12.0) / 0.9, 0.0, 1.0) * uLampT;',
  '  float flick = 0.92 + 0.08 * sin(uTime * 7.0 + vKey * 43.0);',
  '  vec3 lit = mix(vec3(1.0, 0.6, 0.3), vec3(1.0, 0.91, 0.68), vUv.y) * (0.9 + 0.25 * on);',
  '  vec3 glass = mix(vec3(0.16, 0.2, 0.3), lit * flick, on);',
  '  float refl = smoothstep(0.16, 0.0, abs(vUv.x + vUv.y * 0.7 - 0.95));', /* 天光斜反带（未点亮时） */
  '  glass += vec3(0.10, 0.13, 0.17) * refl * (1.0 - on);',
  '  vec2 b = min(vUv, 1.0 - vUv);',
  '  float inTrim = step(0.05, b.x) * step(0.05, b.y);',      /* 奶油外圈以内 */
  '  float inGlass = step(0.125, b.x) * step(0.115, b.y);',   /* 玻璃区 */
  '  float bar = max(1.0 - step(0.026, abs(vUv.x - 0.5)), 1.0 - step(0.022, abs(vUv.y - 0.5)));',
  '  vec3 trim = vec3(0.95, 0.9, 0.79);',
  '  vec3 wood = vec3(0.33, 0.22, 0.13) * (0.6 + 0.4 * on);',
  '  vec3 c = mix(trim, wood, inTrim);',
  '  c = mix(c, glass, inGlass * (1.0 - bar));',
  '  gl_FragColor = vec4(c, 1.0);',
  '}'
].join('\n');
var BOX_FS = [
  'uniform float uDuskSec, uLampT, uTime, uIdle;',
  'uniform vec3 uTint;',
  'varying vec3 vCol; varying float vKey;',
  'void main(){',
  '  float on = clamp((uDuskSec - vKey * 12.0) / 0.9, 0.0, 1.0) * uLampT;',
  '  float flick = 0.9 + 0.1 * sin(uTime * 6.0 + vKey * 51.0);',
  '  vec3 c = vCol * uTint * (uIdle + (1.0 - uIdle) * on * flick);',
  '  gl_FragColor = vec4(c, 1.0);',
  '}'
].join('\n');
var staggerMats = [];
function makeStaggerMat(kind) {
  var m = new THREE.ShaderMaterial({
    uniforms: {
      uDuskSec: { value: 0 }, uLampT: { value: 0 }, uTime: { value: 0 },
      uIdle: { value: kind === 'window' ? 0.0 : (kind === 'lamp' ? 0.1 : 0.22) },
      uTint: { value: new THREE.Color(kind === 'lamp' ? 0xFFB45E : 0xFFFFFF) }
    },
    vertexShader: STAGGER_VS,
    fragmentShader: kind === 'window' ? WIN_FS : BOX_FS
  });
  staggerMats.push(m);
  return m;
}
var winMatStg = makeStaggerMat('window');
var emisMatStg = makeStaggerMat('emis');
var lampMatStg = makeStaggerMat('lamp');

/* 灯头模板几何（灯身 + 顶帽） */
var lampHeadGeo = (function () {
  var bk = new Bucket('tmp');
  bk.push(new THREE.CylinderGeometry(0.13, 0.17, 0.3, 8), M(0, 0, 0, 1, 1, 1), new THREE.Color(1, 1, 1), null);
  bk.push(new THREE.ConeGeometry(0.18, 0.14, 8), M(0, 0.21, 0, 1, 1, 1), new THREE.Color(1, 1, 1), null);
  var geo = new THREE.BufferGeometry();
  geo.setAttribute('position', new THREE.Float32BufferAttribute(bk.pos, 3));
  geo.setAttribute('normal', new THREE.Float32BufferAttribute(bk.nrm, 3));
  geo.setAttribute('uv', new THREE.Float32BufferAttribute(bk.uv, 2));
  geo.setIndex(bk.idx);
  return geo;
})();

/* 灯晕点（同一错峰公式，加法混合） */
var glowUni = {
  uDuskSec: { value: 0 }, uLampT: { value: 0 }, uGlow: { value: 1 }, uTime: { value: 0 }, uScale: { value: 400 }
};
var glowPoints = new THREE.Points(
  new THREE.BufferGeometry(),
  new THREE.ShaderMaterial({
    uniforms: glowUni, transparent: true, depthWrite: false, blending: THREE.AdditiveBlending,
    vertexShader: [
      'uniform float uDuskSec, uLampT, uGlow, uTime, uScale;',
      'varying float vA;',
      'void main(){',
      '  vec4 mv = modelViewMatrix * vec4(position, 1.0);',
      /* 点位就是灯位（glowPoints 挂在 scene 根、无变换）⇒ 与灯头实例的 wxz 同值同式 */
      '  float key = ' + staggerKeyGLSL('position.xz') + ';',
      '  float on = clamp((uDuskSec - key * 12.0) / 0.9, 0.0, 1.0) * uLampT;',
      '  float flick = 0.8 + 0.2 * sin(uTime * 5.0 + key * 31.0);',
      '  vA = on * uGlow * flick;',
      '  gl_PointSize = (2.6 + 1.4 * on) * uScale / max(1.0, -mv.z);',
      '  gl_Position = projectionMatrix * mv;',
      '}'
    ].join('\n'),
    fragmentShader: [
      'varying float vA;',
      'void main(){',
      '  vec2 p = gl_PointCoord * 2.0 - 1.0;',
      '  float d = max(0.0, 1.0 - dot(p, p));',
      '  float a = d * d * vA;',
      '  gl_FragColor = vec4(vec3(1.0, 0.75, 0.42) * a, a);',
      '}'
    ].join('\n')
  }));
glowPoints.frustumCulled = false; glowPoints.renderOrder = 5;
scene.add(glowPoints);
var GLOW_MAX = 256, glowPos = new Float32Array(GLOW_MAX * 3);

/* 炊烟 / 萤火（Points，CPU 动画；reduceMotion 冻结） */
var smokeMat = new THREE.PointsMaterial({ map: glowTex, size: 1.1, transparent: true, depthWrite: false, opacity: 0, color: 0xF7F2E8 });
var smokePoints = new THREE.Points(new THREE.BufferGeometry(), smokeMat);
smokePoints.frustumCulled = false;
scene.add(smokePoints);
var SMOKE_MAX = 240, smokePos = new Float32Array(SMOKE_MAX * 3), smokeEmitters = [];
var fireflyMat = new THREE.PointsMaterial({ map: glowTex, size: 0.5, transparent: true, depthWrite: false, opacity: 0, color: 0xFFC878, blending: THREE.AdditiveBlending });
var fireflyPoints = new THREE.Points(new THREE.BufferGeometry(), fireflyMat);
fireflyPoints.frustumCulled = false;
scene.add(fireflyPoints);
var FF_N = 56, ffBase = new Float32Array(FF_N * 3), ffPh = new Float32Array(FF_N);
(function initFF() {
  var pos = new Float32Array(FF_N * 3);
  for (var i = 0; i < FF_N; i++) {
    var a = hashXZ(i * 7.3, i * 3.1) * PI * 2, r = 4 + Math.sqrt(hashXZ(i * 1.7, i * 9.9)) * 17;
    var fx = Math.cos(a) * r, fz = Math.sin(a) * r;
    ffBase[i * 3] = fx; ffBase[i * 3 + 1] = 0.9 + hashXZ(i * 5.5, i * 2.2) * 1.8; ffBase[i * 3 + 2] = fz;
    pos[i * 3] = fx; pos[i * 3 + 1] = ffBase[i * 3 + 1]; pos[i * 3 + 2] = fz;
    ffPh[i] = hashXZ(i * 4.4, i * 8.8) * 6.28;
  }
  fireflyPoints.geometry.setAttribute('position', new THREE.BufferAttribute(pos, 3));
})();
smokePoints.geometry.setAttribute('position', new THREE.BufferAttribute(smokePos, 3));

/* 动森式漂云（sprite 缓转；opacity/色随氛围档。高而远，只作点缀不遮天） */
var cloudSprites = [];
(function initClouds() {
  for (var i = 0; i < 5; i++) {
    var m = new THREE.SpriteMaterial({ map: puffTex, transparent: true, depthWrite: false, opacity: 0 });
    var sp = new THREE.Sprite(m);
    var a = hashXZ(i * 13.7, i * 7.1) * PI * 2, r = 64 + hashXZ(i * 3.3, i * 9.9) * 28;
    sp.position.set(Math.cos(a) * r, 26 + hashXZ(i * 5.1, i * 2.2) * 9, Math.sin(a) * r);
    var s = 6.5 + hashXZ(i * 8.8, i * 4.4) * 4;
    sp.scale.set(s, s * 0.42, 1);
    sp.userData = { a: a, r: r, sp: 0.004 + hashXZ(i, i * 1.3) * 0.004 };
    scene.add(sp); cloudSprites.push(sp);
  }
})();
function tickClouds(dt) {
  for (var i = 0; i < cloudSprites.length; i++) {
    var sp = cloudSprites[i];
    sp.userData.a += sp.userData.sp * dt;
    sp.position.x = Math.cos(sp.userData.a) * sp.userData.r;
    sp.position.z = Math.sin(sp.userData.a) * sp.userData.r;
  }
}

/* 飘落叶（贴树冠随机飘落；无树时隐藏） */
var leafMat = new THREE.PointsMaterial({ map: leafTex, size: 0.26, transparent: true, depthWrite: false, opacity: 0, color: 0x8FBF6A });
var leafPoints = new THREE.Points(new THREE.BufferGeometry(), leafMat);
leafPoints.frustumCulled = false;
scene.add(leafPoints);
var LEAF_N = 26, leafAnchors = [];
leafPoints.geometry.setAttribute('position', new THREE.BufferAttribute(new Float32Array(LEAF_N * 3), 3));
leafPoints.geometry.setDrawRange(0, 0);
function tickLeaves() {
  if (!leafAnchors.length || leafMat.opacity < 0.02) return;
  var pos = leafPoints.geometry.attributes.position;
  for (var i = 0; i < LEAF_N; i++) {
    var base = leafAnchors[i % leafAnchors.length];
    var ph = hashXZ(i * 7.7, i * 3.3) * 6.28;
    var p = (simT * 0.055 + hashXZ(i * 1.9, i * 5.5)) % 1;
    pos.setX(i, base.x + Math.sin(simT * 0.8 + ph) * 0.7 + p * 1.1);
    pos.setY(i, base.y + 1.1 - p * 2.4);
    pos.setZ(i, base.z + Math.cos(simT * 0.6 + ph * 1.4) * 0.6);
  }
  pos.needsUpdate = true;
}

/* 灯下光池（接地 additive 渐变盘 · 同一错峰公式驱动） */
var poolUni = { uDuskSec: { value: 0 }, uLampT: { value: 0 }, uGlow: { value: 1 }, uTime: { value: 0 } };
var poolMesh = null;
function rebuildPools() {
  if (poolMesh) { scene.remove(poolMesh); poolMesh.geometry.dispose(); poolMesh.material.dispose(); poolMesh = null; }
  if (!lampList.length) return;
  var pos = [], uv = [], idx = [], ctr = [];
  for (var i = 0; i < lampList.length; i++) {
    var l = lampList[i], R = 1.9, base = pos.length / 3;
    pos.push(l.x - R, 0.055, l.z - R, l.x + R, 0.055, l.z - R, l.x + R, 0.055, l.z + R, l.x - R, 0.055, l.z + R);
    uv.push(0, 0, 1, 0, 1, 1, 0, 1);
    for (var v4 = 0; v4 < 4; v4++) ctr.push(l.x, l.z); /* 四角同传灯心 XZ ⇒ 整块光池一个 key */
    idx.push(base, base + 2, base + 1, base, base + 3, base + 2);
  }
  var g = new THREE.BufferGeometry();
  g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
  g.setAttribute('uv', new THREE.Float32BufferAttribute(uv, 2));
  g.setAttribute('aCtr', new THREE.Float32BufferAttribute(ctr, 2));
  g.setIndex(idx);
  poolMesh = new THREE.Mesh(g, new THREE.ShaderMaterial({
    uniforms: poolUni, transparent: true, depthWrite: false, blending: THREE.AdditiveBlending,
    vertexShader: [
      'attribute vec2 aCtr;',
      'varying vec2 vUv; varying float vKey;',
      'void main(){ vUv = uv; vKey = ' + staggerKeyGLSL('aCtr') + ';',
      '  gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0); }'
    ].join('\n'),
    fragmentShader: [
      'uniform float uDuskSec, uLampT, uGlow, uTime;',
      'varying vec2 vUv; varying float vKey;',
      'void main(){',
      '  float on = clamp((uDuskSec - vKey * 12.0) / 0.9, 0.0, 1.0) * uLampT;',
      '  float flick = 0.9 + 0.1 * sin(uTime * 5.0 + vKey * 37.0);',
      '  vec2 p = vUv * 2.0 - 1.0;',
      '  float d = max(0.0, 1.0 - dot(p, p));',
      '  float a = d * d * on * uGlow * flick * 0.5;',
      '  gl_FragColor = vec4(vec3(1.0, 0.72, 0.4) * a, a);',
      '}'
    ].join('\n')
  }));
  poolMesh.renderOrder = 1;
  scene.add(poolMesh);
}

/* ================= 10. 建造器（数据 → 几何） ================= */
var townRoot = new THREE.Group(); scene.add(townRoot);
var winList = [], lampList = [], emisList = [], buildingMetas = [], places = [], labelEls = [], cardEls = [], overEls = [];
var blobList = [], aoMesh = null;
var curTown = null;

function clearTown() {
  Object.keys(buckets).forEach(function (k) {
    var bk = buckets[k];
    if (bk.mesh) { townRoot.remove(bk.mesh); bk.mesh.geometry.dispose(); bk.mesh = null; }
    bk.pos = []; bk.nrm = []; bk.col = []; bk.uv = []; bk.idx = []; bk.ranges = [];
  });
  [winList, lampList, emisList, buildingMetas, labelEls, cardEls, overEls].forEach(function (a) {
    a.forEach(function (e) { if (e && e.el && e.el.parentNode) e.el.parentNode.removeChild(e.el); });
    a.length = 0;
  });
  places.length = 0;
  if (winMesh) { townRoot.remove(winMesh); winMesh.geometry.dispose(); winMesh = null; }
  if (emisMesh) { townRoot.remove(emisMesh); emisMesh.geometry.dispose(); emisMesh = null; }
  if (lampMesh) { townRoot.remove(lampMesh); lampMesh.geometry.dispose(); lampMesh = null; }
  smokeEmitters.length = 0;
  smokePoints.geometry.setDrawRange(0, 0);
  glowPoints.geometry.setDrawRange(0, 0);
  leafAnchors.length = 0;
  leafPoints.geometry.setDrawRange(0, 0);
  blobList.length = 0;
  if (placeRing) placeRing.visible = false; /* W-5：换城清选中金环 */
  if (aoMesh) { scene.remove(aoMesh); aoMesh.geometry.dispose(); aoMesh.material.dispose(); aoMesh = null; }
  if (poolMesh) { scene.remove(poolMesh); poolMesh.geometry.dispose(); poolMesh.material.dispose(); poolMesh = null; }
  resetWalkers(); /* 三期卷一：换城清走动客（栅格由 buildTown 随后重建） */
  WG = null;
}
function pushBlob(x, z, sx, sz) { blobList.push({ x: x, z: z, sx: sx, sz: sz }); }
function rebuildContactAO() {
  if (aoMesh) { scene.remove(aoMesh); aoMesh.geometry.dispose(); aoMesh.material.dispose(); aoMesh = null; }
  if (!blobList.length) return;
  var pos = [], uv = [], idx = [];
  for (var i = 0; i < blobList.length; i++) {
    var b = blobList[i], rx = b.sx / 2, rz = b.sz / 2, base = pos.length / 3;
    pos.push(b.x - rx, 0.021, b.z - rz, b.x + rx, 0.021, b.z - rz, b.x + rx, 0.021, b.z + rz, b.x - rx, 0.021, b.z + rz);
    uv.push(0, 0, 1, 0, 1, 1, 0, 1);
    idx.push(base, base + 2, base + 1, base, base + 3, base + 2);
  }
  var g = new THREE.BufferGeometry();
  g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
  g.setAttribute('uv', new THREE.Float32BufferAttribute(uv, 2));
  g.setIndex(idx);
  aoMesh = new THREE.Mesh(g, new THREE.MeshBasicMaterial({ map: aoTex, transparent: true, depthWrite: false }));
  aoMesh.renderOrder = 0.5;
  scene.add(aoMesh);
}
var winMesh = null, emisMesh = null, lampMesh = null;

function addWindow(x, y, z, rotY, s) { winList.push({ x: x, y: y, z: z, rotY: rotY, s: s }); }
function metaFor(cx, cz, sx, sz) { var m = { cx: cx, cz: cz, sx: sx, sz: sz, placeId: null }; buildingMetas.push(m); return m; }

function buildCottage(b) {
  var sx = b.sx, h = b.h, sz = b.sz, cx = b.cx, cz = b.cz;
  var wall = col3(b.wall), roofC = col3(b.roof);
  var key = hashXZ(cx * 3.7 + 11, cz * 2.9 + 5);
  var y0 = 0;
  bucket('plain').push(G.box, M(cx, y0 + 0.07, cz, sx + 0.18, 0.16, sz + 0.18), new THREE.Color(0.45, 0.4, 0.34), null);
  pushBlob(cx, cz, sx + 1.6, sz + 1.6);
  var meta = metaFor(cx, cz, sx, sz);
  bucket('plaster').push(G.box, M(cx, y0 + 0.14 + h / 2, cz, sx, h, sz), wall, meta);
  var roofH = clamp(Math.min(sx, sz) * 0.5, 0.75, 1.9);
  bucket('roof').push(G.cyl, M(cx, y0 + 0.14 + h + 0.06, cz, sx / 2 * 1.2, 0.14, sz / 2 * 1.2), roofC.clone().multiplyScalar(0.82), meta);
  bucket('roof').push(G.dome, M(cx, y0 + 0.14 + h, cz, sx / 2 * 1.18, roofH, sz / 2 * 1.18), roofC, meta);
  bucket('plain').push(G.sphere, M(cx, y0 + 0.14 + h + roofH * 0.96, cz, 0.15, 0.15, 0.15), new THREE.Color(0.91, 0.77, 0.49), null);
  bucket('door').push(G.door, M(cx, y0 + 0.14 + 0.69, cz + sz / 2 + 0.03, 1, 1, 1), new THREE.Color(1, 1, 1), meta);
  bucket('stone').push(G.box, M(cx, y0 + 0.06, cz + sz / 2 + 0.18, 1.05, 0.11, 0.3), new THREE.Color(0.72, 0.66, 0.55), null);
  bucket('plain').push(G.box, M(cx, y0 + 1.66, cz + sz / 2 + 0.2, 1.0, 0.07, 0.55, -0.42, 0, 0), new THREE.Color(0.55, 0.39, 0.26), null);
  var n = (b.windows | 0), wy = y0 + 0.14 + h * 0.62, ws = clamp(Math.min(sx, h) * 0.26, 0.46, 0.62);
  if (n >= 2) {
    addWindow(cx - sx * 0.32, wy, cz + sz / 2 + 0.03, 0, ws);
    addWindow(cx + sx * 0.32, wy, cz + sz / 2 + 0.03, 0, ws);
  } else {
    addWindow(cx + sx * 0.3, wy, cz + sz / 2 + 0.03, 0, ws);
  }
  if (n >= 3) addWindow(cx + sx / 2 + 0.03, wy, cz, PI / 2, ws);
  if (key > 0.52 && h >= 1.9) {
    bucket('plaster').push(G.box, M(cx + sx * 0.26, y0 + h + roofH * 0.45, cz - sz * 0.16, 0.4, roofH * 0.9 + 0.3, 0.4), wall.clone().multiplyScalar(1.04), null);
    bucket('plain').push(G.box, M(cx + sx * 0.26, y0 + h + roofH * 0.45 + (roofH * 0.9 + 0.3) / 2, cz - sz * 0.16, 0.54, 0.14, 0.54), new THREE.Color(0.5, 0.35, 0.26), null);
    smokeEmitters.push({ x: cx + sx * 0.26, y: y0 + h + roofH * 0.45 + roofH * 0.9 + 0.35, z: cz - sz * 0.16, ph: key * 7 });
  }
  if (key < 0.3) {
    var fbx = cx - sx * 0.32, fbz = cz + sz / 2 + 0.1;
    bucket('plain').push(G.box, M(fbx, wy - 0.42, fbz, 0.8, 0.2, 0.22), new THREE.Color(0.43, 0.29, 0.19), null);
    var fcols = [[0.91, 0.42, 0.42], [0.95, 0.76, 0.31], [0.91, 0.6, 0.72], [1, 0.96, 0.91]];
    for (var f = 0; f < 3; f++) {
      var fc = fcols[Math.floor(hashXZ(cx + f * 3.3, cz - f * 1.7) * 4)];
      bucket('plain').push(G.sphere, M(fbx - 0.22 + f * 0.22, wy - 0.26, fbz + 0.03, 0.09, 0.09, 0.09), new THREE.Color(fc[0], fc[1], fc[2]), null);
    }
  }
}
function buildFiller(f) {
  var sx = 2.4, h = 1.8, sz = 2.1, cx = f.cx, cz = f.cz;
  var wall = col3(f.wall);
  bucket('plain').push(G.box, M(cx, 0.07, cz, sx + 0.14, 0.14, sz + 0.14), new THREE.Color(0.45, 0.4, 0.34), null);
  pushBlob(cx, cz, sx + 1.4, sz + 1.4);
  var meta = metaFor(cx, cz, sx, sz);
  bucket('plaster').push(G.box, M(cx, 0.14 + h / 2, cz, sx, h, sz), wall, meta);
  bucket('roof').push(G.dome, M(cx, 0.14 + h, cz, sx / 2 * 1.18, 0.95, sz / 2 * 1.18), wall.clone().multiplyScalar(0.62), meta);
  bucket('door').push(G.door, M(cx, 0.14 + 0.69, cz + sz / 2 + 0.03, 1, 1, 1), new THREE.Color(1, 1, 1), meta);
  addWindow(cx + sx * 0.26, 0.14 + h * 0.62, cz + sz / 2 + 0.03, 0, 0.52);
}
function buildTree(t) {
  var s = t.s || 1, cx = t.cx, cz = t.cz, leaf = col3(t.leaf);
  var trunkH = (t.trunkH || 0.7) * s, coneH = (t.coneH || 1.5) * s;
  bucket('plain').push(G.cyl, M(cx, trunkH / 2, cz, 0.13 * s, trunkH, 0.13 * s), new THREE.Color(0.44, 0.31, 0.2), null);
  var pine = (t.coneH || 1.5) >= 2.0;
  pushBlob(cx, cz, 2.4 * s, 2.4 * s);
  if (pine) {
    var rr = [0.6, 0.45, 0.3];
    for (var i = 0; i < 3; i++) {
      bucket('leaves').push(G.cone, M(cx, trunkH + coneH * (0.24 + 0.3 * i), cz, rr[i] * s, coneH * 0.55, rr[i] * s), leaf, null);
    }
    bucket('leaves').push(G.cone, M(cx - 0.08 * s, trunkH + coneH * 0.62, cz - 0.06 * s, 0.36 * s, coneH * 0.34, 0.36 * s), leaf.clone().multiplyScalar(1.16), null);
  } else {
    bucket('leaves').push(G.sphere, M(cx, trunkH + coneH * 0.5, cz, 0.95 * s, 0.8 * s, 0.95 * s), leaf, null);
    bucket('leaves').push(G.sphere, M(cx + 0.5 * s, trunkH + coneH * 0.28, cz + 0.18 * s, 0.55 * s, 0.48 * s, 0.55 * s), leaf.clone().multiplyScalar(0.92), null);
    bucket('leaves').push(G.sphere, M(cx - 0.18 * s, trunkH + coneH * 0.66, cz - 0.12 * s, 0.6 * s, 0.46 * s, 0.6 * s), leaf.clone().multiplyScalar(1.18), null);
  }
  leafAnchors.push({ x: cx, y: trunkH + coneH * 0.5, z: cz });
}
function buildLantern(l) {
  var by = l.baseY || 0, cx = l.cx, cz = l.cz;
  var dark = new THREE.Color(0.29, 0.22, 0.15);
  pushBlob(cx, cz, 1.5, 1.5);
  bucket('plain').push(G.box, M(cx, by + 0.17, cz, 0.4, 0.34, 0.4), dark, null);
  bucket('plain').push(G.cyl, M(cx, by + 0.34 + 1.25, cz, 0.05, 2.5, 0.05), dark, null);
  bucket('plain').push(G.box, M(cx + 0.2, by + 2.82, cz, 0.52, 0.05, 0.05), dark, null);
  lampList.push({ x: cx + 0.42, y: by + 2.6, z: cz });
}
function buildGrammar(it) {
  if (it.t === 'lit') {
    var cx = it.x + it.sx / 2, cz = it.z + it.sz / 2;
    var meta = metaFor(cx, cz, it.sx, it.sz);
    pushBlob(cx, cz, it.sx + 1.4, it.sz + 1.4);
    bucket('plaster').push(G.box, M(cx, (it.y || 0) + it.h / 2, cz, it.sx, it.h, it.sz), col3(it.col), meta);
    if (it.h >= 1.2) bucket('door').push(G.door, M(cx, (it.y || 0) + 0.55 + 0.69, cz + it.sz / 2 + 0.03, 1, 1, 1), new THREE.Color(1, 1, 1), meta);
  } else if (it.t === 'roof') {
    var rx = it.x + it.sx / 2, rz = it.z + it.sz / 2, ry = it.y || 0;
    var meta2 = metaFor(rx, rz, it.sx, it.sz);
    var colr = col3(it.col);
    if (it.style === 'GABLE') {
      bucket('roof').push(G.gable, M(rx, ry, rz, it.sx, it.h, it.sz), colr, meta2);
    } else if (it.style === 'PYRAMID') {
      bucket('roof').push(G.pyramid, M(rx, ry, rz, it.sx, it.h, it.sz), colr, meta2);
    } else { /* FLAT：平顶 + 女儿墙 */
      bucket('plain').push(G.box, M(rx, ry + 0.08, rz, it.sx, 0.16, it.sz), colr, meta2);
      bucket('plain').push(G.box, M(rx, ry + 0.3, rz - it.sz / 2 + 0.09, it.sx, 0.28, 0.18), colr.clone().multiplyScalar(0.9), null);
      bucket('plain').push(G.box, M(rx, ry + 0.3, rz + it.sz / 2 - 0.09, it.sx, 0.28, 0.18), colr.clone().multiplyScalar(0.9), null);
      bucket('plain').push(G.box, M(rx - it.sx / 2 + 0.09, ry + 0.3, rz, 0.18, 0.28, it.sz), colr.clone().multiplyScalar(0.9), null);
      bucket('plain').push(G.box, M(rx + it.sx / 2 - 0.09, ry + 0.3, rz, 0.18, 0.28, it.sz), colr.clone().multiplyScalar(0.9), null);
    }
  } else if (it.t === 'emis') {
    emisList.push({ x: it.x + it.sx / 2, y: (it.y || 0) + it.h / 2, z: it.z + it.sz / 2, sx: it.sx, h: it.h, sz: it.sz, col: col3(it.col) });
  }
}

function buildTown(j) {
  clearTown();
  curTown = j;
  /* AI 地表已接管时 ground tint 仅作参考（贴图原色呈现）；程序化兜底时照常吃 tint */
  if (groundAiOn) groundMat.color.set(0xffffff);
  else groundMat.color.copy(col3(j.ground || [0.62, 0.71, 0.44]));
  /* 白天贴图由数据 stops 现烤（正在显示也要换） */
  var oldDay = skyTexDay;
  skyTexDay = buildSkyTex(j.sky, 'day');
  if (skyFrontName === 'day') skyFront.material.map = skyTexDay;
  if (skyBack.visible && skyBack.material.map === oldDay) skyBack.material.map = skyTexDay;
  oldDay.dispose();
  setupWater(j.water);

  (j.buildings || []).forEach(buildCottage);
  (j.fillers || []).forEach(buildFiller);
  (j.grammar || []).forEach(buildGrammar);
  (j.trees || []).forEach(buildTree);
  (j.lanterns || []).forEach(buildLantern);
  (j.litBoxes || []).forEach(function (b) {
    bucket('lit').push(G.box, M(b.cx, (b.y0 || 0) + b.h / 2 + 0.012, b.cz, b.sx, b.h, b.sz), col3(b.col), metaFor(b.cx, b.cz, b.sx, b.sz));
  });
  (j.emisBoxes || []).forEach(function (b) {
    emisList.push({ x: b.cx, y: (b.y0 || 0) + b.h / 2, z: b.cz, sx: b.sx, h: b.h, sz: b.sz, col: col3(b.col) });
  });
  (j.cones || []).forEach(function (c) {
    bucket('plain').push(G.cone, M(c.cx, (c.y || 0) + c.h / 2, c.cz, c.r, c.h, c.r), col3(c.col), metaFor(c.cx, c.cz, c.r * 2, c.r * 2));
    pushBlob(c.cx, c.cz, c.r * 2 + 0.8, c.r * 2 + 0.8);
  });
  (j.places || []).forEach(function (p) { places.push(p); });
  setupPlaces();

  /* 落盘：合并桶 → mesh */
  Object.keys(buckets).forEach(function (k) {
    var bk = buckets[k];
    if (!bk.pos.length) return;
    var geo = new THREE.BufferGeometry();
    geo.setAttribute('position', new THREE.Float32BufferAttribute(bk.pos, 3));
    geo.setAttribute('normal', new THREE.Float32BufferAttribute(bk.nrm, 3));
    geo.setAttribute('color', new THREE.Float32BufferAttribute(bk.col, 3));
    geo.setAttribute('uv', new THREE.Float32BufferAttribute(bk.uv, 2));
    geo.setIndex(bk.idx);
    var mesh = new THREE.Mesh(geo, BUCKET_MATS[k]);
    mesh.castShadow = true; mesh.receiveShadow = true;
    mesh.userData.bucket = bk;
    bk.mesh = mesh;
    townRoot.add(mesh);
  });
  /* 窗实例 */
  if (winList.length) {
    winMesh = new THREE.InstancedMesh(G.win.clone(), winMatStg, winList.length);
    winList.forEach(function (w, i) {
      _q.setFromEuler(new THREE.Euler(0, w.rotY, 0));
      winMesh.setMatrixAt(i, _m4.compose(_v3.set(w.x, w.y, w.z), _q, _s3.set(w.s, w.s, 1)));
    });
    winMesh.instanceMatrix.needsUpdate = true;
    townRoot.add(winMesh);
  }
  /* 发光盒实例（grammar emis + emisBoxes，含 instanceColor） */
  if (emisList.length) {
    emisMesh = new THREE.InstancedMesh(G.box.clone(), emisMatStg, emisList.length);
    emisList.forEach(function (e, i) {
      _q.setFromEuler(new THREE.Euler(0, 0, 0));
      emisMesh.setMatrixAt(i, _m4.compose(_v3.set(e.x, e.y, e.z), _q, _s3.set(e.sx, e.h, e.sz)));
      emisMesh.setColorAt(i, e.col);
    });
    emisMesh.instanceMatrix.needsUpdate = true;
    if (emisMesh.instanceColor) emisMesh.instanceColor.needsUpdate = true;
    townRoot.add(emisMesh);
  }
  /* 灯头实例 */
  if (lampList.length) {
    lampMesh = new THREE.InstancedMesh(lampHeadGeo.clone(), lampMatStg, lampList.length);
    lampList.forEach(function (l, i) {
      _q.setFromEuler(new THREE.Euler(0, 0, 0));
      lampMesh.setMatrixAt(i, _m4.compose(_v3.set(l.x, l.y, l.z), _q, _s3.set(1, 1, 1)));
    });
    lampMesh.instanceMatrix.needsUpdate = true;
    townRoot.add(lampMesh);
  }
  /* 灯晕点 */
  var gn = Math.min(lampList.length, GLOW_MAX);
  for (var i = 0; i < gn; i++) {
    glowPos[i * 3] = lampList[i].x; glowPos[i * 3 + 1] = lampList[i].y; glowPos[i * 3 + 2] = lampList[i].z;
  }
  glowPoints.geometry.setAttribute('position', new THREE.BufferAttribute(glowPos, 3));
  glowPoints.geometry.setDrawRange(0, gn);
  /* 炊烟点 */
  var sn = Math.min(smokeEmitters.length * 4, SMOKE_MAX);
  smokePoints.geometry.setDrawRange(0, sn);
  updateSmoke(0);
  /* 落叶 / 灯下光池 / 接触阴影 */
  leafPoints.geometry.setDrawRange(0, Math.min(LEAF_N, leafAnchors.length));
  rebuildContactAO();
  rebuildPools();

  assignPlaces();
  buildWalkGrid(j); /* 三期卷一：可走域栅格（走动客寻路用·§3.3） */
}
/* 名签 DOM */
function setupPlaces() {
  var layer = document.getElementById('placeLayer') || document.body;
  places.forEach(function (p) {
    var el = document.createElement('div');
    el.className = 'place-tag';
    el.textContent = p.name || p.id;
    el.addEventListener('click', function (ev) {
      ev.stopPropagation();
      if (flags.interactive) bridge('onTapPlace', p.id);
    });
    layer.appendChild(el);
    labelEls.push({ el: el, p: p });
  });
}
function assignPlaces() {
  buildingMetas.forEach(function (m) {
    var best = null, bestD = 1e9;
    places.forEach(function (p) {
      var d = Math.hypot(m.cx - p.x, m.cz - p.z);
      var reach = 3.4 + 0.5 * Math.hypot(m.sx, m.sz);
      if (d < reach && d < bestD) { bestD = d; best = p.id; }
    });
    m.placeId = best;
  });
}

/* ================= 11. 居民卡层 =================
   三期卷一：walking===true 的卡不再钉浮卡，转交 §11b 居民走动系统呈现；
   其余卡（字段缺席当 false）钉卡呈现零变化。钉卡构建抽成 makePinnedCard，
   供「走动翻转 true→false」的退场后延迟转正复用（§3.2）。 */
function makePinnedCard(c) {
  var el = document.createElement('div');
  el.className = 'cast-card' + (c.kind === 'mystery' ? ' mystery' : '') + (c.kind === 'pet' ? ' pet' : '') + (c.present === false ? ' absent' : '');
  var av = document.createElement('div'); av.className = 'cast-av';
  if (c.kind === 'mystery') {
    av.textContent = '?';
  } else if (c.avatar) {
    var img = document.createElement('img');
    img.src = (c.avatar.indexOf('data:') === 0) ? c.avatar : ('data:image/jpeg;base64,' + c.avatar);
    av.appendChild(img);
  } else {
    av.textContent = (c.name && c.name.length) ? c.name.trim().charAt(0) : '?';
  }
  el.appendChild(av);
  if (c.name && c.kind !== 'mystery') {
    var nm = document.createElement('div'); nm.className = 'cast-name'; nm.textContent = c.name;
    el.appendChild(nm);
  }
  /* W-1（契约 v1.2）：状态下标位——睡着了（CSS 月牙·GLM 稿）/ 在家；字段缺席当 false·mystery 恒无 */
  if (c.kind !== 'mystery' && (c.sleeping || c.atHome)) {
    var sub = document.createElement('div');
    sub.className = 'cast-state' + (c.sleeping ? ' asleep' : '');
    if (c.sleeping) { var mn = document.createElement('span'); mn.className = 'moon'; sub.appendChild(mn); }
    sub.appendChild(document.createTextNode(c.sleeping ? '睡着了' : '在家'));
    el.appendChild(sub);
  }
  el.addEventListener('click', function (ev) { ev.stopPropagation(); if (flags.interactive) bridge('onTapCast', c.id); });
  document.body.appendChild(el);
  cardEls.push({ el: el, x: c.x, y: c.y, z: c.z });
}
function setCastLayer(castJson) {
  lastCastRef = castJson || null;
  cardEls.forEach(function (c) { if (c.el.parentNode) c.el.parentNode.removeChild(c.el); });
  overEls.forEach(function (c) { if (c.el.parentNode) c.el.parentNode.removeChild(c.el); });
  cardEls.length = 0; overEls.length = 0;
  if (!castJson) { if (walkersEnabled) { try { reconcileWalkers([]); } catch (e) { walkFail(e); } } dirty = true; return; }
  var cards = castJson.cards || [];
  if (walkersEnabled) { try { reconcileWalkers(cards); } catch (e) { walkFail(e); } }
  cards.forEach(function (c) {
    if (walkersEnabled && c && c.walking === true) return;            /* 走动客由 §11b 呈现 */
    if (walkersEnabled && c && c.id && walkerById.get(c.id)) return;  /* 翻转 true→false：退场完再钉卡（§3.2） */
    makePinnedCard(c);
  });
  (castJson.overflows || []).forEach(function (o) {
    var el = document.createElement('div');
    el.className = 'cast-over';
    el.textContent = '+' + (o.count | 0);
    document.body.appendChild(el);
    overEls.push({ el: el, x: o.x, y: o.y, z: o.z });
  });
  dirty = true;
}

/* ================= 11b. 居民走动（三期卷一 · 契约 §2-§6） =================
   walking===true 的卡 → 镇里散步的 3D 小人：真网格进深度、被房子挡住真被挡住，
   头顶跟随可点标识，点了照旧 bridge('onTapCast', id)。
   - §3.2 增量对账（同 id 保位保路线 / id 消失退场 / 翻转切换）
   - §3.3 可走域栅格（0.5m 格，从 townJson 几何推导阻挡 + 水带 + 活动盘界）
   - §3.4 降级（reduceMotion/staticMode 静立环位锚·时间基 simT·frozen 自停）
   - §3.5 id 种子错峰（速度/驻足/步相/灯火全由 id 哈希，纯 CPU 侧）
   - §3.6 走动上限 10（超出静立锚位）；实例槽 16 兜底，再超钉卡
   - §4  身体唯一工厂 createWalkerBody（卷二 GLB 换模 = 整体替换工厂内部，外部零改动）
   红线：帧循环零分配（栅格/队列/路径/临时向量全预分配）·零 CanvasGradient·
        任何非致命异常静默降级为钉卡（绝不拉全页兜底）·小人族 draw call ≤12。 */
var WALK_SLOTS = 16, WALK_CAP = 10;
var walkersEnabled = true;
var walkerById = new Map();          /* id → walker（对账用·事件期分配） */
var walkerSlots = [];                /* 槽位 → walker|null（槽号 = 各实例网格的 instanceId） */
var leavingPins = new Map();         /* id → card：翻转 true→false 的延迟钉卡 */
var lastCastRef = null;              /* 最近一次 castJson（系统故障静默降级时整卷转钉卡） */
var walkRR = 0, walkLastSimT = 0, walkLastWall = 0, walkWasDegraded = null;
var walkTransient = false; /* 机审修缮①：有走动客处于过渡态（退场/入场/滑步）→ staticMode 也保帧直至落定 */
var walkLayoutList = [];             /* 标识排版复用列表（shim 同 layoutLayer 结构） */
var _freeTmp = { x: 0, z: 0 };
var _wa4 = new THREE.Matrix4(), _waq = new THREE.Quaternion(), _wae = new THREE.Euler(0, 0, 0, 'YXZ');
var _wav = new THREE.Vector3(), _was = new THREE.Vector3(), _cTmp2 = new THREE.Color(), _cWhite = new THREE.Color(1, 1, 1);

function seedFromId(s) {
  var h = 2166136261;
  for (var i = 0; i < s.length; i++) { h ^= s.charCodeAt(i); h = Math.imul(h, 16777619); }
  return h >>> 0;
}
function walkRng01(w) { w.rng = (Math.imul(w.rng, 1664525) + 1013904223) >>> 0; return w.rng / 4294967296; }

/* ---- 可走域栅格（§3.3）。阻挡口径与建造器逐条对齐：
        buildings/fillers/grammar lit（含膨胀 0.6）、树/灯/锥（圆）、litBox 立体件（h>0.2）、
        水带（WEST_RIVER x<-13.6 / EAST_SEA x>11.8）、活动盘 r≤20.5；
        平贴地块（广场/池畔平台 h≤0.2 且面积≥1.2）可走并记 deck 供落脚高度，
        压水的（码头）仍阻挡。 ---- */
var WG = null;
function buildWalkGrid(j) {
  var half = 21, cell = 0.5, n = Math.ceil(half * 2 / cell);
  var blocked = new Uint8Array(n * n);
  var rects = [], circles = [], decks = [];
  function R(x0, z0, x1, z1) { rects.push(x0, z0, x1, z1); }
  function C(cx, cz, r) { circles.push(cx, cz, r); }
  var water = j.water;
  if (water === 'WEST_RIVER') R(-99, -99, -13.6, 99);
  else if (water === 'EAST_SEA') R(11.8, -99, 99, 99);
  (j.buildings || []).forEach(function (b) {
    R(b.cx - b.sx / 2 - 0.62, b.cz - b.sz / 2 - 0.62, b.cx + b.sx / 2 + 0.62, b.cz + b.sz / 2 + 0.62);
  });
  (j.fillers || []).forEach(function (f) { R(f.cx - 1.8, f.cz - 1.65, f.cx + 1.8, f.cz + 1.65); });
  (j.grammar || []).forEach(function (it) {
    if (it.t === 'lit') R(it.x - 0.6, it.z - 0.6, it.x + it.sx + 0.6, it.z + it.sz + 0.6);
  });
  (j.trees || []).forEach(function (t) { var s = t.s || 1; C(t.cx, t.cz, 0.55 + 0.42 * s); });
  (j.lanterns || []).forEach(function (l) { C(l.cx, l.cz, 0.55); });
  (j.cones || []).forEach(function (c) { C(c.cx, c.cz, (c.r || 0.5) + 0.35); });
  (j.litBoxes || []).forEach(function (b) {
    var h = b.h || 0, x0 = b.cx - b.sx / 2, x1 = b.cx + b.sx / 2, z0 = b.cz - b.sz / 2, z1 = b.cz + b.sz / 2;
    if (h > 0.2) { R(x0 - 0.45, z0 - 0.45, x1 + 0.45, z1 + 0.45); return; } /* 井台/长椅/礁石等立体件 */
    var inWater = (water === 'WEST_RIVER' && x0 < -15) || (water === 'EAST_SEA' && x1 > 13);
    if (inWater) R(x0 - 0.5, z0 - 0.5, x1 + 0.5, z1 + 0.5);
    else if (b.sx * b.sz >= 1.2) decks.push({ x0: x0, z0: z0, x1: x1, z1: z1, top: (b.y0 || 0) + h });
  });
  (j.emisBoxes || []).forEach(function (b) {
    if ((b.y0 || b.y || 0) < 0.5) R(b.cx - b.sx / 2 - 0.4, b.cz - b.sz / 2 - 0.4, b.cx + b.sx / 2 + 0.4, b.cz + b.sz / 2 + 0.4);
  });
  function markRect(x0, z0, x1, z1) {
    var i0 = clamp(Math.floor((x0 + half) / cell), 0, n - 1), i1 = clamp(Math.ceil((x1 + half) / cell), 0, n - 1);
    var k0 = clamp(Math.floor((z0 + half) / cell), 0, n - 1), k1 = clamp(Math.ceil((z1 + half) / cell), 0, n - 1);
    for (var ii = i0; ii <= i1; ii++) for (var kk = k0; kk <= k1; kk++) blocked[ii * n + kk] = 1;
  }
  var ri;
  for (ri = 0; ri < rects.length; ri += 4) markRect(rects[ri], rects[ri + 1], rects[ri + 2], rects[ri + 3]);
  for (ri = 0; ri < circles.length; ri += 3) {
    var ccx = circles[ri], ccz = circles[ri + 1], cr = circles[ri + 2];
    var gi0 = Math.max(0, Math.floor((ccx - cr + half) / cell)), gi1 = Math.min(n - 1, Math.ceil((ccx + cr + half) / cell));
    var gk0 = Math.max(0, Math.floor((ccz - cr + half) / cell)), gk1 = Math.min(n - 1, Math.ceil((ccz + cr + half) / cell));
    for (var gi = gi0; gi <= gi1; gi++) for (var gk = gk0; gk <= gk1; gk++) {
      var gx = (gi + 0.5) * cell - half, gz = (gk + 0.5) * cell - half;
      if ((gx - ccx) * (gx - ccx) + (gz - ccz) * (gz - ccz) < cr * cr) blocked[gi * n + gk] = 1;
    }
  }
  for (var di = 0; di < n; di++) for (var dk = 0; dk < n; dk++) {
    var dx0 = (di + 0.5) * cell - half, dz0 = (dk + 0.5) * cell - half;
    if (dx0 * dx0 + dz0 * dz0 > 20.5 * 20.5) blocked[di * n + dk] = 1;
  }
  WG = { n: n, half: half, cell: cell, blocked: blocked, decks: decks,
         prev: new Int32Array(n * n), seen: new Int32Array(n * n), stamp: 0,
         queue: new Int32Array(n * n), rev: new Int32Array(512) };
}
function walkCellOf(x, z) {
  var i = Math.floor((x + WG.half) / WG.cell), k = Math.floor((z + WG.half) / WG.cell);
  return (i < 0 || k < 0 || i >= WG.n || k >= WG.n) ? -1 : i * WG.n + k;
}
function walkBlocked(x, z) {
  if (!WG) return true;
  var c = walkCellOf(x, z);
  return c < 0 || WG.blocked[c] === 1;
}
function walkGroundY(x, z) {
  var top = 0;
  for (var i = 0; i < WG.decks.length; i++) {
    var d = WG.decks[i];
    if (x >= d.x0 && x <= d.x1 && z >= d.z0 && z <= d.z1 && d.top > top) top = d.top;
  }
  return top;
}
function walkFreeNear(x, z, out) { /* 螺旋找最近空格（锚位被阻挡时落脚步） */
  if (!WG) return false;
  var n = WG.n, ci = Math.floor((x + WG.half) / WG.cell), ck = Math.floor((z + WG.half) / WG.cell);
  for (var r = 0; r <= 24; r++) {
    for (var di = -r; di <= r; di++) for (var dk = -r; dk <= r; dk++) {
      if (Math.max(Math.abs(di), Math.abs(dk)) !== r) continue;
      var ii = ci + di, kk = ck + dk;
      if (ii < 0 || kk < 0 || ii >= n || kk >= n) continue;
      if (WG.blocked[ii * n + kk]) continue;
      out.x = (ii + 0.5) * WG.cell - WG.half; out.z = (kk + 0.5) * WG.cell - WG.half;
      return true;
    }
  }
  return false;
}
function walkLOS(x0, z0, x1, z1) {
  var dx = x1 - x0, dz = z1 - z0, d = Math.hypot(dx, dz);
  var steps = Math.ceil(d / 0.28);
  for (var i = 1; i < steps; i++) {
    var t = i / steps;
    if (walkBlocked(x0 + dx * t, z0 + dz * t)) return false;
  }
  return true;
}
/* 预分配 BFS（4 邻域）+ 视线拉直 → 路径写进 w.path（Float32Array 复用），返回点数 */
function walkFindPath(w, tx, tz) {
  if (!WG) return 0;
  var sx = w.x, sz = w.z;
  if (walkBlocked(sx, sz)) { if (!walkFreeNear(sx, sz, _freeTmp)) return 0; sx = _freeTmp.x; sz = _freeTmp.z; }
  if (walkBlocked(tx, tz)) { if (!walkFreeNear(tx, tz, _freeTmp)) return 0; tx = _freeTmp.x; tz = _freeTmp.z; }
  var n = WG.n, start = walkCellOf(sx, sz), goal = walkCellOf(tx, tz);
  if (start < 0 || goal < 0) return 0;
  if (start === goal) { w.path[0] = tx; w.path[1] = tz; return 1; }
  WG.stamp++;
  var q = WG.queue, prev = WG.prev, seen = WG.seen, stamp = WG.stamp;
  var qh = 0, qt = 0, found = false;
  q[qt++] = start; seen[start] = stamp; prev[start] = -1;
  while (qh < qt) {
    var cur = q[qh++];
    if (cur === goal) { found = true; break; }
    var ci = (cur / n) | 0, ck = cur - ci * n;
    if (ci > 0) { var a = cur - n; if (seen[a] !== stamp && !WG.blocked[a]) { seen[a] = stamp; prev[a] = cur; q[qt++] = a; } }
    if (ci < n - 1) { var b = cur + n; if (seen[b] !== stamp && !WG.blocked[b]) { seen[b] = stamp; prev[b] = cur; q[qt++] = b; } }
    if (ck > 0) { var c = cur - 1; if (seen[c] !== stamp && !WG.blocked[c]) { seen[c] = stamp; prev[c] = cur; q[qt++] = c; } }
    if (ck < n - 1) { var d = cur + 1; if (seen[d] !== stamp && !WG.blocked[d]) { seen[d] = stamp; prev[d] = cur; q[qt++] = d; } }
  }
  if (!found) return 0;
  var rn = 0, rev = WG.rev, cell = goal;
  while (cell !== -1 && rn < 512) { rev[rn++] = cell; cell = prev[cell]; }
  /* 机审修缮④：视线拉直重写。原实现两处会产出「未经视线检查的长腿」，穿楼而过——
     ① 内层从近端起扫、见首个可视即断 ⇒ 实际每次只前进一格（压缩失效），跨镇路线必超
        44 格上限，超限后把终点直接缀在路尾 = 一条未检查的直线大腿（实测 c7 pathN=45
        走 12 单位直线横穿建筑群实锤）；② 全部不可视时落到 rev[0]=终点格同样未检查。
     现改标准贪心：从最远(goal 端)往近扫，取第一个通过 walkLOS 的点跳过去；一个都不可视
     则退到相邻 BFS 格（4 邻接天然安全无需检查）。截断时不再缀终点——走到哪算哪，
     到点后驻足、下次寻路自然接续。 */
  var count = 0, px = sx, pz = sz, curIdx = rn - 1;
  w.path[0] = px; w.path[1] = pz; count++;
  while (curIdx > 0 && count < 47) {
    var far = curIdx - 1;
    for (var pi2 = 0; pi2 < curIdx - 1; pi2++) {
      var pc = rev[pi2];
      var pxx = (((pc / n) | 0) + 0.5) * WG.cell - WG.half, pzz = ((pc - ((pc / n) | 0) * n) + 0.5) * WG.cell - WG.half;
      if (walkLOS(px, pz, pxx, pzz)) { far = pi2; break; }
    }
    var fc = rev[far];
    px = (((fc / n) | 0) + 0.5) * WG.cell - WG.half; pz = ((fc - ((fc / n) | 0) * n) + 0.5) * WG.cell - WG.half;
    w.path[count * 2] = px; w.path[count * 2 + 1] = pz; count++;
    curIdx = far;
  }
  if (curIdx === 0) { w.path[count * 2] = tx; w.path[count * 2 + 1] = tz; count++; }
  return count;
}

/* ---- §4 身体工厂（唯一）：网格/材质/步态动画全部只活在这里。
   卷二换模 = 整体替换本工厂内部实现（AI 生成 GLB + 骨骼动画），返回对象形状不变。
   外部只允许触碰：group（position/rotation/scale）、setWalking、setSpeed、update、dispose。
   共享 8 个 InstancedMesh 撑起全族（draw call 8 + 身体/头投影 2 = 10 ≤ 12）：
   身体/头/双臂/双脚/帽/灯笼/加法光晕（广告牌）/接触阴影。 ---- */
var walkParts = null;
var WALK_COATS = ['#C4573F', '#567F46', '#C08A16', '#4E718F', '#A84E5F', '#B07B2E', '#6E8132', '#96522C'];
var WALK_HATS = ['#FFF4DC', '#4A3826', '#C96F42', '#567F46', '#E8C57E', '#3E5470'];
var WALK_SKINS = ['#F2CBA8', '#EDBF97', '#E3AF85', '#C98D5F', '#A9714B'];
var WALK_GLOW_WARM = [1.0, 0.72, 0.38], WALK_GLOW_VIOLET = [0.55, 0.45, 1.0];
function ensureWalkParts() {
  if (walkParts) return;
  /* 脸贴图（零 CanvasGradient·腮红同心盘）。球面 u=0.25 ↔ 本地 +Z（three 球参方程），脸画在 x=0.25S */
  var S = 128, fc = cnv(S, S), fx = fc[1];
  fx.fillStyle = '#FFF8EE'; fx.fillRect(0, 0, S, S);
  var ex = S * 0.25;
  fx.fillStyle = '#33281E';
  fx.beginPath(); fx.ellipse(ex - 6.5, 57, 2.9, 4.3, 0, 0, PI * 2); fx.fill();
  fx.beginPath(); fx.ellipse(ex + 6.5, 57, 2.9, 4.3, 0, 0, PI * 2); fx.fill();
  fx.fillStyle = 'rgba(255,255,255,.9)';
  fx.beginPath(); fx.arc(ex - 5.6, 55.4, 1.1, 0, PI * 2); fx.fill();
  fx.beginPath(); fx.arc(ex + 7.4, 55.4, 1.1, 0, PI * 2); fx.fill();
  fx.strokeStyle = 'rgba(122,74,52,.85)'; fx.lineWidth = 2;
  fx.beginPath(); fx.arc(ex, 66, 3.4, 0.25, PI - 0.25); fx.stroke();
  glowStack(fx, ex - 11.5, 65, 4.2, 3.2, function () { return '238,140,120'; }, function (k) { return lerp(0.5, 0, k); }, 4);
  glowStack(fx, ex + 11.5, 65, 4.2, 3.2, function () { return '238,140,120'; }, function (k) { return lerp(0.5, 0, k); }, 4);
  var faceT = T(fc);
  /* 身体贴图：全域压暗 0.86、前腹(u=0.25 列)提纯白做奶肚两色、底缘再压——instanceColor 乘法下
     白=涂装原色、暗=深一档，白肚在任意涂装上都成立（零 CanvasGradient·逐行插值） */
  var bc = cnv(128, 128), bx = bc[1];
  bx.fillStyle = '#DBDBDB'; bx.fillRect(0, 0, 128, 128);
  glowStack(bx, 32, 84, 21, 30, function () { return '255,255,255'; }, function (k) { return lerp(1, 0.35, k); }, 7);
  for (var br = 104; br < 128; br++) {
    bx.fillStyle = 'rgba(40,30,20,' + (0.16 * (br - 104) / 24).toFixed(3) + ')';
    bx.fillRect(0, br, 128, 1);
  }
  var bodyT = T(bc);

  /* R1 🔴-1：cap 可指定——arm/foot 每人两枚(左右·寻址 2s/2s+1)须建 WALK_SLOTS*2。
     原先全族一律 16：槽位 ≥8 的手脚写到实例 16–31 = Float32Array 越界静默 no-op，
     第 9 位起的居民光溜溜没手没脚（不报错·不进 onError·mock 只 4 人故机审漏网）。 */
  function im(geo, mat, shadow, cap) {
    cap = cap || WALK_SLOTS;
    var m = new THREE.InstancedMesh(geo, mat, cap);
    m.frustumCulled = false;
    if (shadow) { m.castShadow = true; m.receiveShadow = true; }
    for (var i = 0; i < cap; i++) m.setMatrixAt(i, _wa4.makeScale(0, 0, 0));
    m.instanceMatrix.needsUpdate = true;
    scene.add(m);
    return m;
  }
  var armGeo = new THREE.SphereGeometry(1, 10, 8); armGeo.translate(0, -1, 0); /* 挂点在肩 */
  var lantGeo = new THREE.BoxGeometry(0.13, 0.15, 0.13); lantGeo.translate(0, -0.075, 0); /* 挂点在顶 */
  var glowGeo = new THREE.PlaneGeometry(1, 1);
  var blobGeo = new THREE.PlaneGeometry(1, 1); blobGeo.rotateX(-PI / 2);
  var parts = {
    body: im(new THREE.SphereGeometry(1, 18, 14), new THREE.MeshStandardMaterial({ map: bodyT, roughness: 0.95 }), true),
    head: im(new THREE.SphereGeometry(1, 20, 16), new THREE.MeshStandardMaterial({ map: faceT, roughness: 0.85 }), true),
    arm: im(armGeo, new THREE.MeshStandardMaterial({ roughness: 0.95 }), false, WALK_SLOTS * 2),
    foot: im(new THREE.SphereGeometry(1, 10, 8), new THREE.MeshStandardMaterial({ roughness: 1 }), false, WALK_SLOTS * 2),
    hat: im(new THREE.SphereGeometry(1, 14, 10, 0, PI * 2, 0, PI / 2), new THREE.MeshStandardMaterial({ roughness: 0.9 })),
    lant: im(lantGeo, new THREE.MeshStandardMaterial({ color: 0xFFD989, emissive: 0xFFA24E, emissiveIntensity: 0.9, roughness: 0.6 })),
    glow: im(glowGeo, new THREE.MeshBasicMaterial({ map: glowTex, transparent: true, depthWrite: false, blending: THREE.AdditiveBlending })),
    blob: im(blobGeo, new THREE.MeshBasicMaterial({ map: aoTex, transparent: true, depthWrite: false, opacity: 0.42 })),
    used: new Array(WALK_SLOTS).fill(false)
  };
  parts.glow.renderOrder = 6;
  parts.blob.renderOrder = 0.55;
  parts.all = [parts.body, parts.head, parts.arm, parts.foot, parts.hat, parts.lant, parts.glow, parts.blob];
  parts.pickMeshes = [parts.body, parts.head];
  [parts.body, parts.head, parts.hat, parts.arm, parts.foot, parts.glow].forEach(function (m) {
    for (var i = 0; i < m.count; i++) m.setColorAt(i, _cWhite); /* R1 🔴-1：按各自容量刷白（arm/foot=32） */
    if (m.instanceColor) m.instanceColor.needsUpdate = true;
  });
  parts.setVisible = function (v) {
    for (var i = 0; i < parts.all.length; i++) parts.all[i].visible = v;
  };
  parts.claim = function () {
    for (var i = 0; i < WALK_SLOTS; i++) if (!parts.used[i]) { parts.used[i] = true; return i; }
    return -1;
  };
  /* R1 🟡-1：arm/foot 占 2i/2i+1——原先按 i 清 = 自己那两枚手脚永不清零（还误清邻居一枚），
     换城整批 dispose 时满尺寸手脚残留悬空。 */
  parts.release = function (i) {
    _wa4.makeScale(0, 0, 0);
    for (var k = 0; k < parts.all.length; k++) {
      var m = parts.all[k];
      if (m === parts.arm || m === parts.foot) { m.setMatrixAt(i * 2, _wa4); m.setMatrixAt(i * 2 + 1, _wa4); }
      else m.setMatrixAt(i, _wa4);
      m.instanceMatrix.needsUpdate = true;
    }
    parts.used[i] = false;
  };
  parts.setColor = function (i, coat, skin, hatC, bootC, glowC) {
    parts.body.setColorAt(i, _cTmp2.set(coat));
    parts.head.setColorAt(i, _cTmp2.set(skin));
    parts.hat.setColorAt(i, _cTmp2.set(hatC));
    parts.arm.setColorAt(i * 2, _cTmp2.set(coat).multiplyScalar(0.94));
    parts.arm.setColorAt(i * 2 + 1, _cTmp2);
    parts.foot.setColorAt(i * 2, _cTmp2.set(bootC));
    parts.foot.setColorAt(i * 2 + 1, _cTmp2);
    parts.glow.setColorAt(i, _cTmp2.setRGB(glowC[0], glowC[1], glowC[2]));
    [parts.body, parts.head, parts.hat, parts.arm, parts.foot, parts.glow].forEach(function (m) {
      if (m.instanceColor) m.instanceColor.needsUpdate = true;
    });
  };
  parts.flush = function () {
    var any = false;
    for (var i = 0; i < WALK_SLOTS; i++) if (parts.used[i]) { any = true; break; }
    parts.setVisible(any);
    if (!any) return;
    for (var k = 0; k < parts.all.length; k++) parts.all[k].instanceMatrix.needsUpdate = true;
  };
  walkParts = parts;
}
function createWalkerBody(kind, seed) {
  ensureWalkParts();
  var s = walkParts.claim();
  if (s < 0) return null;
  var isPet = kind === 'pet', isMystery = kind === 'mystery';
  var rng = (seed ^ 0x9E3779B9) >>> 0;
  function rnd() { rng = (Math.imul(rng, 1664525) + 1013904223) >>> 0; return rng / 4294967296; }
  var coat, skin, hat, boot, glowC;
  if (isMystery) { /* 深色剪影·微微神秘 */
    coat = '#262E4A'; skin = '#303A5C'; hat = '#1F2740'; boot = '#1A2138'; glowC = WALK_GLOW_VIOLET;
  } else {
    coat = WALK_COATS[(seed >>> 2) % WALK_COATS.length];
    skin = WALK_SKINS[(seed >>> 7) % WALK_SKINS.length];
    hat = WALK_HATS[(seed >>> 12) % WALK_HATS.length];
    boot = '#6B4F38';
    glowC = WALK_GLOW_WARM;
  }
  walkParts.setColor(s, coat, skin, hat, boot, glowC);
  var bs = isPet ? 0.74 : (0.95 + rnd() * 0.16);
  var hatR = 0.35 + rnd() * 0.09, hatH = 0.13 + rnd() * 0.1;
  var lkey = hashXZ((seed % 977) + 3.1, (seed % 761) + 7.7); /* 灯火错峰 key（id 种子·§3.5） */
  var g = new THREE.Group();
  var walking = false, spd = 0.7, gaitT = rnd() * 6.283, idleT = rnd() * 6.283, walkBlend = 0;
  var lookYaw = 0, lookTarget = 0, lookT = 1.5 + rnd() * 3, lonS = 0;
  var P = walkParts;
  return {
    group: g,
    slot: s, /* R1 🔵-2：槽号单源=工厂 claim——外部禁再自扫（walkerSlots 与 used 两分配器靠巧合同步是脆弱耦合） */
    setWalking: function (on) { walking = !!on; },
    setSpeed: function (v) { spd = v; },
    update: function (dt, dtW) {
      if (walking) gaitT += dt * (3.0 + spd * 5.6) * (isMystery ? 0.6 : 1);
      idleT += dt;
      walkBlend += ((walking ? 1 : 0) - walkBlend) * (1 - Math.exp(-dt * 8));
      var wb = walkBlend * (isMystery ? 0.35 : 1);
      var armA = Math.sin(gaitT) * 0.55 * wb + Math.sin(idleT * 1.35) * 0.06 * (1 - walkBlend);
      var footZ = Math.sin(gaitT) * 0.15 * wb;
      var liftL = Math.max(0, Math.sin(gaitT + 1.1)) * 0.06 * wb;
      var liftR = Math.max(0, Math.sin(gaitT + PI + 1.1)) * 0.06 * wb;
      /* 驻足张望（工厂自治·走路时归零） */
      lookT -= dt;
      if (lookT <= 0) { lookT = 2.5 + rnd() * 4.5; lookTarget = (rnd() - 0.5) * 1.15; }
      lookYaw += ((walking ? 0 : lookTarget) - lookYaw) * (1 - Math.exp(-dt * 4.5));
      var bob = Math.abs(Math.sin(gaitT)) * 0.05 * walkBlend + Math.sin(idleT * 1.7) * 0.012 * (1 - walkBlend);
      var hover = isMystery ? 0.09 + Math.sin(idleT * 1.2) * 0.05 : 0;
      var sq = 1 + (Math.sin(gaitT * 2) * 0.03 * walkBlend + Math.sin(idleT * 1.9) * 0.012 * (1 - walkBlend));
      /* 灯笼（§6：lampT>0 提灯·错峰与镇灯一家人·神秘人无灯只有幽光） */
      var lon;
      if (isMystery) lon = 0.3 + 0.1 * Math.sin(idleT * 1.3);
      else lon = clamp((amb.duskSec - lkey * 12) / 0.9, 0, 1) * amb.lampT;
      /* 灯火渐变走墙钟：reduceMotion/staticMode 冻结步态（dt=0）时灯笼仍要跟着 amb 熄/亮 */
      lonS += (lon - lonS) * (1 - Math.exp(-Math.max(dt, dtW || 0) * 3.5));
      var flick = 0.86 + 0.14 * Math.sin(simT * 5.2 + lkey * 43);
      /* ---- 组装实例矩阵（读 group 变换·零分配） ---- */
      var gp = g.position, gyaw = g.rotation.y, S2 = g.scale.x * bs;
      if (S2 <= 0.002) { /* 全隐：零矩阵一次写齐（R1 🟡-1：arm/foot 清 2s/2s+1） */
        _wa4.makeScale(0, 0, 0);
        for (var z2 = 0; z2 < P.all.length; z2++) {
          var pm = P.all[z2];
          if (pm === P.arm || pm === P.foot) { pm.setMatrixAt(s * 2, _wa4); pm.setMatrixAt(s * 2 + 1, _wa4); }
          else pm.setMatrixAt(s, _wa4);
        }
        return;
      }
      var cy2 = Math.cos(gyaw), sy2 = Math.sin(gyaw);
      var self = this;
      function wSet(mesh, idx, lx, ly, lz, rx, rz, sx2, sy3, sz2, addLook) {
        _wae.set(rx, gyaw + (addLook ? lookYaw : 0), rz);
        _waq.setFromEuler(_wae);
        _wav.set(gp.x + lx * cy2 + lz * sy2, gp.y + ly, gp.z - lx * sy2 + lz * cy2);
        _was.set(sx2, sy3, sz2);
        _wa4.compose(_wav, _waq, _was);
        mesh.setMatrixAt(idx, _wa4);
      }
      var yb = (bob + hover) * S2;
      wSet(P.body, s, 0, (0.36) * S2 + yb * 0.7, 0, 0, Math.sin(gaitT) * 0.05 * walkBlend,
        0.30 * S2, 0.34 * sq * S2, 0.26 * S2, false);
      wSet(P.head, s, 0, (0.83) * S2 + yb, 0.01 * S2, 0, 0, 0.335 * S2, 0.315 * S2, 0.325 * S2, true);
      wSet(P.hat, s, 0, (1.0) * S2 + yb, -0.01 * S2, 0, 0,
        (isPet ? 0 : hatR) * S2, (isPet ? 0 : hatH) * S2, (isPet ? 0 : hatR) * S2, true);
      wSet(P.arm, s * 2, 0.29 * S2, (0.55) * S2 + yb * 0.8, 0, armA, 0, 0.07 * S2, 0.17 * S2, 0.07 * S2, false);
      wSet(P.arm, s * 2 + 1, -0.29 * S2, (0.55) * S2 + yb * 0.8, 0, -armA, 0, 0.07 * S2, 0.17 * S2, 0.07 * S2, false);
      wSet(P.foot, s * 2, 0.115 * S2, (0.055 + liftL + hover * 0.5) * S2, footZ * S2, 0, 0,
        0.085 * S2, 0.06 * S2, 0.115 * S2, false);
      wSet(P.foot, s * 2 + 1, -0.115 * S2, (0.055 + liftR + hover * 0.5) * S2, -footZ * S2, 0, 0,
        0.085 * S2, 0.06 * S2, 0.115 * S2, false);
      var lvis = isMystery ? 0 : Math.min(1, lonS * 4) * S2;
      wSet(P.lant, s, 0.31 * S2, (0.30 + hover * 0.6) * S2 + bob * 0.5 * S2, 0.05 * S2,
        Math.sin(gaitT + 0.6) * 0.12 * walkBlend, 0, lvis, lvis, lvis, false);
      /* 光晕：广告牌朝相机（暖=灯笼·只随灯火量出现，白天不亮底；紫=神秘人常驻幽光） */
      var gs = isMystery ? 1.75 * flick * (0.55 + 0.45 * lonS) * S2
                         : 1.55 * flick * lonS * S2;
      _wav.set(gp.x + 0.31 * S2 * cy2 + 0.05 * S2 * sy2, gp.y + (0.46 + hover * 0.7) * S2 + bob * 0.5 * S2,
        gp.z - 0.31 * S2 * sy2 + 0.05 * S2 * cy2);
      _was.set(gs, gs, 1);
      _wa4.compose(_wav, camera.quaternion, _was);
      P.glow.setMatrixAt(s, _wa4);
      var bl = Math.max(0.25, 1 - hover * 2.2);
      wSet(P.blob, s, 0, 0.021, 0, 0, 0, 1.05 * S2 * bl, 1, 1.05 * S2 * bl, false);
    },
    dispose: function () { walkParts.release(s); }
  };
}

/* ---- 标识（头顶可点·精简卡）与对账（§3.1/§3.2） ---- */
function buildWalkMarker(c) {
  var el = document.createElement('div');
  el.className = 'walk-card' + (c.kind === 'mystery' ? ' mystery' : '') + (c.kind === 'pet' ? ' pet' : '') + (c.present === false ? ' absent' : '');
  var av = document.createElement('div'); av.className = 'cast-av';
  if (c.kind === 'mystery') {
    av.textContent = '?'; /* 神秘人绝不露名（§2） */
  } else if (c.avatar) {
    var img = document.createElement('img');
    img.src = (c.avatar.indexOf('data:') === 0) ? c.avatar : ('data:image/jpeg;base64,' + c.avatar);
    av.appendChild(img);
  } else {
    av.textContent = (c.name && c.name.length) ? c.name.trim().charAt(0) : '?';
  }
  el.appendChild(av);
  if (c.name && c.kind !== 'mystery') {
    var nm = document.createElement('div'); nm.className = 'cast-name'; nm.textContent = c.name;
    el.appendChild(nm);
  }
  el.addEventListener('click', function (ev) { ev.stopPropagation(); if (flags.interactive) bridge('onTapCast', c.id); });
  document.body.appendChild(el);
  return el;
}
function refreshWalkMarker(w) {
  var old = w.el;
  w.el = buildWalkMarker(w.card);
  if (w.shim) w.shim.el = w.el;
  if (old && old.parentNode) old.parentNode.removeChild(old);
}
function startLeave(w) {
  w.leaving = true; w.stT = 0;
  w.body.setWalking(false);
  if (w.el) w.el.classList.add('leave');
}
function spawnWalker(c, canWalk) {
  if (!WG) { makePinnedCard(c); return; }
  var seed = seedFromId(c.id);
  var body;
  try { body = createWalkerBody(c.kind || 'native', seed); } catch (e) { walkFail(e); return; }
  if (!body) { makePinnedCard(c); return; }
  var ax = +c.x || 0, az = +c.z || 0;
  if (walkBlocked(ax, az) && walkFreeNear(ax, az, _freeTmp)) { ax = _freeTmp.x; az = _freeTmp.z; }
  var w = {
    id: c.id, kind: c.kind || 'native', card: c, body: body, slot: -1, el: null, shim: null,
    seed: seed, x: ax, z: az, yaw: hashXZ(seed % 31, seed % 17) * PI * 2,
    anchorX: ax, anchorZ: az, canWalk: !!canWalk,
    path: new Float32Array(96), pathN: 0, pathI: 0, targetX: 0, targetZ: 0,
    needPath: false, state: 'in', stT: 0, idleT: 0, glide: 0, glideFromX: 0, glideFromZ: 0,
    speedBase: (c.kind === 'mystery') ? 0.34 + hashXZ(seed % 13, seed % 7) * 0.16
             : (c.kind === 'pet') ? 0.95 + hashXZ(seed % 13, seed % 7) * 0.25
             : 0.55 + hashXZ(seed % 13, seed % 7) * 0.35,
    pauseBase: 1.6 + hashXZ(seed % 29, seed % 23) * 3.8,
    rng: (seed ^ 0x85EB) >>> 0,
    leaving: false
  };
  body.group.position.set(ax, walkGroundY(ax, az), az);
  body.group.rotation.y = w.yaw;
  body.group.scale.setScalar(0.01);
  body.setWalking(false);
  w.slot = body.slot; /* R1 🔵-2：槽号取自工厂·handleTap 的 instanceId→walkerSlots 映射自此有代码保证 */
  walkerSlots[w.slot] = w;
  w.el = buildWalkMarker(c);
  w.shim = { el: w.el, x: ax, y: 1.1, z: az };
  walkLayoutList.push(w.shim);
  walkerById.set(c.id, w);
}
function reconcileWalkers(cards) {
  if (!walkersEnabled) return;
  var seen = new Map(), present = new Map();
  var rank = 0;
  for (var i = 0; i < cards.length; i++) {
    if (!walkersEnabled) return; /* R1 🔵-3：中途 walkFail（已整卷转钉卡）→ 立停，防造出永不被 tick 的僵尸 walker */
    var c = cards[i];
    if (!c || typeof c.id !== 'string') continue;
    present.set(c.id, true);
    if (c.walking === true) {
      rank++;
      seen.set(c.id, true);
      var w = walkerById.get(c.id);
      if (w) {
        /* §3.2：同 id 且仍 walking → 保持位置与路线，只更新标识内容 */
        w.card = c;
        w.canWalk = rank <= WALK_CAP;
        if (w.leaving) { w.leaving = false; w.stT = 0; if (w.el) w.el.classList.remove('leave'); w.state = 'in'; }
        refreshWalkMarker(w);
        leavingPins.delete(c.id);
      } else {
        spawnWalker(c, rank <= WALK_CAP);
      }
    } else {
      var w2 = walkerById.get(c.id);
      if (w2 && !w2.leaving) { startLeave(w2); leavingPins.set(c.id, c); } /* 翻转 true→false：退场后钉卡 */
    }
  }
  walkerById.forEach(function (w3, id) {
    if (!seen.get(id)) {
      if (!w3.leaving) startLeave(w3); /* id 消失 → 优雅退场 */
      if (!present.get(id)) leavingPins.delete(id); /* 真消失才免钉卡；翻转 true→false 的钉卡保留 */
    }
  });
  leavingPins.forEach(function (card, id) {
    var found = null;
    for (var j = 0; j < cards.length; j++) { var cj = cards[j]; if (cj && cj.id === id) { found = cj; break; } }
    if (!found || found.walking === true) leavingPins.delete(id);
    else leavingPins.set(id, found);
  });
  dirty = true;
}
function parkWalker(w) {
  w.state = 'park'; w.needPath = false;
  w.glideFromX = w.x; w.glideFromZ = w.z;
  if (Math.hypot(w.x - w.anchorX, w.z - w.anchorZ) < 0.05) { w.glide = 0; w.x = w.anchorX; w.z = w.anchorZ; }
  else w.glide = 1;
  w.body.setWalking(false);
}
function pickWalkTarget(w) {
  /* 机审修缮②「拴绳」：目标钳在镇核 r≤13（原 19.5）。实测原值下随机游走数分钟必外漂——
     镇心方向常被建筑挡（重试落空）、空旷外环全通过，落点分布天然向外偏，最终 3/4 居民
     聚在 r≈18 荒地、默认镜头里一个人都留不住。人已在绳外时下一步朝镇心回（±0.9rad 扇形），
     走域/避障规则不变。 */
  var LEASH = 13;
  for (var t = 0; t < 6; t++) {
    var r = 3 + walkRng01(w) * 6.5;
    var a = (Math.hypot(w.x, w.z) > LEASH)
      ? Math.atan2(-w.x, -w.z) + (walkRng01(w) - 0.5) * 1.8
      : walkRng01(w) * PI * 2;
    var tx = w.x + Math.sin(a) * r, tz = w.z + Math.cos(a) * r;
    var rr = Math.hypot(tx, tz);
    if (rr > LEASH) { var kk = LEASH / rr; tx *= kk; tz *= kk; }
    if (walkBlocked(tx, tz)) continue;
    w.targetX = tx; w.targetZ = tz; w.needPath = true;
    return;
  }
  w.idleT = 1.5; /* 四周皆堵（罕见）：原地再歇一会 */
}
function tickLeave(w, dtW) {
  w.stT += dtW / 0.5;
  var k = Math.max(0, 1 - smooth01(Math.min(1, w.stT)));
  w.body.group.scale.setScalar(Math.max(0.001, k));
  w.body.update(0);
  if (w.stT >= 1) {
    var li = walkLayoutList.indexOf(w.shim);
    if (li >= 0) walkLayoutList.splice(li, 1);
    if (w.el && w.el.parentNode) w.el.parentNode.removeChild(w.el);
    w.body.dispose();
    walkerSlots[w.slot] = null;
    walkerById.delete(w.id);
    var pin = leavingPins.get(w.id);
    if (pin) { leavingPins.delete(w.id); makePinnedCard(pin); } /* §3.2 退场后钉卡（事件驱动·非每帧） */
    dirty = true;
  }
}
function walkMovingCount() {
  var n = 0;
  for (var i = 0; i < WALK_SLOTS; i++) { var w = walkerSlots[i]; if (w && !w.leaving && w.state === 'walk') n++; }
  return n;
}
function resetWalkers() {
  for (var i = 0; i < WALK_SLOTS; i++) {
    var w = walkerSlots[i];
    if (!w) continue;
    var li = walkLayoutList.indexOf(w.shim);
    if (li >= 0) walkLayoutList.splice(li, 1);
    if (w.el && w.el.parentNode) w.el.parentNode.removeChild(w.el);
    try { w.body.dispose(); } catch (_) { }
    walkerSlots[i] = null;
  }
  walkerById.clear();
  leavingPins.clear();
  walkWasDegraded = null;
  walkRR = 0;
  walkTransient = false;
}
/* 静默降级（§5）：走动系统任何非致命异常 → 整卷退回钉卡，绝不拉全页兜底、不报 onError */
function walkFail(e) {
  if (!walkersEnabled) return;
  walkersEnabled = false;
  try { console.warn('[town] 走动系统异常，静默降级为钉卡', e); } catch (_) { }
  if (MOCK_MODE && window.__townErrors) window.__townErrors.push('walk: ' + String((e && e.message) || e));
  try { resetWalkers(); } catch (_) { }
  if (walkParts) walkParts.setVisible(false);
  var lc = lastCastRef;
  lastCastRef = null;
  if (lc) setCastLayer(lc);
  dirty = true;
}
/* 帧驱动：时间基 simT（frozen 自停·§3.4）；标识/弹入/退场走墙钟（UI 过渡不受冻结卡死） */
function tickWalkers() {
  var now = performance.now();
  var dtW = Math.min(0.1, Math.max(0, (now - walkLastWall) / 1000));
  var dtS = Math.min(0.1, Math.max(0, simT - walkLastSimT));
  walkLastWall = now; walkLastSimT = simT;
  if (!walkersEnabled || !walkParts || walkerById.size === 0) {
    walkTransient = false;
    if (walkParts) walkParts.flush(); /* R1 🟡-1 双保险：空场也刷一次可见性（换城残留兜底） */
    return;
  }
  var degraded = flags.reduceMotion || flags.staticMode;
  if (degraded !== walkWasDegraded) {
    walkWasDegraded = degraded;
    for (var i0 = 0; i0 < WALK_SLOTS; i0++) {
      var w0 = walkerSlots[i0];
      if (!w0 || w0.leaving) continue;
      if (degraded) parkWalker(w0); /* §3.4：静立报文环位锚·恢复后可再走 */
      else { w0.state = 'idle'; w0.idleT = 0.3 + walkRng01(w0) * 0.8; w0.needPath = false; }
    }
  }
  /* 寻路：每帧最多一单（round-robin·预分配 BFS·不进帧分配） */
  if (!degraded && dtS > 0) {
    for (var pi = 0; pi < WALK_SLOTS; pi++) {
      var idx = (walkRR + pi) % WALK_SLOTS;
      var wp = walkerSlots[idx];
      if (wp && !wp.leaving && wp.needPath) {
        walkRR = (idx + 1) % WALK_SLOTS;
        var pn = walkFindPath(wp, wp.targetX, wp.targetZ);
        wp.needPath = false;
        /* 机审修缮③：pathI 从 0 起步（原 1 跳过首点）。walkFindPath 会把落在阻挡格里的起点
           挪到最近自由格，但小人真身没挪——跳过首点时「真身→首个路点」这段腿没做视线检查，
           自由格若在墙另一侧就整个人穿墙过去（实测深穿 >0.75 的唯一通路）。从 0 起步 =
           被重定位时先退回最近自由点再上路；未重定位时首点即脚下、同帧零代价越过。
           顺带治愈 pathN==1 时读 path[2] 脏数据的边角。 */
        if (pn > 0) { wp.pathN = pn; wp.pathI = 0; wp.state = 'walk'; }
        else { wp.state = 'idle'; wp.idleT = 1.0; }
        break;
      }
    }
  }
  for (var i = 0; i < WALK_SLOTS; i++) {
    var w = walkerSlots[i];
    if (!w) continue;
    var body = w.body, gp = body.group.position;
    if (w.leaving) { tickLeave(w, dtW); continue; }
    if (degraded || !w.canWalk) {
      /* §3.4/§3.6：静立环位锚（短滑步过去·标识照常可点） */
      if (w.glide > 0) {
        w.glide = Math.max(0, w.glide - dtW / 0.3);
        var gk = 1 - w.glide;
        w.x = lerp(w.glideFromX, w.anchorX, smooth01(gk));
        w.z = lerp(w.glideFromZ, w.anchorZ, smooth01(gk));
        if (w.glide === 0) { w.x = w.anchorX; w.z = w.anchorZ; }
      }
      body.setWalking(false);
      if (w.state === 'in') { w.stT += dtW / 0.35; if (w.stT >= 1) w.state = 'park'; }
    } else if (w.state === 'in') {
      w.stT += dtW / 0.35;
      if (w.stT >= 1) { w.state = 'idle'; w.idleT = 0.4 + walkRng01(w) * 1.2; }
    } else if (dtS > 0) {
      if (w.state === 'idle') {
        w.idleT -= dtS;
        body.setWalking(false);
        if (w.idleT <= 0) pickWalkTarget(w);
      } else if (w.state === 'walk') {
        var wx = w.path[w.pathI * 2], wz = w.path[w.pathI * 2 + 1];
        var dx = wx - w.x, dz = wz - w.z, d = Math.hypot(dx, dz);
        if (w.pathI >= w.pathN - 1 && d < 0.22) {
          w.state = 'idle';
          w.idleT = w.pauseBase * (0.6 + walkRng01(w) * 0.9);
          body.setWalking(false);
        } else {
          /* R1 🔵-4：d≈0（pathI=0 首帧与真身同点）时 atan2 拿到的是浮点噪声方向 → 不转向 */
          var dyaw = d > 0.001 ? Math.atan2(dx, dz) - w.yaw : 0;
          while (dyaw > PI) dyaw -= PI * 2;
          while (dyaw < -PI) dyaw += PI * 2;
          var tr = 2.6 * dtS;
          w.yaw += clamp(dyaw, -tr, tr);
          var sp = w.speedBase * clamp(1.1 - Math.abs(dyaw) * 0.55, 0.3, 1); /* 转弯减速·自然弧线 */
          w.x += Math.sin(w.yaw) * sp * dtS;
          w.z += Math.cos(w.yaw) * sp * dtS;
          body.setSpeed(sp);
          body.setWalking(true);
          if (d < 0.24 && w.pathI < w.pathN - 1) w.pathI++;
        }
      }
    }
    /* 落脚：deck 高度平滑 + 弹入缩放 + 朝向 */
    var gy = walkGroundY(w.x, w.z);
    gp.x = w.x; gp.z = w.z;
    gp.y += (gy - gp.y) * Math.min(1, dtW * 10);
    body.group.rotation.y = w.yaw;
    var pop = 1;
    if (w.state === 'in') { var pk = Math.min(1, w.stT); pop = smooth01(pk) * (1 + 0.15 * Math.sin(pk * PI)); }
    body.group.scale.setScalar(Math.max(0.01, pop));
    body.update(dtS, dtW);
    if (w.shim) {
      w.shim.x = w.x; w.shim.z = w.z;
      w.shim.y = gp.y + 1.18 * body.group.scale.x;
    }
  }
  /* 机审修缮①：过渡态未落定 → 通知主循环 staticMode 也继续渲（否则按需渲下退场/入场/滑步
     全部停摆：翻转钉卡的替身永远等不来——tickWalkers 只活在 renderScene 里，dirty 无人续帧）。 */
  var trans = false;
  for (var ti = 0; ti < WALK_SLOTS; ti++) {
    var tw = walkerSlots[ti];
    if (tw && (tw.leaving || tw.state === 'in' || tw.glide > 0)) { trans = true; break; }
  }
  walkTransient = trans;
  walkParts.flush();
}

/* ================= 12. 昼夜氛围（App 是唯一时刻权威） ================= */
/* 三档美术基底（白天以外为固定美术；fog/glowA/tint 由 ambJson 覆盖） */
var PHASES = [
  { sunC: [1.0, 0.95, 0.86], sunI: 1.2, az: 3.7, el: 0.95, hSky: [0.75, 0.85, 0.95], hGnd: [0.66, 0.56, 0.44], hI: 0.95,
    fogC: [0.84, 0.89, 0.93], fogN: 44, fogF: 185, glowA: 0.05, hill1: [0.62, 0.71, 0.54], hill2: [0.55, 0.65, 0.5], hill3: [0.47, 0.58, 0.47],
    waterC: [0.55, 0.72, 0.82], expo: 1.0, smoke: 0.35, firefly: 0, cloudC: [1, 1, 1], cloudO: 0.6, leafO: 0.9 },
  { sunC: [1.0, 0.69, 0.44], sunI: 1.15, az: 4.1, el: 0.3, hSky: [0.85, 0.66, 0.72], hGnd: [0.54, 0.41, 0.32], hI: 0.75,
    fogC: [0.91, 0.71, 0.56], fogN: 34, fogF: 150, glowA: 1.0, hill1: [0.55, 0.4, 0.4], hill2: [0.47, 0.36, 0.38], hill3: [0.38, 0.31, 0.36],
    waterC: [0.72, 0.6, 0.68], expo: 1.06, smoke: 0.5, firefly: 0.9, cloudC: [1, 0.85, 0.78], cloudO: 0.5, leafO: 0.7 },
  { sunC: [0.59, 0.66, 0.85], sunI: 0.42, az: 5.6, el: 0.8, hSky: [0.15, 0.19, 0.35], hGnd: [0.06, 0.09, 0.17], hI: 0.42,
    fogC: [0.086, 0.118, 0.227], fogN: 30, fogF: 135, glowA: 1.15, hill1: [0.16, 0.2, 0.31], hill2: [0.14, 0.17, 0.27], hill3: [0.12, 0.14, 0.22],
    waterC: [0.16, 0.24, 0.4], expo: 0.96, smoke: 0.28, firefly: 0.65, cloudC: [0.5, 0.56, 0.72], cloudO: 0.14, leafO: 0.25 }
];
var NUM_F = ['sunI', 'az', 'el', 'hI', 'fogN', 'fogF', 'glowA', 'expo', 'smoke', 'firefly', 'cloudO', 'leafO'];
var COL_F = ['sunC', 'hSky', 'hGnd', 'fogC', 'hill1', 'hill2', 'hill3', 'waterC', 'cloudC', 'tint'];
function phaseSnapshot(pi) {
  var p = PHASES[pi], o = { phase: pi };
  NUM_F.forEach(function (k) { o[k] = p[k]; });
  COL_F.forEach(function (k) { o[k] = col3(k === 'tint' ? [1, 1, 1] : p[k]); });
  o.tint = new THREE.Color(1, 1, 1);
  return o;
}
var amb = {
  cur: phaseSnapshot(1), from: phaseSnapshot(1), to: phaseSnapshot(1), t: 1, dur: 0.001,
  duskSec: 0, secFrom: 0, secTo: 0, secT: 1,
  lampT: 0, ltFrom: 0, ltTo: 0, ltT: 1, received: false
};
function setAmbience(json) {
  var j = json || {};
  var pi = clamp(j.phase | 0, 0, 2);
  amb.from = cloneSnap(amb.cur);
  amb.to = phaseSnapshot(pi);
  if (j.fog) amb.to.fogC = col3(j.fog);
  if (j.tint) amb.to.tint = col3(j.tint);
  if (typeof j.glowA === 'number') amb.to.glowA = clamp(j.glowA, 0, 2);
  amb.t = 0;
  amb.dur = flags.reduceMotion ? 0.001 : 1.6; /* 契约：≥1.5s 渐变 · reduceMotion 直切 */
  amb.secFrom = amb.duskSec; amb.secTo = Math.max(0, +j.duskSec || 0); amb.secT = 0;
  amb.ltFrom = amb.lampT; amb.ltTo = clamp(typeof j.lampT === 'number' ? j.lampT : 1, 0, 1); amb.ltT = 0;
  amb.received = true;
  setSkyTarget(['day', 'dusk', 'night'][pi], false);
  dirty = true;
}
function cloneSnap(s) {
  var o = { phase: s.phase };
  NUM_F.forEach(function (k) { o[k] = s[k]; });
  COL_F.forEach(function (k) { o[k] = s[k].clone(); });
  return o;
}
function mixSnap(a, b, k, out) {
  out.phase = k < 0.5 ? a.phase : b.phase; /* 诊断面板读 amb.cur.phase：过渡过半即报新档 */
  NUM_F.forEach(function (f) { out[f] = lerp(a[f], b[f], k); });
  COL_F.forEach(function (f) { out[f].copy(a[f]).lerp(b[f], k); });
}
var _cTmp = new THREE.Color();
function applyAmbience() {
  var c = amb.cur;
  _cTmp.copy(c.sunC).multiply(c.tint);
  sun.color.copy(_cTmp); sun.intensity = c.sunI;
  sun.position.set(Math.sin(c.az) * Math.cos(c.el), Math.sin(c.el), Math.cos(c.az) * Math.cos(c.el)).multiplyScalar(70);
  hemi.color.copy(_cTmp.copy(c.hSky).multiply(c.tint));
  hemi.groundColor.copy(c.hGnd); hemi.intensity = c.hI;
  scene.fog.color.copy(c.fogC); scene.fog.near = c.fogN; scene.fog.far = c.fogF;
  renderer.toneMappingExposure = c.expo;
  hillRings[0].material.color.copy(c.hill1);
  hillRings[1].material.color.copy(c.hill2);
  hillRings[2].material.color.copy(c.hill3);
  waterUni.uCol.value.copy(c.waterC);
  waterUni.uFogC.value.copy(c.fogC);
  waterUni.uGlow.value = c.glowA;
  waterUni.uTime.value = simT;
  smokeMat.opacity = c.smoke * (0.35 + 0.65 * Math.min(1, c.glowA));
  fireflyMat.opacity = c.firefly;
  leafMat.opacity = c.leafO;
  for (var ci = 0; ci < cloudSprites.length; ci++) {
    cloudSprites[ci].material.opacity = c.cloudO;
    cloudSprites[ci].material.color.copy(c.cloudC);
  }
  var ds = amb.duskSec, lt = amb.lampT;
  staggerMats.forEach(function (m) { m.uniforms.uDuskSec.value = ds; m.uniforms.uLampT.value = lt; m.uniforms.uTime.value = simT; });
  glowUni.uDuskSec.value = ds; glowUni.uLampT.value = lt; glowUni.uTime.value = simT; glowUni.uGlow.value = Math.min(1.2, c.glowA);
  poolUni.uDuskSec.value = ds; poolUni.uLampT.value = lt; poolUni.uTime.value = simT; poolUni.uGlow.value = Math.min(1.2, c.glowA);
}
function tickAmbience(dt) {
  if (amb.t < 1) {
    amb.t = Math.min(1, amb.t + dt / amb.dur);
    mixSnap(amb.from, amb.to, smooth01(amb.t), amb.cur);
    dirty = true;
  }
  /* duskSec / lampT：向 App 下发值补齐（同档时长渐变），到位后黄昏段按真实秒推进（错峰窗肉眼可见） */
  if (amb.secT < 1) {
    amb.secT = Math.min(1, amb.secT + dt / amb.dur);
    amb.duskSec = lerp(amb.secFrom, amb.secTo, smooth01(amb.secT));
    if (amb.secT >= 1) amb.duskSec = amb.secTo;
    dirty = true;
  } else if (amb.received && amb.to.phase === 1 && amb.duskSec < 3599 && !flags.staticMode) {
    amb.duskSec += dt; /* 黄昏档按真实秒推进（错峰窗肉眼可见）；白天/深夜档冻结 */
  }
  if (amb.ltT < 1) {
    amb.ltT = Math.min(1, amb.ltT + dt / amb.dur);
    amb.lampT = lerp(amb.ltFrom, amb.ltTo, smooth01(amb.ltT));
    if (amb.ltT >= 1) amb.lampT = amb.ltTo;
    dirty = true;
  }
}
function updateSmoke(dt) {
  var n = smokeEmitters.length * 4;
  if (!n) return;
  var cnt = Math.min(n, SMOKE_MAX);
  for (var e = 0; e < smokeEmitters.length; e++) {
    var em = smokeEmitters[e];
    for (var i = 0; i < 4; i++) {
      var idx = e * 4 + i;
      if (idx >= SMOKE_MAX) return;
      var p = (simT * 0.13 + i * 0.25 + em.ph) % 1;
      smokePos[idx * 3] = em.x + Math.sin(p * 7 + em.ph * 9) * 0.25;
      smokePos[idx * 3 + 1] = em.y + p * 3.2;
      smokePos[idx * 3 + 2] = em.z;
    }
  }
  smokePoints.geometry.attributes.position.needsUpdate = true;
}
function tickFireflies() {
  if (fireflyMat.opacity < 0.02) return;
  var pos = fireflyPoints.geometry.attributes.position;
  for (var i = 0; i < FF_N; i++) {
    pos.setY(i, ffBase[i * 3 + 1] + Math.sin(simT * 1.3 + ffPh[i]) * 0.35);
    pos.setX(i, ffBase[i * 3] + Math.sin(simT * 0.7 + ffPh[i] * 1.7) * 0.5);
  }
  pos.needsUpdate = true;
}

/* ================= 13. 相机与手势（契约 §5 常数照抄） ================= */
var DEF_POSE = { yaw: 0.7, pitch: 0.36, dist: 30, tx: -1.5, tz: -1.0 }; /* W-2（契约 v1.2）：初始取景对齐原生 TownCamera */
var pose = { yaw: 0.7, pitch: 0.36, dist: 30, tx: 0, tz: 0 };
var flags = { reduceMotion: false, staticMode: false, interactive: true };
var camTween = null, gesturing = false, dirty = true, inited = false, firstFrameSent = true;
var inertia = { vx: 0, vz: 0, active: false };
var lastAct = 0, simT = 0;

function clampPitch(v) { return clamp(v, 0.28, 1.25); }
function clampDist(v) { return clamp(v, 13, 38); }
function softClampAxis(v) {
  var L = 16, M = 20;
  if (v > L) v = L + (v - L) * 0.35;
  if (v < -L) v = -L + (v + L) * 0.35;
  return clamp(v, -M, M);
}
function startTween(keys, to, dur, ease) {
  var from = {};
  keys.forEach(function (k) { from[k] = pose[k]; });
  camTween = { keys: keys, from: from, to: to, t: 0, dur: Math.max(1, dur) / 1000, ease: ease || easeInOutCubic };
}
function tickCameraTween(dt) {
  if (!camTween) return;
  camTween.t += dt / camTween.dur;
  var k = camTween.ease(camTween.t);
  camTween.keys.forEach(function (key) { pose[key] = lerp(camTween.from[key], camTween.to[key], k); });
  dirty = true;
  if (camTween.t >= 1) {
    camTween.keys.forEach(function (key) { pose[key] = camTween.to[key]; });
    camTween = null;
  }
}
function tickInertia(dt) {
  if (!inertia.active) return;
  var decay = Math.pow(0.93, dt * 60); /* 契约：松手速度 ×0.93^帧 */
  inertia.vx *= decay; inertia.vz *= decay;
  if (Math.hypot(inertia.vx, inertia.vz) < 0.05) {
    inertia.active = false;
    snapBackTarget();
    return;
  }
  pose.tx = softClampAxis(pose.tx + inertia.vx * dt);
  pose.tz = softClampAxis(pose.tz + inertia.vz * dt);
  dirty = true;
}
function snapBackTarget() {
  var tx = clamp(pose.tx, -16, 16), tz = clamp(pose.tz, -16, 16);
  if (tx !== pose.tx || tz !== pose.tz) {
    startTween(['tx', 'tz'], { tx: tx, tz: tz }, 260, easeOutCubic);
  }
}
var raycaster = new THREE.Raycaster();
var _ndc = new THREE.Vector2();
function groundPoint(px, py) {
  _ndc.set((px / window.innerWidth) * 2 - 1, -(py / window.innerHeight) * 2 + 1);
  raycaster.setFromCamera(_ndc, camera);
  var o = raycaster.ray.origin, d = raycaster.ray.direction;
  if (d.y > -1e-4) return { x: pose.tx, z: pose.tz };
  var t = -o.y / d.y;
  if (t < 0 || t > 400) return { x: pose.tx, z: pose.tz };
  return { x: o.x + d.x * t, z: o.z + d.z * t };
}

var ptrs = new Map();
var panPrev = null, pinchPrev = null, tapInfo = null, vel = { x: 0, z: 0, t: 0 };
var ozRatio = 1, ozFired = false;
var cvs = renderer.domElement;
cvs.style.touchAction = 'none';
function localXY(e) { return { x: e.clientX, y: e.clientY }; }
cvs.addEventListener('pointerdown', function (e) {
  if (!flags.interactive) return;
  try { cvs.setPointerCapture(e.pointerId); } catch (_) { } /* 合成事件（自测注入）无真实指针 */
  ptrs.set(e.pointerId, localXY(e));
  lastAct = performance.now();
  camTween = null; inertia.active = false;
  if (ptrs.size === 1) {
    gesturing = true;
    panPrev = localXY(e);
    tapInfo = { x: e.clientX, y: e.clientY, t: performance.now() };
    vel.x = 0; vel.z = 0; vel.t = performance.now();
  } else if (ptrs.size === 2) {
    panPrev = null; tapInfo = null;
    ozRatio = 1; ozFired = false;
    pinchPrev = pinchState();
  }
});
cvs.addEventListener('pointermove', function (e) {
  if (!flags.interactive || !ptrs.has(e.pointerId)) return;
  ptrs.set(e.pointerId, localXY(e));
  lastAct = performance.now();
  if (ptrs.size === 1 && panPrev) {
    var p0 = groundPoint(panPrev.x, panPrev.y), p1 = groundPoint(e.clientX, e.clientY);
    var dx = p0.x - p1.x, dz = p0.z - p1.z; /* 地面锚定：指下的地跟着指头走 */
    var now = performance.now(), dtm = Math.max(8, now - vel.t) / 1000;
    vel.x = vel.x * 0.7 + (dx / dtm) * 0.3;
    vel.z = vel.z * 0.7 + (dz / dtm) * 0.3;
    vel.t = now;
    pose.tx = softClampAxis(pose.tx + dx);
    pose.tz = softClampAxis(pose.tz + dz);
    panPrev = localXY(e);
    dirty = true;
  } else if (ptrs.size === 2) {
    var st = pinchState();
    if (pinchPrev) {
      /* 真机批手感修正：GL 基准 ratio = prevSpan/span（TownGLView:145）——张开(span↑)→ratio<1→拉近。
         原写反（span/prevSpan）致缩放方向整体倒置；翻转后 overzoom(顶格继续并拢)分支语义自动归位。 */
      var ratio = pinchPrev.d / Math.max(1, st.d);
      if (pose.dist >= 38 - 0.01 && ratio > 1) {
        ozRatio *= ratio; /* dist 已顶格继续外捏：累积比例 */
        if (ozRatio >= 1.10 && !ozFired) { ozFired = true; bridge('onReturnGesture'); }
      }
      var nd = clampDist(pose.dist * ratio);
      var mid = groundPoint(st.mx, st.my);
      var f = clamp(1 - nd / pose.dist, -1, 1) * 0.9; /* 朝两指中点方向 */
      pose.tx = softClampAxis(pose.tx + (mid.x - pose.tx) * f);
      pose.tz = softClampAxis(pose.tz + (mid.z - pose.tz) * f);
      pose.dist = nd;
      pose.yaw += st.ang - pinchPrev.ang;               /* 双指旋转 = yaw */
      pose.pitch = clampPitch(pose.pitch + (st.my - pinchPrev.my) * 0.004); /* 双指上下滑 = pitch */
      dirty = true;
    }
    pinchPrev = st;
  }
});
function pinchState() {
  var a = [], ptrsArr = ptrs.values();
  for (var p = ptrsArr.next(); !p.done; p = ptrsArr.next()) a.push(p.value);
  if (a.length < 2) return null;
  var dx = a[1].x - a[0].x, dy = a[1].y - a[0].y;
  return {
    d: Math.max(1, Math.hypot(dx, dy)),
    ang: Math.atan2(dy, dx),
    mx: (a[0].x + a[1].x) / 2, my: (a[0].y + a[1].y) / 2
  };
}
function endPointer(e) {
  if (!ptrs.has(e.pointerId)) return;
  ptrs.delete(e.pointerId);
  if (ptrs.size === 1) {
    var rest = ptrs.values().next().value;
    panPrev = { x: rest.x, y: rest.y };
    pinchPrev = null;
  } else if (ptrs.size === 0) {
    gesturing = false;
    pinchPrev = null; panPrev = null;
    ozRatio = 1; ozFired = false; /* 松手复位 */
    if (tapInfo && performance.now() - tapInfo.t < 400 &&
        Math.hypot(e.clientX - tapInfo.x, e.clientY - tapInfo.y) < 8) {
      handleTap(tapInfo.x, tapInfo.y);
    }
    tapInfo = null;
    if (Math.hypot(vel.x, vel.z) > 0.4 && performance.now() - vel.t < 120) {
      inertia.vx = vel.x; inertia.vz = vel.z; inertia.active = true;
    } else {
      snapBackTarget();
    }
    sendPose();
  }
}
cvs.addEventListener('pointerup', endPointer);
cvs.addEventListener('pointercancel', endPointer);
cvs.addEventListener('wheel', function (e) {
  e.preventDefault();
  if (!flags.interactive) return;
  lastAct = performance.now();
  pose.dist = clampDist(pose.dist * (1 + Math.sign(e.deltaY) * 0.09));
  dirty = true;
}, { passive: false });
cvs.addEventListener('contextmenu', function (e) { e.preventDefault(); });

function lookupPlace(bk, faceIdx) {
  for (var i = 0; i < bk.ranges.length; i++) {
    var r = bk.ranges[i];
    if (faceIdx >= r.s && faceIdx < r.e) return r.meta;
  }
  return null;
}
function handleTap(px, py) {
  if (!inited) return;
  _ndc.set((px / window.innerWidth) * 2 - 1, -(py / window.innerHeight) * 2 + 1);
  raycaster.setFromCamera(_ndc, camera);
  /* 三期卷一 §3.1：点小人身体也命中（优先级高于地点拾取——人比楼小） */
  if (walkersEnabled && walkParts) {
    var wh = raycaster.intersectObjects(walkParts.pickMeshes, false);
    for (var wi = 0; wi < wh.length; wi++) {
      if (wh[wi].distance > 50) break;
      var wHit = walkerSlots[wh[wi].instanceId | 0];
      if (wHit && !wHit.leaving) { bridge('onTapCast', wHit.id); return; }
    }
  }
  var meshes = [];
  Object.keys(buckets).forEach(function (k) { if (buckets[k].mesh) meshes.push(buckets[k].mesh); });
  var hits = raycaster.intersectObjects(meshes, false);
  for (var i = 0; i < hits.length; i++) {
    if (hits[i].distance > 50) break;
    var bk = hits[i].object.userData.bucket;
    if (!bk || !bk.pickable) continue;
    var meta = lookupPlace(bk, hits[i].faceIndex);
    bridge('onTapPlace', (meta && meta.placeId) ? meta.placeId : '');
    return;
  }
  bridge('onTapPlace', '');
}

function sendPose() {
  var p = { yaw: r4(pose.yaw), pitch: r4(pose.pitch), dist: r4(pose.dist), tx: r4(pose.tx), tz: r4(pose.tz) };
  if (MOCK_MODE) window.__lastPose = p;
  bridge('onPose', JSON.stringify(p));
}
setInterval(function () { if (inited && !document.hidden) sendPose(); }, 500); /* 心跳 */

/* ================= 14. 投影排版（名签 / 居民卡跟随·屏外隐藏） ================= */
var _pv = new THREE.Vector3();
function layoutLayer(list, bottomAligned) {
  var w = window.innerWidth, h = window.innerHeight;
  for (var i = 0; i < list.length; i++) {
    var it = list[i];
    _pv.set(it.x, it.y, it.z).project(camera);
    if (_pv.z > 1 || _pv.x < -1.15 || _pv.x > 1.15 || _pv.y < -1.15 || _pv.y > 1.15) {
      it.el.style.display = 'none';
      continue;
    }
    var sx = (_pv.x * 0.5 + 0.5) * w, sy = (-_pv.y * 0.5 + 0.5) * h;
    var dist = camera.position.distanceTo(_pv.set(it.x, it.y, it.z));
    var k = clamp(30 / Math.max(6, dist), bottomAligned ? 0.65 : 0.8, 1.15);
    it.el.style.display = '';
    it.el.style.left = sx.toFixed(1) + 'px';
    it.el.style.top = sy.toFixed(1) + 'px';
    it.el.style.transform = (bottomAligned ? 'translate(-50%,-100%)' : 'translate(-50%,-50%)') + ' scale(' + k.toFixed(3) + ')';
    it.el.style.zIndex = String(1000 - Math.round(dist * 10));
  }
}
function layoutLayers() {
  layoutLayer(labelEls.map(function (l) { return { el: l.el, x: l.p.x, y: (l.p.top || 2) + 0.6, z: l.p.z }; }), true);
  layoutLayer(cardEls, true);
  layoutLayer(overEls, false);
  layoutLayer(walkLayoutList, true); /* 三期卷一：走动客头顶标识（shim 复用·零分配） */
}

/* ================= 15. 主循环（30/60 变频 · staticMode 按需渲） ================= */
var fpsEl = document.getElementById('fps');
var fpsShow = /[?&]fps=1/.test(location.search);
if (fpsShow) fpsEl.style.display = 'block';
var _px = new Uint8Array(4);
var fpsN = 0, fpsT = 0, lastTs = performance.now(), pageHidden = false;
document.addEventListener('visibilitychange', function () {
  pageHidden = document.hidden;
  lastTs = performance.now();
});
function renderScene() {
  /* 相机：看向 target(tx, 0.8, tz) */
  var cy = 0.8 + pose.dist * Math.sin(pose.pitch);
  var rh = pose.dist * Math.cos(pose.pitch);
  camera.position.set(pose.tx + rh * Math.sin(pose.yaw), cy, pose.tz + rh * Math.cos(pose.yaw));
  camera.lookAt(pose.tx, 0.8, pose.tz);
  applyAmbience();
  try { tickWalkers(); } catch (e) { walkFail(e); } /* 三期卷一：居民走动（时间基 simT·frozen 自停·静默降级） */
  if (!flags.reduceMotion && !flags.staticMode) { updateSmoke(simT); tickFireflies(); tickLeaves(); }
  if (placeRing && placeRing.visible && !flags.reduceMotion && !flags.staticMode) { /* W-5 金环呼吸 */
    var prk = 1 + Math.sin(simT * 3.4) * 0.05;
    placeRing.scale.set(prk, 1, prk);
  }
  renderer.render(scene, camera);
  if (MOCK_MODE) { /* 自诊断：渲染后立刻读中心像素（同帧内缓冲仍有效） */
    try {
      var gl = renderer.getContext();
      gl.readPixels(Math.floor(gl.drawingBufferWidth / 2), Math.floor(gl.drawingBufferHeight / 2), 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, _px);
      window.__px = [_px[0], _px[1], _px[2], _px[3]];
    } catch (_) { }
  }
  fpsN++;
  if (!firstFrameSent) {
    firstFrameSent = true;
    var veil = document.getElementById('veil');
    if (veil) veil.style.opacity = 0;
    bridge('onFirstFrame');
  }
  layoutLayers();
}
function tick(now) {
  requestAnimationFrame(tick);
  /* dt 下钳 0：rAF 时间戳在可见性切换/虚拟时间下可能倒退，负 dt 会把 tickSky 的 skyFading
     打成负值 → 换档淡出永久卡死（三期卷一走动上线后帧时序变化触发过实录，故随本卷一并修） */
  var dt = Math.min(0.1, Math.max(0, (now - lastTs) / 1000));
  lastTs = now;
  if (pageHidden || ctxLost) return;
  var frozen = flags.reduceMotion || flags.staticMode;
  if (!frozen) { simT += dt; tickClouds(dt); }
  tickAmbience(dt);
  tickSky(dt);
  tickCameraTween(dt);
  tickInertia(dt);
  /* 静置 2.2s 后极慢自转（reduceMotion / staticMode 免） */
  if (!flags.reduceMotion && !flags.staticMode && inited && !gesturing && !camTween &&
      performance.now() - lastAct > 2200) {
    pose.yaw += 0.00035;
  }
  var busy = gesturing || !!camTween || inertia.active || amb.t < 1 || skyFading > 0 ||
             amb.secT < 1 || amb.ltT < 1;
  var doRender = flags.staticMode ? (dirty || walkTransient) : (busy || (frameCount % 2 === 0));
  if (doRender) {
    renderScene();
    dirty = false;
  }
  frameCount++;
  fpsT += dt;
  if (fpsT >= 0.5) {
    if (fpsShow) fpsEl.textContent = Math.round(fpsN / fpsT) + ' fps';
    if (MOCK_MODE) {
      var ri = renderer.info;
      var texProbe = null;
      try {
        function pixelOf(tex) {
          var cv = tex && tex.image;
          if (!cv || !cv.getContext) return 'no-canvas';
          var d = cv.getContext('2d').getImageData(Math.floor(cv.width / 2), Math.floor(cv.height * 0.3), 1, 1).data;
          return [d[0], d[1], d[2], d[3]];
        }
        texProbe = { sky: pixelOf(skyTexDusk), ground: pixelOf(groundTex), wall: pixelOf(wallTex), nTex: ri.memory.textures, prog: ri.programs.length };
      } catch (e) { texProbe = 'probe-err:' + e.message; }
      _dg.textContent = JSON.stringify({
        fps: Math.round(fpsN / fpsT), err: (window.__townErrors || []).length,
        calls: ri.render.calls, tris: ri.render.triangles, px: window.__px || null,
        hidden: pageHidden, dpr: renderer.getPixelRatio(),
        pose: [r4(pose.yaw), r4(pose.pitch), r4(pose.dist), r4(pose.tx), r4(pose.tz)],
        amb: [r4(amb.cur.phase), r4(amb.lampT), Math.round(amb.duskSec)], sky: skyFrontName,
        tags: [labelEls.filter(function (l) { return l.el.style.display !== 'none'; }).length, labelEls.length],
        cards: [cardEls.filter(function (l) { return l.el.style.display !== 'none'; }).length, cardEls.length],
        walk: [walkMovingCount(), walkerById.size, walkersEnabled ? 1 : 0],
        ev: (window.__tlog || []).filter(function (t) { return t.n !== 'onPose'; }).slice(-5)
          .map(function (t) { return t.n + ':' + (typeof t.a === 'string' ? t.a.slice(0, 48) : ''); }),
        res: performance.getEntriesByType('resource').map(function (r) { return r.name.replace(location.origin, ''); }),
        tex: texProbe
      });
    }
    fpsN = 0; fpsT = 0;
  }
}
var frameCount = 0;
requestAnimationFrame(tick);

window.addEventListener('resize', function () {
  camera.aspect = window.innerWidth / window.innerHeight;
  camera.updateProjectionMatrix();
  renderer.setSize(window.innerWidth, window.innerHeight);
  glowUni.uScale.value = window.innerHeight * 0.5 * renderer.getPixelRatio();
  dirty = true;
});
glowUni.uScale.value = window.innerHeight * 0.5 * renderer.getPixelRatio();

/* ================= 16. window.townWeb（桥·七入） ================= */
window.townWeb = {
  init: function (townJson) {
    var j = (typeof townJson === 'string') ? JSON.parse(townJson) : townJson;
    buildTown(j || {});
    pose.yaw = DEF_POSE.yaw; pose.pitch = DEF_POSE.pitch; pose.tx = DEF_POSE.tx; pose.tz = DEF_POSE.tz;
    if (flags.reduceMotion) { pose.dist = DEF_POSE.dist; camTween = null; }
    else { pose.dist = 38; startTween(['dist'], { dist: DEF_POSE.dist }, 900, easeOutCubic); } /* 入场：38 俯冲到 30 */
    inited = true; firstFrameSent = false;
    dirty = true;
  },
  setCast: function (castJson) { setCastLayer(castJson); },
  setAmbience: function (ambJson) { setAmbience(ambJson); },
  setFlags: function (flagsJson) {
    var f = flagsJson || {};
    flags.reduceMotion = !!f.reduceMotion;
    flags.staticMode = !!f.staticMode;
    flags.interactive = (f.interactive !== false);
    if (flags.reduceMotion) { /* 直切：氛围瞬时到位 */
      amb.t = 1; amb.cur = cloneSnap(amb.to);
      amb.secT = 1; amb.ltT = 1;
      amb.duskSec = amb.secTo; amb.lampT = amb.ltTo;
      skyFading = 0; skyBack.visible = false;
      camTween = null;
      if (skyFrontName !== ['day', 'dusk', 'night'][amb.to.phase]) {
        skyFront.material.map = skyTexByName(['day', 'dusk', 'night'][amb.to.phase]);
        skyFrontName = ['day', 'dusk', 'night'][amb.to.phase];
      }
    }
    dirty = true;
  },
  restorePose: function (poseJson) {
    var p = poseJson || DEF_POSE;
    pose.yaw = (typeof p.yaw === 'number') ? p.yaw : DEF_POSE.yaw;
    pose.pitch = clampPitch((typeof p.pitch === 'number') ? p.pitch : DEF_POSE.pitch);
    pose.dist = clampDist((typeof p.dist === 'number') ? p.dist : DEF_POSE.dist);
    pose.tx = softClampAxis((typeof p.tx === 'number') ? p.tx : DEF_POSE.tx);
    pose.tz = softClampAxis((typeof p.tz === 'number') ? p.tz : DEF_POSE.tz);
    camTween = null; inertia.active = false;
    dirty = true;
  },
  playExit: function (ms) {
    startTween(['pitch', 'dist'], { pitch: 1.15, dist: 38 }, ms || 1200, easeInOutCubic);
  },
  playDiveTo: function (x, z, ms) {
    startTween(['tx', 'tz', 'dist'], { tx: x, tz: z, dist: 13 }, ms || 1200, easeInOutCubic);
  },
  /* W-5（契约 v1.2）：选中地点——dist→min(当前,19) 只拉近不推远·target 不动·亮金环 */
  focusPlace: function (placeId) {
    var p = null;
    for (var i = 0; i < places.length; i++) if (places[i].id === placeId) { p = places[i]; break; }
    if (!p) return;
    ensurePlaceRing();
    placeRing.position.set(p.x, 0.06, p.z);
    placeRing.visible = true;
    if (pose.dist > 19) startTween(['dist'], { dist: 19 }, flags.reduceMotion ? 1 : 600, easeInOutCubic);
    dirty = true;
  },
  /* W-5：收起选中——只熄金环·相机不复位（= GL 版关卡语义） */
  clearPlaceFocus: function () {
    if (placeRing) placeRing.visible = false;
    dirty = true;
  }
};

/* W-5 选中金环（暖金贴地圆环·呼吸感在 renderScene 里驱动） */
var placeRing = null;
function ensurePlaceRing() {
  if (placeRing) return;
  placeRing = new THREE.Mesh(
    new THREE.RingGeometry(2.0, 2.36, 36).rotateX(-PI / 2),
    new THREE.MeshBasicMaterial({ color: 0xE8C57E, transparent: true, opacity: 0.85, depthWrite: false, side: THREE.DoubleSide }));
  placeRing.visible = false;
  placeRing.renderOrder = 2;
  scene.add(placeRing);
}

/* ================= 17. MOCK 双城（仅 ?mock=1 自测用·App 不引用） ================= */
var MOCK_TOWNS = {
  yunye: {"cityId":"mock_yunye","cityName":"云野镇","curated":true,"glowA":0.6,"sky":[{"pos":0,"rgb":[0.239,0.529,0.831]},{"pos":0.167,"rgb":[0.34,0.603,0.859]},{"pos":0.333,"rgb":[0.442,0.676,0.887]},{"pos":0.5,"rgb":[0.556,0.747,0.91]},{"pos":0.667,"rgb":[0.679,0.816,0.931]},{"pos":0.833,"rgb":[0.813,0.88,0.918]},{"pos":1,"rgb":[0.957,0.937,0.871]}],"ground":[0.616,0.706,0.439],"water":"NONE","buildings":[{"cx":3.2,"cz":8.6,"sx":2.8,"h":2.4,"sz":2.2,"wall":[0.965,0.929,0.863],"roof":[0.851,0.482,0.298],"windows":3},{"cx":7.8,"cz":6.2,"sx":3.2,"h":2.8,"sz":2.4,"wall":[0.949,0.886,0.8],"roof":[0.808,0.424,0.251],"windows":3},{"cx":12.4,"cz":7.4,"sx":2.6,"h":2.2,"sz":2,"wall":[0.937,0.878,0.808],"roof":[0.855,0.518,0.322],"windows":2},{"cx":16.9,"cz":8.8,"sx":3,"h":2.6,"sz":2.3,"wall":[0.957,0.914,0.831],"roof":[0.769,0.396,0.235],"windows":3},{"cx":3.4,"cz":-7.6,"sx":2.9,"h":2.5,"sz":2.2,"wall":[0.941,0.902,0.824],"roof":[0.851,0.482,0.298],"windows":3},{"cx":8,"cz":-9.2,"sx":3.1,"h":2.6,"sz":2.5,"wall":[0.965,0.929,0.863],"roof":[0.808,0.424,0.251],"windows":3},{"cx":-6.9,"cz":-9.6,"sx":2.7,"h":2.3,"sz":2.1,"wall":[0.929,0.886,0.824],"roof":[0.855,0.518,0.322],"windows":3},{"cx":-10.4,"cz":-13.4,"sx":2.9,"h":2.4,"sz":2.2,"wall":[0.949,0.902,0.808],"roof":[0.769,0.396,0.235],"windows":3},{"cx":-6.2,"cz":5.4,"sx":2.8,"h":2.4,"sz":2.2,"wall":[0.957,0.918,0.855],"roof":[0.851,0.482,0.298],"windows":3},{"cx":-10.8,"cz":7.8,"sx":3,"h":2.5,"sz":2.3,"wall":[0.937,0.89,0.796],"roof":[0.808,0.424,0.251],"windows":3},{"cx":6.6,"cz":-4.4,"sx":2.6,"h":2.3,"sz":2,"wall":[0.941,0.894,0.808],"roof":[0.855,0.518,0.322],"windows":2},{"cx":-6.6,"cz":-3.2,"sx":2.7,"h":2.3,"sz":2.1,"wall":[0.957,0.918,0.855],"roof":[0.769,0.396,0.235],"windows":3},{"cx":1.9,"cz":1.2,"sx":4.4,"h":3.8,"sz":4.4,"wall":[0.965,0.929,0.863],"roof":[0.788,0.435,0.259],"windows":2}],"fillers":[{"cx":-3.6,"cz":10.6,"wall":[0.949,0.91,0.839]},{"cx":11,"cz":-1,"wall":[0.937,0.89,0.804]}],"lanterns":[{"cx":2.3,"cz":5.5,"baseY":0},{"cx":9.6,"cz":8.7,"baseY":0},{"cx":17.9,"cz":9.6,"baseY":0},{"cx":-2.3,"cz":-5.7,"baseY":0},{"cx":-9.7,"cz":-11.5,"baseY":0},{"cx":-5.5,"cz":4.9,"baseY":0}],"trees":[{"cx":8.8,"cz":12.4,"s":1.15,"leaf":[0.498,0.659,0.369],"trunkH":0.7,"coneH":1.6},{"cx":14.6,"cz":4.6,"s":0.95,"leaf":[0.561,0.706,0.447],"trunkH":0.7,"coneH":1.6},{"cx":18.2,"cz":11.8,"s":1.1,"leaf":[0.431,0.604,0.329],"trunkH":0.7,"coneH":1.6},{"cx":20.2,"cz":4.2,"s":0.9,"leaf":[0.498,0.686,0.4],"trunkH":0.7,"coneH":1.6},{"cx":-8.8,"cz":11.8,"s":1.05,"leaf":[0.498,0.659,0.369],"trunkH":0.7,"coneH":1.6},{"cx":-13.8,"cz":4.4,"s":1.1,"leaf":[0.561,0.706,0.447],"trunkH":0.7,"coneH":1.6},{"cx":-16.4,"cz":9.4,"s":0.95,"leaf":[0.431,0.604,0.329],"trunkH":0.7,"coneH":1.6},{"cx":-12.2,"cz":12.6,"s":0.85,"leaf":[0.498,0.686,0.4],"trunkH":0.7,"coneH":1.6},{"cx":4.4,"cz":-11.6,"s":1,"leaf":[0.498,0.659,0.369],"trunkH":0.7,"coneH":1.6},{"cx":-2.6,"cz":-12.4,"s":1.15,"leaf":[0.561,0.706,0.447],"trunkH":0.7,"coneH":1.6},{"cx":12.8,"cz":-6.6,"s":0.9,"leaf":[0.431,0.604,0.329],"trunkH":0.7,"coneH":1.6},{"cx":15.4,"cz":-11.2,"s":1.05,"leaf":[0.498,0.686,0.4],"trunkH":0.7,"coneH":1.6},{"cx":-12.6,"cz":-4.2,"s":0.9,"leaf":[0.498,0.659,0.369],"trunkH":0.7,"coneH":1.6},{"cx":18.6,"cz":-4.4,"s":1,"leaf":[0.561,0.706,0.447],"trunkH":0.7,"coneH":1.6},{"cx":-16.2,"cz":-2.4,"s":1,"leaf":[0.431,0.604,0.329],"trunkH":0.7,"coneH":1.6},{"cx":2.2,"cz":13.6,"s":0.85,"leaf":[0.498,0.686,0.4],"trunkH":0.7,"coneH":1.6},{"cx":-3.4,"cz":-16.2,"s":1.1,"leaf":[0.498,0.659,0.369],"trunkH":0.7,"coneH":1.6},{"cx":6.2,"cz":16,"s":1,"leaf":[0.561,0.706,0.447],"trunkH":0.7,"coneH":1.6},{"cx":-14.6,"cz":8.2,"s":1,"leaf":[0.373,0.541,0.314],"trunkH":0.8,"coneH":2.1},{"cx":-16,"cz":6.2,"s":0.85,"leaf":[0.373,0.541,0.314],"trunkH":0.8,"coneH":2.1},{"cx":-13.4,"cz":10.2,"s":0.9,"leaf":[0.373,0.541,0.314],"trunkH":0.8,"coneH":2.1},{"cx":21,"cz":9,"s":1,"leaf":[0.373,0.541,0.314],"trunkH":0.8,"coneH":2.1},{"cx":19.6,"cz":12.8,"s":0.9,"leaf":[0.373,0.541,0.314],"trunkH":0.8,"coneH":2.1}],"litBoxes":[{"cx":0,"y0":0.04,"cz":0,"sx":10.8,"h":0.08,"sz":10.8,"col":[0.788,0.741,0.643]},{"cx":0,"y0":0.1,"cz":0,"sx":1.6,"h":0.72,"sz":1.6,"col":[0.72,0.66,0.56]},{"cx":20.9,"y0":0.05,"cz":6.6,"sx":1.5,"h":0.5,"sz":0.5,"col":[0.541,0.384,0.251]},{"cx":-11.2,"y0":0.05,"cz":-11.9,"sx":1.5,"h":0.5,"sz":0.5,"col":[0.541,0.384,0.251]},{"cx":-15.2,"y0":0.05,"cz":-11,"sx":7.6,"h":0.06,"sz":5.4,"col":[0.851,0.659,0.706]},{"cx":-16.6,"y0":0.13,"cz":-10.4,"sx":0.5,"h":0.03,"sz":0.5,"col":[0.42,0.6,0.36]},{"cx":-13.9,"y0":0.13,"cz":-12.1,"sx":0.42,"h":0.03,"sz":0.42,"col":[0.47,0.64,0.4]},{"cx":-14.6,"y0":0.13,"cz":-9.6,"sx":0.36,"h":0.03,"sz":0.36,"col":[0.52,0.68,0.44]}],"emisBoxes":[{"cx":1.9,"y0":5.95,"cz":1.2,"sx":0.5,"h":0.5,"sz":0.5,"col":[1,0.788,0.494]}],"cones":[{"cx":6.2,"y":0,"cz":1.8,"r":0.55,"h":0.7,"col":[0.42,0.58,0.33]},{"cx":-3.4,"y":0,"cz":7.4,"r":0.5,"h":0.62,"col":[0.38,0.54,0.31]},{"cx":14.2,"y":0,"cz":3.4,"r":0.52,"h":0.66,"col":[0.45,0.6,0.35]}],"grammar":[],"places":[{"id":"yunye_tower","name":"晨钟楼","x":1.9,"z":1.2,"top":6.2},{"id":"yunye_plaza","name":"水井广场","x":0,"z":0,"top":2.9},{"id":"yunye_cafe","name":"拾光咖啡馆","x":-6.6,"z":-3.2,"top":4.1},{"id":"yunye_bakery","name":"暖窑面包房","x":3.2,"z":8.6,"top":4.2},{"id":"yunye_pond","name":"荷塘小筑","x":-15.2,"z":-11,"top":1.6}]},
  panshi: {"cityId":"mock_panshi","cityName":"磐石城","curated":false,"glowA":0.6,"sky":[{"pos":0,"rgb":[0.322,0.571,0.825]},{"pos":0.167,"rgb":[0.411,0.636,0.85]},{"pos":0.333,"rgb":[0.501,0.7,0.874]},{"pos":0.5,"rgb":[0.601,0.763,0.894]},{"pos":0.667,"rgb":[0.709,0.824,0.913]},{"pos":0.833,"rgb":[0.827,0.88,0.901]},{"pos":1,"rgb":[0.954,0.93,0.86]}],"ground":[0.72,0.65,0.5],"water":"WEST_RIVER","buildings":[],"fillers":[],"lanterns":[{"cx":-1,"cz":1,"baseY":0},{"cx":9,"cz":1,"baseY":0},{"cx":4,"cz":-6.5,"baseY":0},{"cx":4,"cz":6.5,"baseY":0},{"cx":-16.5,"cz":6.5,"baseY":0}],"trees":[{"cx":-6.5,"cz":8.2,"s":1.1,"leaf":[0.431,0.604,0.329],"trunkH":0.7,"coneH":1.6},{"cx":14.5,"cz":8,"s":0.95,"leaf":[0.498,0.659,0.369],"trunkH":0.7,"coneH":1.6},{"cx":-7.5,"cz":-8.5,"s":1.05,"leaf":[0.561,0.706,0.447],"trunkH":0.7,"coneH":1.6},{"cx":16,"cz":-8,"s":1,"leaf":[0.373,0.541,0.314],"trunkH":0.8,"coneH":2.1},{"cx":0.5,"cz":-8,"s":0.9,"leaf":[0.498,0.686,0.4],"trunkH":0.7,"coneH":1.6},{"cx":-15,"cz":-4,"s":1.1,"leaf":[0.431,0.604,0.329],"trunkH":0.7,"coneH":1.6}],"litBoxes":[{"cx":4,"y0":0.04,"cz":2,"sx":6.5,"h":0.08,"sz":5,"col":[0.788,0.741,0.643]},{"cx":-18.5,"y0":0.08,"cz":6.5,"sx":5,"h":0.16,"sz":1.6,"col":[0.604,0.478,0.337]}],"emisBoxes":[{"cx":-11.5,"y":3.1,"cz":0,"sx":0.6,"h":0.6,"sz":0.6,"col":[1,0.886,0.627]}],"cones":[{"cx":2.2,"y":0.1,"cz":3.6,"r":0.95,"h":0.9,"col":[0.878,0.471,0.29]},{"cx":5.8,"y":0.1,"cz":0.6,"r":0.95,"h":0.9,"col":[0.369,0.549,0.627]},{"cx":-2.2,"y":0,"cz":-1.2,"r":0.5,"h":0.62,"col":[0.42,0.58,0.33]},{"cx":10.6,"y":0,"cz":-1.4,"r":0.55,"h":0.7,"col":[0.38,0.54,0.31]}],"grammar":[{"t":"lit","x":-11.8,"y":0,"z":1.6,"sx":4.6,"h":2.4,"sz":3.6,"col":[0.91,0.851,0.745]},{"t":"roof","style":"PYRAMID","x":-11.95,"y":2.4,"z":1.45,"sx":4.9,"h":1.98,"sz":3.9,"col":[0.769,0.396,0.235]},{"t":"emis","x":-10.74,"y":1.01,"z":5.15,"sx":0.55,"h":0.62,"sz":0.1,"col":[1,0.831,0.541]},{"t":"emis","x":-8.81,"y":1.01,"z":5.15,"sx":0.55,"h":0.62,"sz":0.1,"col":[1,0.831,0.541]},{"t":"lit","x":-5.4,"y":0,"z":2.5,"sx":3.8,"h":1.8,"sz":3,"col":[0.875,0.788,0.659]},{"t":"roof","style":"PYRAMID","x":-5.55,"y":1.8,"z":2.35,"sx":4.1,"h":1.65,"sz":3.3,"col":[0.722,0.353,0.227]},{"t":"emis","x":-3.77,"y":0.68,"z":5.45,"sx":0.55,"h":0.62,"sz":0.1,"col":[1,0.831,0.541]},{"t":"lit","x":6.9,"y":0,"z":1.9,"sx":4.2,"h":2.8,"sz":3.4,"col":[0.894,0.824,0.706]},{"t":"roof","style":"GABLE","x":6.75,"y":2.8,"z":1.75,"sx":4.5,"h":1.87,"sz":3.7,"col":[0.659,0.329,0.29]},{"t":"emis","x":7.84,"y":1.23,"z":5.25,"sx":0.55,"h":0.62,"sz":0.1,"col":[1,0.831,0.541]},{"t":"emis","x":9.61,"y":1.23,"z":5.25,"sx":0.55,"h":0.62,"sz":0.1,"col":[1,0.831,0.541]},{"t":"lit","x":12,"y":0,"z":2.8,"sx":3.2,"h":1.6,"sz":2.8,"col":[0.847,0.761,0.612]},{"t":"roof","style":"GABLE","x":11.85,"y":1.6,"z":2.65,"sx":3.5,"h":1.54,"sz":3.1,"col":[0.769,0.396,0.235]},{"t":"emis","x":13.32,"y":0.57,"z":5.55,"sx":0.55,"h":0.62,"sz":0.1,"col":[1,0.831,0.541]},{"t":"lit","x":-11,"y":0,"z":-5.4,"sx":4,"h":2,"sz":3.2,"col":[0.918,0.867,0.769]},{"t":"roof","style":"PYRAMID","x":-11.15,"y":2,"z":-5.55,"sx":4.3,"h":1.76,"sz":3.5,"col":[0.722,0.353,0.227]},{"t":"emis","x":-9.28,"y":0.79,"z":-2.25,"sx":0.55,"h":0.62,"sz":0.1,"col":[1,0.831,0.541]},{"t":"lit","x":-3.8,"y":0,"z":-5.9,"sx":3.6,"h":2.6,"sz":3,"col":[0.824,0.737,0.588]},{"t":"roof","style":"GABLE","x":-3.95,"y":2.6,"z":-6.05,"sx":3.9,"h":1.65,"sz":3.3,"col":[0.659,0.329,0.29]},{"t":"emis","x":-3.03,"y":1.12,"z":-2.95,"sx":0.55,"h":0.62,"sz":0.1,"col":[1,0.831,0.541]},{"t":"emis","x":-1.52,"y":1.12,"z":-2.95,"sx":0.55,"h":0.62,"sz":0.1,"col":[1,0.831,0.541]},{"t":"lit","x":6.4,"y":0,"z":-5.3,"sx":4.4,"h":2.2,"sz":3.4,"col":[0.91,0.851,0.745]},{"t":"roof","style":"GABLE","x":6.25,"y":2.2,"z":-5.45,"sx":4.7,"h":1.87,"sz":3.7,"col":[0.769,0.396,0.235]},{"t":"emis","x":7.4,"y":0.9,"z":-1.95,"sx":0.55,"h":0.62,"sz":0.1,"col":[1,0.831,0.541]},{"t":"emis","x":9.25,"y":0.9,"z":-1.95,"sx":0.55,"h":0.62,"sz":0.1,"col":[1,0.831,0.541]},{"t":"lit","x":11.7,"y":0,"z":-5.3,"sx":3,"h":1.5,"sz":2.6,"col":[0.875,0.788,0.659]},{"t":"roof","style":"PYRAMID","x":11.55,"y":1.5,"z":-5.45,"sx":3.3,"h":1.43,"sz":2.9,"col":[0.722,0.353,0.227]},{"t":"emis","x":12.92,"y":0.52,"z":-2.75,"sx":0.55,"h":0.62,"sz":0.1,"col":[1,0.831,0.541]},{"t":"lit","x":-13.2,"y":0,"z":-2.3,"sx":3.4,"h":3,"sz":4.6,"col":[0.894,0.824,0.706]},{"t":"roof","style":"GABLE","x":-13.35,"y":3,"z":-2.45,"sx":3.7,"h":1.87,"sz":4.9,"col":[0.659,0.329,0.29]},{"t":"emis","x":-12.49,"y":1.34,"z":2.25,"sx":0.55,"h":0.62,"sz":0.1,"col":[1,0.831,0.541]},{"t":"emis","x":-11.06,"y":1.34,"z":2.25,"sx":0.55,"h":0.62,"sz":0.1,"col":[1,0.831,0.541]},{"t":"lit","x":2,"y":0,"z":-12.6,"sx":4,"h":2.3,"sz":3.2,"col":[0.847,0.761,0.612]},{"t":"roof","style":"PYRAMID","x":1.85,"y":2.3,"z":-12.75,"sx":4.3,"h":1.76,"sz":3.5,"col":[0.769,0.396,0.235]},{"t":"emis","x":2.89,"y":0.95,"z":-9.45,"sx":0.55,"h":0.62,"sz":0.1,"col":[1,0.831,0.541]},{"t":"emis","x":4.56,"y":0.95,"z":-9.45,"sx":0.55,"h":0.62,"sz":0.1,"col":[1,0.831,0.541]},{"t":"lit","x":2.4,"y":0,"z":8,"sx":3.6,"h":1.9,"sz":3,"col":[0.918,0.867,0.769]},{"t":"roof","style":"PYRAMID","x":2.25,"y":1.9,"z":7.85,"sx":3.9,"h":1.65,"sz":3.3,"col":[0.722,0.353,0.227]},{"t":"emis","x":3.93,"y":0.73,"z":10.95,"sx":0.55,"h":0.62,"sz":0.1,"col":[1,0.831,0.541]},{"t":"lit","x":-7.7,"y":0,"z":-12.9,"sx":3.4,"h":1.6,"sz":2.8,"col":[0.824,0.737,0.588]},{"t":"roof","style":"FLAT","x":-7.85,"y":1.6,"z":-13.05,"sx":3.7,"h":0.45,"sz":3.1,"col":[0.659,0.329,0.29]},{"t":"emis","x":-6.28,"y":0.57,"z":-10.15,"sx":0.55,"h":0.62,"sz":0.1,"col":[1,0.831,0.541]},{"t":"lit","x":9.6,"y":0,"z":-12.5,"sx":3.8,"h":2.5,"sz":3,"col":[0.91,0.851,0.745]},{"t":"roof","style":"PYRAMID","x":9.45,"y":2.5,"z":-12.65,"sx":4.1,"h":1.65,"sz":3.3,"col":[0.769,0.396,0.235]},{"t":"emis","x":10.43,"y":1.06,"z":-9.55,"sx":0.55,"h":0.62,"sz":0.1,"col":[1,0.831,0.541]},{"t":"emis","x":12.02,"y":1.06,"z":-9.55,"sx":0.55,"h":0.62,"sz":0.1,"col":[1,0.831,0.541]},{"t":"lit","x":-14.1,"y":0,"z":7.6,"sx":3.2,"h":1.7,"sz":2.8,"col":[0.875,0.788,0.659]},{"t":"roof","style":"PYRAMID","x":-14.25,"y":1.7,"z":7.45,"sx":3.5,"h":1.54,"sz":3.1,"col":[0.722,0.353,0.227]},{"t":"emis","x":-12.78,"y":0.63,"z":10.35,"sx":0.55,"h":0.62,"sz":0.1,"col":[1,0.831,0.541]}],"places":[{"id":"panshi_hall","name":"磐石会堂","x":-11.5,"z":0,"top":4.6},{"id":"panshi_market","name":"北巷市集","x":4,"z":2,"top":2.6},{"id":"panshi_ferry","name":"河畔渡口","x":-18.5,"z":6.5,"top":2.2}]}
};
var MOCK_AMB = {
  day: { phase: 0, lampT: 0, duskSec: 0, tint: [1, 1, 1], fog: [0.84, 0.89, 0.93], glowA: 0.05 },
  dusk: { phase: 1, lampT: 1, duskSec: 5.0, tint: [1, 0.88, 0.74], fog: [0.91, 0.71, 0.56], glowA: 1.0 },
  night: { phase: 2, lampT: 1, duskSec: 3600, tint: [0.56, 0.63, 0.86], fog: [0.086, 0.118, 0.227], glowA: 1.15 }
};
function mockCast(city, walkOn) {
  var on = walkOn !== false;
  if (city === 'yunye') {
    /* 三期卷一 §7：走动样例 = 角色 c6 + 原住民 c2/c7 + 神秘人 c4；「走动开关」off →
       c2 翻转钉卡、c6 消失（id 退场不钉）、新 id c8 登场（验 §3.2 全套）；c1/c3/c5 钉卡零变化 */
    var cards = [
      { id: 'c1', kind: 'member', name: '小音', x: -1.6, y: 1.7, z: 3.4, avatar: mockAvatar(), present: true, atHome: true },
      { id: 'c2', kind: 'native', name: '灰炉师傅', x: 3.2, y: 1.8, z: 8.6, avatar: null, present: true, walking: on },
      { id: 'c3', kind: 'pet', name: '团子', x: 7.0, y: 1.2, z: 5.6, avatar: null, present: true },
      { id: 'c4', kind: 'mystery', name: '???', x: -4.9, y: 1.7, z: 3.9, avatar: null, present: true, walking: true },
      { id: 'c5', kind: 'member', name: '阿岸', x: -5.5, y: 1.7, z: 4.9, avatar: null, present: false, sleeping: true },
      { id: 'c7', kind: 'native', name: '溪尾叔', x: -8.4, y: 1.7, z: 0.6, avatar: null, present: true, walking: true }
    ];
    if (on) cards.push({ id: 'c6', kind: 'member', name: '小满', x: -1.6, y: 1.6, z: 7.2, avatar: mockAvatar(), present: true, walking: true });
    else cards.push({ id: 'c8', kind: 'member', name: '汀兰', x: 6.8, y: 1.6, z: 3.2, avatar: null, present: true, walking: true });
    return { cards: cards, overflows: [ { x: 0, y: 2.2, z: 0, count: 3 } ] };
  }
  var cards2 = [
    { id: 'p1', kind: 'native', name: '石匠北伯', x: -10.4, y: 1.8, z: 0.6, avatar: null, present: true },
    { id: 'p2', kind: 'member', name: '阿澄', x: 4.4, y: 1.6, z: 2.2, avatar: null, present: true, walking: on },
    { id: 'p3', kind: 'mystery', name: '', x: -1.5, y: 1.6, z: 3.5, avatar: null, present: true, walking: true }
  ];
  return { cards: on ? cards2 : cards2.slice(0, 2), overflows: [ { x: 4, y: 1.6, z: 2, count: 5 } ] };
}
/* R1 返工新增：满槽 16 走动客机审档——🔴-1（槽 ≥8 手脚越界）/🟡-1（换城残留）的取证区间。
   i%5===4 共三名神秘人（w5/w10/w15·R2 nit-1 更正注释）；换城/走动开关即回常规 mock。 */
function mockCastFull(city) {
  var cards = [];
  for (var i = 0; i < 16; i++) {
    var myst = i % 5 === 4;
    cards.push({
      id: 'w' + (i + 1), kind: myst ? 'mystery' : 'member', name: myst ? '' : '客' + (i + 1),
      x: -8 + (i % 4) * 4.6, y: 1.7, z: -7 + Math.floor(i / 4) * 4.4,
      avatar: null, present: true, walking: true
    });
  }
  return { cards: cards, overflows: [] };
}
var _mockAvatarCache = null;
function mockAvatar() {
  if (_mockAvatarCache) return _mockAvatarCache;
  var c = cnv(64, 64), x = c[1];
  x.fillStyle = '#F2CBA8'; x.fillRect(0, 0, 64, 64);
  x.fillStyle = '#5E8CA0'; x.beginPath(); x.arc(32, 22, 16, 0, PI * 2); x.fill();
  x.fillStyle = '#C96F42'; x.fillRect(10, 44, 44, 20);
  x.fillStyle = '#4A3826'; x.beginPath(); x.arc(25, 30, 2.4, 0, PI * 2); x.arc(39, 30, 2.4, 0, PI * 2); x.fill();
  x.strokeStyle = '#A8686A'; x.lineWidth = 2.4; x.beginPath(); x.arc(32, 36, 6, 0.2, PI - 0.2); x.stroke();
  _mockAvatarCache = c[0].toDataURL('image/jpeg', 0.75);
  return _mockAvatarCache;
}
/* mock 手势注入（仅 ?mock=1 自动化自测用：向画布派发合成 PointerEvent） */
if (MOCK_MODE) {
  var _firePtr = function (type, id, x, y) {
    cvs.dispatchEvent(new PointerEvent(type, {
      pointerId: id, pointerType: 'touch', isPrimary: id === 1,
      clientX: x, clientY: y, bubbles: true, cancelable: true
    }));
  };
  window.townMockGestures = {
    drag: function (x0, y0, x1, y1, steps) {
      steps = steps || 12;
      _firePtr('pointerdown', 1, x0, y0);
      var i = 0;
      var iv = setInterval(function () {
        i++;
        var t = i / steps;
        _firePtr('pointermove', 1, x0 + (x1 - x0) * t, y0 + (y1 - y0) * t);
        if (i >= steps) { clearInterval(iv); _firePtr('pointerup', 1, x1, y1); }
      }, 16);
    },
    pinchOut: function (cx, cy, from, to, steps) {
      steps = steps || 16;
      _firePtr('pointerdown', 1, cx - from / 2, cy);
      _firePtr('pointerdown', 2, cx + from / 2, cy);
      var i = 0;
      var iv = setInterval(function () {
        i++;
        var d = from + (to - from) * (i / steps);
        _firePtr('pointermove', 1, cx - d / 2, cy);
        _firePtr('pointermove', 2, cx + d / 2, cy);
        if (i >= steps) {
          clearInterval(iv);
          _firePtr('pointerup', 1, cx - to / 2, cy);
          _firePtr('pointerup', 2, cx + to / 2, cy);
        }
      }, 16);
    },
    twoFinger: function (cx, cy, steps) { /* 双指同向下滑(pitch) + 对转(rotate) */
      steps = steps || 12;
      _firePtr('pointerdown', 1, cx - 100, cy);
      _firePtr('pointerdown', 2, cx + 100, cy);
      var i = 0;
      var iv = setInterval(function () {
        i++;
        var t = i / steps;
        var dy = 60 * t;                          /* 同向下滑 60px → pitch 变化 */
        var rot = 0.5 * t;                        /* 双指对转 → yaw 变化 */
        var c = Math.cos(rot - 0), s = Math.sin(rot);
        var ox = 100 * c, oy = 100 * s;
        _firePtr('pointermove', 1, cx - ox, cy + dy - oy);
        _firePtr('pointermove', 2, cx + ox, cy + dy + oy);
        if (i >= steps) {
          clearInterval(iv);
          _firePtr('pointerup', 1, cx - ox, cy + 60 - oy);
          _firePtr('pointerup', 2, cx + ox, cy + 60 + oy);
        }
      }, 16);
    }
  };
}
if (MOCK_MODE) {
  var mockUI = document.getElementById('mockUI');
  mockUI.style.display = 'flex';
  var curCity = 'yunye', curPhase = 'dusk', walkMockOn = true;
  function loadMock(city) {
    curCity = city;
    window.townWeb.init(MOCK_TOWNS[city]);
    window.townWeb.setCast(mockCast(city, walkMockOn));
    window.townWeb.setAmbience(MOCK_AMB[curPhase]);
    document.querySelectorAll('#mockCities .mbtn').forEach(function (b) {
      b.classList.toggle('on', b.dataset.c === city);
    });
  }
  var rowC = document.getElementById('mockCities');
  [['yunye', '云野镇·精修'], ['panshi', '磐石城·程序']].forEach(function (d) {
    var b = document.createElement('button');
    b.className = 'mbtn'; b.dataset.c = d[0]; b.textContent = d[1];
    b.addEventListener('click', function () { loadMock(d[0]); });
    rowC.appendChild(b);
  });
  var rowP = document.getElementById('mockPhases');
  [['day', '白天'], ['dusk', '黄昏燃灯'], ['night', '深夜星空'], ['stagger', '错峰演示']].forEach(function (d) {
    var b = document.createElement('button');
    b.className = 'mbtn'; b.dataset.p = d[0]; b.textContent = d[1];
    b.addEventListener('click', function () {
      if (d[0] === 'stagger') {
        /* duskSec 从 0 起由页面按真实秒推进 → 12s 内可见全镇窗火错峰点亮 */
        window.townWeb.setAmbience(Object.assign({}, MOCK_AMB.dusk, { duskSec: 0 }));
        return;
      }
      curPhase = d[0];
      window.townWeb.setAmbience(MOCK_AMB[d[0]]);
      document.querySelectorAll('#mockPhases .mbtn').forEach(function (x) {
        if (x.dataset.p !== 'stagger') x.classList.toggle('on', x.dataset.p === d[0]);
      });
    });
    rowP.appendChild(b);
  });
  /* 自测动作按钮：dist 顶格后双指继续并拢（overzoom→onReturnGesture）/ 旋转俯仰 / 俯冲+离场镜头 */
  [['捏合演示', function () {
      pose.dist = 38; /* 手感修正后：顶格继续「并拢」(span 变小·ratio>1) 才触发返回 */
      if (window.townMockGestures) window.townMockGestures.pinchOut(450, 300, 220, 110, 18);
    }],
   ['旋转俯仰', function () {
      if (window.townMockGestures) window.townMockGestures.twoFinger(450, 300, 12);
    }],
   ['镜头演示', function () {
      window.townWeb.playDiveTo(-6.6, -3.2, 1400);
      setTimeout(function () { window.townWeb.playExit(1400); }, 2400);
    }],
   ['选中聚焦', function () { /* W-5 自测：收距 + 金环 → 3s 后熄环（相机按语义不复位） */
      window.townWeb.focusPlace('yunye_cafe');
      setTimeout(function () { window.townWeb.clearPlaceFocus(); }, 3000);
    }],
   ['走动开关', function () { /* 三期卷一 §7：模拟 walking 翻转 / id 消失 / 新 id 生成（§3.2） */
      walkMockOn = !walkMockOn;
      this.classList.toggle('on', walkMockOn);
      window.townWeb.setCast(mockCast(curCity, walkMockOn));
    }, true],
   ['满槽演示', function () { /* R1 返工新增：16 走动客满槽（🔴-1/🟡-1 取证区间） */
      window.townWeb.setCast(mockCastFull(curCity));
    }]].forEach(function (d) {
    var b = document.createElement('button');
    b.className = 'mbtn'; b.textContent = d[0];
    b.addEventListener('click', d[1]);
    if (d[2]) b.classList.add('on');
    rowP.appendChild(b);
  });
  /* 三期卷一 mock 取证钩子（仅 ?mock=1 生效）：?phase=day|dusk|night|stagger 定档 ·
     ?pose=yaw,pitch,dist[,tx,tz] 定格相机 · ?city=yunye|panshi 定城 · ?nov=1 跳过揭幕渐变（headless 截图用） */
  var mcity = /[?&]city=(yunye|panshi)/.exec(location.search);
  var mphase = /[?&]phase=(day|dusk|night|stagger)/.exec(location.search);
  if (mphase) curPhase = mphase[1];
  loadMock(mcity ? mcity[1] : 'yunye');
  rowP.querySelector('[data-p="' + (curPhase === 'stagger' ? 'dusk' : curPhase) + '"]').classList.add('on');
  var mpose = /[?&]pose=([-\d.+,]+)/.exec(location.search);
  if (mpose) {
    var pv = mpose[1].split(',');
    window.townWeb.restorePose({ yaw: +pv[0], pitch: +pv[1], dist: +pv[2],
      tx: (pv.length > 3 ? +pv[3] : -1.5), tz: (pv.length > 4 ? +pv[4] : -1.0) });
  }
  if (/[?&]nov=1/.test(location.search)) {
    var vvn = document.getElementById('veil');
    if (vvn) vvn.style.transition = 'none';
  }
}

/* 就绪：App 在 init 之前就能收到 */
bridge('onReady');

})();
