import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const read = path => fs.readFileSync('src/main/webapp/' + path, 'utf8');
const butterfly = read('skins/bolo-butterfly/js/common.js');
const handler = butterfly.split('$("#rightside_config").on("click", (function() {')[1]?.split('})), $("#darkmode")')[0];
assert(handler, 'Butterfly reading-settings handler must be available');
const values = new Map(), classes = new Set();
let focused = 0, dismissed = 0;
const trigger = {getAttribute: name => values.get(name),setAttribute: (name,value) => values.set(name,value),focus(){ focused++; }};
const child = {}, panel = {contains: node => node === child,setAttribute: (name,value) => values.set('panel:' + name,value),
  classList: {toggle(name,on){ on ? classes.add(name) : classes.delete(name); }},inert:true};
const document = {activeElement: trigger,getElementById: id => id === 'rightside-config-hide' ? panel : null};
const api = {closeMenu(){ dismissed++; }};
const click = vm.runInNewContext('(function(){' + handler + '})', {document, window:{BoloTheme:api},BoloTheme:api});
click.call(trigger);
assert.equal(values.get('aria-expanded'),'true');assert.equal(values.get('panel:aria-hidden'),'false');
assert(classes.has('rightside-in'));assert(!panel.inert);
document.activeElement = child;
click.call(trigger);
assert.equal(values.get('aria-expanded'),'false');assert.equal(values.get('panel:aria-hidden'),'true');
assert(classes.has('rightside-out'));assert(panel.inert);assert.equal(focused,1);assert.equal(dismissed,1);
click.call(trigger);assert(!panel.inert);assert(classes.has('rightside-in'));
// The homepage has no reading/font controls. Its original appearance action must
// remain in the gear menu, otherwise the gear opens an empty panel.
const activeTemplate = read('skins/bolo-butterfly/rightside.ftl').replace(/<#--[\s\S]*?-->/g,'');
assert(/id="rightside-config-hide"[\s\S]*?id="darkmode"[\s\S]*?id="rightside-config-show"/.test(activeTemplate));
assert(!read('skins/bolo-butterfly/define-header.ftl').includes('data-bolo-theme-slot'));

// Closing the right drawer must unlock the page, rather than relocking it and
// leaving a blank scrollbar compensation strip beside the hero.
{
  const bodyStyle={overflow:'hidden','padding-right':'20px'},drawerStyle={transform:'translate3d(-100%,0,0)'},menuClasses=new Set(['open']),menuAttributes=new Map();
  let masked=true,lockedAgain=0;
  const menu={removeClass(name){menuClasses.delete(name);return this;},addClass(name){menuClasses.add(name);return this;},attr(name,value){menuAttributes.set(name,value);return this;}};
  const drawer={css(name,value){drawerStyle[name]=value;return this;}};
  const jquery=selector=>({css(name,value){if(selector==='body')Object.assign(bodyStyle,name);return this;}});
  const source='function b(e) {'+butterfly.split('function b(e) {')[1].split('m.on("click"')[0];
  const context={$:jquery,m:menu,g:drawer,v:{},y:{fadeOut(){masked=false;}},sidebarPaddingR(){lockedAgain++;}};
  vm.runInNewContext(source,context);context.b('closeMenu');
  assert.equal(bodyStyle.overflow,'');assert.equal(bodyStyle['padding-right'],'');assert.equal(drawerStyle.transform,'');
  assert(!masked);assert.equal(lockedAgain,0);assert(menuClasses.has('close'));assert.equal(menuAttributes.get('aria-expanded'),'false');
}

// Exercise the actual decoration callback at the random upper boundary and at a
// zero-sized viewport; neither case may index a triangle past the grid.
const trailSource=read('skins/bolo-butterfly/js/TweenMax.js');
const trailCode='function createTrail(){'+trailSource.split('function createTrail(){')[1].split('function getNeighbour')[0];
let opened=0,closed=0;
const triangle={selectedForTrail:false,open(){opened++;},close(){closed++;}};
const trailEnv={Math:Object.assign(Object.create(Math),{random:()=>.999999}),tris:[triangle],trailMaxLength:3,unselectTris(){},getNeighbour:()=>null,speedTriOpen:1,speedTrailAppear:1,delayBeforeDisappear:1,speedTrailDisappear:1};
vm.runInNewContext(trailCode,trailEnv);trailEnv.createTrail();assert.equal(opened,1);assert.equal(closed,1);
trailEnv.tris=[];trailEnv.createTrail();assert.equal(opened,1);

const yiliaSource = read('skins/bolo-yilia/js/bolo-yilia.js').replace(/Yilia\.init\(\);\s*$/, '');
const sideClasses = new Set(), animations = [];
let themeRefresh = 0, tocFocus = 0, reduced = false;
const tocButton = {setAttribute: (name,value) => values.set('toc:' + name,value),focus(){ tocFocus++; }};
const toc = {hidden:true,animate(frames,options){
  assert(!this.hidden);assert(sideClasses.has('yilia-toc-open'),'The profile must be hidden before the TOC begins to appear');
  const animation={frames,options,cancel(){this.cancelled=true;}};animations.push(animation);return animation;
}};
const side = {querySelector: selector => selector === '.toc' ? toc : tocButton,classList:{
  add: name => sideClasses.add(name),toggle(name,on){ on ? sideClasses.add(name) : sideClasses.delete(name); }
}};
const yiliaEnvironment = {document:{querySelector:()=>side},window:{BoloTheme:{},getComputedStyle:()=>({opacity:'.55',transform:'matrix(1,0,0,1,0,4)'}),matchMedia:()=>({matches:reduced})},BoloTheme:{refresh(){themeRefresh++;}}};
vm.runInNewContext(yiliaSource,yiliaEnvironment);
const yilia = yiliaEnvironment.Yilia;
yilia.toggleToc(true);assert(!toc.hidden);assert.equal(animations[0].options.duration,180);
animations[0].onfinish();assert(!toc.hidden);assert(sideClasses.has('yilia-toc-open'));
yilia.toggleToc(false);const closing=animations[1],staleFinish=closing.onfinish;
assert(!toc.hidden);assert(sideClasses.has('yilia-toc-open'),'The profile must not reappear during the closing transition');
yilia.toggleToc(true);assert(closing.cancelled);staleFinish();assert(!toc.hidden,'A cancelled close must not hide the reopened directory');
animations[2].onfinish();
yilia.toggleToc(false);animations[3].onfinish();assert(toc.hidden);assert(!sideClasses.has('yilia-toc-open'));assert.equal(tocFocus,1);
const count=animations.length;reduced=true;yilia.toggleToc(true);assert.equal(animations.length,count);assert(!toc.hidden);
yilia.toggleToc(false,true);assert(toc.hidden);assert.equal(animations.length,count);assert(themeRefresh>=6);
for (const [skin, object, method] of [['bolo-nexmoe','Skin','_initBackToTop'],['bolo-next','NexT','initBackToTop']]) {
  const classes=new Set(),attributes=new Map(),events=new Map(),keys=new Map(),cleanups=[];
  let returned=0;
  const control={classList:{toggle(name,on){on?classes.add(name):classes.delete(name)}},setAttribute:(name,value)=>attributes.set(name,value),addEventListener:(type,callback)=>keys.set(type,callback),removeEventListener:type=>keys.delete(type)};
  const window={scrollY:0,addEventListener:(type,callback)=>events.set(type,callback),removeEventListener:type=>events.delete(type),BoloNavigation:{onLeave:callback=>cleanups.push(callback)}};
  const sandbox={window,document:{querySelector:()=>control},Util:{goTop(){returned++;window.scrollY=0;}}};
  const file=skin==='bolo-next'?'bolo-next.js':'common.js';
  vm.runInNewContext(read('skins/'+skin+'/js/'+file).replace(new RegExp(object+'\\.init\\(\\);?\\s*$'), ''),sandbox);
  sandbox[object][method]();assert(!classes.has('is-visible'));assert.equal(attributes.get('aria-hidden'),'true');assert.equal(control.tabIndex,-1);assert(control.inert);
  window.scrollY=241;events.get('scroll')();assert(classes.has('is-visible'));assert.equal(control.tabIndex,0);assert(!control.inert);
  keys.get('keydown')({key:'Enter',preventDefault(){}});assert.equal(returned,1);events.get('scroll')();assert(!classes.has('is-visible'));assert(control.inert);
  cleanups[0]();assert(!events.size);assert(!keys.size);
}
{
  const source=read('skins/bolo-bubble/js/TweenMax.js');
  const animation=source.slice(source.indexOf('var mouseX ='));
  const listeners=new Map(),cleanups=[],ticks=new Set();let active=false,opened=0;
  const sandbox={Math:Object.assign(Object.create(Math),{random:()=>.999999}),
    window:{addEventListener:(type,fn)=>listeners.set(type,fn),removeEventListener:type=>listeners.delete(type),BoloNavigation:{onLeave:fn=>cleanups.push(fn)}},
    document:{getElementById:()=>({getContext:()=>({}),scrollWidth:1,scrollHeight:1,getBoundingClientRect:()=>({})})},
    TweenLite:{ticker:{addEventListener:(type,fn)=>ticks.add(fn),removeEventListener:(type,fn)=>ticks.delete(fn)},killTweensOf(){}},
    setInterval:()=>{active=true;return 1;},clearInterval:()=>{active=false;}};
  vm.runInNewContext(animation,sandbox);
  sandbox.createTrail();
  sandbox.tris=[];sandbox.createTrail();
  sandbox.cols=1;sandbox.rows=1;sandbox.tris=[{pos:0,col:0,row:0,isLeft:true,open(){opened++;},close(){}}];
  sandbox.createTrail();assert.equal(opened,1,'A random value near one must select the last existing triangle');
  sandbox.initCanvas('fixture');assert(active);assert.equal(listeners.size,1);assert.equal(ticks.size,1);
  cleanups[0]();assert(!active);assert.equal(listeners.size,0);assert.equal(ticks.size,0,'PJAX must remove the animation ticker');
}

// Round 5: Butterfly live bolo:themechange data-theme sync & onLeave removal
{
  const rootAttrs=new Map();
  const winEvents=new Map();
  const leaveCallbacks=[];
  let curTheme='light';
  const noopJq=()=>{
    const obj={
      0:{},length:0,
      off(){return obj;},on(){return obj;},click(){return obj;},mouseover(){return obj;},
      css(){return obj;},each(){return obj;},children(){return obj;},eq(){return obj;},
      hide(){return obj;},show(){return obj;},fadeOut(){return obj;},fadeIn(){return obj;},
      addClass(){return obj;},removeClass(){return obj;},toggleClass(){return obj;},
      hasClass:()=>false,attr(){return obj;},prop(){return obj;},find(){return obj;},
      width:()=>100,height:()=>100,scrollTop:()=>0,ready(fn){obj._ready=fn;return obj;}
    };
    return obj;
  };
  const win={
    innerWidth:1280,
    BoloTheme:{current:()=>curTheme},
    BoloNavigation:{onLeave:fn=>leaveCallbacks.push(fn)},
    GLOBAL_CONFIG:{Snackbar:undefined,isanchor:false},
    GLOBAL_CONFIG_SITE:{isPost:false,isSidebar:false},
    matchMedia:()=>({matches:false,addListener(){},removeListener(){}}),
    addEventListener(type,fn){winEvents.set(type,fn);},
    removeEventListener(type,fn){if(winEvents.get(type)===fn)winEvents.delete(type);}
  };
  const doc={
    cookie:'',readyState:'complete',
    body:{clientWidth:1280},
    documentElement:{
      setAttribute:(k,v)=>rootAttrs.set(k,String(v)),
      removeAttribute:k=>rootAttrs.delete(k),
      getAttribute:k=>rootAttrs.get(k)??null
    },
    querySelector:()=>null,
    addEventListener(){},
    removeEventListener(){}
  };
  const bfSandbox={
    window:win,document:doc,$:noopJq,
    BoloTheme:win.BoloTheme,BoloNavigation:win.BoloNavigation,
    GLOBAL_CONFIG:win.GLOBAL_CONFIG,GLOBAL_CONFIG_SITE:win.GLOBAL_CONFIG_SITE,
    throttle:fn=>fn,scrollToDest(){},sidebarPaddingR(){},closeSidebar(){},openSidebar(){},leftRight(){}
  };
  vm.runInNewContext(butterfly,bfSandbox);
  win.Skin.init();
  assert.equal(rootAttrs.get('data-theme'),'light','Butterfly Skin.init must sync initial data-theme');
  curTheme='dark';
  winEvents.get('bolo:themechange')({detail:{theme:'dark'}});
  assert.equal(rootAttrs.get('data-theme'),'dark','Butterfly must update data-theme on live bolo:themechange');
  leaveCallbacks.forEach(fn=>fn());
  assert(!rootAttrs.has('data-theme'),'Butterfly onLeave must remove html[data-theme]');
  assert(!winEvents.has('bolo:themechange'),'Butterfly onLeave must remove bolo:themechange listener');
}

// Round 5: NexT >= 1200 sidebar auto-open threshold & < 1200 / onLeave cleanup
{
  let viewport=1100;
  const toggleClasses=new Set();
  const bodyCss={};
  const sidebarCss={};
  const winHandlers=new Map();
  const leaveCallbacks=[];
  let readyFn=null;
  let toggleClickHandler=null;
  const toggleObj={
    hasClass:c=>toggleClasses.has(c),
    addClass(c){toggleClasses.add(c);return this;},
    removeClass(c){toggleClasses.delete(c);return this;},
    click(fn){if(fn)toggleClickHandler=fn;else toggleClickHandler?.call(this);return this;}
  };
  const $next=sel=>{
    if(sel===windowObj){
      return {
        width:()=>viewport,
        off(ev){winHandlers.delete(ev);return this;},
        on(ev,fn){winHandlers.set(ev,fn);return this;},
        scrollTop:()=>0
      };
    }
    if(sel===docObj){
      return {ready(fn){readyFn=fn;}};
    }
    if(sel==='.sidebar-toggle'||sel===toggleObj){
      return toggleObj;
    }
    if(sel==='.sidebar'){
      return {
        stop(){return this;},
        css(k,v){sidebarCss[k]=v;return this;},
        animate(props,cb){Object.assign(sidebarCss,props);cb?.();return this;},
        find:()=>({stop(){return this;},css(){return this;},animate(){return this;}})
      };
    }
    if(sel==='body'){
      return {
        stop(){return this;},
        css(k,v){bodyCss[k]=v;return this;},
        animate(props){Object.assign(bodyCss,props);return this;}
      };
    }
    if(sel==='.article__toc li')return {length:3};
    if(sel==='.main')return {animate(_,__,cb){cb?.();return this;}};
    return {
      length:0,click(){return this;},slideToggle(){return this;},
      css(){return this;},animate(_,cb){if(typeof _==='object'&&typeof cb==='function')cb();return this;},
      each(){return this;},find(){return this;},parent(){return this;},addClass(){return this;},removeClass(){return this;}
    };
  };
  const windowObj={scrollY:0,addEventListener(){},removeEventListener(){},BoloNavigation:{onLeave:fn=>leaveCallbacks.push(fn)}};
  const docObj={querySelector:()=>null};
  const nextSandbox={
    window:windowObj,document:docObj,$:$next,Util:{goTop(){}},
    setTimeout:()=>1,requestAnimationFrame:()=>1,cancelAnimationFrame(){}
  };
  vm.runInNewContext(read('skins/bolo-next/js/bolo-next.js').replace(/NexT\.init\(\);\s*$/,''),nextSandbox);
  nextSandbox.NexT.init();
  readyFn();
  nextSandbox.NexT.initArticle();
  assert(!toggleClasses.has('sidebar-active'),'NexT must NOT auto-open sidebar at 1100px (< 1200px)');
  assert(!toggleClasses.has('has-toc'),'NexT initArticle must NOT activate desktop TOC below 1200px');

  viewport=1280;
  readyFn();
  assert(toggleClasses.has('sidebar-active'),'NexT must auto-open sidebar at >= 1200px');
  assert.equal(bodyCss['padding-right'],320);

  viewport=1024;
  winHandlers.get('resize.boloNextSidebar')();
  assert(!toggleClasses.has('sidebar-active'),'Resizing below 1200px must close active NexT sidebar');
  assert.equal(bodyCss['padding-right'],'','Resizing below 1200px must clear body padding-right');

  viewport=1280;
  readyFn();
  assert(toggleClasses.has('sidebar-active'));
  leaveCallbacks.forEach(fn=>fn());
  assert(!toggleClasses.has('sidebar-active'),'Leaving NexT page must clean up sidebar-active');
  assert.equal(bodyCss['padding-right'],'','Leaving NexT page must clean up body padding-right');
}

console.log('Skin control checks passed: Butterfly tools, Bubble animation bounds/cleanup, Yilia transitions, Nexmoe and Next scroll visibility and keyboard return.');
