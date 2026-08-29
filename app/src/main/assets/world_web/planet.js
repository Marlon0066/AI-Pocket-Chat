/* ============================================================================
   planet.js — 「AI Pocket Chat」世界系统 · 星球屏网页渲染引擎（二期）
   契约：网页世界二期前端施工契约 v1.0（大陆 + 星球 + 桥协议）
   铁律：零网络请求 · 零硬编码布局（一切来自 init(planetJson)）· 背景不闪白
   美术：深夜蓝金 · 水彩晕染 · 灯火叙事（canvas 程序化手绘，无外部资源）
   同 seed 必须同貌：星球地貌全部由 mulberry32(seed) 确定性驱动，不用 Math.random
   行数豁免：本文件为外部 AI 按契约交付的整体产物，机审验收后由前端承包方整文件维护
   ========================================================================= */
'use strict';
(function () {

/* ================= 0. 桥（页面 → App） ================= */
var BR = window.AndroidBridge || null;
function bridge(name, a) {
  if (window.__tlog) window.__tlog.push({ n: name, a: a === undefined ? null : a });
  try {
    if (BR && typeof BR[name] === 'function') {
      if (a === undefined) BR[name](); else BR[name](a);
    } else {
      var show = (a === undefined) ? '' : (typeof a === 'string' ? a : JSON.stringify(a));
      console.log('[planet→app]', name, show);
    }
  } catch (e) { console.error('[planet→app]', name, e); }
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
var MOCK_MODE = /[?&]mock=1/.test(location.search);
if (/[?&]nov=1/.test(location.search)) { /* 截图自测：跳过揭幕暗场（veil 不参与验收） */
  var _nv = document.getElementById('veil'); if (_nv) _nv.style.display = 'none';
}
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
function r4(v) { return Math.round(v * 10000) / 10000; }
/* mulberry32：同 seed 同貌的确定性随机（星球地貌铁律） */
function mulberry32(seed) {
  var s = seed | 0;
  return function () {
    s = (s + 0x6D2B79F5) | 0;
    var t = Math.imul(s ^ (s >>> 15), 1 | s);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}
/* 整点哈希（3D 值噪声晶格用，确定性） */
function hash3i(x, y, z, seed) {
  var h = Math.imul(x, 374761393) ^ Math.imul(y, 668265263) ^ Math.imul(z, 2246822519) ^ (seed | 0);
  h = Math.imul(h ^ (h >>> 13), 1274126177);
  return ((h ^ (h >>> 16)) >>> 0) / 4294967296;
}
/* 3D 值噪声（球面采样 ⇒ 等距柱状贴图零接缝） */
function vnoise3(x, y, z, seed) {
  var ix = Math.floor(x), iy = Math.floor(y), iz = Math.floor(z);
  var fx = x - ix, fy = y - iy, fz = z - iz;
  var ux = fx * fx * (3 - 2 * fx), uy = fy * fy * (3 - 2 * fy), uz = fz * fz * (3 - 2 * fz);
  var c000 = hash3i(ix, iy, iz, seed), c100 = hash3i(ix + 1, iy, iz, seed);
  var c010 = hash3i(ix, iy + 1, iz, seed), c110 = hash3i(ix + 1, iy + 1, iz, seed);
  var c001 = hash3i(ix, iy, iz + 1, seed), c101 = hash3i(ix + 1, iy, iz + 1, seed);
  var c011 = hash3i(ix, iy + 1, iz + 1, seed), c111 = hash3i(ix + 1, iy + 1, iz + 1, seed);
  var x00 = lerp(c000, c100, ux), x10 = lerp(c010, c110, ux);
  var x01 = lerp(c001, c101, ux), x11 = lerp(c011, c111, ux);
  return lerp(lerp(x00, x10, uy), lerp(x01, x11, uy), uz);
}
function fbm3(x, y, z, oct, seed) {
  var v = 0, a = 0.5, f = 1, norm = 0;
  for (var i = 0; i < oct; i++) { v += a * vnoise3(x * f, y * f, z * f, seed + i * 131); norm += a; a *= 0.5; f *= 2.07; }
  return v / norm;
}

/* ================= 2. 渲染基座 ================= */
var renderer = new THREE.WebGLRenderer({ antialias: true });
renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2)); /* 契约：pixelRatio 上限 2 */
renderer.setSize(window.innerWidth, window.innerHeight);
renderer.outputEncoding = THREE.sRGBEncoding;
renderer.toneMapping = THREE.ACESFilmicToneMapping;
renderer.toneMappingExposure = 1.16;
renderer.domElement.id = 'gl';
document.body.appendChild(renderer.domElement);

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
var FOV_RAD = 0.9; /* 契约 §5.2：竖向 FOV 0.9 rad */
var camera = new THREE.PerspectiveCamera(FOV_RAD * 180 / PI, window.innerWidth / window.innerHeight, 0.05, 300);

var sun = new THREE.DirectionalLight(0xFFD9AC, 1.9);
sun.position.set(-2.2, 1.35, 1.7).normalize().multiplyScalar(8);
scene.add(sun);
/* 补光：相机侧深空蓝弱光，把 night side 抬到可读（水彩不留死黑） */
var fill = new THREE.DirectionalLight(0x5A6FA8, 0.42);
fill.position.set(1.4, -0.4, 2.2).normalize().multiplyScalar(8);
scene.add(fill);
var hemi = new THREE.HemisphereLight(0x46548A, 0x1A2030, 0.9);
scene.add(hemi);

/* ================= 3. 无渐变画笔（契约 §2.1：大画布全程禁用 CanvasGradient） =================
   渐变带 = 逐行/逐列插值纯色 fillRect；光晕 = 同心盘按精确合成公式 p=(aIn−aOut)/(1−aOut) 叠加。
   每张大画布画完立刻 getImageData 回读非透明（gradProbe），把「静默变透明」钉死在 creation 时。 */
function cnv(w, h) { var c = document.createElement('canvas'); c.width = w; c.height = h; return [c, c.getContext('2d', { willReadFrequently: true })]; }
function rgbStr(a) { return 'rgb(' + Math.round(clamp(a[0], 0, 1) * 255) + ',' + Math.round(clamp(a[1], 0, 1) * 255) + ',' + Math.round(clamp(a[2], 0, 1) * 255) + ')'; }
function stopColorAt(list, t) {
  var lo = list[0], hi = list[list.length - 1];
  if (t <= lo.pos) return lo.rgb;
  if (t >= hi.pos) return hi.rgb;
  for (var i = 1; i < list.length; i++) { if (list[i].pos >= t) { hi = list[i]; lo = list[i - 1]; break; } }
  var k = (t - lo.pos) / Math.max(1e-6, hi.pos - lo.pos);
  return [lerp(lo.rgb[0], hi.rgb[0], k), lerp(lo.rgb[1], hi.rgb[1], k), lerp(lo.rgb[2], hi.rgb[2], k)];
}
/* 竖向渐变带：逐行插值纯色（0 行 = 顶） */
function vGradRows(x, w, h, stops) {
  for (var row = 0; row < h; row++) {
    x.fillStyle = rgbStr(stopColorAt(stops, row / Math.max(1, h - 1)));
    x.fillRect(0, row, w, 1);
  }
}
/* 同心椭圆盘从外向内叠加逼近径向渐变（aFn(k)=半径占比 k 处目标累计透明度·aFn(1)=0） */
function glowStack(x, cx, cy, r, ry, colStr, aFn, rings) {
  for (var i = rings; i >= 1; i--) {
    var aOut = aFn(i / rings), aIn = aFn((i - 1) / rings);
    var p = (aIn - aOut) / Math.max(0.02, 1 - aOut);
    if (p <= 0.003) continue;
    x.fillStyle = 'rgba(' + colStr + ',' + Math.min(1, p).toFixed(3) + ')';
    x.beginPath(); x.ellipse(cx, cy, Math.max(0.01, r * i / rings), Math.max(0.01, ry * i / rings), 0, 0, PI * 2); x.fill();
  }
}
function grainRng(x, w, h, n, alpha, rng, dark) {
  for (var i = 0; i < n; i++) {
    x.fillStyle = 'rgba(' + (dark || '60,45,30') + ',' + (rng() * alpha).toFixed(3) + ')';
    x.fillRect(rng() * w, rng() * h, 1 + rng() * 1.6, 1 + rng() * 1.6);
  }
}
function T(c) {
  var t = new THREE.CanvasTexture(c[0]);
  t.encoding = THREE.sRGBEncoding;
  t.anisotropy = Math.min(8, renderer.capabilities.getMaxAnisotropy());
  return t;
}
/* 渐变探针（§2.1）：画完立刻回读，任何样本点 alpha=0 即探针失败 → onError + 记账 */
window.__gradProbe = {};
function gradProbe(name, canvas, pts) {
  try {
    var g = canvas.getContext('2d'), out = [], ok = false;
    for (var i = 0; i < pts.length; i++) {
      var d = g.getImageData(Math.floor(pts[i][0]), Math.floor(pts[i][1]), 1, 1).data;
      out.push([d[0], d[1], d[2], d[3]]);
      if (d[3] > 8) ok = true;
    }
    window.__gradProbe[name] = { ok: ok, px: out };
    if (!ok) bridge('onError', '渐变探针失败: ' + name);
  } catch (e) { window.__gradProbe[name] = 'probe-err:' + e.message; }
}
/* 稀疏图层（云）探针：整张扫非透明占比，画上了就算过（§2.1 探针抓的是「画不上去」，
   不是「这颗星球本来云就少」——onError 在 App 侧等于整屏放弃网页版，不许稀疏内容误触）。
   占比为 0（整张全透明）才是真·画布故障 → onError；占比偏低只 console.warn 供观察。 */
function gradProbeRatio(name, canvas, healthyRatio) {
  try {
    var g = canvas.getContext('2d');
    var d = g.getImageData(0, 0, canvas.width, canvas.height).data;
    var hit = 0, total = 0;
    for (var i = 3; i < d.length; i += 44) { total++; if (d[i] > 8) hit++; } /* 每 11 像素抽 1 */
    var ratio = hit / Math.max(1, total);
    var ok = hit > 0;
    window.__gradProbe[name] = { ok: ok, ratio: Math.round(ratio * 10000) / 10000 };
    if (!ok) bridge('onError', '渐变探针失败: ' + name);
    else if (ratio < healthyRatio) console.log('[planet] 云覆盖率偏低(随 seed 波动·非故障):', (ratio * 100).toFixed(2) + '%');
  } catch (e) { window.__gradProbe[name] = 'probe-err:' + e.message; }
}

/* ================= 4. 宇宙背景（深空幕 + 银河 + 星野 + 流星） ================= */
var bgStops = [
  { pos: 0.0, rgb: [0.051, 0.071, 0.125] }, { pos: 0.3, rgb: [0.066, 0.090, 0.153] },
  { pos: 0.55, rgb: [0.078, 0.102, 0.173] }, { pos: 0.8, rgb: [0.059, 0.080, 0.141] },
  { pos: 1.0, rgb: [0.043, 0.059, 0.106] }
];
function buildBgTex() {
  var rng = mulberry32(907101);
  var W = 1024, H = 512, c = cnv(W, H), x = c[1];
  vGradRows(x, W, H, bgStops);
  /* 银河：斜向软带（大量低透明小点 + 少量柔盘），沿一条倾斜椭圆弧撒 */
  var tilt = -0.42, cx0 = W * 0.5, cy0 = H * 0.46, RW = W * 0.62, RH = H * 0.16;
  x.save(); x.translate(cx0, cy0); x.rotate(tilt); x.translate(-cx0, -cy0);
  for (var i = 0; i < 1300; i++) {
    var a = rng() * PI * 2, rr = Math.sqrt(rng());
    var bx = cx0 + Math.cos(a) * RW * rr, by = cy0 + Math.sin(a) * RH * rr * (0.5 + rng() * 0.9);
    x.fillStyle = 'rgba(214,224,244,' + (0.02 + rng() * 0.09).toFixed(3) + ')';
    x.beginPath(); x.arc(bx, by, 0.5 + rng() * 1.2, 0, PI * 2); x.fill();
  }
  for (i = 0; i < 26; i++) {
    var a2 = rng() * PI * 2, rr2 = Math.sqrt(rng());
    glowStack(x, cx0 + Math.cos(a2) * RW * rr2, cy0 + Math.sin(a2) * RH * rr2 * 0.7,
      26 + rng() * 52, 10 + rng() * 20, '208,218,242', function (k) { return lerp(0.05, 0, k); }, 6);
  }
  /* 深空星尘两抹（紫青 / 暮玫，极低透明） */
  glowStack(x, W * 0.24, H * 0.30, 190, 120, '108,116,178', function (k) { return lerp(0.075, 0, k); }, 10);
  glowStack(x, W * 0.76, H * 0.66, 170, 110, '168,116,140', function (k) { return lerp(0.06, 0, k); }, 10);
  /* 主星野（带外也要有星，别只在银河里） */
  for (i = 0; i < 760; i++) {
    var sx = rng() * W, sy = rng() * H, sr = 0.4 + rng() * 1.1, sa = 0.18 + rng() * 0.6;
    if (rng() < 0.16) { /* 软芯星 */
      glowStack(x, sx, sy, sr * 2.6, sr * 2.6, '238,236,222', function (k) { return lerp(sa, 0, k); }, 3);
    } else {
      x.fillStyle = 'rgba(238,236,222,' + sa.toFixed(3) + ')';
      x.beginPath(); x.arc(sx, sy, sr, 0, PI * 2); x.fill();
    }
  }
  /* 几颗大主星（十字芒） */
  for (i = 0; i < 6; i++) {
    var mx = rng() * W, my = rng() * H * 0.9, mr = 2.2 + rng() * 1.8;
    glowStack(x, mx, my, mr * 5, mr * 5, '242,238,220', function (k) { return lerp(0.5, 0, k); }, 5);
    x.strokeStyle = 'rgba(242,238,220,.5)'; x.lineWidth = 1;
    x.beginPath(); x.moveTo(mx - mr * 3.4, my); x.lineTo(mx + mr * 3.4, my); x.stroke();
    x.beginPath(); x.moveTo(mx, my - mr * 3.4); x.lineTo(mx, my + mr * 3.4); x.stroke();
  }
  grainRng(x, W, H, 2200, 0.05, rng, '20,26,44');
  gradProbe('bg', c[0], [[W * 0.5, H * 0.5], [W * 0.2, H * 0.2], [W * 0.85, H * 0.7]]);
  return T(c);
}
var bgTex = buildBgTex();
var bgMesh = new THREE.Mesh(
  new THREE.SphereGeometry(120, 32, 24),
  new THREE.MeshBasicMaterial({ map: bgTex, side: THREE.BackSide, depthWrite: false }));
bgMesh.renderOrder = -10; bgMesh.frustumCulled = false;
scene.add(bgMesh);

/* 闪星点（GPU 单侧伪随机 ⇒ 契约 §2.3：key 一律由着色器算，CPU 不掺和） */
var starUni = { uTime: { value: 0 }, uScale: { value: 300 } };
var starPoints = (function () {
  var rng = mulberry32(512407), N = 420;
  var pos = new Float32Array(N * 3), size = new Float32Array(N), ph = new Float32Array(N);
  for (var i = 0; i < N; i++) {
    var u = rng() * 2 - 1, a = rng() * PI * 2, r = 46 + rng() * 42, s = Math.sqrt(1 - u * u);
    pos[i * 3] = Math.cos(a) * s * r; pos[i * 3 + 1] = u * r; pos[i * 3 + 2] = Math.sin(a) * s * r;
    size[i] = 0.5 + rng() * 1.5; ph[i] = rng() * 6.283;
  }
  var g = new THREE.BufferGeometry();
  g.setAttribute('position', new THREE.BufferAttribute(pos, 3));
  g.setAttribute('aSize', new THREE.BufferAttribute(size, 1));
  g.setAttribute('aPh', new THREE.BufferAttribute(ph, 1));
  var m = new THREE.ShaderMaterial({
    uniforms: starUni, transparent: true, depthWrite: false, blending: THREE.AdditiveBlending,
    vertexShader: [
      'attribute float aSize; attribute float aPh;',
      'uniform float uTime, uScale; varying float vA;',
      'void main(){',
      '  vec4 mv = modelViewMatrix * vec4(position, 1.0);',
      '  float tw = sin(uTime * (0.5 + fract(aPh * 0.37) * 0.8) + aPh * 17.0);',
      '  vA = 0.5 + 0.45 * tw;',
      '  gl_PointSize = aSize * uScale / max(1.0, -mv.z);',
      '  gl_Position = projectionMatrix * mv;',
      '}'
    ].join('\n'),
    fragmentShader: [
      'varying float vA;',
      'void main(){',
      '  vec2 p = gl_PointCoord * 2.0 - 1.0;',
      '  float d = max(0.0, 1.0 - dot(p, p));',
      '  float a = d * d * vA * 0.85;',
      '  gl_FragColor = vec4(vec3(0.94, 0.93, 0.87) * a, a);',
      '}'
    ].join('\n')
  });
  var pts = new THREE.Points(g, m);
  pts.frustumCulled = false; pts.renderOrder = -9;
  scene.add(pts);
  return pts;
})();

/* 流星（池 2 · 拖尾贴图逐列插值纯色 · CPU 排班不影响着色器对齐） */
function buildStreakTex() {
  var W = 128, H = 24, c = cnv(W, H), x = c[1];
  for (var i = 0; i < W; i++) { /* 头亮尾淡：逐列插值（无 CanvasGradient） */
    var k = i / (W - 1), a = Math.pow(k, 2.4) * 0.9;
    x.fillStyle = 'rgba(255,238,210,' + a.toFixed(3) + ')';
    x.fillRect(i, H * 0.5 - 1.6, 1, 3.2);
  }
  glowStack(x, W - 5, H * 0.5, 7, 5, '255,244,220', function (k) { return lerp(0.95, 0, k); }, 8);
  gradProbe('streak', c[0], [[W - 5, H * 0.5], [W * 0.4, H * 0.5]]);
  return T(c);
}
var meteors = [];
(function initMeteors() {
  var tex = buildStreakTex();
  for (var i = 0; i < 2; i++) {
    var m = new THREE.SpriteMaterial({ map: tex, transparent: true, depthWrite: false, blending: THREE.AdditiveBlending, opacity: 0 });
    var sp = new THREE.Sprite(m);
    sp.visible = false; sp.renderOrder = 6;
    scene.add(sp);
    meteors.push({ sp: sp, t: 1, dur: 1, from: new THREE.Vector3(), to: new THREE.Vector3(), next: 3 + i * 5 });
  }
})();
var meteorRng = mulberry32(88231);
function tickMeteors(dt, simT) {
  if (flags.reduceMotion) return;
  for (var i = 0; i < meteors.length; i++) {
    var mt = meteors[i];
    if (mt.t >= 1) {
      if (simT >= mt.next) {
        mt.t = 0; mt.dur = 0.9 + meteorRng() * 0.5;
        var a = meteorRng() * PI * 2, r = 30 + meteorRng() * 26;
        mt.from.set(Math.cos(a) * r, 10 + meteorRng() * 22, Math.sin(a) * r);
        var ang = 0.45 + meteorRng() * 0.5, len = 16 + meteorRng() * 10;
        mt.to.copy(mt.from).add(new THREE.Vector3(Math.cos(ang) * len, -Math.sin(ang) * len * 0.55, (meteorRng() - 0.5) * 8));
        mt.sp.visible = true;
      }
      continue;
    }
    mt.t = Math.min(1, mt.t + dt / mt.dur);
    var k = mt.t, fade = Math.sin(k * PI);
    mt.sp.position.lerpVectors(mt.from, mt.to, k);
    mt.sp.material.opacity = fade * 0.85;
    mt.sp.scale.set(7.5, 1.4, 1);
    mt.sp.material.rotation = Math.atan2(mt.to.y - mt.from.y, mt.to.x - mt.from.x);
    if (mt.t >= 1) { mt.sp.visible = false; mt.next = simT + 6 + meteorRng() * 10; }
  }
}

/* ================= 5. 星球贴图（同 seed 同貌 · 全程序化手绘水彩） =================
   高度场 = 球面 3D 值噪声 fBm（等距柱状采样零接缝）+ 家点陆块隆起（home 落陆做实）；
   上色 = 逐像素高度带 + 每像素哈希抖动 + 陆块水彩晕斑（软盘叠加，无 CanvasGradient）。 */
var PLANET_W = 1024, PLANET_H = 512, GRID_W = 768, GRID_H = 384;
var homeDir = new THREE.Vector3(0.2104, 0.6815, 0.7007); /* init 时被 planetJson.home 归一化覆盖 */

function buildPlanetMaps(seed, seedOff, home) {
  var rng = mulberry32((seed | 0) ^ 0x9E3779B9);
  var o = seedOff || 0;
  var SX = 11.5 + o * 2.2, SY = 15.0 + o * 1.6, SZ = 19.0 + o * 3.4;
  var SEA = 0.525;
  /* ---- 5a. 高度网格（GRID_W×GRID_H）---- */
  var gh = new Float32Array(GRID_W * GRID_H);
  for (var gy = 0; gy < GRID_H; gy++) {
    var v = (gy + 0.5) / GRID_H, lat = (0.5 - v) * PI, cl = Math.cos(lat), sl = Math.sin(lat);
    for (var gx = 0; gx < GRID_W; gx++) {
      var u = (gx + 0.5) / GRID_W * PI * 2;
      var px = Math.cos(u) * cl, py = sl, pz = Math.sin(u) * cl;
      var warp = fbm3(px * 2.1 + o, py * 2.1, pz * 2.1, 3, seed ^ 0x51ab) - 0.5;
      /* 大洲感：陆/海判定由宏观场主导（连贯大洲），细节场只雕海岸线 */
      var mMacro = fbm3(px * 2.6 + o * 2.0 + warp * 0.8, py * 2.6 + warp * 0.8, pz * 2.6 + warp * 0.8, 3, seed ^ 0x77e5);
      var mDetail = fbm3(px * 11.5 + warp * 0.9, py * 15.0 + warp * 0.9, pz * 19.0 + warp * 0.9, 2, seed);
      var e = mMacro + (mDetail - 0.5) * 0.22;
      /* 家点陆块隆起：保证家落陆（确定性、视觉自然——大陆就在家周围长出来） */
      var dotH = clamp(px * home.x + py * home.y + pz * home.z, -1, 1);
      var d = Math.acos(dotH);
      e += 0.20 * Math.exp(-(d * d) / (2 * 0.30 * 0.30));
      gh[gy * GRID_W + gx] = e;
    }
  }
  /* ---- 5b. 逐像素上色（表面色 + 凹凸 + 夜灯火，一次循环出三张） ---- */
  var cS = cnv(PLANET_W, PLANET_H), xS = cS[1];
  var cB = cnv(PLANET_W, PLANET_H), xB = cB[1];
  var cL = cnv(512, 256), xL = cL[1];
  xL.fillStyle = '#000000'; xL.fillRect(0, 0, 512, 256);
  var imgS = xS.createImageData(PLANET_W, PLANET_H);
  var imgB = xB.createImageData(PLANET_W, PLANET_H);
  var dS = imgS.data, dB = imgB.data;
  var deepC = [0.08, 0.17, 0.30], midC = [0.13, 0.30, 0.47], shalC = [0.196, 0.42, 0.51];
  var shoreC = [0.34, 0.56, 0.62], sandC = [0.83, 0.75, 0.55];
  var lowC = [0.50, 0.61, 0.40], mid2C = [0.60, 0.65, 0.41], highC = [0.60, 0.52, 0.38], rockC = [0.52, 0.47, 0.40];
  var dryC = [0.64, 0.56, 0.38];
  var snowC = [0.94, 0.93, 0.89];
  function smp(u, v) { /* 网格双线性（u 横向 wrap） */
    var fx = u * GRID_W - 0.5, fy = v * GRID_H - 0.5;
    var x0 = Math.floor(fx), y0 = Math.floor(fy);
    var tx = fx - x0, ty = fy - y0;
    var x1 = (x0 + 1) % GRID_W; if (x0 < 0) x0 += GRID_W;
    var ya = clamp(y0, 0, GRID_H - 1), yb = clamp(y0 + 1, 0, GRID_H - 1);
    var a = gh[ya * GRID_W + x0], b = gh[ya * GRID_W + x1], c2 = gh[yb * GRID_W + x0], d2 = gh[yb * GRID_W + x1];
    return lerp(lerp(a, b, tx), lerp(c2, d2, tx), ty);
  }
  var latRad;
  for (var py2 = 0; py2 < PLANET_H; py2++) {
    var v2 = (py2 + 0.5) / PLANET_H;
    var latS = (0.5 - v2) * PI;
    latRad = Math.abs(latS); /* 0..π/2 */
    var capK = smooth01((latRad - 1.18) / 0.22); /* 极冠纬度因子 */
    var clR = Math.cos(latS), slR = Math.sin(latS);
    for (var px2 = 0; px2 < PLANET_W; px2++) {
      var u2 = (px2 + 0.5) / PLANET_W;
      var uang = u2 * PI * 2;
      var sxp = Math.cos(uang) * clR, syp = slR, szp = Math.sin(uang) * clR;
      var h = smp(u2, v2);
      var jit = hash3i(px2, py2, 7, 12345) - 0.5; /* 每像素水彩抖动（非相干，作纸纹） */
      var r, g, b, bump;
      if (h < SEA) { /* ---- 海洋 ---- */
        var dt2 = clamp((SEA - h) / 0.30, 0, 1);
        var oc = dt2 < 0.12 ? stopColorAt([{ pos: 0, rgb: shoreC }, { pos: 1, rgb: midC }], dt2 / 0.12)
                            : stopColorAt([{ pos: 0, rgb: midC }, { pos: 1, rgb: deepC }], (dt2 - 0.12) / 0.88);
        r = oc[0]; g = oc[1]; b = oc[2];
        var floe = fbm3((u2 * 8) % 8, latRad * 14 + o, 3.7, 2, seed ^ 0xF10);
        var iceK = capK * smooth01((floe - 0.42) / 0.2) * 0.9;
        r = lerp(r, 0.90, iceK); g = lerp(g, 0.93, iceK); b = lerp(b, 0.95, iceK);
        bump = 0.5;
      } else { /* ---- 陆地 ---- */
        var t = clamp((h - SEA) / (1 - SEA), 0, 1);
        var lc;
        if (t < 0.045) { var kE = t / 0.045; lc = stopColorAt([{ pos: 0, rgb: sandC }, { pos: 1, rgb: lowC }], kE); }
        else if (t < 0.5) { lc = stopColorAt([{ pos: 0, rgb: lowC }, { pos: 1, rgb: mid2C }], (t - 0.045) / 0.455); }
        else if (t < 0.72) { lc = stopColorAt([{ pos: 0, rgb: mid2C }, { pos: 1, rgb: highC }], (t - 0.5) / 0.22); }
        else { lc = stopColorAt([{ pos: 0, rgb: highC }, { pos: 1, rgb: rockC }], clamp((t - 0.72) / 0.2, 0, 1)); }
        r = lc[0]; g = lc[1]; b = lc[2];
        var snowT = smooth01((t - 0.78) / 0.14) + capK * smooth01((0.55 - t) / 0.3) * 0.85;
        snowT = clamp(snowT, 0, 1);
        if (snowT > 0) { r = lerp(r, snowC[0], snowT); g = lerp(g, snowC[1], snowT); b = lerp(b, snowC[2], snowT); }
        /* 干湿气候斑驳（大尺度·水彩地理感） */
        var dry = vnoise3(sxp * 5.5 + o * 3.0, syp * 5.5, szp * 5.5, (seed | 0) ^ 0xD2A7);
        var dryK = smooth01((dry - 0.52) / 0.18) * (1 - snowT) * 0.55;
        if (dryK > 0) { r = lerp(r, dryC[0], dryK); g = lerp(g, dryC[1], dryK); b = lerp(b, dryC[2], dryK); }
        if (h - SEA < 0.006) { r *= 0.86; g *= 0.86; b *= 0.88; } /* 海岸墨线（水彩勾边·极细） */
        var tiny = vnoise3(sxp * 26 + 7.7, syp * 26, szp * 26 + 3.1, (seed | 0) ^ 0xAAAA);
        var vv2 = 0.93 + tiny * 0.16; /* 陆内水彩细节（中频斑驳·球面坐标零接缝） */
        r *= vv2; g *= vv2; b *= vv2;
        bump = 0.45 + t * 0.55;
      }
      /* 水彩整体抖动 + 纸纹 */
      var vv = 1 + jit * 0.10;
      r *= vv; g *= vv; b *= vv * 0.995;
      var oI = (py2 * PLANET_W + px2) * 4;
      dS[oI] = clamp(r, 0, 1) * 255; dS[oI + 1] = clamp(g, 0, 1) * 255; dS[oI + 2] = clamp(b, 0, 1) * 255; dS[oI + 3] = 255;
      var bb = clamp(bump + jit * 0.02, 0, 1) * 255;
      dB[oI] = bb; dB[oI + 1] = bb; dB[oI + 2] = bb; dB[oI + 3] = 255;
      /* 夜灯火候选：低地近海 + 簇状噪声（画进 cL，soft 盘点） */
      if (h > SEA + 0.004 && h - SEA < 0.16 && latRad < 1.15) {
        var cl2 = fbm3(u2 * 14 + o, v2 * 14, 5.1, 2, seed ^ 0x1E45);
        if (cl2 > 0.62 && hash3i(px2, py2, 11, 777) > 0.996) {
          glowStack(xL, px2 / 2, py2 / 2, 1.7, 1.7, '255,196,120', function (k) { return lerp(0.8, 0, k); }, 4);
        }
      }
    }
  }
  xS.putImageData(imgS, 0, 0);
  xB.putImageData(imgB, 0, 0);
  /* 陆块水彩晕斑（软盘叠加·落在陆上的低透明罩染） */
  for (var bi = 0; bi < 420; bi++) {
    var bu = rng(), bv = rng();
    var bh = smp(bu, bv);
    if (bh <= SEA) continue;
    var isSnow = bh > SEA + 0.72 || Math.abs((0.5 - bv) * PI) > 1.18;
    var col = isSnow ? '236,236,228' : (rng() < 0.5 ? '120,142,86' : '150,152,96');
    glowStack(xS, bu * PLANET_W, bv * PLANET_H, 14 + rng() * 46, 10 + rng() * 30, col, function (k) { return lerp(0.10, 0, k); }, 7);
  }
  grainRng(xS, PLANET_W, PLANET_H, 5200, 0.05, rng, '30,36,52');
  grainRng(xS, PLANET_W, PLANET_H, 2600, 0.04, rng, '255,246,228');
  gradProbe('surface', cS[0], [[PLANET_W * 0.5, PLANET_H * 0.5], [PLANET_W * 0.21, PLANET_H * 0.31], [PLANET_W * 0.8, PLANET_H * 0.7]]);
  gradProbe('lights', cL[0], [[256, 128], [60, 60], [430, 180]]);
  return { map: T(cS), bump: T(cB), lights: T(cL) };
}

function buildCloudTex(seed, seedOff) {
  var rng = mulberry32((seed | 0) ^ 0xC0FFEE);
  var o = (seedOff || 0) + 3.7;
  var W = 1024, H = 512, G2W = 512, G2H = 256;
  var gc = new Float32Array(G2W * G2H);
  for (var gy = 0; gy < G2H; gy++) {
    var v = (gy + 0.5) / G2H, lat = (0.5 - v) * PI, cl = Math.cos(lat), sl = Math.sin(lat);
    for (var gx = 0; gx < G2W; gx++) {
      var u = (gx + 0.5) / G2W * PI * 2;
      var px = Math.cos(u) * cl, py = sl, pz = Math.sin(u) * cl;
      var wv = fbm3(px * 5.5 + o, py * 5.5, pz * 5.5, 3, (seed | 0) ^ 0xC10D) - 0.5;
      gc[gy * G2W + gx] = fbm3(px * 10.0 + wv * 1.7 + o, py * 10.0 + wv * 1.7, pz * 10.0, 4, (seed | 0) ^ 0xBEEF);
    }
  }
  var c = cnv(W, H), x = c[1];
  var img = x.createImageData(W, H), d = img.data;
  function smp(u, v) {
    var fx = clamp(u * G2W - 0.5, 0, G2W - 1.001), fy = clamp(v * G2H - 0.5, 0, G2H - 1.001);
    var x0 = Math.floor(fx), y0 = Math.floor(fy), tx = fx - x0, ty = fy - y0;
    var x1 = Math.min(G2W - 1, x0 + 1), y1 = Math.min(G2H - 1, y0 + 1);
    var a = gc[y0 * G2W + x0], b = gc[y0 * G2W + x1], c2 = gc[y1 * G2W + x0], d2 = gc[y1 * G2W + x1];
    return lerp(lerp(a, b, tx), lerp(c2, d2, tx), ty);
  }
  for (var py2 = 0; py2 < H; py2++) {
    var v2 = (py2 + 0.5) / H;
    for (var px2 = 0; px2 < W; px2++) {
      var u2 = (px2 + 0.5) / W;
      var n = smp(u2, v2);
      var a = smooth01((n - 0.585) / 0.16) * 0.72;
      a *= smooth01((Math.abs(v2 - 0.5) * 2) < 0.94 ? 1 : 0.4); /* 极区少云 */
      var oI = (py2 * W + px2) * 4;
      d[oI] = 250; d[oI + 1] = 250; d[oI + 2] = 245;
      d[oI + 3] = clamp(a, 0, 1) * 255;
    }
  }
  x.putImageData(img, 0, 0);
  for (var i = 0; i < 60; i++) { /* 云絮软盘 */
    var cu = rng(), cv = 0.2 + rng() * 0.6;
    glowStack(x, cu * W, cv * H, 6 + rng() * 16, 4 + rng() * 9, '252,252,248', function (k) { return lerp(0.07, 0, k); }, 6);
  }
  gradProbeRatio('clouds', c[0], 0.005);
  return T(c);
}

/* 大气辉光贴图（光晕 sprite 底·同心盘） */
function buildHaloTex() {
  var S = 256, c = cnv(S, S), x = c[1];
  glowStack(x, S / 2, S / 2, S * 0.48, S * 0.48, '255,214,150', function (k) { return lerp(0.16, 0, Math.pow(k, 1.6)); }, 14);
  gradProbe('halo', c[0], [[S * 0.5, S * 0.30], [S * 0.5, S * 0.5]]);
  return T(c);
}

/* ================= 6. 星球本体 ================= */
var planetRoot = new THREE.Group(); scene.add(planetRoot);
var surfMesh = null, cloudMesh = null, rimMesh = null, haloSp = null;
var cloudMat = null;
function buildPlanet(maps) {
  if (surfMesh) { planetRoot.remove(surfMesh); surfMesh.geometry.dispose(); surfMesh.material.map.dispose(); surfMesh.material.bumpMap.dispose(); surfMesh.material.emissiveMap.dispose(); surfMesh.material.dispose(); }
  if (cloudMesh) { planetRoot.remove(cloudMesh); cloudMesh.geometry.dispose(); cloudMesh.material.map.dispose(); cloudMesh.material.dispose(); }
  if (rimMesh) { planetRoot.remove(rimMesh); rimMesh.geometry.dispose(); rimMesh.material.dispose(); }
  if (haloSp) { planetRoot.remove(haloSp); haloSp.material.map.dispose(); haloSp.material.dispose(); }
  var mat = new THREE.MeshStandardMaterial({
    map: maps.map, bumpMap: maps.bump, bumpScale: 0.045,
    emissiveMap: maps.lights, emissive: new THREE.Color(1.0, 0.78, 0.45), emissiveIntensity: 1.45,
    roughness: 0.94, metalness: 0
  });
  surfMesh = new THREE.Mesh(new THREE.SphereGeometry(1, 96, 64), mat);
  planetRoot.add(surfMesh);
  cloudMat = new THREE.MeshLambertMaterial({ map: buildCloudTexShared, transparent: true, depthWrite: false });
  cloudMesh = new THREE.Mesh(new THREE.SphereGeometry(1.024, 64, 48), cloudMat);
  planetRoot.add(cloudMesh);
  /* 大气边缘辉（FrontSide fresnel · 薄壳 R=1.05，色随昼夜朝向） */
  var rimMat = new THREE.ShaderMaterial({
    uniforms: { uSunDir: { value: sun.position.clone().normalize() } },
    transparent: true, depthWrite: false, blending: THREE.AdditiveBlending,
    vertexShader: [
      'varying vec3 vN; varying vec3 vV; varying vec3 vWN;',
      'void main(){',
      '  vN = normalize(normalMatrix * normal);',
      '  vWN = normalize(mat3(modelMatrix) * normal);',
      '  vec4 mv = modelViewMatrix * vec4(position, 1.0);',
      '  vV = normalize(-mv.xyz);',
      '  gl_Position = projectionMatrix * mv;',
      '}'
    ].join('\n'),
    fragmentShader: [
      'uniform vec3 uSunDir; varying vec3 vN; varying vec3 vV; varying vec3 vWN;',
      'void main(){',
      '  float fres = pow(1.0 - abs(dot(normalize(vN), normalize(vV))), 2.2);',
      '  float sunK = clamp(dot(normalize(vWN), uSunDir) * 0.5 + 0.5, 0.0, 1.0);',
      '  vec3 c = mix(vec3(0.45, 0.58, 0.86), vec3(1.0, 0.78, 0.5), sunK);',
      '  float a = fres * (0.55 + 0.5 * sunK);',
      '  gl_FragColor = vec4(c * a, a);',
      '}'
    ].join('\n')
  });
  rimMesh = new THREE.Mesh(new THREE.SphereGeometry(1.055, 64, 48), rimMat);
  rimMesh.renderOrder = 3;
  planetRoot.add(rimMesh);
  var haloMat = new THREE.SpriteMaterial({ map: buildHaloTex(), transparent: true, depthWrite: false, blending: THREE.AdditiveBlending, opacity: 0.75 });
  haloSp = new THREE.Sprite(haloMat);
  haloSp.scale.set(3.1, 3.1, 1);
  haloSp.renderOrder = 2;
  planetRoot.add(haloSp);
}
var buildCloudTexShared = null; /* init 时先赋值再 buildPlanet（占位声明顺序） */

/* ================= 7. 家标记 + 雪佛龙 ================= */
var homeG = new THREE.Group(); /* 挂 planetRoot：随球（本球不转，标记即恒定锚定） */
var homeHit = null, homeGlowSp = null, homeWinMat = null;
var homeTagEl = null, chevronEl = null;
var homeCityName = '';
function buildHomeMarker() {
  while (homeG.children.length) {
    var ch = homeG.children.pop();
    if (ch.isSprite) continue; /* Sprite 共享内部 geometry·glow 贴图共享，都不能 dispose */
    if (ch.geometry) ch.geometry.dispose();
    if (ch.material) ch.material.dispose();
  }
  planetRoot.remove(homeG);
  var up = homeDir.clone();
  homeG.position.copy(up);
  homeG.quaternion.setFromUnitVectors(new THREE.Vector3(0, 1, 0), up);
  var wallMat = new THREE.MeshStandardMaterial({ color: 0xF2E7CE, roughness: 1 });
  var roofMat = new THREE.MeshStandardMaterial({ color: 0xC96F42, roughness: 1 });
  homeWinMat = new THREE.MeshBasicMaterial({ color: 0xFFD9A0 });
  var body = new THREE.Mesh(new THREE.BoxGeometry(0.030, 0.024, 0.026), wallMat);
  body.position.y = 0.012;
  var roof = new THREE.Mesh(new THREE.ConeGeometry(0.024, 0.018, 4), roofMat);
  roof.position.y = 0.033; roof.rotation.y = PI / 4;
  var win = new THREE.Mesh(new THREE.BoxGeometry(0.008, 0.008, 0.002), homeWinMat);
  win.position.set(0, 0.013, 0.0145);
  homeGlowSp = new THREE.Sprite(new THREE.SpriteMaterial({
    map: glowTexShared, transparent: true, depthWrite: false, blending: THREE.AdditiveBlending, opacity: 0.8 }));
  homeGlowSp.scale.set(0.12, 0.12, 1);
  homeGlowSp.position.y = 0.02;
  homeHit = new THREE.Mesh(new THREE.SphereGeometry(0.075, 8, 6), new THREE.MeshBasicMaterial({ visible: false }));
  homeHit.position.y = 0.02;
  homeHit.userData.tap = 'home';
  homeG.add(body); homeG.add(roof); homeG.add(win); homeG.add(homeGlowSp); homeG.add(homeHit);
  planetRoot.add(homeG);
  /* 名签 DOM（底尖对齐投影点） */
  if (homeTagEl && homeTagEl.parentNode) homeTagEl.parentNode.removeChild(homeTagEl);
  homeTagEl = document.createElement('div');
  homeTagEl.className = 'place-tag';
  var dot = document.createElement('span'); dot.className = 'home-dot';
  homeTagEl.appendChild(dot);
  homeTagEl.appendChild(document.createTextNode(homeCityName || '家'));
  homeTagEl.addEventListener('click', function (ev) {
    ev.stopPropagation();
    if (flags.interactive && homeVisible) bridge('onTapHome');
  });
  document.body.appendChild(homeTagEl);
}
var glowTexShared = null;
function buildGlowTex() {
  var S = 128, c = cnv(S, S), x = c[1];
  glowStack(x, S / 2, S / 2, S * 0.48, S * 0.48, '255,206,132', function (k) { return lerp(0.9, 0, Math.pow(k, 1.35)); }, 12);
  gradProbe('homeGlow', c[0], [[S * 0.5, S * 0.5], [S * 0.5, S * 0.22]]);
  return T(c);
}
/* 家可见性：dot(homeDir, camDir) > 1/dist（球面地平线判定·略提前一点点藏轮廓闪跳） */
var homeVisible = true;
var _homeWorld = new THREE.Vector3(), _camDir = new THREE.Vector3(), _viewPos = new THREE.Vector3();
var _tanDir = new THREE.Vector3(), _projV = new THREE.Vector3();
function layoutHomeLayer() {
  if (!homeTagEl) return;
  var w = window.innerWidth, h = window.innerHeight;
  var dist = camera.position.length();
  _camDir.copy(camera.position).normalize();
  var vis = _homeWorld.copy(homeDir).dot(_camDir) > (1 / dist) + 0.015;
  if (vis !== homeVisible) {
    homeVisible = vis;
    homeG.visible = vis;
    if (chevronEl) chevronEl.style.display = vis ? 'none' : 'block';
    dirty = true;
  }
  if (vis) {
    _projV.copy(homeDir).multiplyScalar(1.045).project(camera);
    if (_projV.z < 1) {
      var sx = (_projV.x * 0.5 + 0.5) * w, sy = (-_projV.y * 0.5 + 0.5) * h;
      homeTagEl.style.display = '';
      homeTagEl.style.left = sx.toFixed(1) + 'px';
      homeTagEl.style.top = (sy - 6).toFixed(1) + 'px';
      return;
    }
  }
  homeTagEl.style.display = 'none';
  /* 雪佛龙：指向「家 - 视向」切向分量 ⇒ 屏缘朝家最近的 limb 方向 */
  if (!vis && chevronEl) {
    _viewPos.copy(homeDir).transformDirection(camera.matrixWorldInverse);
    _tanDir.copy(homeDir).addScaledVector(_camDir, -_homeWorld.copy(homeDir).dot(_camDir));
    if (_tanDir.lengthSq() < 1e-6) _tanDir.set(1, 0, 0);
    _tanDir.transformDirection(camera.matrixWorldInverse);
    var dx = _tanDir.x, dy = -_tanDir.y; /* 屏幕 y 向下 */
    /* 放到真·屏缘：沿方向推到距四边 64px 的矩形边界上 */
    var mX = w / 2 - 64, mY = h / 2 - 64, tScale = Infinity;
    if (Math.abs(dx) > 1e-4) tScale = Math.min(tScale, mX / Math.abs(dx));
    if (Math.abs(dy) > 1e-4) tScale = Math.min(tScale, mY / Math.abs(dy));
    if (!isFinite(tScale)) tScale = 0;
    var cxp = w / 2 + dx * tScale, cyp = h / 2 + dy * tScale;
    cxp = clamp(cxp, 46, w - 46); cyp = clamp(cyp, 46 + (window.__safeTop || 0), h - 46);
    chevronEl.style.left = cxp.toFixed(1) + 'px';
    chevronEl.style.top = cyp.toFixed(1) + 'px';
    chevronEl.style.transform = 'rotate(' + Math.atan2(dy, dx).toFixed(3) + 'rad)';
  }
}
function spinHomeToFace() { /* 页面自己把球转回家正面（相机最短路） */
  var yawT = Math.atan2(homeDir.x, homeDir.z);
  var pitchT = Math.asin(clamp(homeDir.y, -1, 1));
  var dYaw = yawT - pose.yaw;
  dYaw = ((dYaw + PI) % (2 * PI) + 2 * PI) % (2 * PI) - PI; /* wrap 到 ±π */
  startTween(['yaw', 'pitch'], { yaw: pose.yaw + dYaw, pitch: clamp(pitchT, -1.25, 1.25) }, 900, easeInOutCubic);
}

/* ================= 8. 相机与手势（契约 §5.2 常数照抄） ================= */
var DEF_POSE = { yaw: 0.6, pitch: -0.25, dist: 3.1 };
var pose = { yaw: 0.6, pitch: -0.25, dist: 3.1 };
var flags = { reduceMotion: false, staticMode: false, interactive: true };
var camTween = null, gesturing = false, dirty = true, inited = false, firstFrameSent = true;
var inertia = { yaw: 0, pitch: 0, active: false };
var lastAct = 0, simT = 0;

function clampPitch(v) { return clamp(v, -1.25, 1.25); }
function clampDist(v) { return clamp(v, 1.9, 6.4); }
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
  var decay = Math.pow(0.94, dt * 60); /* 契约：松手速度 ×0.94^帧 */
  inertia.yaw *= decay; inertia.pitch *= decay;
  if (Math.abs(inertia.yaw) < 1e-4 && Math.abs(inertia.pitch) < 1e-4) { inertia.active = false; return; }
  pose.yaw += inertia.yaw * dt;
  pose.pitch = clampPitch(pose.pitch + inertia.pitch * dt);
  dirty = true;
}
/* 球面粘手指：每像素弧度 = 2·(dist−1)·tan(FOV/2) / 视口高px（契约 §5.2 公式逐字） */
function radPerPixel() {
  return 2 * (pose.dist - 1) * Math.tan(FOV_RAD / 2) / window.innerHeight;
}

var ptrs = new Map();
var rotPrev = null, pinchPrev = null, tapInfo = null;
var vel = { y: 0, p: 0, t: 0 };
var odRatio = 1, odFired = false;
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
    rotPrev = localXY(e);
    tapInfo = { x: e.clientX, y: e.clientY, t: performance.now() };
    vel.y = 0; vel.p = 0; vel.t = performance.now();
  } else if (ptrs.size === 2) {
    rotPrev = null; tapInfo = null;
    odRatio = 1; odFired = false;
    pinchPrev = pinchState();
  }
});
cvs.addEventListener('pointermove', function (e) {
  if (!flags.interactive || !ptrs.has(e.pointerId)) return;
  ptrs.set(e.pointerId, localXY(e));
  lastAct = performance.now();
  if (ptrs.size === 1 && rotPrev) {
    var dA = radPerPixel();
    var dyaw = -(e.clientX - rotPrev.x) * dA;
    var dpit = (e.clientY - rotPrev.y) * dA; /* 指下的球面跟着指头走 */
    var now = performance.now(), dtm = Math.max(8, now - vel.t) / 1000;
    vel.y = vel.y * 0.7 + (dyaw / dtm) * 0.3;
    vel.p = vel.p * 0.7 + (dpit / dtm) * 0.3;
    vel.t = now;
    pose.yaw += dyaw;
    pose.pitch = clampPitch(pose.pitch + dpit);
    rotPrev = localXY(e);
    dirty = true;
  } else if (ptrs.size === 2) {
    var st = pinchState();
    if (pinchPrev) {
      /* 真机批手感修正：GL 基准 ratio = prevSpan/span（PlanetGLView:139）——张开→ratio<1→拉近。
         原写反致缩放方向倒置；翻转后 overdive(到底继续张开)分支语义自动归位（对齐原生 PlanetCamera:157 pinch<1）。 */
      var ratio = pinchPrev.d / Math.max(1, st.d);
      if (pose.dist <= 1.9 + 0.01 && ratio < 1) {
        odRatio *= ratio; /* dist 已到底继续内捏：累积比例 */
        if (odRatio <= 0.90 && !odFired) { odFired = true; bridge('onDiveGesture'); }
      }
      pose.dist = clampDist(pose.dist * ratio);
      dirty = true;
    }
    pinchPrev = st;
  }
});
function pinchState() {
  var a = [], it = ptrs.values();
  for (var p = it.next(); !p.done; p = it.next()) a.push(p.value);
  if (a.length < 2) return null;
  return { d: Math.max(1, Math.hypot(a[1].x - a[0].x, a[1].y - a[0].y)) };
}
function endPointer(e) {
  if (!ptrs.has(e.pointerId)) return;
  ptrs.delete(e.pointerId);
  if (ptrs.size === 1) {
    var rest = ptrs.values().next().value;
    rotPrev = { x: rest.x, y: rest.y };
    pinchPrev = null;
  } else if (ptrs.size === 0) {
    gesturing = false;
    pinchPrev = null; rotPrev = null;
    odRatio = 1; odFired = false; /* 松手复位 */
    if (tapInfo && performance.now() - tapInfo.t < 400 &&
        Math.hypot(e.clientX - tapInfo.x, e.clientY - tapInfo.y) < 8) {
      handleTap(tapInfo.x, tapInfo.y);
    }
    tapInfo = null;
    if ((Math.abs(vel.y) > 0.05 || Math.abs(vel.p) > 0.05) && performance.now() - vel.t < 120) {
      inertia.yaw = vel.y; inertia.pitch = vel.p; inertia.active = true;
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

var raycaster = new THREE.Raycaster();
var _ndc = new THREE.Vector2();
function handleTap(px, py) {
  if (!inited) return;
  _ndc.set((px / window.innerWidth) * 2 - 1, -(py / window.innerHeight) * 2 + 1);
  raycaster.setFromCamera(_ndc, camera);
  if (homeHit && homeVisible) {
    var hits = raycaster.intersectObject(homeHit, false);
    if (hits.length) { bridge('onTapHome'); return; }
  }
  /* 点球体其他位置 = 场景交互空白，契约未定义回调 → 不发（App 只认 onTapHome） */
}

function sendPose() {
  var p = { yaw: r4(pose.yaw), pitch: r4(pose.pitch), dist: r4(pose.dist) };
  if (MOCK_MODE) window.__lastPose = p;
  bridge('onPose', JSON.stringify(p));
}
setInterval(function () { if (inited && !document.hidden) sendPose(); }, 500); /* 心跳 */

/* ================= 9. window.worldWeb（桥·四入） ================= */
window.worldWeb = {
  init: function (planetJson) {
    var j = (typeof planetJson === 'string') ? JSON.parse(planetJson) : (planetJson || {});
    var seed = (typeof j.seed === 'number') ? j.seed : 123456789;
    var seedOff = (typeof j.seedOff === 'number') ? j.seedOff : 0.37;
    homeCityName = j.homeCityName || '家';
    if (j.home && typeof j.home.x === 'number') {
      homeDir.set(j.home.x, j.home.y, j.home.z).normalize();
    }
    var maps = buildPlanetMaps(seed, seedOff, homeDir);
    buildCloudTexShared = buildCloudTex(seed, seedOff);
    glowTexShared = glowTexShared || buildGlowTex();
    buildPlanet(maps);
    buildHomeMarker();
    pose.yaw = DEF_POSE.yaw; pose.pitch = DEF_POSE.pitch;
    if (flags.reduceMotion) { pose.dist = DEF_POSE.dist; camTween = null; }
    else { pose.dist = 4.7; startTween(['dist'], { dist: DEF_POSE.dist }, 1200, easeOutCubic); } /* 入场缓推 */
    inited = true; firstFrameSent = false;
    dirty = true;
  },
  setFlags: function (flagsJson) {
    var f = flagsJson || {};
    flags.reduceMotion = !!f.reduceMotion;
    flags.staticMode = !!f.staticMode;
    flags.interactive = (f.interactive !== false);
    if (flags.reduceMotion && camTween) { /* 直切：镜头瞬时到位 */
      camTween.keys.forEach(function (key) { pose[key] = camTween.to[key]; });
      camTween = null;
    }
    dirty = true;
  },
  restorePose: function (poseJson) {
    camTween = null; inertia.active = false;
    if (!poseJson) {
      pose.yaw = DEF_POSE.yaw; pose.pitch = DEF_POSE.pitch;
      if (flags.reduceMotion) pose.dist = DEF_POSE.dist;
      else { pose.dist = Math.max(pose.dist, 4.2); startTween(['dist'], { dist: DEF_POSE.dist }, 1000, easeOutCubic); }
    } else {
      if (typeof poseJson.yaw === 'number') pose.yaw = poseJson.yaw;
      if (typeof poseJson.pitch === 'number') pose.pitch = clampPitch(poseJson.pitch);
      if (typeof poseJson.dist === 'number') pose.dist = clampDist(poseJson.dist);
    }
    dirty = true;
  },
  playPose: function (poseJson, ms) {
    var p = (typeof poseJson === 'string') ? JSON.parse(poseJson) : (poseJson || {});
    var keys = [], to = {};
    ['yaw', 'pitch', 'dist'].forEach(function (k) {
      if (typeof p[k] === 'number') { keys.push(k); to[k] = p[k]; } /* dist 1.45 越下界属契约转场值：补间不钳 */
    });
    if (!keys.length) return;
    inertia.active = false;
    startTween(keys, to, ms || 500, easeInOutCubic);
  }
};

/* ================= 10. MOCK（仅 ?mock=1 自测用·App 不引用） ================= */
if (MOCK_MODE) {
  var _firePtr = function (type, id, x, y) {
    cvs.dispatchEvent(new PointerEvent(type, {
      pointerId: id, pointerType: 'touch', isPrimary: id === 1,
      clientX: x, clientY: y, bubbles: true, cancelable: true
    }));
  };
  window.planetMockGestures = {
    drag: function (x0, y0, x1, y1, steps) {
      steps = steps || 12;
      _firePtr('pointerdown', 1, x0, y0);
      var i = 0;
      var iv = setInterval(function () {
        i++;
        _firePtr('pointermove', 1, x0 + (x1 - x0) * (i / steps), y0 + (y1 - y0) * (i / steps));
        if (i >= steps) { clearInterval(iv); _firePtr('pointerup', 1, x1, y1); }
      }, 16);
    },
    pinchIn: function (cx, cy, from, to, steps) {
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
    }
  };
  var mockUI = document.getElementById('mockUI');
  mockUI.style.display = 'flex';
  var row = document.getElementById('mockRow');
  [['转到背面', function () { pose.yaw += PI * 0.75; dirty = true; }],
   ['雪佛龙回家', function () { spinHomeToFace(); bridge('onSpinHome'); }],
   ['俯冲演示', function () { window.worldWeb.playPose({ dist: 1.45 }, 520); setTimeout(function () { window.worldWeb.restorePose(null); }, 900); }],
   ['粘手指', function () { window.planetMockGestures.drag(180, 200, 330, 260, 12); }],
   ['捏到底', function () { pose.dist = 1.9; window.planetMockGestures.pinchIn(280, 300, 60, 150, 18); }] /* 手感修正后：到底继续「张开」才触发俯冲 */
  ].forEach(function (d) {
    var b = document.createElement('button');
    b.className = 'mbtn'; b.textContent = d[0];
    b.addEventListener('click', d[1]);
    row.appendChild(b);
  });
  /* R1 返修自测：?seed= 指定种子；「换种子」按钮在 5 个差异大的种子间轮换（含两个曾误报的） */
  var SEEDS = [123456789, 8113981484089857648, 1, 20260829, 99999];
  var mq2 = /[?&]seed=(\d+)/.exec(location.search);
  var seedBtn = null, seedIdx = 0;
  function initPlanetMock(seed) {
    window.worldWeb.init({ seed: seed, seedOff: 0.37, home: { x: 0.21, y: 0.68, z: 0.70 }, homeCityName: '云野镇' });
    if (seedBtn) seedBtn.textContent = '换种子(' + seed + ')';
  }
  var bSeed = document.createElement('button');
  bSeed.className = 'mbtn';
  bSeed.addEventListener('click', function () {
    seedIdx = (seedIdx + 1) % SEEDS.length;
    window.__tlog.length = 0;
    initPlanetMock(SEEDS[seedIdx]);
  });
  seedBtn = bSeed;
  row.appendChild(bSeed);
  initPlanetMock(mq2 ? Number(mq2[1]) : SEEDS[0]);
  /* ?pose=yaw,pitch[,dist] 指定相机（无控制台复现场景用） */
  var mp = /[?&]pose=([-\d.,]+)/.exec(location.search);
  if (mp) {
    var pv = mp[1].split(',').map(Number);
    window.worldWeb.restorePose({ yaw: pv[0], pitch: pv[1], dist: pv.length > 2 ? pv[2] : 3.1 });
  }
}

/* ================= 11. 主循环（30/60 变频 · staticMode 按需渲） ================= */
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
  var cp = Math.cos(pose.pitch);
  camera.position.set(Math.sin(pose.yaw) * cp * pose.dist, Math.sin(pose.pitch) * pose.dist, Math.cos(pose.yaw) * cp * pose.dist);
  camera.lookAt(0, 0, 0);
  renderer.render(scene, camera);
  if (MOCK_MODE) {
    try {
      var gl = renderer.getContext();
      gl.readPixels(Math.floor(gl.drawingBufferWidth / 2), Math.floor(gl.drawingBufferHeight / 2), 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, _px);
      window.__px = [_px[0], _px[1], _px[2], _px[3]];
      if (window.__snapPending) { /* 自测截帧：与 render 同任务，drawing buffer 仍有效 */
        window.__snapData = renderer.domElement.toDataURL('image/jpeg', 0.72);
        window.__snapPending = false;
      }
    } catch (_) { }
  }
  fpsN++;
  if (!firstFrameSent) {
    firstFrameSent = true;
    var veil = document.getElementById('veil');
    if (veil) veil.style.opacity = 0;
    bridge('onFirstFrame');
  }
  layoutHomeLayer();
}
function tick(now) {
  requestAnimationFrame(tick);
  var dt = Math.min(0.1, (now - lastTs) / 1000);
  lastTs = now;
  if (pageHidden || ctxLost) return;
  var frozen = flags.reduceMotion || flags.staticMode;
  if (!frozen) {
    simT += dt;
    if (cloudMesh) cloudMesh.rotation.y += dt * 0.012; /* 云层缓旋 */
    tickMeteors(dt, simT);
  }
  starUni.uTime.value = simT;
  tickCameraTween(dt);
  tickInertia(dt);
  /* 静置 1.6s 后极慢自转（reduceMotion / staticMode 免） */
  if (!flags.reduceMotion && !flags.staticMode && inited && !gesturing && !camTween &&
      performance.now() - lastAct > 1600) {
    pose.yaw += 0.00045;
    dirty = true;
  }
  var busy = gesturing || !!camTween || inertia.active;
  var doRender = flags.staticMode ? dirty : (busy || (frameCount % 2 === 0));
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
      _dg.textContent = JSON.stringify({
        fps: Math.round(fpsN / fpsT), err: (window.__townErrors || []).length,
        calls: ri.render.calls, tris: ri.render.triangles, px: window.__px || null,
        hidden: pageHidden, dpr: renderer.getPixelRatio(),
        pose: [r4(pose.yaw), r4(pose.pitch), r4(pose.dist)],
        homeVis: homeVisible, chev: chevronEl && chevronEl.style.display === 'block',
        inited: inited, frozen: frozen,
        ev: (window.__tlog || []).filter(function (t) { return t.n !== 'onPose'; }).slice(-5)
          .map(function (t) { return t.n + ':' + (typeof t.a === 'string' ? t.a.slice(0, 40) : ''); }),
        res: performance.getEntriesByType('resource').map(function (r) { return r.name.split('/').pop(); }),
        probe: window.__gradProbe
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
  starUni.uScale.value = window.innerHeight * 0.5 * renderer.getPixelRatio();
  dirty = true;
});
starUni.uScale.value = window.innerHeight * 0.5 * renderer.getPixelRatio();
chevronEl = document.getElementById('chevron');
chevronEl.addEventListener('click', function (ev) {
  ev.stopPropagation();
  if (!flags.interactive || !inited) return;
  spinHomeToFace();
  bridge('onSpinHome'); /* 仅供 App 触觉反馈（契约 §3.2） */
});

/* 纸感颗粒（DOM 层 · 32² 噪声 dataURL 平铺） */
(function grainLayer() {
  var S = 48, c = cnv(S, S), x = c[1];
  var img = x.createImageData(S, S), d = img.data;
  for (var i = 0; i < S * S; i++) {
    var a = Math.random() * 30;
    d[i * 4] = 255; d[i * 4 + 1] = 255; d[i * 4 + 2] = 255; d[i * 4 + 3] = a;
  }
  x.putImageData(img, 0, 0);
  document.getElementById('grain').style.background = 'url(' + c[0].toDataURL() + ')';
})();

/* 就绪：App 在 init 之前就能收到 */
bridge('onReady');

})();
