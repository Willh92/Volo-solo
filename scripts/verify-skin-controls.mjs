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
console.log('Skin control checks passed: Butterfly tools, Bubble animation bounds/cleanup, Yilia transitions, Nexmoe and Next scroll visibility and keyboard return.');
