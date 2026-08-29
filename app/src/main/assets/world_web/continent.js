/* ============================================================================
   continent.js — 「AI Pocket Chat」世界系统 · 大陆屏网页渲染引擎（二期）
   契约：网页世界二期前端施工契约 v1.0（大陆 + 星球 + 桥协议）
   铁律：零网络请求 · 零硬编码布局（一切来自 init(continentJson)）· 背景不闪白
   变脸铁律：十区靠 style 数据变脸——同一份代码吃十区，无任何 styleKey 分支特例
   美术：动物森友会 × 水彩绘本 · 深夜蓝金 · 黄昏燃灯（canvas 程序化手绘，无外部资源）
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
      console.log('[continent→app]', name, show);
    }
  } catch (e) { console.error('[continent→app]', name, e); }
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
function mulberry32(seed) {
  var s = (seed * 7919) | 0;
  return function () {
    s = (s + 0x6D2B79F5) | 0;
    var t = Math.imul(s ^ (s >>> 15), 1 | s);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}
function hash2i(x, y, seed) {
  var h = Math.imul(x, 374761393) ^ Math.imul(y, 668265263) ^ (seed | 0);
  h = Math.imul(h ^ (h >>> 13), 1274126177);
  return ((h ^ (h >>> 16)) >>> 0) / 4294967296;
}
function vnoise2(x, y, seed) {
  var ix = Math.floor(x), iy = Math.floor(y);
  var fx = x - ix, fy = y - iy;
  var ux = fx * fx * (3 - 2 * fx), uy = fy * fy * (3 - 2 * fy);
  var a = hash2i(ix, iy, seed), b = hash2i(ix + 1, iy, seed);
  var c = hash2i(ix, iy + 1, seed), d = hash2i(ix + 1, iy + 1, seed);
  return lerp(lerp(a, b, ux), lerp(c, d, ux), uy);
}
function fbm2(x, y, oct, seed) {
  var v = 0, a = 0.5, f = 1, norm = 0;
  for (var i = 0; i < oct; i++) { v += a * vnoise2(x * f, y * f, seed + i * 101); norm += a; a *= 0.5; f *= 2.03; }
  return v / norm;
}

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
var FOV_RAD = 0.85; /* 契约 §4.2：竖向 FOV 0.85 rad */
var camera = new THREE.PerspectiveCamera(FOV_RAD * 180 / PI, window.innerWidth / window.innerHeight, 0.1, 700);

var hemi = new THREE.HemisphereLight(0xD9A8B8, 0x8A6852, 0.8); scene.add(hemi);
var sun = new THREE.DirectionalLight(0xFFB070, 1.15);
sun.castShadow = true;
sun.shadow.mapSize.set(2048, 2048);
sun.shadow.camera.left = -28; sun.shadow.camera.right = 28;
sun.shadow.camera.top = 28; sun.shadow.camera.bottom = -28;
sun.shadow.camera.near = 5; sun.shadow.camera.far = 160;
sun.shadow.bias = -0.0004; sun.shadow.normalBias = 0.03;
scene.add(sun); scene.add(sun.target);

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
function vGradRows(x, w, h, stops) {
  for (var row = 0; row < h; row++) {
    x.fillStyle = rgbStr(stopColorAt(stops, row / Math.max(1, h - 1)));
    x.fillRect(0, row, w, 1);
  }
}
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
function T(c, repX, repY) {
  var t = new THREE.CanvasTexture(c[0]);
  t.encoding = THREE.sRGBEncoding;
  t.anisotropy = Math.min(8, renderer.capabilities.getMaxAnisotropy());
  if (repX) { t.wrapS = t.wrapT = THREE.RepeatWrapping; t.repeat.set(repX, repY || repX); }
  return t;
}
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
/* 共享小贴图（跨区复用，不随 rebuild 销毁） */
function buildGlowTex() {
  var S = 128, c = cnv(S, S), x = c[1];
  glowStack(x, S / 2, S / 2, S * 0.48, S * 0.48, '255,206,132', function (k) { return lerp(0.9, 0, Math.pow(k, 1.35)); }, 12);
  gradProbe('glow', c[0], [[S * 0.5, S * 0.5]]);
  return T(c);
}
function buildPuffTex() {
  var S = 256, c = cnv(S, S), x = c[1];
  var rng = mulberry32(3312);
  for (var i = 0; i < 9; i++) {
    var cx = S * 0.2 + rng() * S * 0.6, cy = S * 0.4 + rng() * S * 0.28, r = 26 + rng() * 46;
    glowStack(x, cx, cy, r, r * 0.62, '255,253,247', function (k) { return lerp(0.9, 0, k); }, 8);
  }
  gradProbe('puff', c[0], [[S * 0.5, S * 0.45]]);
  return T(c);
}
function buildAoTex() {
  var S = 128, c = cnv(S, S), x = c[1];
  glowStack(x, S / 2, S / 2, S * 0.48, S * 0.48, '42,28,16', function (k) { return lerp(0.55, 0, k); }, 10);
  return T(c);
}
function buildMottleTex() {
  var S = 256, c = cnv(S, S), x = c[1];
  var rng = mulberry32(9021);
  x.fillStyle = '#E9E6DA'; x.fillRect(0, 0, S, S);
  for (var i = 0; i < 70; i++) {
    glowStack(x, rng() * S, rng() * S, 10 + rng() * 42, 8 + rng() * 30,
      rng() < 0.5 ? '222,218,204' : '238,236,226', function (k) { return lerp(0.14, 0, k); }, 6);
  }
  grainRng(x, S, S, 900, 0.05, rng);
  return T(c);
}
var glowTex = buildGlowTex(), puffTex = buildPuffTex(), aoTex = buildAoTex(), mottleTex = buildMottleTex();

/* ================= 4. 天空背景幕（SKY_BAND 重映射·一期做法同源） =================
   大陆相机恒俯视（pitch 0.30..1.22·FOV 0.85rad），可见天区只有地平线附近窄带：
   整张 style.sky 渐变铺进 SKY_BAND_TOP..SKY_BAND_BOT（球面 UV 重映射·带外钳边缘行），
   装饰（云·落日光晕）在纵向拉伸 SKY_AY 的坐标系里画（画圆得圆）。 */
var SKY_BAND_TOP = 0.40, SKY_BAND_BOT = -0.52;
var SKY_TEX_W = 1024, SKY_TEX_H = 512;
var SKY_AY = (SKY_TEX_H / (SKY_BAND_TOP - SKY_BAND_BOT)) / (SKY_TEX_W / (2 * PI));
var SKY_GRAD_TOP = 0.13, SKY_GRAD_BOT = -0.11;
function skyRowOf(elev) { return clamp((SKY_BAND_TOP - elev) / (SKY_BAND_TOP - SKY_BAND_BOT), 0, 1) * SKY_TEX_H; }
function buildSkyTex(stops, warm, haze) {
  var rng = mulberry32(Math.round((stops[0].rgb[0] * 997 + stops[2].rgb[1] * 613 + stops[4].rgb[2] * 331) * 1000));
  var W = SKY_TEX_W, H = SKY_TEX_H, c = cnv(W, H), x = c[1];
  var gTop = skyRowOf(SKY_GRAD_TOP), gBot = skyRowOf(SKY_GRAD_BOT);
  x.fillStyle = rgbStr(stops[0].rgb); x.fillRect(0, 0, W, Math.floor(gTop) + 1);
  for (var row = Math.floor(gTop); row <= Math.ceil(gBot); row++) {
    x.fillStyle = rgbStr(stopColorAt(stops, clamp((row - gTop) / Math.max(1e-6, gBot - gTop), 0, 1)));
    x.fillRect(0, row, W, 1);
  }
  x.fillStyle = rgbStr(stops[stops.length - 1].rgb); x.fillRect(0, Math.ceil(gBot), W, H - Math.ceil(gBot));
  /* 装饰：纵向拉伸坐标系（eY(仰角°) 给该系里的 y） */
  function eY(deg) { return skyRowOf(deg * PI / 180) / SKY_AY; }
  function cloud(cx, cy, s, tint, hi) {
    for (var w = -1; w <= 1; w++) {
      for (var i = 0, n = 6; i < n; i++) {
        var ox = (rng() * 3 - 1.5) * s, oy = (rng() * 0.5 - 0.25) * s * 0.4, r = (0.45 + rng() * 0.55) * s;
        x.fillStyle = tint; x.beginPath(); x.ellipse(cx + ox + w * W, cy + oy + r * 0.28, r, r * 0.62, 0, 0, PI * 2); x.fill();
      }
      for (i = 0; i < n; i++) {
        ox = (rng() * 2.6 - 1.3) * s; oy = (rng() * 0.35 - 0.3) * s; r = (0.35 + rng() * 0.45) * s;
        x.fillStyle = hi; x.beginPath(); x.ellipse(cx + ox + w * W, cy + oy, r, r * 0.5, 0, 0, PI * 2); x.fill();
      }
    }
  }
  x.save(); x.scale(1, SKY_AY);
  var sunA = rgbStr(warm), sunB = rgbStr([lerp(warm[0], 1, 0.3), lerp(warm[1], 0.9, 0.3), lerp(warm[2], 0.75, 0.3)]);
  var sx0 = W * 0.26, sy0 = eY(-1.6);
  glowStack(x, sx0, sy0, 120, 120,
    function (k) { return k < 0.4 ? sunB.slice(4, -1) : sunA.slice(4, -1); },
    function (k) { return k >= 1 ? 0 : (k < 0.4 ? lerp(0.85, 0.42, k / 0.4) : lerp(0.42, 0, (k - 0.4) / 0.6)); }, 14);
  var hzStr = Math.round(haze[0] * 255) + ',' + Math.round(haze[1] * 255) + ',' + Math.round(haze[2] * 255);
  for (var i = 0; i < 11; i++) {
    var nearSun = rng() < 0.5;
    cloud(nearSun ? sx0 + (rng() * 440 - 220) : rng() * W, eY(0.4 + rng() * 3.0), 4.5 + rng() * 6,
      nearSun ? 'rgba(255,196,150,.5)' : 'rgba(' + hzStr + ',.4)',
      nearSun ? 'rgba(255,232,204,.6)' : 'rgba(246,222,200,.5)');
  }
  x.restore();
  grainRng(x, W, H, 2400, 0.04, rng, '40,30,30');
  gradProbe('sky', c[0], [[W * 0.5, Math.floor(H * 0.45)], [W * 0.2, Math.floor(H * 0.5)], [W * 0.8, Math.floor(H * 0.4)]]);
  return T(c);
}
function makeHillTex(cyc, rough, seed) {
  var W = 1024, H = 256, c = cnv(W, H), x = c[1], N = 384;
  var rng = mulberry32(seed);
  x.clearRect(0, 0, W, H);
  for (var layer = 0; layer < 2; layer++) {
    var prof = [], i, a;
    for (i = 0; i <= N; i++) {
      a = i / N * PI * 2;
      prof.push(-Math.sin(a * cyc + layer * 2.4) - 0.28 * Math.sin(a * (cyc + 3) + layer)
        + (rough ? 0.12 * Math.sin(a * cyc * 3 + layer * 5) : 0) + (rng() - 0.5) * 0.06);
    }
    var lo = Math.min.apply(null, prof), hi = Math.max.apply(null, prof), span = Math.max(1e-3, hi - lo);
    var y0 = H * (0.08 + (layer === 0 ? 0 : 0.24));
    var y1 = H * (0.08 + (layer === 0 ? 0.55 : 0.72));
    x.beginPath(); x.moveTo(0, H);
    for (i = 0; i <= N; i++) x.lineTo(i / N * W, y0 + (prof[i] - lo) / span * (y1 - y0));
    x.lineTo(W, H); x.closePath();
    x.fillStyle = layer === 0 ? 'rgba(176,176,176,.6)' : 'rgba(128,128,128,1)';
    x.fill();
  }
  grainRng(x, W, H, 700, 0.05, rng, '20,20,20');
  var t = T(c);
  t.wrapS = THREE.RepeatWrapping; t.wrapT = THREE.ClampToEdgeWrapping;
  return t;
}
var hillTexA = makeHillTex(6, false, 411), hillTexB = makeHillTex(4, true, 412);
function buildHills(haze) {
  var rings = [];
  var hzA = new THREE.Color(haze[0] * 0.62, haze[1] * 0.56, haze[2] * 0.58);
  var hzB = new THREE.Color(haze[0] * 0.46, haze[1] * 0.40, haze[2] * 0.44);
  [[96, -10.5, 9.5, hillTexA, hzA], [72, -7.0, 6.4, hillTexB, hzB]].forEach(function (d, i) {
    var hh = d[2] - d[1];
    var m = new THREE.Mesh(
      new THREE.CylinderGeometry(d[0], d[0], hh, 64, 1, true),
      new THREE.MeshBasicMaterial({ map: d[3], transparent: true, side: THREE.BackSide, fog: false, depthWrite: false, color: d[4] }));
    m.position.y = d[1] + hh / 2;
    m.renderOrder = -7 + i;
    m.frustumCulled = false;
    rings.push(m);
  });
  return rings;
}
/* 天空球（UV 纵向重映射进可见仰角带） */
var skyGeo = new THREE.SphereGeometry(320, 40, 96);
(function remapSkyBandUV() {
  var pos = skyGeo.attributes.position, uv = skyGeo.attributes.uv, span = SKY_BAND_TOP - SKY_BAND_BOT;
  for (var i = 0; i < pos.count; i++) {
    var elev = Math.atan2(pos.getY(i), Math.hypot(pos.getX(i), pos.getZ(i)));
    uv.setY(i, 1 - clamp((SKY_BAND_TOP - elev) / span, 0, 1));
  }
  uv.needsUpdate = true;
})();
var skyMesh = new THREE.Mesh(skyGeo, new THREE.MeshBasicMaterial({ side: THREE.BackSide, fog: false, transparent: true, depthWrite: false }));
skyMesh.renderOrder = -9; skyMesh.frustumCulled = false;
scene.add(skyMesh);
var hillMeshes = [];

/* ================= 5. 大陆建造（style 数据 → 盒景地形） =================
   四条锁定：① 站位 (x,z) 平整可站台面（高度参考 padH）·标记锚 (x, markerTop, z)；
   ② 场地有效范围=台座半宽 23（平移硬止 ±22）；③ 水面高度感与 sea 一致；④ 十区靠 style 变脸。 */
var PED_HALF = 23, GRID = 384;
var regionRoot = new THREE.Group(); scene.add(regionRoot);
var regionDisposables = [];
var st = null, sites = [], curRegion = null;
var gh = null, gSlope = null, gMoist = null;
var seaY = 0, maxDepth = 3, stepH = 1;
var treePts = [];

function disposeRegion() {
  while (regionRoot.children.length) {
    var ch = regionRoot.children.pop();
    regionRoot.remove(ch);
  }
  regionDisposables.forEach(function (d) { d.dispose(); });
  regionDisposables.length = 0;
  /* DOM 名签清理 */
  tagEntries.forEach(function (t) { if (t.el && t.el.parentNode) t.el.parentNode.removeChild(t.el); });
  tagEntries.length = 0;
  if (selRing) { selRing.visible = false; }
}
function trackD(d) { regionDisposables.push(d); return d; }

function heightAtWorld(x, z) {
  var fx = clamp((x + PED_HALF) / (PED_HALF * 2), 0, 1) * (GRID - 1);
  var fz = clamp((z + PED_HALF) / (PED_HALF * 2), 0, 1) * (GRID - 1);
  var x0 = Math.floor(fx), z0 = Math.floor(fz);
  var tx = fx - x0, tz = fz - z0;
  var x1 = Math.min(GRID - 1, x0 + 1), z1 = Math.min(GRID - 1, z0 + 1);
  var a = gh[z0 * GRID + x0], b = gh[z0 * GRID + x1], c = gh[z1 * GRID + x0], d = gh[z1 * GRID + x1];
  var h = lerp(lerp(a, b, tx), lerp(c, d, tx), tz);
  var s = lerp(lerp(gSlope[z0 * GRID + x0], gSlope[z0 * GRID + x1], tx), lerp(gSlope[z1 * GRID + x0], gSlope[z1 * GRID + x1], tx), tz);
  return { h: h, s: s };
}
function baseField(x, z) { /* 原始噪声场 e∈~[0,1]（含岸线复杂度 coast 与宏观陆块） */
  var coastK = 0.6 + st.coast * 1.7;
  var warp = fbm2(x * 0.045 + 7.7, z * 0.045 + 3.1, 3, st._sd + 11) - 0.5;
  var e = fbm2(x * 0.021 + warp * coastK, z * 0.021 + warp * coastK, 5, st._sd);
  e = e * 0.72 + fbm2(x * 0.0082 + 31.7, z * 0.0082 + 11.3, 2, st._sd + 29) * 0.28;
  return e;
}
function landYFromE(e, x, z) { /* e → 世界高度（sea=0 基准·terrace/边缘/台面在此前不含） */
  var amp = st.amp;
  if (e >= st._sea) {
    var t = clamp((e - st._sea) / (1 - st._sea), 0, 1);
    var y = amp * Math.pow(t, 0.85); /* 低仰角也见起伏（盒景要有山体感） */
    var ridge = 1 - Math.abs(fbm2(x * 0.06 + 91, z * 0.06 + 17, 3, st._sd + 41) * 2 - 1);
    y += ridge * amp * 0.5 * smooth01((t - 0.48) / 0.3);
    if (st.terrace) { /* 梯田地貌：宽台面 + 陡短坎（fread 22% 即登顶 ⇒ 台面平整可读） */
      var sIdx = y / stepH, k = Math.floor(sIdx);
      y = (k + smooth01(clamp((sIdx - k) * 4.5, 0, 1))) * stepH;
    }
    return y;
  }
  var d = clamp((st._sea - e) / Math.max(0.05, st._sea), 0, 1);
  return -maxDepth * Math.pow(Math.min(1, d * 1.35), 0.68);
}
function finalHeight(x, z) { /* 完整管线：场 → 地貌 → 边缘沉圈 → 站位台面 */
  var e = baseField(x, z);
  var y = landYFromE(e, x, z);
  var r = Math.max(Math.abs(x), Math.abs(z));
  var edgeK = smooth01((r - 19.2) / 3.6);
  y = lerp(y, seaY - maxDepth * 0.55, edgeK); /* 边缘沉到水下 ⇒ 盒景四周留一圈水 */
  for (var i = 0; i < sites.length; i++) { /* 站位平整可站台面（高度参考 padH） */
    var s = sites[i];
    var d = Math.hypot(x - s.x, z - s.z);
    if (d < 3.4) {
      var k = smooth01(1 - d / 3.4);
      y = lerp(y, st.padH, k);
    }
  }
  return y;
}
function buildHeightGrid() {
  gh = new Float32Array(GRID * GRID);
  gSlope = new Float32Array(GRID * GRID);
  var eArr = new Float32Array(GRID * GRID);
  var i, x, z;
  for (var gz = 0; gz < GRID; gz++) {
    z = -PED_HALF + (gz / (GRID - 1)) * PED_HALF * 2;
    for (var gx = 0; gx < GRID; gx++) {
      x = -PED_HALF + (gx / (GRID - 1)) * PED_HALF * 2;
      eArr[gz * GRID + gx] = baseField(x, z);
    }
  }
  /* sea 分位数：水面占比严格 = style.sea（「sea 大=水多」做实） */
  var sample = Array.prototype.slice.call(eArr).filter(function (_, idx) { return idx % 3 === 0; }).sort(function (a, b) { return a - b; });
  st._sea = sample[Math.floor(clamp(st.sea, 0.02, 0.98) * (sample.length - 1))];
  stepH = Math.max(0.7, st.amp / 6);
  maxDepth = st.amp * 0.42;
  for (gz = 0; gz < GRID; gz++) {
    z = -PED_HALF + (gz / (GRID - 1)) * PED_HALF * 2;
    for (gx = 0; gx < GRID; gx++) {
      x = -PED_HALF + (gx / (GRID - 1)) * PED_HALF * 2;
      gh[gz * GRID + gx] = finalHeight(x, z);
    }
  }
  for (gz = 0; gz < GRID; gz++) {
    for (gx = 0; gx < GRID; gx++) {
      i = gz * GRID + gx;
      var hx0 = gh[gz * GRID + Math.max(0, gx - 1)], hx1 = gh[gz * GRID + Math.min(GRID - 1, gx + 1)];
      var hz0 = gh[Math.max(0, gz - 1) * GRID + gx], hz1 = gh[Math.min(GRID - 1, gz + 1) * GRID + gx];
      gSlope[i] = clamp(Math.hypot((hx1 - hx0) * GRID / (PED_HALF * 2), (hz1 - hz0) * GRID / (PED_HALF * 2)) * 0.5, 0, 1.5);
    }
  }
  gMoist = new Float32Array(128 * 128);
  for (gz = 0; gz < 128; gz++) for (gx = 0; gx < 128; gx++) {
    gMoist[gz * 128 + gx] = fbm2(gx * 0.11, gz * 0.11, 3, st._sd + 77);
  }
}
function moistAt(x, z) {
  var fx = clamp((x + PED_HALF) / (PED_HALF * 2), 0, 1) * 127;
  var fz = clamp((z + PED_HALF) / (PED_HALF * 2), 0, 1) * 127;
  var x0 = Math.floor(fx), z0 = Math.floor(fz), tx = fx - x0, tz = fz - z0;
  var x1 = Math.min(127, x0 + 1), z1 = Math.min(127, z0 + 1);
  return lerp(lerp(gMoist[z0 * 128 + x0], gMoist[z0 * 128 + x1], tx), lerp(gMoist[z1 * 128 + x0], gMoist[z1 * 128 + x1], tx), tz);
}

/* ---- 5a. 地表贴图（水彩上色·无 CanvasGradient） ---- */
function buildTerrainTex() {
  var rng = mulberry32(st._sd + 555);
  var S = 1024, c = cnv(S, S), x = c[1];
  var img = x.createImageData(S, S), d = img.data;
  var water = st.water, bed = st.bed, beach = st.beach, g1 = st.g1, g2 = st.g2;
  var cliff = st.cliff, snow = st.snow, rock = st.rock, earth = st.earth, warm = st.warm;
  var beachW = 0.14 + st.coast * 0.14;
  for (var py = 0; py < S; py++) {
    var z = -PED_HALF + ((py + 0.5) / S) * PED_HALF * 2;
    for (var px = 0; px < S; px++) {
      var xx = -PED_HALF + ((px + 0.5) / S) * PED_HALF * 2;
      var hs = heightAtWorld(xx, z);
      var h = hs.h, sl = hs.s;
      var jit = hash2i(px, py, 12345) - 0.5;
      var mo = moistAt(xx, z);
      var r, g, b;
      if (h < 0.02) { /* ---- 水下河床（透过水看见） ---- */
        var dt = clamp(-h / maxDepth, 0, 1);
        r = lerp(bed[0] * 1.15, bed[0] * 0.55, dt);
        g = lerp(bed[1] * 1.15, bed[1] * 0.55, dt);
        b = lerp(bed[2] * 1.15, bed[2] * 0.58, dt);
        if (h > -0.10) { var wet = 1 - (h + 0.10) / 0.12; r = lerp(r, beach[0] * 0.72, wet * 0.7); g = lerp(g, beach[1] * 0.7, wet * 0.7); b = lerp(b, beach[2] * 0.68, wet * 0.7); }
      } else { /* ---- 滩 + 陆 ---- */
        var landT = clamp(h / Math.max(0.5, st.amp), 0, 1);
        var base = stopColorAt([{ pos: 0, rgb: g1 }, { pos: 1, rgb: g2 }], clamp(mo * 0.9 + landT * 0.25 + jit * 0.2, 0, 1));
        r = base[0]; g = base[1]; b = base[2];
        if (h < beachW) { /* 滩涂带（内缘噪声参差） */
          var bk = smooth01((beachW - h) / 0.24 + jit * 0.3);
          r = lerp(r, beach[0], bk); g = lerp(g, beach[1], bk); b = lerp(b, beach[2], bk);
        }
        if (h < 0.14) { /* 水线湿泥 */
          var wet2 = 1 - h / 0.14;
          r *= lerp(1, 0.74, wet2); g *= lerp(1, 0.72, wet2); b *= lerp(1, 0.70, wet2);
        }
        /* 坡面：崖 / 岩；梯田坎=土 */
        if (sl > 0.42) {
          var ck = smooth01((sl - 0.42) / 0.3);
          var cc = st.terrace && h > 0.3 ? earth : (sl > 0.62 ? cliff : rock);
          r = lerp(r, cc[0], ck * 0.9); g = lerp(g, cc[1], ck * 0.9); b = lerp(b, cc[2], ck * 0.9);
        }
        /* 田块晕染（中频噪声大斑块 → earth/g2 交替，梯田区尤明显） */
        var patch = fbm2(xx * 0.09 + 41, z * 0.09 + 87, 2, st._sd + 91);
        if (patch > 0.56 && sl < 0.45 && h > 0.3) {
          var pk = smooth01((patch - 0.56) / 0.14) * 0.6;
          r = lerp(r, earth[0], pk); g = lerp(g, earth[1], pk); b = lerp(b, earth[2], pk);
        }
        /* 梯田坎线：台沿轻提亮（几何台阶为主，色线只点神） */
        if (st.terrace && h > 0.3 && sl < 0.5) {
          var f = (h % stepH) / stepH;
          if (f < 0.06 || f > 0.94) { var lk = 0.09 * (1 - Math.min(f, 1 - f) / 0.06); r += lk; g += lk; b += lk * 0.8; }
        }
        /* 雪线（99 = 无雪） */
        if (st.snowLine < 90) {
          var snK = smooth01((h - (st.snowLine - 0.5) + jit * 0.5) / 0.8);
          r = lerp(r, snow[0], snK); g = lerp(g, snow[1], snK); b = lerp(b, snow[2], snK);
        }
        /* 峰顶提亮 / 谷底压暗 */
        var shade = 1 + landT * 0.10 - clamp((0.35 - h) * 0.3, 0, 0.12);
        r *= shade; g *= shade; b *= shade;
      }
      /* 暖罩染（style.warm）+ 水彩抖动 */
      var wj = (1 + jit * 0.10);
      r = r * warm[0] * wj; g = g * warm[1] * wj; b = b * warm[2] * wj;
      var oI = (py * S + px) * 4;
      d[oI] = clamp(r, 0, 1) * 255; d[oI + 1] = clamp(g, 0, 1) * 255; d[oI + 2] = clamp(b, 0, 1) * 255; d[oI + 3] = 255;
    }
  }
  x.putImageData(img, 0, 0);
  /* 陆上水彩晕斑（软盘罩染） */
  for (var i = 0; i < 380; i++) {
    var bu = rng(), bv = rng();
    var bx = -PED_HALF + bu * PED_HALF * 2, bz = -PED_HALF + bv * PED_HALF * 2;
    var bh = heightAtWorld(bx, bz).h;
    if (bh < 0.35) continue;
    var col = rng() < 0.5 ? g2 : (rng() < 0.5 ? earth : g1);
    glowStack(x, bu * S, bv * S, 12 + rng() * 42, 9 + rng() * 28,
      Math.round(col[0] * 255) + ',' + Math.round(col[1] * 255) + ',' + Math.round(col[2] * 255),
      function (k) { return lerp(0.13, 0, k); }, 6);
  }
  grainRng(x, S, S, 3600, 0.045, rng);
  gradProbe('terrain', c[0], [[S * 0.5, S * 0.5], [S * 0.3, S * 0.3], [S * 0.7, S * 0.7]]);
  return trackD(T(c));
}

/* ---- 5b. 地形网格 ---- */
function buildTerrainMesh() {
  var seg = 128, size = PED_HALF * 2 + 0.5;
  var geo = new THREE.PlaneGeometry(size, size, seg, seg);
  geo.rotateX(-PI / 2);
  var pos = geo.attributes.position;
  var colors = new Float32Array(pos.count * 3);
  for (var i = 0; i < pos.count; i++) {
    var vx = pos.getX(i), vz = pos.getZ(i);
    var hs = heightAtWorld(vx, vz);
    pos.setY(i, hs.h);
    var k = 0.92 + 0.08 * (1 - clamp(hs.s, 0, 1)); /* 陡坡微暗（假 AO） */
    colors[i * 3] = k; colors[i * 3 + 1] = k; colors[i * 3 + 2] = k * 0.99;
  }
  geo.setAttribute('color', new THREE.BufferAttribute(colors, 3));
  geo.computeVertexNormals();
  trackD(geo);
  var mat = trackD(new THREE.MeshStandardMaterial({ map: buildTerrainTexMESH, roughness: 1, vertexColors: true }));
  var m = new THREE.Mesh(geo, mat);
  m.receiveShadow = true; m.castShadow = false;
  regionRoot.add(m);
  return m;
}
var buildTerrainTexMESH = null; /* init 时先烤贴图再建网格（顺序占位） */

/* ---- 5c. 水面（深度蒙版驱动·岸线呼吸白沫） ---- */
function buildWaterMask() {
  var S = 512, c = cnv(S, S), x = c[1];
  var img = x.createImageData(S, S), d = img.data;
  for (var py = 0; py < S; py++) {
    var z = -PED_HALF + ((py + 0.5) / S) * PED_HALF * 2;
    for (var px = 0; px < S; px++) {
      var xx = -PED_HALF + ((px + 0.5) / S) * PED_HALF * 2;
      var h = heightAtWorld(xx, z).h;
      var depth = clamp(-h / maxDepth, 0, 1);
      var oI = (py * S + px) * 4;
      d[oI] = depth * 255; d[oI + 1] = depth * 255; d[oI + 2] = depth * 255; d[oI + 3] = 255;
    }
  }
  x.putImageData(img, 0, 0);
  gradProbe('watermask', c[0], [[S * 0.5, S * 0.5], [S * 0.5, S * 0.9]]);
  var t = new THREE.CanvasTexture(c[0]);
  return trackD(t);
}
var waterMat = null, waterMesh = null;
var waterUni = { uTime: { value: 0 }, uCol: { value: new THREE.Color() }, uGlow: { value: 1 } };
function buildWater() {
  if (waterMesh) { regionRoot.remove(waterMesh); waterMesh.geometry.dispose(); waterMesh = null; }
  var geo = trackD(new THREE.PlaneGeometry(PED_HALF * 2 + 0.5, PED_HALF * 2 + 0.5));
  geo.rotateX(-PI / 2);
  var mask = buildWaterMask();
  waterMat = new THREE.ShaderMaterial({
    uniforms: { uTime: waterUni.uTime, uCol: { value: new THREE.Color() }, uGlow: waterUni.uGlow, uMask: { value: mask } },
    transparent: true, depthWrite: false,
    vertexShader: 'varying vec2 vUv; void main(){ vUv = uv; gl_Position = projectionMatrix * modelViewMatrix * vec4(position,1.0); }',
    fragmentShader: [
      'uniform float uTime, uGlow; uniform vec3 uCol; uniform sampler2D uMask; varying vec2 vUv;',
      'void main(){',
      '  float depth = texture2D(uMask, vUv).r;',
      '  vec2 w1 = vec2(vUv.x*83.0 + uTime*0.9, vUv.y*127.0 - uTime*0.7);',
      '  vec2 w2 = vec2(vUv.x*113.0 - vUv.y*61.0 - uTime*1.1, vUv.x*47.0 + vUv.y*131.0 + uTime*0.8);',
      '  float rip = sin(w1.x)*sin(w1.y)*0.6 + sin(w2.x)*sin(w2.y)*0.4;',
      '  vec3 c = uCol * (0.96 + 0.022*rip);',
      '  c *= 0.94 + 0.08 * sin(vUv.x*6.0 + 1.7) * sin(vUv.y*5.0 + 0.6);', /* 大尺度水色漂移（去均匀感） */
      '  c = mix(c*1.15, c*0.55, depth);', /* 浅处亮·深处暗 */
      '  float foam = smoothstep(0.085, 0.012, depth) * (0.55 + 0.45*sin(depth*90.0 - uTime*2.1));',
      '  c = mix(c, vec3(0.96, 0.95, 0.9), foam * 0.4);',
      '  c += vec3(1.0, 0.85, 0.6) * pow(max(0.0, rip), 6.0) * 0.10 * uGlow;', /* 灯火碎金 */
      '  float a = mix(0.62, 0.94, smoothstep(0.0, 0.16, depth));', /* 极浅处透出湿滩 */
      '  gl_FragColor = vec4(c, a); }'
    ].join('\n')
  });
  trackD(waterMat);
  waterMesh = new THREE.Mesh(geo, waterMat);
  waterMesh.position.y = seaY;
  waterMesh.renderOrder = 1;
  regionRoot.add(waterMesh);
}

/* ---- 5d. 盒景侧帮 + 浮空岩底 ---- */
function buildPedestal() {
  var c = cnv(256, 128), x = c[1], rng = mulberry32(st._sd + 808);
  var bands = [
    [0.00, 0.10, [st.g1[0] * 0.55, st.g1[1] * 0.55, st.g1[2] * 0.5]],
    [0.10, 0.34, st.earth],
    [0.34, 0.62, st.rock],
    [0.62, 0.88, [st.bed[0] * 0.8, st.bed[1] * 0.8, st.bed[2] * 0.85]],
    [0.88, 1.00, [0.17, 0.15, 0.16]]
  ];
  bands.forEach(function (bd) {
    var y0 = Math.floor(bd[0] * 128), y1 = Math.floor(bd[1] * 128);
    for (var row = y0; row < y1; row++) {
      var j = (hash2i(3, row, 77) - 0.5) * 0.08;
      x.fillStyle = rgbStr([clamp(bd[2][0] + j, 0, 1), clamp(bd[2][1] + j, 0, 1), clamp(bd[2][2] + j, 0, 1)]);
      x.fillRect(0, row, 256, 1);
    }
  });
  for (var i = 0; i < 40; i++) { /* 竖向风化痕 */
    var sx = rng() * 256, sw = 2 + rng() * 6, sy = rng() * 60, sh = 20 + rng() * 60;
    x.fillStyle = 'rgba(30,22,16,' + (0.05 + rng() * 0.08).toFixed(3) + ')';
    x.fillRect(sx, sy, sw, sh);
  }
  grainRng(x, 256, 128, 700, 0.08, rng, '20,14,10');
  gradProbe('pedestal', c[0], [[128, 40], [60, 90]]);
  var tex = trackD(T(c));
  var bottomY = seaY - maxDepth * 0.55 - 2.6;
  var geo = new THREE.BufferGeometry();
  var hw = PED_HALF + 0.24, top = 0.05, pos = [], uv = [], idx = [];
  function quad(ax, az, bx, bz, cx2, cz2, dx2, dz2) {
    var base = pos.length / 3;
    pos.push(ax, top, az, bx, top, bz, cx2, bottomY, cz2, dx2, bottomY, dz2);
    uv.push(0, 0, 1, 0, 1, 1, 0, 1);
    idx.push(base, base + 2, base + 1, base, base + 3, base + 2);
  }
  quad(-hw, -hw, hw, -hw, hw, -hw, -hw, -hw); /* +z 朝里的面序不重要：DoubleSide */
  quad(hw, hw, -hw, hw, -hw, hw, hw, hw);
  quad(hw, -hw, hw, hw, hw, hw, hw, -hw);
  quad(-hw, hw, -hw, -hw, -hw, -hw, -hw, hw);
  geo.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
  geo.setAttribute('uv', new THREE.Float32BufferAttribute(uv, 2));
  geo.setIndex(idx);
  geo.computeVertexNormals();
  trackD(geo);
  var mesh = new THREE.Mesh(geo, trackD(new THREE.MeshStandardMaterial({ map: tex, roughness: 1, side: THREE.DoubleSide })));
  regionRoot.add(mesh);
  /* 浮空岩底（四棱锥朝下） */
  var keel = new THREE.Mesh(trackD(new THREE.ConeGeometry(1, 1, 4)),
    trackD(new THREE.MeshStandardMaterial({ color: new THREE.Color(st.rock[0] * 0.5, st.rock[1] * 0.5, st.rock[2] * 0.52), roughness: 1 })));
  keel.rotation.y = PI / 4;
  keel.scale.set(hw * 1.32, 7.5, hw * 1.32);
  keel.position.y = bottomY - 3.72;
  regionRoot.add(keel);
}

/* ---- 5e. 植被 / 岩石 / 芦苇（实例化） ---- */
function mergeGeos(list) { /* position/normal/index 手工合并（r147 core 无 utils） */
  var pos = [], nrm = [], idx = [], uvA = [];
  list.forEach(function (g) {
    var p = g.attributes.position, n = g.attributes.normal, uv = g.attributes.uv;
    var base = pos.length / 3;
    for (var i = 0; i < p.count; i++) { pos.push(p.getX(i), p.getY(i), p.getZ(i)); nrm.push(n.getX(i), n.getY(i), n.getZ(i)); }
    if (uv) for (i = 0; i < uv.count; i++) uvA.push(uv.getX(i), uv.getY(i)); else for (i = 0; i < p.count; i++) uvA.push(0, 0);
    if (g.index) for (i = 0; i < g.index.count; i++) idx.push(base + g.index.getX(i));
    else for (i = 0; i < p.count; i++) idx.push(base + i);
  });
  var geo = new THREE.BufferGeometry();
  geo.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
  geo.setAttribute('normal', new THREE.Float32BufferAttribute(nrm, 3));
  geo.setAttribute('uv', new THREE.Float32BufferAttribute(uvA, 2));
  geo.setIndex(idx);
  return geo;
}
var FOLIAGE_RNG = null;
function buildVegetation() {
  var rng = mulberry32(st._sd + 313);
  var landN = 0;
  for (var i = 0; i < gh.length; i += 7) if (gh[i] > 0.5) landN++;
  var landFrac = landN / (gh.length / 7);
  var target = Math.round(clamp(st.treeN * (0.5 + landFrac), 20, 240));
  var trunkH = st.trunk, treeR = st.treeR, treeH = st.treeH;
  var spots = [], tries = 0;
  while (spots.length < target && tries < target * 40) {
    tries++;
    var x = (rng() * 2 - 1) * (PED_HALF - 2.2), z = (rng() * 2 - 1) * (PED_HALF - 2.2);
    var hs = heightAtWorld(x, z);
    if (hs.h < 0.45 || hs.h > st.amp * 0.94) continue;
    if (st.snowLine < 90 && hs.h > st.snowLine - 0.5) continue;
    if (hs.s > 0.5) continue;
    var nearSite = false;
    for (var si = 0; si < sites.length; si++) if (Math.hypot(x - sites[si].x, z - sites[si].z) < 4.2) { nearSite = true; break; }
    if (nearSite) continue;
    spots.push({ x: x, z: z, y: hs.h, s: 0.72 + rng() * 0.62, pine: rng() < 0.28, leaf: Math.floor(rng() * st.leafs.length) });
  }
  treePts = spots;
  var _m4 = new THREE.Matrix4(), _q = new THREE.Quaternion(), _v = new THREE.Vector3(), _s = new THREE.Vector3();
  var _e = new THREE.Euler();
  function fill(mesh, list, fn) {
    list.forEach(function (sp, i) { fn(sp, i); mesh.setMatrixAt(i, _m4); });
    mesh.instanceMatrix.needsUpdate = true;
    mesh.frustumCulled = false;
    mesh.castShadow = true;
    regionRoot.add(mesh);
  }
  /* 阔叶：三球丛（unit·底在 y=0） */
  var blob = mergeGeos([
    new THREE.SphereGeometry(0.95, 10, 8).translate(0, 0.95, 0),
    new THREE.SphereGeometry(0.55, 9, 7).translate(0.52, 0.44, 0.18),
    new THREE.SphereGeometry(0.6, 9, 7).translate(-0.2, 1.38, -0.12)
  ]);
  trackD(blob);
  /* 松：三层锥（unit·底在 y=0） */
  var pine = mergeGeos([
    new THREE.ConeGeometry(1.0, 1.5, 8).translate(0, 0.75, 0),
    new THREE.ConeGeometry(0.78, 1.2, 8).translate(0, 1.55, 0),
    new THREE.ConeGeometry(0.5, 0.95, 8).translate(0, 2.25, 0)
  ]);
  trackD(pine);
  var trunkGeo = trackD(new THREE.CylinderGeometry(0.14, 0.2, 1, 6));
  var blobMesh = new THREE.InstancedMesh(blob, trackD(new THREE.MeshStandardMaterial({ map: mottleTex, roughness: 1 })), spots.length);
  var pineMesh = new THREE.InstancedMesh(pine, blobMesh.material, spots.length);
  var trunkMesh = new THREE.InstancedMesh(trunkGeo, trackD(new THREE.MeshStandardMaterial({ color: 0x6B4E33, roughness: 1 })), spots.length);
  var nBlob = 0, nPine = 0;
  fill(trunkMesh, spots, function (sp) {
    _e.set(0, sp.x * 3.1 + sp.z, 0); _q.setFromEuler(_e);
    _v.set(sp.x, sp.y + trunkH * sp.s * 0.5 - 0.1, sp.z);
    _s.set(sp.s * 0.9, trunkH * sp.s, sp.s * 0.9);
    _m4.compose(_v, _q, _s);
  });
  spots.forEach(function (sp) {
    var lc = st.leafs[Math.min(sp.leaf, st.leafs.length - 1)] || st.leafs[0]; /* leafs 为变长数组（1..3 片）·双保险 */
    var col = new THREE.Color(clamp(lc[0] * (0.9 + rng() * 0.22), 0, 1), clamp(lc[1] * (0.9 + rng() * 0.22), 0, 1), clamp(lc[2] * (0.9 + rng() * 0.22), 0, 1));
    _e.set(0, rng() * PI * 2, 0); _q.setFromEuler(_e);
    _s.set(sp.s * treeR, sp.s * treeH, sp.s * treeR);
    if (sp.pine) {
      _v.set(sp.x, sp.y + trunkH * sp.s * 0.7, sp.z);
      _m4.compose(_v, _q, _s);
      pineMesh.setMatrixAt(nPine, _m4);
      pineMesh.setColorAt(nPine, col); nPine++;
    } else {
      _v.set(sp.x, sp.y + trunkH * sp.s * 0.82, sp.z);
      _m4.compose(_v, _q, _s);
      blobMesh.setMatrixAt(nBlob, _m4);
      blobMesh.setColorAt(nBlob, col); nBlob++;
    }
  });
  pineMesh.count = nPine; blobMesh.count = nBlob;
  if (pineMesh.instanceColor) pineMesh.instanceColor.needsUpdate = true;
  if (blobMesh.instanceColor) blobMesh.instanceColor.needsUpdate = true;
  fill(blobMesh, spots.slice(0, 0), function () { }); /* 已逐实例填好，仅入场景 */
  fill(pineMesh, spots.slice(0, 0), function () { });
  /* 岩石 */
  var rockGeo = trackD(new THREE.DodecahedronGeometry(1, 0));
  var nR = 24, rockMesh = new THREE.InstancedMesh(rockGeo, trackD(new THREE.MeshStandardMaterial({ map: mottleTex, roughness: 1 })), nR);
  var placed = 0, guard = 0;
  while (placed < nR && guard < 400) {
    guard++;
    var rx = (rng() * 2 - 1) * (PED_HALF - 2.5), rz = (rng() * 2 - 1) * (PED_HALF - 2.5);
    var rhs = heightAtWorld(rx, rz);
    if (rhs.h < 0.2) continue;
    _e.set(rng() * 0.5, rng() * PI * 2, rng() * 0.5); _q.setFromEuler(_e);
    var rs = 0.22 + rng() * 0.5;
    _m4.compose(_v.set(rx, rhs.h + rs * 0.3, rz), _q, _s.set(rs, rs * 0.8, rs));
    rockMesh.setMatrixAt(placed, _m4);
    var rc = st.rock;
    rockMesh.setColorAt(placed, new THREE.Color(clamp(rc[0] * (0.85 + rng() * 0.3), 0, 1), clamp(rc[1] * (0.85 + rng() * 0.3), 0, 1), clamp(rc[2] * (0.85 + rng() * 0.3), 0, 1)));
    placed++;
  }
  rockMesh.count = placed;
  rockMesh.instanceMatrix.needsUpdate = true;
  if (rockMesh.instanceColor) rockMesh.instanceColor.needsUpdate = true;
  rockMesh.frustumCulled = false; rockMesh.castShadow = true;
  regionRoot.add(rockMesh);
  /* 芦苇（水乡 sea 大时长） */
  if (st.sea > 0.3) {
    var reedGeo = trackD(new THREE.ConeGeometry(0.05, 1, 4));
    reedGeo.translate(0, 0.5, 0);
    var nReed = 150, reedLeaf = st.leafs[Math.min(1, st.leafs.length - 1)]; /* 单片区大区回退到第 1 片 */
    var reedMesh = new THREE.InstancedMesh(reedGeo, trackD(new THREE.MeshStandardMaterial({ color: new THREE.Color(reedLeaf[0] * 0.9, reedLeaf[1] * 0.95, reedLeaf[2] * 0.8), roughness: 1 })), nReed);
    var rp = 0, rg2 = 0;
    while (rp < nReed && rg2 < 2200) {
      rg2++;
      var wx = (rng() * 2 - 1) * (PED_HALF - 1.6), wz = (rng() * 2 - 1) * (PED_HALF - 1.6);
      var whs = heightAtWorld(wx, wz);
      if (whs.h < 0.06 || whs.h > 0.4 || whs.s > 0.4) continue;
      _e.set((rng() - 0.5) * 0.3, rng() * PI, (rng() - 0.5) * 0.3); _q.setFromEuler(_e);
      _m4.compose(_v.set(wx, whs.h - 0.05, wz), _q, _s.set(1, 0.7 + rng() * 0.7, 1));
      reedMesh.setMatrixAt(rp, _m4);
      rp++;
    }
    reedMesh.count = rp;
    reedMesh.instanceMatrix.needsUpdate = true;
    reedMesh.frustumCulled = false;
    regionRoot.add(reedMesh);
  }
}

/* ---- 5f. 站位建造（平台 + 微缩屋 + 奇观晶石 + 家徽记 + 名签） ---- */
var tagEntries = []; /* { el, x, y, z } */
var selRing = null, crystal = null;
function buildSites() {
  var platMat = trackD(new THREE.MeshStandardMaterial({ map: mottleTex, roughness: 1, color: new THREE.Color(lerp(st.beach[0], st.rock[0], 0.62), lerp(st.beach[1], st.rock[1], 0.62), lerp(st.beach[2], st.rock[2], 0.6)) }));
  var wallMat = trackD(new THREE.MeshStandardMaterial({ color: 0xF2E9D6, roughness: 1 }));
  var roofMat = trackD(new THREE.MeshStandardMaterial({ color: 0xC96F42, roughness: 1 }));
  var winMat = trackD(new THREE.MeshBasicMaterial({ color: 0xFFD9A0 }));
  var darkMat = trackD(new THREE.MeshStandardMaterial({ color: 0x4A3826, roughness: 1 }));
  var _m4 = new THREE.Matrix4(), _q = new THREE.Quaternion(), _v = new THREE.Vector3(), _s = new THREE.Vector3();
  var lampPts = [];
  sites.forEach(function (s, si) {
    var gy = heightAtWorld(s.x, s.z).h;
    var platY = st.padH;
    /* 台面 */
    var plat = new THREE.Mesh(trackD(new THREE.CylinderGeometry(2.0, 2.3, 0.34, 18)), platMat);
    plat.position.set(s.x, platY - 0.15, s.z);
    plat.receiveShadow = true; plat.castShadow = true;
    regionRoot.add(plat);
    /* 微缩屋（城市 curated·buildingCount 顶 3） */
    var nH = s.isWonder ? 0 : Math.min(3, s.buildingCount || 0);
    for (var hi = 0; hi < nH; hi++) {
      var ha = (hi / Math.max(1, nH)) * PI * 2 + si * 1.3;
      var hx = s.x + Math.cos(ha) * 1.05, hz = s.z + Math.sin(ha) * 1.05;
      var hs2 = 0.62 + (hi % 2) * 0.16;
      var wall = new THREE.Mesh(trackD(new THREE.BoxGeometry(hs2, 0.52, hs2 * 0.9)), wallMat);
      wall.position.set(hx, platY + 0.26, hz);
      wall.rotation.y = ha;
      wall.castShadow = true;
      regionRoot.add(wall);
      var roof = new THREE.Mesh(trackD(new THREE.ConeGeometry(hs2 * 0.78, 0.34, 4)), roofMat);
      roof.position.set(hx, platY + 0.69, hz);
      roof.rotation.y = ha + PI / 4;
      roof.castShadow = true;
      regionRoot.add(roof);
      var win = new THREE.Mesh(trackD(new THREE.BoxGeometry(0.12, 0.12, 0.02)), winMat);
      win.position.set(hx + Math.sin(ha) * (hs2 * 0.46), platY + 0.28, hz + Math.cos(ha) * (hs2 * 0.46));
      regionRoot.add(win);
    }
    /* 家徽记 / 奇观晶石 / 城市金钉 */
    if (s.isWonder) {
      var cr = new THREE.Mesh(trackD(new THREE.OctahedronGeometry(0.34, 0)),
        trackD(new THREE.MeshStandardMaterial({ color: new THREE.Color(st.warm[0], st.warm[1] * 0.9, st.warm[2] * 0.7), roughness: 0.4, metalness: 0.1, emissive: new THREE.Color(st.warm[0] * 0.55, st.warm[1] * 0.42, st.warm[2] * 0.22) })));
      cr.position.set(s.x, platY + 1.5, s.z);
      cr.castShadow = true;
      regionRoot.add(cr);
      crystal = cr;
      var dais = new THREE.Mesh(trackD(new THREE.CylinderGeometry(0.55, 0.7, 0.3, 10)), platMat);
      dais.position.set(s.x, platY + 0.15, s.z);
      regionRoot.add(dais);
    } else {
      var pole = new THREE.Mesh(trackD(new THREE.CylinderGeometry(0.035, 0.05, 1.15, 6)), darkMat);
      pole.position.set(s.x, platY + 0.57, s.z);
      regionRoot.add(pole);
      var orb = new THREE.Mesh(trackD(new THREE.SphereGeometry(s.isHome ? 0.15 : 0.11, 10, 8)),
        trackD(new THREE.MeshStandardMaterial({ color: 0xE8A24C, roughness: 0.5, emissive: new THREE.Color(0.55, 0.34, 0.12) })));
      orb.position.set(s.x, platY + (s.isHome ? 1.24 : 1.2), s.z);
      regionRoot.add(orb);
    }
    /* 灯柱 ×2（平台缘） */
    for (var li = 0; li < 2; li++) {
      var la = si * 1.9 + li * PI + 0.6;
      var lx = s.x + Math.cos(la) * 1.72, lz = s.z + Math.sin(la) * 1.72;
      var lp = new THREE.Mesh(trackD(new THREE.CylinderGeometry(0.045, 0.06, 0.95, 6)), darkMat);
      lp.position.set(lx, platY + 0.47, lz);
      lp.castShadow = true;
      regionRoot.add(lp);
      var lh = new THREE.Mesh(trackD(new THREE.SphereGeometry(0.09, 8, 6)), winMat);
      lh.position.set(lx, platY + 1.0, lz);
      regionRoot.add(lh);
      lampPts.push({ x: lx, y: platY + 1.0, z: lz });
    }
    /* 点击热区（隐形柱） */
    var hit = new THREE.Mesh(trackD(new THREE.CylinderGeometry(2.3, 2.3, 3.4, 8)),
      new THREE.MeshBasicMaterial({ visible: false }));
    hit.position.set(s.x, platY + 1.4, s.z);
    hit.userData.siteId = s.id;
    regionRoot.add(hit);
    hitMeshes.push(hit);
    /* 名签 DOM */
    var el = document.createElement('div');
    el.className = 'place-tag' + (s.isWonder ? ' wonder' : '') + (s.isHome ? ' home' : '');
    var glyph = document.createElement('span'); glyph.className = 's-glyph';
    glyph.textContent = s.isWonder ? '✦' : (s.isHome ? '⌂' : '');
    if (glyph.textContent) el.appendChild(glyph);
    var dot = document.createElement('span'); dot.className = 's-dot';
    el.appendChild(dot);
    el.appendChild(document.createTextNode(s.name || s.id));
    el.addEventListener('click', function (ev) {
      ev.stopPropagation();
      if (flags.interactive) bridge('onTapSite', s.id);
    });
    document.body.appendChild(el);
    tagEntries.push({ el: el, x: s.x, y: (s.markerTop || platY + 2.4) + 0.6, z: s.z, site: s });
  });
  /* 灯晕点（GPU 侧伪随机相位·契约 §2.3） */
  if (lampPts.length) {
    var gpos = new Float32Array(lampPts.length * 3);
    lampPts.forEach(function (l, i) { gpos[i * 3] = l.x; gpos[i * 3 + 1] = l.y; gpos[i * 3 + 2] = l.z; });
    var gg = new THREE.BufferGeometry();
    gg.setAttribute('position', new THREE.BufferAttribute(gpos, 3));
    var gm = new THREE.ShaderMaterial({
      uniforms: { uTime: { value: 0 }, uGlow: { value: 1 }, uScale: { value: 260 } },
      transparent: true, depthWrite: false, blending: THREE.AdditiveBlending,
      vertexShader: [
        'uniform float uTime, uScale; varying float vA;',
        'void main(){',
        '  vec4 mv = modelViewMatrix * vec4(position, 1.0);',
        '  vec2 wxz = position.xz;',
        '  float key = fract(sin(dot(floor(wxz * 7.0 + 0.5), vec2(12.9898, 78.233))) * 43758.5453);',
        '  float flick = 0.85 + 0.15 * sin(uTime * 5.0 + key * 43.0);',
        '  vA = uGlow * flick;',
        '  gl_PointSize = 3.4 * uScale / max(1.0, -mv.z);',
        '  gl_Position = projectionMatrix * mv;',
        '}'
      ].join('\n'),
      fragmentShader: [
        'varying float vA;',
        'void main(){',
        '  vec2 p = gl_PointCoord * 2.0 - 1.0;',
        '  float d = max(0.0, 1.0 - dot(p, p));',
        '  float a = d * d * vA;',
        '  gl_FragColor = vec4(vec3(1.0, 0.78, 0.45) * a, a);',
        '}'
      ].join('\n')
    });
    trackD(gm);
    glowMat = gm;
    var gp = new THREE.Points(gg, gm);
    gp.frustumCulled = false; gp.renderOrder = 5;
    trackD(gg);
    regionRoot.add(gp);
  }
  /* 灯下光池（恒亮·吃 glowA） */
  if (lampPts.length) {
    var pos2 = [], uv2 = [], idx2 = [];
    lampPts.forEach(function (l) {
      var R = 1.15, base = pos2.length / 3;
      pos2.push(l.x - R, l.y - 0.98, l.z - R, l.x + R, l.y - 0.98, l.z - R, l.x + R, l.y - 0.98, l.z + R, l.x - R, l.y - 0.98, l.z + R);
      uv2.push(0, 0, 1, 0, 1, 1, 0, 1);
      idx2.push(base, base + 2, base + 1, base, base + 3, base + 2);
    });
    var pg = new THREE.BufferGeometry();
    pg.setAttribute('position', new THREE.Float32BufferAttribute(pos2, 3));
    pg.setAttribute('uv', new THREE.Float32BufferAttribute(uv2, 2));
    pg.setIndex(idx2);
    var pm = new THREE.ShaderMaterial({
      uniforms: { uGlow: { value: 1 } },
      transparent: true, depthWrite: false, blending: THREE.AdditiveBlending,
      vertexShader: 'varying vec2 vUv; void main(){ vUv = uv; gl_Position = projectionMatrix * modelViewMatrix * vec4(position,1.0); }',
      fragmentShader: [
        'uniform float uGlow; varying vec2 vUv;',
        'void main(){',
        '  vec2 p = vUv * 2.0 - 1.0;',
        '  float d = max(0.0, 1.0 - dot(p, p));',
        '  float a = d * d * uGlow * 0.4;',
        '  gl_FragColor = vec4(vec3(1.0, 0.72, 0.4) * a, a);',
        '}'
      ].join('\n')
    });
    trackD(pm); trackD(pg);
    poolMat = pm;
    var pool = new THREE.Mesh(pg, pm);
    pool.renderOrder = 1;
    regionRoot.add(pool);
  }
  /* 选中环 */
  selRing = new THREE.Mesh(trackD(new THREE.RingGeometry(2.05, 2.42, 36).rotateX(-PI / 2)),
    new THREE.MeshBasicMaterial({ color: new THREE.Color(st.warm[0], st.warm[1], st.warm[2]), transparent: true, opacity: 0.85, depthWrite: false, side: THREE.DoubleSide }));
  selRing.visible = false;
  selRing.renderOrder = 2;
  regionRoot.add(selRing);
}
var glowMat = null, poolMat = null;
var hitMeshes = [];

/* ---- 5g. 漂云 / 萤火 ---- */
var cloudSprites = [], fireflies = null, ffBase = null, ffPh = null;
function buildAmbient() {
  cloudSprites = [];
  var rng = mulberry32(st._sd + 616);
  for (var i = 0; i < 5; i++) {
    var m = new THREE.SpriteMaterial({ map: puffTex, transparent: true, depthWrite: false, opacity: 0.5 });
    var sp = new THREE.Sprite(m);
    var a = rng() * PI * 2, r = 34 + rng() * 26;
    sp.position.set(Math.cos(a) * r, -5 + rng() * 14, Math.sin(a) * r);
    var s = 7 + rng() * 5;
    sp.scale.set(s, s * 0.42, 1);
    sp.userData = { a: a, r: r, sp2: 0.004 + rng() * 0.004 };
    regionRoot.add(sp);
    cloudSprites.push(sp);
  }
  var FF_N = 40;
  var pos = new Float32Array(FF_N * 3);
  ffBase = new Float32Array(FF_N * 3); ffPh = new Float32Array(FF_N);
  var okN = 0, guard = 0;
  while (okN < FF_N && guard < 600) {
    guard++;
    var fx = (rng() * 2 - 1) * (PED_HALF - 3), fz = (rng() * 2 - 1) * (PED_HALF - 3);
    var fh = heightAtWorld(fx, fz).h;
    if (fh < 0.5) continue;
    ffBase[okN * 3] = fx; ffBase[okN * 3 + 1] = fh + 0.7 + rng() * 1.6; ffBase[okN * 3 + 2] = fz;
    ffPh[okN] = rng() * 6.28;
    pos[okN * 3] = fx; pos[okN * 3 + 1] = ffBase[okN * 3 + 1]; pos[okN * 3 + 2] = fz;
    okN++;
  }
  var fg = new THREE.BufferGeometry();
  fg.setAttribute('position', new THREE.BufferAttribute(pos, 3));
  var fm = new THREE.PointsMaterial({ map: glowTex, size: 0.4, transparent: true, depthWrite: false, opacity: 0, color: 0xFFC878, blending: THREE.AdditiveBlending });
  trackD(fm); trackD(fg);
  fireflies = new THREE.Points(fg, fm);
  fireflies.frustumCulled = false;
  regionRoot.add(fireflies);
}
function tickAmbient(dt, simT) {
  for (var i = 0; i < cloudSprites.length; i++) {
    var sp = cloudSprites[i];
    sp.userData.a += sp.userData.sp2 * dt;
    sp.position.x = Math.cos(sp.userData.a) * sp.userData.r;
    sp.position.z = Math.sin(sp.userData.a) * sp.userData.r;
  }
  if (fireflies && fireflies.material.opacity > 0.02) {
    var pos = fireflies.geometry.attributes.position;
    for (var i2 = 0; i2 < ffPh.length; i2++) {
      pos.setY(i2, ffBase[i2 * 3 + 1] + Math.sin(simT * 1.3 + ffPh[i2]) * 0.3);
      pos.setX(i2, ffBase[i2 * 3] + Math.sin(simT * 0.7 + ffPh[i2] * 1.7) * 0.4);
    }
    pos.needsUpdate = true;
  }
  if (crystal) { crystal.rotation.y += dt * 0.7; crystal.position.y = st.padH + 1.5 + Math.sin(simT * 1.1) * 0.12; }
  if (selRing && selRing.visible) {
    var k = 1 + Math.sin(simT * 3.4) * 0.04;
    selRing.scale.set(k, 1, k);
  }
}

/* ---- 5h. 整场重建 ---- */
function buildContinent(j) {
  disposeRegion();
  hitMeshes.length = 0;
  crystal = null; glowMat = null; poolMat = null;
  curRegion = j;
  st = j.style;
  st._sd = Math.round(Math.abs(st.seed) * 1000) | 0;
  sites = (j.sites || []).slice();
  buildHeightGrid();
  /* 氛围：灯/雾/光从 style 读（暖罩染 warm·雾霭 haze·辉光 glowA） */
  sun.color.setRGB(st.warm[0], st.warm[1] * 0.82, st.warm[2] * 0.6);
  sun.intensity = 1.12;
  sun.position.set(Math.sin(4.1) * Math.cos(0.55), Math.sin(0.55), Math.cos(4.1) * Math.cos(0.55)).multiplyScalar(70);
  hemi.color.setRGB(lerp(st.sky[1].rgb[0], 1, 0.2), lerp(st.sky[1].rgb[1], 0.92, 0.2), lerp(st.sky[1].rgb[2], 0.85, 0.2));
  hemi.groundColor.setRGB(st.g1[0] * 0.5, st.g1[1] * 0.5, st.g1[2] * 0.45);
  hemi.intensity = 0.8;
  scene.fog = new THREE.Fog(new THREE.Color(st.haze[0], st.haze[1], st.haze[2]), 44, 175);
  buildTerrainTexMESH = buildTerrainTex();
  buildTerrainMesh();
  buildWater();
  buildPedestal();
  buildVegetation();
  buildSites();
  buildAmbient();
  /* 天空 + 远山 */
  if (skyMesh.material.map) skyMesh.material.map.dispose();
  skyMesh.material.map = buildSkyTex(st.sky, st.warm, st.haze);
  skyMesh.material.needsUpdate = true;
  hillMeshes.forEach(function (m) { scene.remove(m); });
  hillMeshes = buildHills(st.haze);
  hillMeshes.forEach(function (m) { scene.add(m); });
  /* presence 初始 */
  applyPresence(j.presence || null);
}

/* ================= 6. presence 徽记（「TA 在这里」·两态） ================= */
function applyPresence(p) {
  curPresence = p;
  tagEntries.forEach(function (t) {
    var el = t.el;
    var old = el.querySelector('.here-chip');
    if (old) old.parentNode.removeChild(old);
    el.classList.remove('presence-here', 'presence-grey');
    if (!p) return;
    if (p.traveling) {
      var chip = document.createElement('span');
      chip.className = 'here-chip grey';
      chip.textContent = '旅途中';
      el.appendChild(chip);
      el.classList.add('presence-grey');
    } else if (p.cityId && t.site && t.site.id === p.cityId) {
      var chip2 = document.createElement('span');
      chip2.className = 'here-chip';
      chip2.textContent = 'TA 在这里';
      el.appendChild(chip2);
      el.classList.add('presence-here');
    }
  });
  dirty = true;
}
var curPresence = null;

/* ================= 7. 相机与手势（契约 §4.2 常数照抄） ================= */
var DEF_POSE = { yaw: 0.78, pitch: 0.72, dist: 34, tx: 0, tz: 0 };
var pose = { yaw: 0.78, pitch: 0.72, dist: 34, tx: 0, tz: 0, tdist: 34 };
var flags = { reduceMotion: false, staticMode: false, interactive: true };
var camTween = null, gesturing = false, dirty = true, inited = false, firstFrameSent = true;
var inertia = { vx: 0, vz: 0, active: false };
var lastAct = 0, simT = 0;

function clampPitch(v) { return clamp(v, 0.30, 1.22); }
function clampDist(v) { return clamp(v, 8, 60); }
function softClampAxis(v) {
  var L = 18, M = 22; /* 契约：软边 ±18·硬止 ±22 */
  if (v > L) v = L + (v - L) * 0.35; /* 越界阻尼 0.35 */
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
  if (camTween.keys.indexOf('dist') >= 0) pose.tdist = camTween.to.dist;
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
  if (Math.hypot(inertia.vx, inertia.vz) < 0.05) { inertia.active = false; snapBackTarget(); return; }
  pose.tx = softClampAxis(pose.tx + inertia.vx * dt);
  pose.tz = softClampAxis(pose.tz + inertia.vz * dt);
  dirty = true;
}
function snapBackTarget() {
  var tx = clamp(pose.tx, -18, 18), tz = clamp(pose.tz, -18, 18); /* 松手弹回软边 */
  if (tx !== pose.tx || tz !== pose.tz) startTween(['tx', 'tz'], { tx: tx, tz: tz }, 260, easeOutCubic);
}
var raycaster = new THREE.Raycaster();
var _ndc = new THREE.Vector2();
function groundPoint(px, py) { /* 地面锚定平面 y=1.2（= 相机 target 高度） */
  _ndc.set((px / window.innerWidth) * 2 - 1, -(py / window.innerHeight) * 2 + 1);
  raycaster.setFromCamera(_ndc, camera);
  var o = raycaster.ray.origin, d = raycaster.ray.direction;
  if (d.y > -1e-4) return { x: pose.tx, z: pose.tz };
  var t = -(o.y - 1.2) / d.y;
  if (t < 0 || t > 500) return { x: pose.tx, z: pose.tz };
  return { x: o.x + d.x * t, z: o.z + d.z * t };
}

var ptrs = new Map();
var panPrev = null, pinchPrev = null, tapInfo = null, vel = { x: 0, z: 0, t: 0 };
var ozRatio = 1, ozFired = false, odRatio = 1, odFired = false;
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
    ozRatio = 1; ozFired = false; odRatio = 1; odFired = false;
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
    var st2 = pinchState();
    if (pinchPrev) {
      /* 真机批手感修正：GL 基准 ratio = prevSpan/span（ContinentGLView:151）——张开→ratio<1→拉近。
         原写反致缩放方向倒置；翻转后 overzoom/overdive 两分支语义自动归位。 */
      var ratio = pinchPrev.d / Math.max(1, st2.d);
      if (pose.dist >= 60 - 0.01 && ratio > 1) {
        ozRatio *= ratio; /* dist 已顶格继续外捏：累积比例 */
        if (ozRatio >= 1.10 && !ozFired) { ozFired = true; bridge('onReturnGesture'); }
      }
      if (pose.dist <= 8 + 0.01 && ratio < 1) {
        odRatio *= ratio; /* dist 已到底继续内捏：累积比例 */
        if (odRatio <= 0.90 && !odFired) { odFired = true; bridge('onTownDive'); }
      }
      var nd = clampDist(pose.dist * ratio);
      var mid = groundPoint(st2.mx, st2.my);
      var f = clamp(1 - nd / pose.dist, -1, 1) * 0.9; /* 朝两指中点方向 */
      pose.tx = softClampAxis(pose.tx + (mid.x - pose.tx) * f);
      pose.tz = softClampAxis(pose.tz + (mid.z - pose.tz) * f);
      pose.dist = nd; pose.tdist = nd;
      pose.yaw += st2.ang - pinchPrev.ang;               /* 双指旋转 = yaw */
      pose.pitch = clampPitch(pose.pitch + (st2.my - pinchPrev.my) * 0.004); /* 双指同向上下滑 = pitch */
      dirty = true;
    }
    pinchPrev = st2;
  }
});
function pinchState() {
  var a = [], it = ptrs.values();
  for (var p = it.next(); !p.done; p = it.next()) a.push(p.value);
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
    ozRatio = 1; ozFired = false; odRatio = 1; odFired = false; /* 松手复位 */
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
  pose.tdist = pose.dist;
  dirty = true;
}, { passive: false });
cvs.addEventListener('contextmenu', function (e) { e.preventDefault(); });

function handleTap(px, py) {
  if (!inited) return;
  _ndc.set((px / window.innerWidth) * 2 - 1, -(py / window.innerHeight) * 2 + 1);
  raycaster.setFromCamera(_ndc, camera);
  var hits = raycaster.intersectObjects(hitMeshes, false);
  if (hits.length) { bridge('onTapSite', hits[0].object.userData.siteId); return; }
  bridge('onTapEmpty'); /* 点空地 */
}

function sendPose() {
  var p = { yaw: r4(pose.yaw), pitch: r4(pose.pitch), dist: r4(pose.dist), tx: r4(pose.tx), tz: r4(pose.tz), tdist: r4(pose.tdist) };
  if (MOCK_MODE) window.__lastPose = p;
  bridge('onPose', JSON.stringify(p));
}
setInterval(function () { if (inited && !document.hidden) sendPose(); }, 500); /* 心跳 */

/* ================= 8. window.worldWeb（桥·八入·函数名逐字） ================= */
window.worldWeb = {
  init: function (continentJson) {
    var j = (typeof continentJson === 'string') ? JSON.parse(continentJson) : continentJson;
    buildContinent(j || {});
    pose.yaw = DEF_POSE.yaw; pose.tx = DEF_POSE.tx; pose.tz = DEF_POSE.tz; pose.tdist = DEF_POSE.dist;
    if (flags.reduceMotion) {
      pose.pitch = DEF_POSE.pitch; pose.dist = DEF_POSE.dist; camTween = null;
    } else { /* 契约入场：yaw 0.78·pitch 1.12→0.72·dist 95→34 约 1.8s 缓出俯冲 */
      pose.pitch = 1.12; pose.dist = 95;
      startTween(['pitch', 'dist'], { pitch: DEF_POSE.pitch, dist: DEF_POSE.dist }, 1800, easeOutCubic);
    }
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
      pose.yaw = DEF_POSE.yaw; pose.tx = DEF_POSE.tx; pose.tz = DEF_POSE.tz; pose.tdist = DEF_POSE.dist;
      if (flags.reduceMotion) { pose.pitch = DEF_POSE.pitch; pose.dist = DEF_POSE.dist; }
      else {
        pose.pitch = 1.12; pose.dist = 95;
        startTween(['pitch', 'dist'], { pitch: DEF_POSE.pitch, dist: DEF_POSE.dist }, 1800, easeOutCubic);
      }
    } else {
      if (typeof poseJson.yaw === 'number') pose.yaw = poseJson.yaw;
      if (typeof poseJson.pitch === 'number') pose.pitch = clampPitch(poseJson.pitch);
      if (typeof poseJson.tx === 'number') pose.tx = softClampAxis(poseJson.tx);
      if (typeof poseJson.tz === 'number') pose.tz = softClampAxis(poseJson.tz);
      if (typeof poseJson.tdist === 'number') { pose.tdist = clampDist(poseJson.tdist); pose.dist = pose.tdist; }
      else if (typeof poseJson.dist === 'number') { pose.dist = clampDist(poseJson.dist); pose.tdist = pose.dist; }
    }
    dirty = true;
  },
  playPose: function (poseJson, ms) {
    var p = (typeof poseJson === 'string') ? JSON.parse(poseJson) : (poseJson || {});
    var keys = [], to = {};
    ['yaw', 'pitch', 'dist', 'tx', 'tz'].forEach(function (k) {
      if (typeof p[k] === 'number') { keys.push(k); to[k] = p[k]; } /* dist 4.5 属契约转场值：补间不钳 */
    });
    if (!keys.length) return;
    if (to.dist !== undefined) pose.tdist = to.dist;
    inertia.active = false;
    startTween(keys, to, ms || 500, easeInOutCubic);
  },
  setPresence: function (presenceJson) {
    var p = (typeof presenceJson === 'string') ? JSON.parse(presenceJson) : presenceJson;
    applyPresence(p || null);
  },
  focusSite: function (id) {
    var s = null;
    for (var i = 0; i < sites.length; i++) if (sites[i].id === id) { s = sites[i]; break; }
    if (!s) return;
    if (selRing) {
      selRing.position.set(s.x, st.padH + 0.08, s.z);
      selRing.visible = true;
    }
    startTween(['tx', 'tz', 'dist'], { tx: s.x, tz: s.z, dist: 10.5 }, 900, easeInOutCubic); /* tDist→10.5 + target 滑到站位 */
    dirty = true;
  },
  clearFocus: function () {
    if (selRing) selRing.visible = false;
    var td = Math.max(pose.tdist, 26); /* tDist 回 max(当前,26) */
    startTween(['dist'], { dist: td }, 700, easeInOutCubic);
    dirty = true;
  },
  closeSheet: function () {
    if (selRing) selRing.visible = false;
    var td = Math.max(pose.tdist, 34); /* tDist 回 max(当前,34) */
    startTween(['dist'], { dist: td }, 700, easeInOutCubic);
    dirty = true;
  }
};

/* ================= 9. 投影排版（名签跟随·屏外隐藏） ================= */
var _pv = new THREE.Vector3();
function layoutTags() {
  var w = window.innerWidth, h = window.innerHeight;
  for (var i = 0; i < tagEntries.length; i++) {
    var it = tagEntries[i];
    _pv.set(it.x, it.y, it.z).project(camera);
    if (_pv.z > 1 || _pv.x < -1.15 || _pv.x > 1.15 || _pv.y < -1.15 || _pv.y > 1.15) {
      it.el.style.display = 'none';
      continue;
    }
    var sx = (_pv.x * 0.5 + 0.5) * w, sy = (-_pv.y * 0.5 + 0.5) * h;
    var dist = camera.position.distanceTo(_pv.set(it.x, it.y, it.z));
    var k = clamp(30 / Math.max(6, dist), 0.65, 1.15);
    it.el.style.display = '';
    it.el.style.left = sx.toFixed(1) + 'px';
    it.el.style.top = sy.toFixed(1) + 'px';
    it.el.style.transform = 'translate(-50%,-100%) scale(' + k.toFixed(3) + ')';
    it.el.style.zIndex = String(1000 - Math.round(dist * 10));
  }
}

/* ================= 10. MOCK 双区（仅 ?mock=1 自测用·App 不引用） ================= */
var MOCK_REGIONS = {
  yunze: {
    regionId: 'yunze', regionName: '云泽大区', isHome: true,
    style: {
      styleKey: 'willow_mist', seed: 11.7, sea: 0.46, amp: 5.2, coast: 0.60, padH: 1.5,
      terrace: false, snowLine: 4.4, treeN: 60, trunk: 0.7, treeR: 0.8, treeH: 1.5,
      warm: [1, 0.86, 0.70], haze: [0.79, 0.54, 0.46],
      water: [0.30, 0.52, 0.62], bed: [0.20, 0.24, 0.26], beach: [0.87, 0.78, 0.60],
      g1: [0.55, 0.66, 0.38], g2: [0.68, 0.74, 0.44],
      cliff: [0.55, 0.55, 0.55], snow: [0.95, 0.95, 0.93], rock: [0.55, 0.52, 0.48], earth: [0.62, 0.48, 0.34],
      leafs: [[0.42, 0.60, 0.32], [0.62, 0.70, 0.36]],
      sky: [
        { pos: 0.0, rgb: [0.20, 0.27, 0.44] }, { pos: 0.28, rgb: [0.42, 0.42, 0.60] },
        { pos: 0.55, rgb: [0.72, 0.55, 0.56] }, { pos: 0.78, rgb: [0.94, 0.68, 0.50] },
        { pos: 1.0, rgb: [1.0, 0.84, 0.64] }],
      glowA: 1.0
    },
    sites: [
      { id: 'city_yunye', name: '云野镇', isWonder: false, isHome: true, curated: true, x: -3.2, z: 5.6, markerTop: 3.1, buildingCount: 3 },
      { id: 'wonder_jinghu', name: '镜湖奇观', isWonder: true, isHome: false, curated: true, x: 7.5, z: -4.5, markerTop: 4.4, buildingCount: 0 },
      { id: 'city_luzhou', name: '芦洲埠', isWonder: false, isHome: false, curated: false, x: -9.5, z: -7.0, markerTop: 2.6, buildingCount: 2 }
    ],
    presence: { cityId: 'city_yunye', traveling: false, homeCityId: 'city_yunye' }
  },
  hefeng: {
    regionId: 'hefeng', regionName: '赫风大区', isHome: false,
    style: {
      styleKey: 'ochre_dry', seed: 47.3, sea: 0.14, amp: 7.4, coast: 0.38, padH: 1.5,
      terrace: true, snowLine: 99, treeN: 26, trunk: 0.9, treeR: 0.7, treeH: 1.1,
      warm: [1, 0.82, 0.58], haze: [0.88, 0.60, 0.40],
      water: [0.38, 0.62, 0.60], bed: [0.42, 0.36, 0.28], beach: [0.88, 0.76, 0.55],
      g1: [0.72, 0.62, 0.34], g2: [0.80, 0.68, 0.38],
      cliff: [0.72, 0.50, 0.34], snow: [0.95, 0.93, 0.88], rock: [0.62, 0.48, 0.36], earth: [0.70, 0.42, 0.26],
      leafs: [[0.66, 0.58, 0.30]], /* 真表 ochre_dry 只有 1 片叶色（R1 返修：mock 必须覆盖单片区） */
      sky: [
        { pos: 0.0, rgb: [0.25, 0.36, 0.56] }, { pos: 0.30, rgb: [0.55, 0.55, 0.66] },
        { pos: 0.55, rgb: [0.86, 0.66, 0.52] }, { pos: 0.80, rgb: [0.98, 0.80, 0.56] },
        { pos: 1.0, rgb: [1.0, 0.88, 0.68] }],
      glowA: 0.4
    },
    sites: [
      { id: 'city_tanshi', name: '坛石城', isWonder: false, isHome: false, curated: true, x: 2.0, z: 3.0, markerTop: 3.4, buildingCount: 3 },
      { id: 'wonder_fenglin', name: '风陵奇观', isWonder: true, isHome: false, curated: true, x: -7.0, z: 6.5, markerTop: 4.6, buildingCount: 0 },
      { id: 'city_yanyao', name: '岩窑驿', isWonder: false, isHome: false, curated: false, x: 9.0, z: -6.0, markerTop: 2.8, buildingCount: 1 }
    ],
    presence: { cityId: null, traveling: false, homeCityId: 'city_yunye' }
  },
  jueyu: {
    regionId: 'jueyu', regionName: '蕨雨大区', isHome: false,
    style: {
      styleKey: 'fern_rain', seed: 23.1, sea: 0.40, amp: 4.2, coast: 0.72, padH: 1.5,
      terrace: false, snowLine: 99, treeN: 88, trunk: 0.65, treeR: 0.9, treeH: 1.7,
      warm: [0.88, 0.95, 0.88], haze: [0.62, 0.72, 0.68],
      water: [0.26, 0.46, 0.47], bed: [0.16, 0.22, 0.22], beach: [0.72, 0.74, 0.56],
      g1: [0.36, 0.54, 0.34], g2: [0.50, 0.64, 0.40],
      cliff: [0.44, 0.50, 0.48], snow: [0.92, 0.94, 0.92], rock: [0.46, 0.50, 0.46], earth: [0.44, 0.40, 0.30],
      leafs: [[0.30, 0.52, 0.28], [0.42, 0.62, 0.30], [0.56, 0.70, 0.34]], /* 真表 fern_rain 有 3 片叶色 */
      sky: [
        { pos: 0.0, rgb: [0.38, 0.46, 0.55] }, { pos: 0.30, rgb: [0.55, 0.62, 0.64] },
        { pos: 0.55, rgb: [0.72, 0.76, 0.70] }, { pos: 0.80, rgb: [0.86, 0.85, 0.72] },
        { pos: 1.0, rgb: [0.94, 0.90, 0.76] }],
      glowA: 0.55
    },
    sites: [
      { id: 'city_jueyu', name: '蕨雨坞', isWonder: false, isHome: false, curated: true, x: -2.5, z: -3.5, markerTop: 3.0, buildingCount: 3 },
      { id: 'wonder_tingyu', name: '听雨奇观', isWonder: true, isHome: false, curated: true, x: 6.5, z: 5.5, markerTop: 4.2, buildingCount: 0 },
      { id: 'city_qingxi', name: '青溪驿', isWonder: false, isHome: false, curated: false, x: 8.5, z: -6.5, markerTop: 2.6, buildingCount: 1 }
    ],
    presence: { cityId: 'city_jueyu', traveling: false, homeCityId: 'city_yunye' }
  }
};
if (MOCK_MODE) {
  var _firePtr = function (type, id, x, y) {
    cvs.dispatchEvent(new PointerEvent(type, {
      pointerId: id, pointerType: 'touch', isPrimary: id === 1,
      clientX: x, clientY: y, bubbles: true, cancelable: true
    }));
  };
  window.continentMockGestures = {
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
    pinch: function (cx, cy, from, to, steps) {
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
        var dy = 60 * t, rot = 0.5 * t;
        var c = Math.cos(rot), s = Math.sin(rot);
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
  var mockUI = document.getElementById('mockUI');
  mockUI.style.display = 'flex';
  window.MOCK_REGIONS = MOCK_REGIONS; /* 自动化自测读（仅 ?mock=1） */
  var curRegionKey = 'yunze';
  function loadMock(key) {
    curRegionKey = key;
    window.worldWeb.init(MOCK_REGIONS[key]);
    document.querySelectorAll('#mockRegions .mbtn').forEach(function (b) {
      b.classList.toggle('on', b.dataset.r === key);
    });
  }
  var rowR = document.getElementById('mockRegions');
  /* 三区覆盖 leafs 1/2/3 片（R1 返修自测项：单片区必须可复现） */
  [['yunze', '云泽大区·水乡'], ['hefeng', '赫风大区·梯田'], ['jueyu', '蕨雨大区·霖林']].forEach(function (d) {
    var b = document.createElement('button');
    b.className = 'mbtn'; b.dataset.r = d[0]; b.textContent = d[1];
    b.addEventListener('click', function () { loadMock(d[0]); });
    rowR.appendChild(b);
  });
  var rowP = document.getElementById('mockPresence');
  [['在云野镇', function () { window.worldWeb.setPresence({ cityId: 'city_yunye', traveling: false, homeCityId: 'city_yunye' }); }],
   ['旅行中', function () { window.worldWeb.setPresence({ cityId: null, traveling: true, homeCityId: 'city_yunye' }); }],
   ['无 presence', function () { window.worldWeb.setPresence(null); }]].forEach(function (d) {
    var b = document.createElement('button');
    b.className = 'mbtn'; b.textContent = d[0];
    b.addEventListener('click', d[1]);
    rowP.appendChild(b);
  });
  var rowA = document.getElementById('mockActions');
  [['捏出演示', function () { pose.dist = 60; window.continentMockGestures.pinch(280, 300, 190, 100, 18); }], /* 手感修正后：顶格继续并拢=回星球 */
   ['捏入演示', function () { pose.dist = 8; window.continentMockGestures.pinch(280, 300, 100, 190, 18); }], /* 到底继续张开=进镇 */
   ['聚焦云野镇', function () { window.worldWeb.focusSite('city_yunye'); }],
   ['clearFocus', function () { window.worldWeb.clearFocus(); }],
   ['closeSheet', function () { window.worldWeb.closeSheet(); }],
   ['旋转俯仰', function () { window.continentMockGestures.twoFinger(280, 300, 12); }]
  ].forEach(function (d) {
    var b = document.createElement('button');
    b.className = 'mbtn'; b.textContent = d[0];
    b.addEventListener('click', d[1]);
    rowA.appendChild(b);
  });
  loadMock('yunze');
  /* ?region=hefeng 指定初始区；?pose=yaw,pitch,dist[,tx,tz] 指定相机（无控制台复现场景用） */
  var mq = /[?&]region=(\w+)/.exec(location.search);
  if (mq && MOCK_REGIONS[mq[1]]) loadMock(mq[1]);
  var mp = /[?&]pose=([-\d.,]+)/.exec(location.search);
  if (mp) {
    var pv = mp[1].split(',').map(Number);
    window.worldWeb.restorePose({ yaw: pv[0], pitch: pv[1], dist: pv[2], tx: pv[3] || 0, tz: pv[4] || 0 });
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
  var cy = 1.2 + pose.dist * Math.sin(pose.pitch); /* 相机看向 target(tx, 1.2, tz) */
  var rh = pose.dist * Math.cos(pose.pitch);
  camera.position.set(pose.tx + rh * Math.sin(pose.yaw), cy, pose.tz + rh * Math.cos(pose.yaw));
  camera.lookAt(pose.tx, 1.2, pose.tz);
  if (glowMat) { glowMat.uniforms.uTime.value = simT; glowMat.uniforms.uGlow.value = st ? st.glowA : 1; }
  if (poolMat) poolMat.uniforms.uGlow.value = st ? Math.min(1.2, st.glowA) : 1;
  if (waterMat) waterMat.uniforms.uCol.value.setRGB(st.water[0], st.water[1], st.water[2]);
  if (fireflies) fireflies.material.opacity = st ? clamp(st.glowA - 0.25, 0, 1) * 0.8 : 0;
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
  layoutTags();
}
function tick(now) {
  requestAnimationFrame(tick);
  var dt = Math.min(0.1, (now - lastTs) / 1000);
  lastTs = now;
  if (pageHidden || ctxLost) return;
  var frozen = flags.reduceMotion || flags.staticMode;
  if (!frozen) { simT += dt; tickAmbient(dt, simT); }
  if (waterMat) waterMat.uniforms.uTime.value = simT;
  tickCameraTween(dt);
  tickInertia(dt);
  /* 静置 2.2s 后极慢自转（reduceMotion / staticMode 免） */
  if (!flags.reduceMotion && !flags.staticMode && inited && !gesturing && !camTween &&
      performance.now() - lastAct > 2200) {
    pose.yaw += 0.00035;
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
        pose: [r4(pose.yaw), r4(pose.pitch), r4(pose.dist), r4(pose.tx), r4(pose.tz), r4(pose.tdist)],
        region: curRegion ? curRegion.regionId : null,
        tags: [tagEntries.filter(function (t) { return t.el.style.display !== 'none'; }).length, tagEntries.length],
        pres: curPresence ? [curPresence.cityId, curPresence.traveling] : null,
        sel: selRing ? selRing.visible : false,
        ev: (window.__tlog || []).filter(function (t) { return t.n !== 'onPose'; }).slice(-5)
          .map(function (t) { return t.n + ':' + (typeof t.a === 'string' ? t.a.slice(0, 48) : ''); }),
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
  dirty = true;
});

/* 纸感颗粒（DOM 层 · 48² 噪声 dataURL 平铺） */
(function grainLayer() {
  var S = 48, c = cnv(S, S), x = c[1];
  var img = x.createImageData(S, S), d = img.data;
  for (var i = 0; i < S * S; i++) {
    d[i * 4] = 255; d[i * 4 + 1] = 255; d[i * 4 + 2] = 255; d[i * 4 + 3] = Math.random() * 30;
  }
  x.putImageData(img, 0, 0);
  document.getElementById('grain').style.background = 'url(' + c[0].toDataURL() + ')';
})();

/* 就绪：App 在 init 之前就能收到 */
bridge('onReady');

})();
