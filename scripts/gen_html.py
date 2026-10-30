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
var b = document.getElementById('bubble');
var cv = document.getElementById('cv');
var cx = cv.getContext('2d');
var A = document.getElementById('a'), B = document.getElementById('b');
var cur = null, nxt = null, idx = -1, curWork = null, bTimer = null, lastBalance = null, lastNotify = null;
var curBox = [0, 0, 640, 360];
window.petBox = function(x, y, w, h){ curBox = [x, y, w, h]; };
var frozen = false;
window.petFreeze = function(){
  frozen = true;
  if(cur){ try{ cur.pause(); }catch(e){} }
};
window.petResume = function(){
  if(!frozen) return;
  frozen = false;
  if(cur){ try{ cur.play().catch(function(){}); }catch(e){} }
};

function fitCanvas(){
  var r = cv.getBoundingClientRect();
  cv.width = Math.max(1, Math.round(r.width * (window.devicePixelRatio || 1)));
  cv.height = Math.max(1, Math.round(r.height * (window.devicePixelRatio || 1)));
  cx.clearRect(0, 0, cv.width, cv.height);
}
window.addEventListener('resize', fitCanvas);
fitCanvas();

// 视频全部藏在屏幕外，画面由 canvas 逐帧绘制，WebView 的原生媒体控件永远不会出现
// 固定按 150dp 宽度绘制视频：窗口缩小只是裁掉透明边，模型本身大小不变
var BASE_W = 150, CONTENT_CX = 0.5, CONTENT_CY = 0.5475;
var lastDraw = 0;
function loop(t){
  if(t - lastDraw > 6){
    lastDraw = t;
    var dpr = window.devicePixelRatio || 1;
    cx.clearRect(0, 0, cv.width, cv.height);
    if(cur && cur.readyState >= 2 && cur.videoWidth > 0){
      var pad = 0.143;
      cx.drawImage(cur, curBox[0], curBox[1], curBox[2], curBox[3], cv.width*pad, cv.height*pad, cv.width*(1-2*pad), cv.height*(1-2*pad));
    }
  }
  requestAnimationFrame(loop);
}
requestAnimationFrame(loop);

function play(i){
  if(i < 0) return;
  var t = (cur === A) ? B : A;
  if(t === cur) return;
  nxt = t;
  t.src = 'webm/' + vids[i];
  t.oncanplay = function(){
    if(nxt !== t) return;
    t.oncanplay = null;
    t.muted = true;
    t.play().catch(function(){});
    var old = cur;
    cur = t;
    idx = i;
    if(window.PetHost && window.PetHost.setAnim) window.PetHost.setAnim(i);
    if(old){
      setTimeout(function(){ try{ old.pause(); }catch(e){} }, 180);
    }
  };
  t.load();
}
function playByName(n){ var f = mapping[n]; if(!f) return false; var i = vids.indexOf(f); if(i < 0) return false; play(i); return true; }
function bubble(t, ms){ /* 气泡改由原生小窗显示，页面内不再画，避免遮挡宠物 */ }
function randomIdle(){ if(curWork || frozen) return; var n; do { n = Math.floor(Math.random() * vids.length); } while(n === idx && vids.length > 1); play(n); }
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
  if(d.state !== curWork){
    curWork = d.state || null;
    if(curWork){
      var i = WORK.indexOf(curWork);
      if(i >= 0){ playByName(WORK_ANIM[i]); bubble(pick(WORK_TEXT[i])); }
    }
  }
};

window.petPlay = playByName;
window.petBubble = bubble;
window.petTap = function(){ var n; do { n = Math.floor(Math.random() * vids.length); } while(n === idx && vids.length > 1); play(n); };
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

setTimeout(function(){ play(Math.floor(Math.random() * vids.length)); }, 0);
setInterval(function(){ if(!curWork) randomIdle(); }, 15000);
</script>
</body></html>
'''
html = html.replace('PLACEHOLDER_VIDS', json.dumps(names)).replace('PLACEHOLDER_MAP', json.dumps(mapping, ensure_ascii=False))

with open(os.path.join(DST, 'pet.html'), 'w', encoding='utf-8') as f:
    f.write(html)
print('pet.html written, %d animations, %d mapping entries' % (len(names), len(mapping)))
