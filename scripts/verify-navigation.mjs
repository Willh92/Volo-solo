import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

// Test the real navigation controller's routing, history and lifecycle without a browser driver.
class Target {
  handlers = new Map();
  addEventListener(type, listener) { if(!this.handlers.has(type))this.handlers.set(type,new Set());this.handlers.get(type).add(listener); }
  removeEventListener(type, listener) { this.handlers.get(type)?.delete(listener); }
  dispatchEvent(event) { for(const listener of [...this.handlers.get(event.type)||[]]) { if(typeof listener==='function')listener.call(this,event);else listener.handleEvent(event);if(event.stopped)break; } }
}
const source=fs.readFileSync('src/main/webapp/js/auth-navigation.js','utf8');
function fixture(skin,initialURL='http://localhost:18085/') {
  let sequence=0,current=new URL(initialURL),fallBacks=0;
  const timers=new Set(),calls=[],document=new Target(),window=new Target();
  const persistent={hasAttribute:()=>true};
  const node=()=>({attributes:[],childNodes:[],hasAttribute:()=>false,matches:()=>false,remove(){},setAttribute(){},removeAttribute(){}});
  const head=node();head.children=[];head.appendChild=element=>head.children.push(element);head.querySelectorAll=()=>[];
  const root=node();root.dataset={boloPage:'public',boloSkin:skin,boloLoginUrl:'/start',boloAdminUrl:'/admin-index.do'};
  Object.assign(document,{currentScript:persistent,readyState:'complete',head,documentElement:root,
    body:{...node(),classList:{contains:()=>false},replaceChildren(){},setAttribute(){},removeAttribute(){}},
    createElement:node,querySelectorAll:()=>[],importNode:value=>value,title:'Index'});
  const location={get href(){return current.href;},get origin(){return current.origin;},assign(href){fallBacks++;current=new URL(href);}};
  const history={state:null,replaceState(state,_,href){this.state=state;current=new URL(href,current);},pushState(state,_,href){this.state=state;current=new URL(href,current);}};
  Object.assign(window,{location,scrollY:0,scrollX:0,scrollTo(){},confirm:()=>true});
  for(const [schedule,cancel]of [['setTimeout','clearTimeout'],['setInterval','clearInterval'],['requestAnimationFrame','cancelAnimationFrame']]){
    window[schedule]=()=>{const id=++sequence;timers.add(id);return id;};window[cancel]=id=>timers.delete(id);
  }
  class XHR extends Target { send(){} abort(){this.aborted=true;this.dispatchEvent({type:'loadend'});} }
  const sandbox={window,document,location,history,URL,URLSearchParams,FormData,AbortController,XMLHttpRequest:XHR,HTMLFormElement:class{},
    Event:class{constructor(type){this.type=type;}},CustomEvent:class{constructor(type,options){this.type=type;this.detail=options?.detail;}},console,
    fetch:async(href,options)=>{calls.push({href,options});return{ok:true,status:200,url:href,redirected:false,headers:new Map([['Content-Type','text/html']]),text:async()=>''};},
    DOMParser:class{parseFromString(){const body={...node(),childNodes:[],querySelectorAll:()=>[]};return{head:{...node(),children:[],querySelectorAll:()=>[]},body,documentElement:{...node(),lang:'zh-CN',className:''},querySelectorAll:()=>[],title:'Article'};}}};
  vm.runInNewContext(source,sandbox);
  document.currentScript=null;
  return{window,document,timers,calls,location,history,XHR,fallbacks:()=>fallBacks};
}
const skins=fs.readdirSync('src/main/webapp/skins').filter(name=>fs.existsSync('src/main/webapp/skins/'+name+'/skin.properties'));
{
  const f=fixture('volo-studio');let selected=0,reset=0;
  f.document.getElementById=id=>id==='comment-123'?{scrollIntoView(options){assert.equal(options.block,'start');selected++;}}:null;
  f.window.scrollTo=()=>reset++;
  await f.window.BoloNavigation.navigate('/article.html#comment-123');
  assert.equal(selected,1,'Cross-page comment links must scroll to their rendered anchor');
  assert.equal(reset,0,'An anchor target must not be reset to the top');
  await f.window.BoloNavigation.navigate('/article.html#missing');
  assert.equal(reset,1,'Missing anchors fall back to the top');
  await f.window.BoloNavigation.navigate('/article.html#comment-123',{pop:true});
  assert.equal(selected,1,'History restores its saved position instead of forcing an anchor');
  assert.equal(reset,2);
  await f.window.BoloNavigation.navigate('/article.html#%broken');
  assert.equal(f.fallbacks(),0,'Malformed fragments must not force a page reload');
}
{
  const f=fixture('bolo-pinghsu','http://localhost:18085/?skin=bolo-pinghsu');
  await f.window.BoloNavigation.navigate('/article.html');
  assert.equal(new URL(f.calls.at(-1).href).searchParams.get('skin'),'bolo-pinghsu','Public links retain an explicit skin preview');
  await f.window.BoloNavigation.navigate('/?p=2');
  assert.equal(new URL(f.calls.at(-1).href).searchParams.get('p'),'2');
  assert.equal(new URL(f.calls.at(-1).href).searchParams.get('skin'),'bolo-pinghsu','Pagination retains the preview');
  await f.window.BoloNavigation.navigate('/article.html?skin=volo-studio');
  assert.equal(new URL(f.calls.at(-1).href).searchParams.get('skin'),'volo-studio','An explicit new preview takes priority');
  await f.window.BoloNavigation.navigate('/start');
  assert.equal(f.calls.at(-1).href,'http://localhost:18085/start','Login does not inherit a preview');
  const back=fixture('bolo-pinghsu','http://localhost:18085/?skin=bolo-pinghsu');
  await back.window.BoloNavigation.navigate('/article.html',{pop:true});
  assert.equal(new URL(back.calls.at(-1).href).searchParams.get('skin'),null,'History restores the original URL without adding a preview');
  const modified=fixture('bolo-pinghsu','http://localhost:18085/?skin=bolo-pinghsu');
  const anchor={href:'http://localhost:18085/article.html',target:'_blank',hasAttribute:()=>false};
  modified.document.dispatchEvent({type:'click',button:0,ctrlKey:true,target:{closest:()=>anchor}});
  assert.equal(new URL(anchor.href).searchParams.get('skin'),'bolo-pinghsu','A preview opened in a new tab still uses the selected skin');
  assert.equal(modified.calls.length,0,'Modified clicks keep the native new-tab behavior');
}
for(const skin of skins){
  const f=fixture(skin);let stale=0,persistent=0;
  f.document.currentScript={hasAttribute:()=>true};f.window.addEventListener('resize',()=>persistent++);f.document.currentScript=null;
  f.window.addEventListener('resize',()=>stale++);
  f.window.setInterval(()=>{},100);f.window.requestAnimationFrame(()=>{});
  const xhr=new f.XHR();xhr.send();
  await f.window.BoloNavigation.navigate('/article.html');
  assert.equal(f.location.href,'http://localhost:18085/article.html',skin);
  assert.equal(f.calls[0].options.headers['X-Bolo-PJAX'],'true');
  assert.equal(f.fallbacks(),0,skin+' should not force a full document load');
  assert.equal(f.timers.size,0,skin+' must release old animation tasks');
  assert(xhr.aborted,skin+' must abort old page XHRs');
  f.window.dispatchEvent({type:'resize'});assert.equal(stale,0);assert.equal(persistent,1);
  assert.equal(f.history.state.boloAuthPjax,true);
  await f.window.BoloNavigation.navigate('/start');
  assert.equal(f.location.href,'http://localhost:18085/start');
}
{
  const f=fixture('volo-studio');
  function click(href,attrs={}){
    const anchor={href,target:'',hasAttribute:name=>Boolean(attrs[name]),...attrs};
    const event={type:'click',button:0,target:{closest:()=>anchor},preventDefault(){this.defaultPrevented=true;},stopImmediatePropagation(){this.stopped=true;}};
    f.document.dispatchEvent(event);return event;
  }
  for(const href of ['https://external.example/article.html','http://localhost:18085/rss.xml','http://localhost:18085/images/a.png','http://localhost:18085/import/markdown','http://localhost:18085/console/export/json','http://localhost:18085/#comments']){
    assert(!click(href).defaultPrevented,href+' should retain native navigation');
  }
  assert(!click('http://localhost:18085/article.html',{download:true}).defaultPrevented);
  assert(!click('http://localhost:18085/article.html',{target:'_blank'}).defaultPrevented);
  assert(click('http://localhost:18085/article.html',{target:'_self'}).defaultPrevented);
  assert(click('http://localhost:18085/search?keyword=notes',{target:'_blank'}).defaultPrevented,'Search should remain in the current tab');
  assert(click('http://localhost:18085/article.html').defaultPrevented);
  await new Promise(resolve=>setImmediate(resolve));
  assert.equal(f.fallbacks(),0);
}
{
  const events=new Map(),tasks=new Map(),cleanups=[];let next=0,scroll=0;
  const classes=()=>({current:false,toggle(name,on){this[name]=on;}});
  const links=[0,800,1600].map((top,index)=>({hash:'#heading-'+index,parentElement:{classList:classes()},attrs:{},setAttribute(key,value){this.attrs[key]=value;},removeAttribute(key){delete this.attrs[key];},top}));
  const chapters=new Map(links.map((link,index)=>['heading-'+index,{id:'heading-'+index,getBoundingClientRect:()=>({top:link.top-scroll})}]));
  const root={querySelectorAll:()=>links},panelEvents=new Map(),mediaEvents=new Map(),documentEvents=new Map();let focused=false,roots=[root];
  const panel={open:true,contains:target=>target===panel,addEventListener:(type,listener)=>panelEvents.set(type,listener),removeEventListener:type=>panelEvents.delete(type),querySelector:()=>({focus(){focused=true;}})};
  const media={matches:true,addEventListener:(type,listener)=>mediaEvents.set(type,listener),removeEventListener:type=>mediaEvents.delete(type)};
  const window={matchMedia:query=>query.includes('prefers-reduced-motion')?{matches:false}:media,addEventListener:(type,listener)=>events.set(type,listener),removeEventListener:(type,listener)=>{if(events.get(type)===listener)events.delete(type);},BoloNavigation:{onLeave:cleanup=>cleanups.push(cleanup)}};
  const document={readyState:'complete',addEventListener:(type,listener,capture)=>documentEvents.set(type+(capture?':capture':''),listener),removeEventListener:(type,listener,capture)=>documentEvents.delete(type+(capture?':capture':'')),querySelectorAll:selector=>selector==='[data-reading-toc]'?[panel]:roots,getElementById:id=>chapters.get(id)};
  const sandbox={window,document,BoloNavigation:window.BoloNavigation,requestAnimationFrame:callback=>{tasks.set(++next,callback);return next;},cancelAnimationFrame:id=>tasks.delete(id)};
  vm.runInNewContext(fs.readFileSync('src/main/webapp/js/toc-navigation.js','utf8'),sandbox);
  assert.equal(panel.open,false,'Narrow screens start with a compact directory button');
  panel.open=true;panelEvents.get('keydown')({type:'keydown',key:'Escape',target:{closest:()=>panel}});assert(!panel.open);assert(focused,'Escape returns focus to the directory button');
  panel.open=true;panelEvents.get('click')({type:'click',target:{closest:selector=>selector==='[data-reading-toc]'?panel:{}}});assert(!panel.open,'Selecting a chapter closes the mobile panel');
  panel.open=true;documentEvents.get('click')({target:panel});assert(panel.open,'An inside click keeps the mobile directory open');
  documentEvents.get('click')({target:{}});assert(!panel.open,'An outside click closes the mobile directory before opening other controls');
  panel.open=true;window.BoloTOC.close();assert(!panel.open,'Opening another menu can dismiss the mobile directory without relying on event bubbling');
  media.matches=false;mediaEvents.get('change')();assert(panel.open,'Desktop layout opens the sidebar');
  window.BoloTOC.close();assert(panel.open,'Other controls must not fold the desktop directory');
  assert.equal(links[0].attrs['aria-current'],'location');
  scroll=900;events.get('scroll')();events.get('scroll')();assert.equal(tasks.size,1,'Scroll work must coalesce into one frame');
  for(const [id,callback]of tasks){tasks.delete(id);callback();}
  assert.equal(links[1].attrs['aria-current'],'location');assert(!links[0].attrs['aria-current']);
  scroll=1700;events.get('scroll')();for(const [id,callback]of tasks){tasks.delete(id);callback();}
  assert.equal(links[2].attrs['aria-current'],'location');
  root.clientHeight=120;root.scrollHeight=300;root.scrollTop=0;root.getBoundingClientRect=()=>({top:200});
  links.forEach((link,index)=>{link.closest=()=>root;link.getBoundingClientRect=()=>({top:200+index*70-root.scrollTop,bottom:260+index*70-root.scrollTop});});
  window.getComputedStyle=()=>({overflowY:'auto',scrollPaddingTop:'96px'});
  events.get('resize')();for(const [id,callback]of tasks){tasks.delete(id);callback();}
  assert.equal(root.scrollTop,86,'The complete active directory entry, including its second line, must be visible');
  assert.equal(scroll,1700,'Revealing a directory entry must never move the article');
  root.scrollTop=160;events.get('resize')();for(const [id,callback]of tasks){tasks.delete(id);callback();}
  assert.equal(root.scrollTop,134,'Entries clipped above the directory scroll back into view');
  root.clientHeight=0;root.scrollTop=0;events.get('resize')();for(const [id,callback]of tasks){tasks.delete(id);callback();}
  assert.equal(root.scrollTop,0,'Closed directory panes do not scroll hidden content');
  root.clientHeight=120;events.get('resize')();for(const [id,callback]of tasks){tasks.delete(id);callback();}
  assert.equal(root.scrollTop,86,'The same active entry is revealed when the pane becomes visible again');
  window.getComputedStyle=(element,pseudo)=>pseudo?{display:'block',content:'"目录"',height:'20px',paddingTop:'8px',paddingBottom:'12px'}:{overflowY:'auto',scrollPaddingTop:'96px'};
  root.scrollTop=160;events.get('resize')();for(const [id,callback]of tasks){tasks.delete(id);callback();}
  assert.equal(root.scrollTop,94,'The selected entry must stay below a sticky directory heading');
  const scrollCalls=[],hashCalls=[];Object.defineProperty(window,'scrollY',{get:()=>scroll});
  window.scrollTo=options=>{scrollCalls.push(options);scroll=options.top};window.history={state:{boloAuthPjax:true},replaceState:(state,title,hash)=>hashCalls.push(hash)};
  const tick=now=>{for(const [id,callback]of [...tasks]){tasks.delete(id);callback(now)}};
  documentEvents.get('click:capture')({target:{closest:()=>links[1]},preventDefault(){},stopImmediatePropagation(){}});
  tick(0);tick(100);assert(scroll<1700&&scroll>704,'A chapter jump must animate through intermediate positions');tick(460);
  assert.equal(scrollCalls.at(-1).behavior,'instant');assert.equal(scrollCalls.at(-1).top,704);assert.equal(hashCalls[0],'#heading-1');assert.equal(tasks.size,0);
  const calls=scrollCalls.length;documentEvents.get('click:capture')({ctrlKey:true,target:{closest:()=>links[1]},preventDefault(){throw Error('Modified link click must stay native')}});assert.equal(scrollCalls.length,calls);
  links[2].top=100000;documentEvents.get('click:capture')({target:{closest:()=>links[2]},preventDefault(){},stopImmediatePropagation(){}});tick(1000);tick(1460);assert.equal(scroll,99904,'Even a very distant chapter finishes within 460ms');assert.equal(tasks.size,0);
  chapters.get('heading-1').isConnected=true;
  documentEvents.get('click:capture')({target:{closest:()=>links[1]},preventDefault(){},stopImmediatePropagation(){}});tick(1500);links[1].top=850;tick(1960);assert.equal(scroll,754,'Chapter alignment follows content moving during a skin entrance animation');assert.equal(tasks.size,0);
  documentEvents.get('click:capture')({target:{closest:()=>links[0]},preventDefault(){},stopImmediatePropagation(){}});tick(2000);tick(2060);events.get('wheel')();assert.equal(tasks.size,0,'Manual scrolling immediately cancels the animation');
  const animations=[];panel.style={overflow:'auto'};panel.classList={add(){},remove(){}};
  panel.getBoundingClientRect=()=>({width:210,height:panel.open?240:28});
  window.getComputedStyle=()=>({padding:'0px',borderRadius:'6px'});
  panel.animate=(frames,options)=>{const animation={cancelled:false,cancel(){this.cancelled=true;}};animations.push({frames,options,animation});return animation;};
  const summary={parentElement:panel};const summaryEvent=()=>({type:'click',target:{closest:selector=>selector==='summary'?summary:selector==='[data-reading-toc]'?panel:null},preventDefault(){}});
  panelEvents.get('click')(summaryEvent());assert(panel.open,'Closing content stays available for its height animation');assert.equal(animations[0].frames[1].height,'28px');animations[0].animation.onfinish();assert(!panel.open);assert.equal(panel.style.overflow,'auto');
  panelEvents.get('click')(summaryEvent());assert(panel.open);assert.equal(animations[1].frames[0].height,'28px');assert.equal(animations[1].frames[1].height,'240px');
  events.get('scroll')();cleanups[0]();assert.equal(tasks.size,0);assert(!events.has('scroll'),'Leaving the page must remove chapter tracking');assert.equal(panelEvents.size,0);assert.equal(mediaEvents.size,0);assert.equal(documentEvents.size,0);
  assert(animations[1].animation.cancelled,'Leaving during animation cancels pending work');assert.equal(panel.style.overflow,'auto');
  roots=[];window.BoloTOC.refresh();assert(!events.has('scroll'),'Articles without headings must not keep directory listeners');
}

{
  let click;const opened=[];
  const $=value=>typeof value==='string'?{on(event,selector,handler){click=handler;}}:{hasClass:()=>!!value.prevent};
  const sandbox={window:{open:url=>opened.push(url)},document:{},$,console,setTimeout,clearTimeout};
  vm.runInNewContext(fs.readFileSync('src/main/webapp/js/common.js','utf8'),sandbox);
  sandbox.Util.previewImg();
  click.call({src:'/github-stats/reader.svg',closest:()=>({href:'https://github.com/reader'})});
  assert.equal(opened.length,0,'Linked GitHub images must use their profile link instead of opening a second image tab');
  click.call({src:'/photo.jpg',closest:()=>null});assert.deepEqual(opened,['/photo.jpg']);
  click.call({src:'/protected.jpg',prevent:true,closest:()=>null});assert.equal(opened.length,1);
}

{
  const attrs={'aria-expanded':'false'},states=[];
  const button={getAttribute:name=>attrs[name],setAttribute:(name,value)=>attrs[name]=value};
  const menu={classList:{toggle:(name,open)=>states.push([name,open])}};
  const sandbox={document:{querySelector:()=>button,getElementById:()=>menu}};
  vm.runInNewContext(fs.readFileSync('src/main/webapp/skins/bolo-xups/js/common.js','utf8').replace(/Skin\.init\(\)\s*$/, ''),sandbox);
  sandbox.Skin._initMenu();button.onclick();assert.equal(attrs['aria-expanded'],'true');
  button.onclick();assert.equal(attrs['aria-expanded'],'false');assert.deepEqual(states,[['is-open',true],['is-open',false]]);
  sandbox.document.querySelector=()=>null;sandbox.Skin._initMenu();
}

// PJAX navigation must preserve exact <head> stylesheet cascade order (e.g. appearance.css after admin.css).
{
  let current=new URL('http://localhost:18085/start');
  const headChildren=[];
  const makeLink=href=>({
    tagName:'LINK',href,attributes:[],childNodes:[],
    hasAttribute:()=>false,matches:sel=>sel==='link[rel="stylesheet"]',
    remove(){const idx=headChildren.indexOf(this);if(idx>=0)headChildren.splice(idx,1);},
    compareDocumentPosition(other){const a=headChildren.indexOf(this),b=headChildren.indexOf(other);return a>=0&&b>=0&&b>a?4:2;}
  });
  headChildren.push(makeLink('http://localhost:18085/js/lib/vditor/dist/index.css'),makeLink('http://localhost:18085/scss/account.css'),makeLink('http://localhost:18085/scss/appearance.css'));
  const node=()=>({attributes:[],childNodes:[],hasAttribute:()=>false,matches:()=>false,remove(){},setAttribute(){},removeAttribute(){}});
  const head={...node(),children:headChildren,querySelectorAll:sel=>sel==='link[rel="stylesheet"]'?headChildren.filter(c=>c.tagName==='LINK'):[],appendChild(el){const idx=headChildren.indexOf(el);if(idx>=0)headChildren.splice(idx,1);headChildren.push(el);queueMicrotask(()=>el.onload?.());return el;}};
  const doc=Object.assign(new Target(),{currentScript:{hasAttribute:()=>true},readyState:'complete',head,documentElement:{...node(),dataset:{boloPage:'auth',boloSkin:'',boloLoginUrl:'/start',boloAdminUrl:'/admin-index.do'}},body:{...node(),classList:{contains:()=>false},replaceChildren(){},setAttribute(){},removeAttribute(){}},createElement:node,querySelectorAll:()=>[],importNode:el=>el.tagName==='LINK'?makeLink(el.href):el,title:'Start'});
  const loc={get href(){return current.href;},get origin(){return current.origin;},assign(h){current=new URL(h);}};
  const hist={state:null,replaceState(s,_,h){this.state=s;current=new URL(h,current);},pushState(s,_,h){this.state=s;current=new URL(h,current);}};
  const win=Object.assign(new Target(),{location:loc,scrollY:0,scrollX:0,scrollTo(){},confirm:()=>true,setTimeout:()=>1,clearTimeout(){},setInterval:()=>1,clearInterval(){},requestAnimationFrame:()=>1,cancelAnimationFrame(){}});
  const incomingLinks=['http://localhost:18085/js/lib/vditor/dist/index.css','http://localhost:18085/scss/admin.css','http://localhost:18085/scss/console-theme.css','http://localhost:18085/scss/appearance.css'].map(makeLink);
  class XHR extends Target{send(){}abort(){}}
  vm.runInNewContext(source,{window:win,document:doc,location:loc,history:hist,URL,URLSearchParams,FormData,AbortController,XMLHttpRequest:XHR,HTMLFormElement:class{},Event:class{constructor(t){this.type=t;}},CustomEvent:class{constructor(t,o){this.type=t;this.detail=o?.detail;}},console,fetch:async href=>({ok:true,status:200,url:href,redirected:false,headers:new Map([['Content-Type','text/html']]),text:async()=>''}),DOMParser:class{parseFromString(){return{head:{...node(),children:incomingLinks,querySelectorAll:sel=>sel==='link[rel="stylesheet"]'?incomingLinks:[]},body:{...node(),childNodes:[],querySelectorAll:()=>[]},documentElement:{...node(),lang:'zh-CN',className:''},querySelectorAll:()=>[],title:'Admin'};}}});
  doc.currentScript=null;
  await win.BoloNavigation.navigate('/admin-index.do');
  assert.deepEqual(head.querySelectorAll('link[rel="stylesheet"]').map(l=>l.href),incomingLinks.map(l=>l.href),'PJAX navigation must keep appearance.css after admin.css and console-theme.css');
}

// Singleton vendor deduplication & SweetAlert <style> preservation across PJAX transitions.
{
  let current=new URL('http://localhost:18085/');
  const headChildren=[],executedSrcs=[];
  const makeNode=(tagName,extra={})=>{
    const attrs=new Map();
    return {
      tagName,attributes:[],childNodes:[],...extra,
      hasAttribute:k=>attrs.has(k),
      getAttribute:k=>attrs.get(k)??'',
      setAttribute(k,v){const s=String(v);attrs.set(k,s);if(k==='src'||k==='href'||k==='type')this[k]=s;this.attributes=[...attrs.entries()].map(([name,value])=>({name,value}));},
      removeAttribute(k){attrs.delete(k);},
      matches:()=>false,
      remove(){const i=headChildren.indexOf(this);if(i>=0)headChildren.splice(i,1);}
    };
  };
  // Initial full-page load SweetAlert <style> (injected without data-bolo-navigation)
  const swalStyle=makeNode('STYLE',{textContent:'.swal-overlay{opacity:0}.swal-modal{background:#fff}'});
  headChildren.push(swalStyle);
  const head={...makeNode('HEAD'),children:headChildren,querySelectorAll:()=>[],appendChild(el){headChildren.push(el);return el;}};
  const body={...makeNode('BODY'),classList:{contains:()=>false},replaceChildren(){},appendChild(el){
    if(el.tagName==='SCRIPT'&&el.src){
      executedSrcs.push(el.src);
      queueMicrotask(()=>el.onload?.());
    }
    return el;
  }};
  const doc=Object.assign(new Target(),{
    currentScript:{hasAttribute:()=>true},readyState:'complete',head,
    documentElement:{...makeNode('HTML'),dataset:{boloPage:'public',boloSkin:'bolo-nexmoe',boloLoginUrl:'/start',boloAdminUrl:'/admin-index.do'}},
    body,
    createElement:tag=>makeNode(String(tag).toUpperCase()),
    querySelectorAll:()=>[],importNode:v=>v,title:'Home'
  });
  const loc={get href(){return current.href;},get origin(){return current.origin;},assign(h){current=new URL(h);}};
  const hist={state:null,replaceState(s,_,h){this.state=s;current=new URL(h,current);},pushState(s,_,h){this.state=s;current=new URL(h,current);}};
  const jq=Object.assign(()=>({on(){},off(){},empty(){}}),{isReady:true,holdReady(){},fn:{ready(){},fancybox(){}},cookie(){}});
  const win=Object.assign(new Target(),{
    location:loc,scrollY:0,scrollX:0,scrollTo(){},confirm:()=>true,
    setTimeout:()=>1,clearTimeout(){},setInterval:()=>1,clearInterval(){},requestAnimationFrame:()=>1,cancelAnimationFrame(){},
    jQuery:jq,$:jq,
    swal(){},
    mdui:{mutation(){}},
    LazyLoad:function(){},
    Typed:function(){},
    Swiper:function(){},
    TweenMax:function(){}
  });
  const makeScript=src=>{const s=makeNode('SCRIPT',{type:''});s.setAttribute('src',src);return s;};
  const incomingScripts=[
    'http://localhost:18085/js/bolo/sweetalert.min.js?1',
    'http://localhost:18085/skins/bolo-nexmoe/source/mdui_043tiny/js/mdui.min.js?1',
    'http://localhost:18085/js/lib/fancybox/jquery.fancybox.min.js?1',
    'http://localhost:18085/js/lib/jquery/jquery.cookie.min.js?1',
    'http://localhost:18085/skins/bolo-sakura/js/lazyload.iife.min.js?1',
    'http://localhost:18085/skins/bolo-butterfly/js/typed.min.js?1',
    'http://localhost:18085/skins/bolo-fantastic/js/swiper.min.js?1',
    'http://localhost:18085/skins/bolo-bubble/js/TweenMax.min.js?1',
    'http://localhost:18085/skins/bolo-nexmoe/js/app.min.js?1'
  ].map(makeScript);
  class XHR extends Target{send(){}abort(){}}
  vm.runInNewContext(source,{
    window:win,document:doc,location:loc,history:hist,URL,URLSearchParams,FormData,AbortController,XMLHttpRequest:XHR,HTMLFormElement:class{},
    Event:class{constructor(t){this.type=t;}},CustomEvent:class{constructor(t,o){this.type=t;this.detail=o?.detail;}},console,
    fetch:async href=>({ok:true,status:200,url:href,redirected:false,headers:new Map([['Content-Type','text/html']]),text:async()=>''}),
    DOMParser:class{parseFromString(){return{head:{...makeNode('HEAD'),children:[],querySelectorAll:()=>[]},body:{...makeNode('BODY'),childNodes:[],querySelectorAll:()=>[]},documentElement:{...makeNode('HTML'),lang:'zh-CN',className:''},querySelectorAll:sel=>sel==='script'?incomingScripts:[],title:'Post'};}}
  });
  doc.currentScript=null;
  await win.BoloNavigation.navigate('/articles/1.html');
  assert(headChildren.includes(swalStyle),'SweetAlert <style> in <head> must survive PJAX cleanup');
  assert(swalStyle.hasAttribute('data-bolo-navigation'),'SweetAlert <style> must be marked persistent for future navigations');
  assert.deepEqual(executedSrcs,['http://localhost:18085/skins/bolo-nexmoe/js/app.min.js?1'],'Already-loaded singleton vendor scripts (sweetalert, mdui, fancybox, cookie, lazyload, typed, swiper, TweenMax) must be skipped across PJAX');

  const commonHead=fs.readFileSync('src/main/webapp/common-template/macro-common_head.ftl','utf8');
  assert(!commonHead.includes('user-scalable=0')&&!commonHead.includes('maximum-scale=1.0'),'Viewport meta must allow mobile pinch-to-zoom');
  assert(/<script\s+data-bolo-navigation[^>]*jquery\.min\.js/.test(commonHead),'macro-common_head.ftl must load persistent jquery.min.js');
  for(const skin of ['bolo-butterfly','bolo-emiya','bolo-NeoEase','bolo-next','bolo-yilia']){
    const footer=fs.readFileSync(`src/main/webapp/skins/${skin}/footer.ftl`,'utf8');
    assert(!footer.includes('jquery.min.js'),`${skin}/footer.ftl must not reload duplicate jquery.min.js`);
  }
}

console.log('Navigation checks passed: '+skins.length+' skins, page cleanup, history, native-link exclusions and chapter tracking');
