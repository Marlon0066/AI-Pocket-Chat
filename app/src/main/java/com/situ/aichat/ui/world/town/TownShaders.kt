package com.situ.aichat.ui.world.town

/**
 * 小镇盒景 GLSL 源串（W9c 图纸 §4.1E·GLES2）。
 *
 * [T_FS_LIT]：从对版 demo `design/world/town-3d-demo.html` L196-201 **逐行移植**——warm 内嵌、暮色雾数值与
 * 表达式一字不改（图纸 §9 禁改·对版脚本逐行零漂移）。[T_BG_FS]：天空 **7 停靠**竖向渐变 + 椭圆辉光（demo
 * DOM `.sky`/`.glow` → GL·值同源·中心 (0.50,0.720)·半径 0.715×0.200·α 0.5→0.15→0·screen 混合同 9b 公式）。
 * 顶点着色器与 emis 片元**复用** [com.situ.aichat.ui.world.continent.ContinentShaders].C_VS / C_FS_EMIS（不改）。
 */
internal object TownShaders {

    /**
     * 小镇本地顶点着色器（§3.3）= [com.situ.aichat.ui.world.continent.ContinentShaders].C_VS 原文 + 一条世界坐标
     * varying `vW`。台阶1 的材质采样按世界坐标平面推 uv（不加 UV 顶点属性 → 几何格式与 [TriStream] 零改），
     * 故 lit 六桶 / emis 辉光 / 软影三程序共用本 VS；大陆侧 C_VS **零碰**。
     */
    const val T_VS_WORLD = """
attribute vec3 aPos;attribute vec3 aNor;attribute vec3 aCol;
uniform mat4 uMVP;varying vec3 vN;varying vec3 vC;varying float vD;varying vec3 vW;
void main(){vN=aNor;vC=aCol;vW=aPos;vec4 p=uMVP*vec4(aPos,1.0);vD=p.w;gl_Position=p;}
"""

    /**
     * 地形/建筑/环境件片元（demo:L196-201 移植 + 台阶0 色温 + 台阶1 材质·§3.3 锁定表达式）：光照/雾行一字不改；
     * 旧 `uNightDim` 标量压暗换成 `uSceneTint` 三分量色温（§4.1）；材质 uv 由**世界坐标平面**推——顶/底面
     * （|n.y|>0.6）取 xz 平面，立面按 |n.x|/|n.z| 择 zy 或 xy 平面，故不需 UV 顶点属性。
     * 双轨兜底：`uTexMix=0` 时 `mix(vec3(1.0), …, 0.0)` 恒等 ⇒ 该桶与无贴图字节级同源。
     */
    const val T_FS_LIT = """
precision mediump float;varying vec3 vN;varying vec3 vC;varying float vD;varying vec3 vW;
uniform vec3 uSun;
uniform vec3 uFogCol;
uniform vec3 uSceneTint;
uniform sampler2D uTex;
uniform float uTexScale;
uniform float uTexMix;
void main(){float d=max(dot(normalize(vN),normalize(uSun)),0.0);
 vec3 warm=vec3(1.0,0.86,0.70);vec3 col=vC*(0.42+0.75*d*warm);
 col=mix(col,uFogCol,clamp((vD-26.0)/34.0,0.0,0.45));
 vec2 uv=(abs(vN.y)>0.6)?(vW.xz*uTexScale):(mix(vW.zy,vW.xy,step(abs(vN.x),abs(vN.z)))*uTexScale);
 vec3 tex=texture2D(uTex,uv).rgb;
 col*=mix(vec3(1.0),tex*2.0,uTexMix);
 gl_FragColor=vec4(col*uSceneTint,1.0);}
"""

    /**
     * 小镇 emis 片元（§3.3 锁定表达式·**小镇专用·远景层仍走 C_FS_EMIS**）：窗火按**世界坐标哈希**错峰点亮——
     * 同一扇窗的顶点量化到同格 ⇒ 整窗同刻亮；点亮序随位置确定（无 Random、无新几何、无状态机）。
     * 点亮窗口 12s + 单窗 0.9s 渐亮；熄灭态不是消失而是冷玻璃（保留立面结构）；`uLampT` 是灯火主控（黎明渐熄）。
     *
     * 精度：哈希的 `sin(...)*43758.5453` 峰值远超 mediump 保证范围（2^14），故优先 highp——不改锁定表达式，
     * 只补平台必需的精度限定（无 highp 的老设备退回 mediump·哈希退化仅表现为错峰序变规律，不崩不黑）。
     */
    const val T_FS_EMIS_GLOW = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
varying vec3 vC;varying vec3 vW;
uniform float uDuskSec;
uniform float uLampT;
void main(){
 float key=fract(sin(dot(floor((vW.xz+vec2(37.7))/1.7),vec2(12.9898,78.233)))*43758.5453);
 float on=clamp((uDuskSec-key*12.0)/0.9,0.0,1.0)*uLampT;
 vec3 unlit=vC*vec3(0.28,0.32,0.40);
 gl_FragColor=vec4(mix(unlit,vC*1.15,on),1.0);}
"""

    /**
     * 远景层片元（R2 修订·撤 J8 豁免）：= C_FS_EMIS 的 `vC×1.15` 语义 + 场景色温——天空入 GL 后远山直接
     * 衬在水彩星空前，恒暖棕会发脏；乘 `uSceneTint` 让它深夜随场景转靛蓝、白天 tint=(1,1,1) 与原字节级一致。
     */
    const val T_FS_FAR = """
precision mediump float;varying vec3 vC;
uniform vec3 uSceneTint;
void main(){gl_FragColor=vec4(vC*1.15*uSceneTint,1.0);}
"""

    /** 软影顶点（平放 quad·pos3 + uv2）。 */
    const val T_VS_SHADOW = """
attribute vec3 aPos;attribute vec2 aUv;
uniform mat4 uMVP;varying vec2 vUv;
void main(){vUv=aUv;gl_Position=uMVP*vec4(aPos,1.0);}
"""

    /** 软影片元（§3.3 锁定）：径向 smoothstep(0.55,1.0) 收羽·峰值 α0.38·色 #0B0F1B·标准 alpha 混合。 */
    const val T_FS_SHADOW = """
precision mediump float;varying vec2 vUv;
void main(){
 float a=(1.0-smoothstep(0.55,1.0,length(vUv*2.0-1.0)))*0.38;
 gl_FragColor=vec4(vec3(0.043,0.059,0.106),a);}
"""

    /**
     * 窗火光晕顶点（§3.3 锁定·billboard）：顶点流只存中心 + 角 uv + 边长，真实位置 =
     * `center + (u-0.5)·size·uCamRight + (v-0.5)·size·uCamUp`（相机右/上向量由 CPU 每帧从 yaw/pitch 算），
     * 故转相机不需重传几何。中心同时以 `vW` 送进片元，供哈希算「这盏灯此刻亮没亮」。
     */
    const val T_VS_GLOW = """
attribute vec3 aCenter;attribute vec2 aUv;attribute float aSize;
uniform mat4 uMVP;uniform vec3 uCamRight;uniform vec3 uCamUp;
varying vec2 vUv;varying vec3 vW;
void main(){vUv=aUv;vW=aCenter;
 vec3 pos=aCenter+(aUv.x-0.5)*aSize*uCamRight+(aUv.y-0.5)*aSize*uCamUp;
 gl_Position=uMVP*vec4(pos,1.0);}
"""

    /**
     * 窗火光晕片元（§3.3 锁定）：径向 smoothstep(0.0,1.0)·峰值 α0.30·色 #FFD98A·加色混合（SRC_ALPHA, ONE）。
     * 点亮值 `on` 与 [T_FS_EMIS_GLOW] **同式同源**（世界坐标同一格哈希）⇒ 光晕与它那扇窗同刻点亮。
     */
    const val T_FS_GLOW = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
varying vec2 vUv;varying vec3 vW;
uniform float uDuskSec;
uniform float uLampT;
void main(){
 float key=fract(sin(dot(floor((vW.xz+vec2(37.7))/1.7),vec2(12.9898,78.233)))*43758.5453);
 float on=clamp((uDuskSec-key*12.0)/0.9,0.0,1.0)*uLampT;
 float a=(1.0-smoothstep(0.0,1.0,length(vUv*2.0-1.0)))*0.30*on;
 gl_FragColor=vec4(vec3(1.0,0.8509804,0.5411765),a);}
"""

    /**
     * 画层天空片元（R2 修订·天空入 GL·用户 2026-08-28 打回幕布方案）：全屏 quad 采样手绘天空贴图，画于渐变
     * quad 之后、**一切几何之前** → 屋顶/山影凭深度自然遮挡天空（「房子抠出来在天空前面」的正确图层序），
     * Compose 幕布整族退役。cover 裁切：`uUvSX` = 水平可见比例（CPU 按 屏幕纵横比/贴图纵横比 算·横版素材
     * 恒 ≤1 只裁 x）。`uAlpha` = 相位渐显（2.5s·渲染器逐帧推进·冻结即直切）。BG_VS 的 vUv 顶=0 与位图行序一致。
     * NPOT 贴图（2048×1152）走 CLAMP_TO_EDGE + LINEAR 无 mipmap = ES2 核心保证面。
     */
    const val T_SKY_TEX_FS = """
precision mediump float;
varying vec2 vUv;
uniform sampler2D uSkyTex;
uniform float uUvSX;
uniform float uAlpha;
void main(){
  vec2 uv=vec2(uUvSX*(vUv.x-0.5)+0.5, vUv.y);
  gl_FragColor=vec4(texture2D(uSkyTex,uv).rgb, uAlpha);
}
"""

    /**
     * 背景天空片元（§4.1E·值同源）：竖向 **7 停靠**渐变（`uSky[7]`+`uSkyPos[7]`）+ 椭圆辉光（中心 (0.50,0.720)·
     * 半径 0.715×0.200·(255,214,150)α0.5@0→(255,196,130)α0.15@0.45→透明@0.70·screen 叠加·整体 ×uGlowA）。
     * VS 复用 PlanetShaders.BG_VS（vUv 顶=0 底=1）。
     */
    const val T_BG_FS = """
precision mediump float;
varying vec2 vUv;
uniform vec3 uSky[7];
uniform float uSkyPos[7];
uniform float uGlowA;
vec3 gradAt(float y){
  if(y<=uSkyPos[1]) return mix(uSky[0],uSky[1],clamp((y-uSkyPos[0])/(uSkyPos[1]-uSkyPos[0]),0.0,1.0));
  if(y<=uSkyPos[2]) return mix(uSky[1],uSky[2],clamp((y-uSkyPos[1])/(uSkyPos[2]-uSkyPos[1]),0.0,1.0));
  if(y<=uSkyPos[3]) return mix(uSky[2],uSky[3],clamp((y-uSkyPos[2])/(uSkyPos[3]-uSkyPos[2]),0.0,1.0));
  if(y<=uSkyPos[4]) return mix(uSky[3],uSky[4],clamp((y-uSkyPos[3])/(uSkyPos[4]-uSkyPos[3]),0.0,1.0));
  if(y<=uSkyPos[5]) return mix(uSky[4],uSky[5],clamp((y-uSkyPos[4])/(uSkyPos[5]-uSkyPos[4]),0.0,1.0));
  return mix(uSky[5],uSky[6],clamp((y-uSkyPos[5])/(uSkyPos[6]-uSkyPos[5]),0.0,1.0));
}
void main(){
  vec3 col=gradAt(vUv.y);
  vec2 e=vec2((vUv.x-0.50)/0.715,(vUv.y-0.720)/0.200);
  float ed=length(e);
  float t0=smoothstep(0.0,0.45,ed);
  float t1=smoothstep(0.45,0.70,ed);
  float ga=(ed<0.45 ? mix(0.5,0.15,t0) : mix(0.15,0.0,t1))*uGlowA;
  vec3 gcol=mix(vec3(1.0,0.8392157,0.5882353),vec3(1.0,0.7686275,0.5098039),t0);
  vec3 src=gcol*ga;
  col=1.0-(1.0-col)*(1.0-src);
  gl_FragColor=vec4(col,1.0);
}
"""

}
