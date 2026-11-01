#!/usr/bin/env python3
"""Generate assets/pet.html: animations play into a canvas so no native media controls can appear."""
import json
import os
import shutil

SRC = os.environ.get("DSH_PET_ASSETS", "/root/.dsh/profiles/web/node_modules/dsh-pet/assets/webm")
DST = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "app", "src", "main", "assets")

files = sorted(f for f in os.listdir(SRC) if f.endswith('.webm'))
os.makedirs(os.path.join(DST, 'webm'), exist_ok=True)
for i, f in enumerate(files, 1):
    dst = os.path.join(DST, 'webm', '%03d.webm' % i)
    if not os.path.exists(dst):
        shutil.copyfile(os.path.join(SRC, f), dst)
names = ['%03d.webm' % i for i in range(1, len(files) + 1)]
mapping = {os.path.splitext(f)[0]: names[i] for i, f in enumerate(files)}

html = '''<!DOCTYPE html>
<html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
<style>
html,body{margin:0;padding:0;width:100%;height:100%;overflow:hidden;background:transparent;}
#wrap{position:absolute;left:0;top:0;width:100%;height:100%;transform-origin:50% 85%;will-change:transform;}
#wrap.squash{animation:squash .38s cubic-bezier(.2,1.6,.35,1);}
@keyframes squash{0%{transform:scale(1,1)}18%{transform:scale(1.28,.7)}46%{transform:scale(.86,1.16)}70%{transform:scale(1.06,.95)}100%{transform:scale(1,1)}}
#wrap.bounce-x{animation:bouncex .26s cubic-bezier(.2,1.5,.35,1);}
#wrap.bounce-y{animation:bouncey .26s cubic-bezier(.2,1.5,.35,1);}
#wrap.bounce-xy{animation:bouncexy .26s cubic-bezier(.2,1.5,.35,1);}
@keyframes bouncex{0%{transform:scale(1,1)}14%{transform:scale(.64,1.32)}38%{transform:scale(1.14,.88)}60%{transform:scale(.9,1.1)}80%{transform:scale(1.05,.96)}100%{transform:scale(1,1)}}
@keyframes bouncey{0%{transform:scale(1,1)}14%{transform:scale(1.32,.64)}38%{transform:scale(.88,1.14)}60%{transform:scale(1.1,.9)}80%{transform:scale(.96,1.05)}100%{transform:scale(1,1)}}
@keyframes bouncexy{0%{transform:scale(1,1)}14%{transform:scale(.8,1.28)}38%{transform:scale(1.16,.86)}60%{transform:scale(.92,1.08)}100%{transform:scale(1,1)}}
#cv{position:absolute;left:0;top:0;width:100%;height:100%;}
.src{position:fixed;left:-4000px;top:-4000px;width:4px;height:4px;opacity:0;pointer-events:none;}
#bubble{position:absolute;left:2px;right:2px;bottom:4px;background:rgba(255,255,255,.94);border-radius:9px;padding:4px 7px;font:11.5px/1.35 sans-serif;color:#333;opacity:0;transition:opacity .25s;word-break:break-all;pointer-events:none;z-index:5;}
#bubble.show{opacity:1;}
</style></head><body>
<div id="wrap"><canvas id="cv"></canvas></div>
<video id="a" class="src" autoplay muted playsinline loop></video>
<video id="b" class="src" autoplay muted playsinline loop></video>
<div id="bubble"></div>
<script>
var vids = PLACEHOLDER_VIDS;
var mapping = PLACEHOLDER_MAP;
function JLog(m){ try{ if(window.PetHost && window.PetHost.log) window.PetHost.log(m); }catch(e){} }
var IDLE_NAME = '待机呼吸休闲';
var RANDOM_GAP_MS = 15000;   // 静止待机时多久换一次随机动作
var TAP_QUIET_MS = 8000;     // 点击/拖动后安静站多久，然后回到随机
var nextRandomAt = 0;
var oneShotPlaying = false;   // 随机动作是“只播一遍”，播完自动收尾
var oneShotAt = 0;            // 本轮随机动作开始时间（防止异常卡死）
var wantWork = null;             // 当前 DSH 状态希望显示的工作动画名
var lastSwapAt = 0;
var curStartAt = 0, curDurMs = 10000;   // 当前动作从哪一刻开始播、整段多长
// 静默/默认状态统一用这个动作
var QUIET = ['待机呼吸休闲','东张西望','悠闲哼歌','哈欠连天','原地小憩沉眠','女仆屈膝礼仪','小幅度原地360度旋转展示'];
var b = document.getElementById('bubble');
var cv = document.getElementById('cv');
var cx = cv.getContext('2d');
var A = document.getElementById('a'), B = document.getElementById('b');
var curWorkAt = 0, cur = null, nxt = null, idx = -1, curWork = null, bTimer = null, lastBalance = null, lastNotify = null;
var curBox = [0, 0, 640, 360];   // 当前动作取景框（视频像素）
window.petBox = function(x, y, w, h, s){
  curBox = [x, y, w, h];
  curScale = (s > 0) ? s : 0;
};
var frozen = false;
window.petFreeze = function(){
  frozen = true;
  JLog('freeze idx=' + idx);
  if(cur){ try{ cur.pause(); }catch(e){} }
};
// 拖动结束（没甩出去）：恢复播放并回到静默待机
window.petSettle = function(){
  oneShotPlaying = false;   // 拖完回到循环待机，旧的一次性动作不再占闸门
  window.petResume();
  var i = idxOfName(IDLE_NAME);
  if(i >= 0 && i !== idx) play(i, false);
  nextRandomAt = performance.now() + TAP_QUIET_MS;
};
window.petResume = function(){
  if(!frozen) return;
  JLog('resume idx=' + idx);
  frozen = false;
  if(cur){ try{ cur.play().catch(function(){}); }catch(e){} }
};

var lastFitSig = '';
function fitCanvas(){
  // 必须用布局尺寸 clientWidth/Height，绝对不能用 getBoundingClientRect()：
  // 后者会把 #wrap 的 squash / bounce 动画 transform 一起算进去。
  // 点一下会放 scale(1.28,.7) 的 Q 弹，若此刻正好赶上换动作触发的窗口 resize，
  // 画布就会按"被拉扁的矩形"建立宽高比，而静默期不再有 resize，人偶就一直扁着。
  var w = cv.clientWidth || cv.offsetWidth || 0;
  var h = cv.clientHeight || cv.offsetHeight || 0;
  if(w < 2 || h < 2){ w = window.innerWidth || w; h = window.innerHeight || h; }
  // 封顶 2：窗口在 3x 屏上也能省一半画布像素（等比缩放，不会变形）
  var dpr = Math.min(window.devicePixelRatio || 1, 2);
  var bw = Math.max(1, Math.round(w * dpr));
  var bh = Math.max(1, Math.round(h * dpr));
  if(bw === cv.width && bh === cv.height) return;
  cv.width = bw;
  cv.height = bh;
  cx.clearRect(0, 0, cv.width, cv.height);
  lastDraw = 0;
  var sig = w + 'x' + h + '@' + dpr;
  if(sig !== lastFitSig){ lastFitSig = sig; JLog('fit css=' + w + 'x' + h + ' cv=' + bw + 'x' + bh + ' dpr=' + dpr); }
  // 改 backing 尺寸会清空画布：立刻补画一帧，免得换动作那一瞬间闪一下空白
  if(!sleeping){ try{ paint(); }catch(e){} }   // 过渡期也补画：等比包含算法保证旧帧不会被新窗口拉扁
}
window.addEventListener('resize', fitCanvas);
// 有些 WebView 在 updateViewLayout 后不一定派发 resize：再挂一个 ResizeObserver 兜底
try{ if(window.ResizeObserver){ new ResizeObserver(function(){ fitCanvas(); }).observe(cv); } }catch(e){}
fitCanvas();

// 视频全部藏在屏幕外，画面由 canvas 逐帧绘制，WebView 的原生媒体控件永远不会出现
// 固定按 150dp 宽度绘制视频：窗口缩小只是裁掉透明边，模型本身大小不变
var BASE_W = 150, CONTENT_CX = 0.5, CONTENT_CY = 0.5475;
var lastDraw = 0, drawGap = 40, idleGap = 100, lastFitAt = 0;
var activeUntil = 0, sleeping = false;
// 灭屏/后台彻底停画并暂停解码：不是降频，是直接不画
window.petSleep = function(on){
  sleeping = !!on;
  try {
    if (on) { if (cur) cur.pause(); if (nxt && nxt !== cur) nxt.pause(); }
    else { if (cur) { cur.muted = true; cur.play().catch(function(){}); } lastDraw = 0; }
  } catch (e) {}
};
function petWake(){ activeUntil = performance.now() + 2500; }
var curScale = 0;  // 视频像素 → CSS 像素（窗口取景框方案下由 Java 传 5 参才启用，默认走等比包含）
// 把当前帧画到画布上；画布 backing 必须先由 fitCanvas 定好，否则比例会错
function paint(){
  cx.clearRect(0, 0, cv.width, cv.height);
  if(!cur || cur.readyState < 2 || !(cur.videoWidth > 0)) return;
  if(curScale > 0){
    // 统一比例：整帧按固定比例画，窗口只是取景框
    cx.drawImage(cur, 0, 0, cur.videoWidth, cur.videoHeight,
      -curBox[0] * curScale, -curBox[1] * curScale,
      cur.videoWidth * curScale, cur.videoHeight * curScale);
  } else {
    // 5.1.2.0 的窗口方案，但用"等比包含"而不是"拉伸铺满"：
    // 窗口比例和取景框比例一致时铺满（和以前完全一样）；
    // 万一窗口还没来得及 resize（切换的那 1~2 帧），就等比缩进窗口内居中，
    // 绝不会被拉扁/压扁 —— 这是"挤压一下"的根治手段。
    var pad = 0.143;
    var iw = cv.width * (1 - 2 * pad), ih = cv.height * (1 - 2 * pad);
    var ar = (curBox[3] > 0) ? (curBox[2] / curBox[3]) : (cv.width / cv.height);
    var dw = iw, dh = iw / ar;
    if(dh > ih){ dh = ih; dw = ih * ar; }
    cx.drawImage(cur, curBox[0], curBox[1], curBox[2], curBox[3],
      cv.width * pad + (iw - dw) / 2, cv.height * pad + (ih - dh) / 2, dw, dh);
  }
}
function loop(t){
  if(document.hidden){ requestAnimationFrame(loop); return; }
  if (sleeping) { requestAnimationFrame(loop); return; }
  // 每 250ms 校准一次画布：Java 改窗口尺寸后 resize 事件可能缺席，画布必须自个儿跟上，否则会被 CSS 拉扁
  if(t - lastFitAt > 250){ lastFitAt = t; fitCanvas(); }
  // 刚换过动作/被点过 → 25fps；静止闲置 → 10fps（呼吸循环在 10fps 下看不出差别）
  var active = performance.now() < activeUntil;
  var gap = frozen ? 120 : (active ? drawGap : idleGap);
  if(holdDraw){ requestAnimationFrame(loop); return; }   // 过渡期保留上一帧，绝不清屏（清屏=人偶凭空消失一下）
  if(t - lastDraw > gap){ lastDraw = t; paint(); }
  requestAnimationFrame(loop);
}
requestAnimationFrame(loop);

var pending = null;      // 正在预加载 / 待切换的那一路
var prefetched = -2;     // 预取好的随机动作 index（-2 = 没有）
var holdDraw = false, holdSince = 0;   // 切换过渡期：先不画，避免旧画面被新尺寸窗口拉扁

// 预加载：只把片子读进备用 video，不动窗口
function preload(i, oneShot){
  if(i < 0) return null;
  var t = (cur === A) ? B : A;
  if(pending && pending.t === t && pending.i === i){ pending.oneShot = !!oneShot; return pending; }
  if(i !== prefetched) prefetched = -2;
  var p = {i: i, t: t, oneShot: !!oneShot, ready: false, framed: false, boxReady: false, done: false, swapped: false, dur: 0};
  pending = p;
  t.onended = function(){
    if(cur !== t){ JLog('stale ended ignored'); return; }
    // 复用 video 元素时如果没回到片头，会"一播就到尾"，ended 提前触发，
    // 表现就是每个动作只播了一小截。只有真的播到片尾才算播完。
    var dur = t.duration || 0, ct = t.currentTime || 0;
    if(!t.ended && dur > 0.2 && ct < dur - 0.25){
      JLog('early ended ignored ct=' + ct.toFixed(2) + ' dur=' + dur.toFixed(2));
      return;
    }
    oneShotPlaying = false;
    JLog('oneshot end idx=' + idx + ' ct=' + ct.toFixed(2) + ' dur=' + dur.toFixed(2));
    if(curWork && wantWork){
      var wi = idxOfName(wantWork);
      if(wi >= 0 && wi !== idx) play(wi, false);
      return;
    }
    if(frozen || sleeping) return;
    // 播完直接接下一个随机动作（中间不插待机动作）
    setTimeout(function(){ if(!frozen && !sleeping && !curWork) randomIdle(true); }, 300);
  };
  t.oncanplay = function(){
    if(pending !== p) return;
    p.ready = true;
    if(t.duration > 0.2) p.dur = t.duration;
    t.muted = true;
    t.loop = !p.oneShot;
    JLog('preloaded ' + i + ' dur=' + (t.duration || 0).toFixed(2) + ' loop=' + t.loop);
  };
  // 循环方式当场定好：pending 被替换时 oncanplay 会提前 return，否则 loop 会留成上一次的值
  t.muted = true;
  t.loop = !p.oneShot;
  t.src = 'webm/' + vids[i];
  t.load();
  try{ t.currentTime = 0; }catch(e){}   // 复用元素必须回到片头，不能接着上一部的位置播
  return p;
}

// 窗口尺寸就绪（Java updateViewLayout 之后回调）
window.petBoxReady = function(i){
  // Java 已完成窗口几何设置（几何没变时会立即回调）。这里什么都不用做：
  // 画面切换由下面的 doSwap 在 frameCb 里直接完成，不再依赖 resize 事件。
};

// 出帧 + 窗口尺寸都就绪，才真正换画面，避免新画面被塞进旧尺寸窗口里压扁
function trySwap(){
  var p = pending;
  if(!p || p.swapped) return;
  if(!p.framed) return;
  p.swapped = true;
  var t = p.t, old = cur;
  cur = t;
  idx = p.i;
  holdDraw = false;
  lastDraw = 0;
  petWake();
  lastSwapAt = performance.now();
  // 当前动作的"开始时间 / 总时长"：任何自动换动作都要等它播满
  curStartAt = lastSwapAt;
  var d = (t.duration > 0.2) ? t.duration : ((p.dur > 0.2) ? p.dur : 10);
  curDurMs = d * 1000;
  if(p.oneShot) oneShotAt = curStartAt;
  JLog('swap dur=' + d.toFixed(2) + ' loop=' + (t.loop ? 1 : 0));
  JLog('swap idx=' + p.i + (p.oneShot ? ' oneshot' : ''));
  pending = null;
  if(nextRandomAt < performance.now() + 2000) nextRandomAt = performance.now() + RANDOM_GAP_MS;
  if(old && old !== t) setTimeout(function(){ try{ old.pause(); }catch(e){} }, 120);
  setTimeout(planNext, 320);   // 换完立刻预加载下一个
}

// 真正启动一次切换：先让 Java 把窗口调成目标动作的尺寸，再等出帧
function commit(p){
  if(!p || p.done || pending !== p) return;
  p.done = true;
  var t = p.t;
  // 加载期间不干扰当前画面：旧动作继续正常播，所以看不到“闪一下 / 抖一下”
  function askBox(){ if(window.PetHost && window.PetHost.applyBox) window.PetHost.applyBox(p.i); }
  function frameCb(){
    if(pending !== p || p.swapped || p.framed) return;
    p.framed = true;
    JLog('frame ready ' + p.i);
    askBox();          // 请 Java 一次性把窗口几何设置成目标动作的尺寸
    // 同一帧内直接换画面；Java 的 updateViewLayout 幂等且异步，
    // 新帧按自己的取景框画进当前画布，再被窗口等比缩放，不会挤压
    trySwap();
  }
  t.muted = true;
  t.loop = !p.oneShot;
  try{ if(t.currentTime > 0.05) t.currentTime = 0; }catch(e){}
  t.play().catch(function(){});
  if(t.requestVideoFrameCallback){
    t.requestVideoFrameCallback(function(){ frameCb(); });
    t.requestVideoFrameCallback(function(){ frameCb(); });
  }
  setTimeout(frameCb, 260);
  // 兜底：帧回调缺失也不要卡死
  setTimeout(function(){ if(pending === p && !p.swapped){ p.framed = true; if(window.PetHost && window.PetHost.applyBox) window.PetHost.applyBox(p.i); p.boxReady = true; trySwap(); } }, 800);
}

function play(i, oneShot){
  if(i < 0) return;
  commit(preload(i, oneShot));
}

// 提前把下一部片子读进备用缓冲（只 load，不动窗口）
function planNext(){
  if(pending) return;
  if(curWork && wantWork){
    var wi = idxOfName(wantWork);
    if(wi >= 0 && wi !== idx) preload(wi, false);
    return;
  }
  if(frozen || sleeping) return;
  var n; do { n = Math.floor(Math.random() * vids.length); } while(n === idx && vids.length > 1);
  prefetched = n;
  preload(n, true);
}

function playByName(n){ var f = mapping[n]; if(!f) return false; var i = vids.indexOf(f); if(i < 0) return false; play(i); return true; }
function bubble(t, ms){ /* 气泡改由原生小窗显示，页面内不再画，避免遮挡宠物 */ }
function randomIdle(force){
  if(!force && (curWork || frozen || sleeping)) { JLog('randomIdle skip curWork=' + curWork + ' frozen=' + frozen); return; }
  var n = (prefetched >= 0 && prefetched !== idx) ? prefetched : -1;
  if(n < 0){ do { n = Math.floor(Math.random() * vids.length); } while(n === idx && vids.length > 1); }
  prefetched = -2;
  JLog('randomIdle play ' + n);
  nextRandomAt = performance.now() + RANDOM_GAP_MS;
  oneShotPlaying = true;
  oneShotAt = performance.now();
  play(n, true);
}
// 甩飞落地停稳后：恢复播放并抽一个随机动作
window.petSettleRandom = function(){
  JLog('settleRandom');
  oneShotPlaying = false;
  window.petResume();
  // 落地后直接继续随机动作，不插待机
  if(curWork && wantWork){
    var wi = idxOfName(wantWork);
    if(wi >= 0 && wi !== idx) play(wi, false);
    return;
  }
  randomIdle(true);
};
function pick(a){ return a[Math.floor(Math.random() * a.length)]; }

var WORK = ['thinking','working','result','waiting','success','error'];
var WORK_ANIM = ['工作状态-思考冒泡','工作状态-忙碌点按','工作状态-清点归档','工作状态-原地踱步张望','工作状态-雀跃庆祝','工作状态-垂头叹气冒汗'];
var WORK_TEXT = [
  ['正在认真想下一步呢','让我整理一下思路~','正在梳理接下来要做什么'],
  ['正在处理任务呢','这一步正在进行中哦','正在继续干活呢，别打扰我~'],
  ['这一步处理好啦，接着看下一步','搞定一步，继续看看哦~'],
  ['需要你确认一下呢','这里要等你看一下哦','轮到你来决定啦，等你~'],
  ['这一轮搞定啦，真棒！','任务完成咯，可以摸鱼啦~'],
  ['这一步好像没跑通呢','刚才的操作遇到点问题哦','这里卡了一下，再等等你呢']
];

window.petUpdate = function(d){
  if(d.balance && d.balance !== lastBalance){ lastBalance = d.balance; bubble('余额 ¥' + d.balance, 5000); }
  if(d.notify && d.notify !== lastNotify){
    lastNotify = d.notify;
    if(d.notify === 'success') bubble(pick(WORK_TEXT[4]));
    else if(d.notify === 'error') bubble(pick(WORK_TEXT[5]));
    else if(d.notify === 'waiting') bubble(pick(WORK_TEXT[3]));
  }
  var st = d.state || null;
  curWorkAt = performance.now();          // 心跳：每次推送都续期，不会因为 8 秒判定而反复清空
  if(st !== curWork){
    curWork = st;
    JLog('state=' + st);
    if(curWork){
      var i = WORK.indexOf(curWork);
      if(i >= 0){ wantWork = WORK_ANIM[i]; }
      else { curWork = null; wantWork = null; }   // idle/done 之类不属于工作时段的，不能挡住随机动作
    } else {
      wantWork = null;
    }
  }
};

window.petPlay = playByName;
window.petBubble = bubble;
function idxOfName(n){ var f = mapping[n]; return f ? vids.indexOf(f) : -1; }
// 点击：固定回到待机呼吸（不随机抽动作，避免抽到甩动/特效类看起来像被甩出去）
// 点击：回到默认待机动作 + Q 弹
window.petTap = function(){
  JLog('tap');
  oneShotPlaying = false;   // 待机是循环动作，清掉旧随机动作的一次性闸门
  // 待机动作只在“用户操作它”时出现；TAP_QUIET_MS 后自动回到随机播放
  var i = idxOfName(IDLE_NAME);
  if(i >= 0 && i !== idx) play(i, false);
  nextRandomAt = performance.now() + TAP_QUIET_MS;
};
window.petSquash = function(){
  var w = document.getElementById('wrap');
  if(!w) return;
  w.classList.remove('squash','bounce-x','bounce-y','bounce-xy');
  void w.offsetWidth;
  w.classList.add('squash');
};
window.petBounce = function(dir){
  var w = document.getElementById('wrap');
  if(!w) return;
  w.classList.remove('squash','bounce-x','bounce-y','bounce-xy');
  void w.offsetWidth;
  w.classList.add(dir === 'xy' ? 'bounce-xy' : (dir === 'y' ? 'bounce-y' : 'bounce-x'));
};

setTimeout(function(){ var i = idxOfName(IDLE_NAME); play(i >= 0 ? i : 0); }, 0);
// 每秒判断一次：到点且不在工作/冻结/休眠状态才换随机动作
setInterval(function(){
  if(curWork && performance.now() - curWorkAt > 8000){ JLog('curWork expired'); curWork = null; }
  if(frozen || sleeping) return;
  // 工作动画：thinking/working 会来回抖，限制最快 3 秒才允许换一次，且已经在播同一个就不重开。
  // 必须放在 curWork 提前返回之前 —— 否则桌宠停在循环待机时（没有 onended 事件）工作动画永远播不出来。
  // !oneShotPlaying 保证随机动作仍然整段播完才让位。
  if(curWork && wantWork && !oneShotPlaying){
    var want = mapping[wantWork];
    if(want && vids[idx] !== want && performance.now() - lastSwapAt > 3000 && performance.now() - curStartAt > 800){
      JLog('work play ' + wantWork);
      playByName(wantWork);
      var wi = WORK.indexOf(curWork);
      if(wi >= 0) bubble(pick(WORK_TEXT[wi]));
    }
  }
  if(curWork) return;
  if(oneShotPlaying && curStartAt && performance.now() - curStartAt > curDurMs + 2000){
    // 看门狗：这部片子已经播满（还多给 2 秒），onended 就算没来也不该再卡着
    oneShotPlaying = false;
    nextRandomAt = 0;
    JLog('oneshot watchdog ct=' + (cur.currentTime || 0).toFixed(2) + ' dur=' + (cur.duration || 0).toFixed(2));
  }
  if(oneShotPlaying) return;   // 随机动作还没播完，别中途打断
  if(performance.now() < nextRandomAt) return;
  randomIdle();
}, 1000);
JLog('boot randomGap=' + RANDOM_GAP_MS);
nextRandomAt = performance.now() + RANDOM_GAP_MS;
</script>
</body></html>
'''
html = html.replace('PLACEHOLDER_VIDS', json.dumps(names)).replace('PLACEHOLDER_MAP', json.dumps(mapping, ensure_ascii=False))

with open(os.path.join(DST, 'pet.html'), 'w', encoding='utf-8') as f:
    f.write(html)
print('pet.html written, %d animations, %d mapping entries' % (len(names), len(mapping)))
