import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import postcss from 'postcss';

const source = fs.readFileSync('src/main/webapp/js/appearance.js','utf8');
let cases = 0;
function environment({hour = 12, stored, dark = false, blockedStorage = false} = {}) {
  const listeners = {};
  const dataset = {};
  const values = new Map(stored ? [['bolo.appearance.mode',stored]] : []);
  const meta = {content:'forest'};
  const media = {matches:dark,addEventListener(type,listener){listeners['media:' + type]=listener;}};
  const root = {dataset,style:{}};
  const document = {documentElement:root,readyState:'loading',hidden:false,body:{classList:{contains:()=>false}},
    querySelector(selector){return selector.includes('bolo-accent') ? meta : selector === '.bolo-appearance' ? {} : selector.includes('bolo-skin') ? {content:'volo-studio'} : null;},
    querySelectorAll:()=>[],addEventListener(type,listener){listeners['document:' + type]=listener;}};
  const window = {matchMedia:()=>media,addEventListener(type,listener){listeners['window:' + type]=listener;},dispatchEvent:()=>{}};
  const clock = {hour};
  class FakeDate extends Date {getHours(){return clock.hour;}}
  const sandbox = {window,document,location:{pathname:'/'},localStorage:{getItem(name){if(blockedStorage)throw Error('disabled');return values.get(name);},setItem(name,value){if(blockedStorage)throw Error('disabled');values.set(name,value);}},
    Date:FakeDate,CustomEvent:class {},MutationObserver:class {observe(){}},setInterval:()=>1};
  vm.runInNewContext(source,sandbox);
  return {api:window.BoloTheme,dataset,values,listeners,clock,media,document,window};
}
for (const [hour,expected] of [[0,'dark'],[6,'dark'],[7,'light'],[12,'light'],[18,'light'],[19,'dark'],[23,'dark']]) {
  const test=environment({hour}); assert.equal(test.api.current(),expected); assert.equal(test.api.mode(),'auto'); cases++;
}
for (const [stored,hour,dark,expected] of [['light',23,true,'light'],['dark',12,false,'dark'],['system',12,true,'dark'],['system',23,false,'light'],['invalid',23,false,'dark']]) {
  assert.equal(environment({stored,hour,dark}).api.current(),expected); cases++;
}
{
  const test=environment({hour:18});test.clock.hour=19;test.listeners['document:visibilitychange']();assert.equal(test.api.current(),'dark');
  test.api.set('light');assert.equal(test.api.current(),'light');assert.equal(test.values.get('bolo.appearance.mode'),'light');
  test.api.set('invalid');assert.equal(test.api.mode(),'light');
  test.api.set('system');test.media.matches=true;test.listeners['media:change']();assert.equal(test.api.current(),'dark');
  test.listeners['window:storage']({key:'bolo.appearance.mode',newValue:'light'});assert.equal(test.api.current(),'light');
  test.api.setAccent('ocean');assert.equal(test.dataset.boloAccent,'ocean');test.api.setAccent('unsafe');assert.equal(test.dataset.boloAccent,'ocean');
  assert.equal(test.dataset.boloSkin,'volo-studio');assert.equal(test.dataset.boloPage,'public');cases+=7;
}
{
  const test=environment({hour:21,blockedStorage:true});assert.equal(test.api.current(),'dark');test.api.set('light');assert.equal(test.api.current(),'light');cases++;
}
{
  const test=environment();let expanded='false',stopped=0,focus=0;
  const menu={hidden:true,style:{},getBoundingClientRect:()=>({height:260}),querySelector:()=>({focus(){}})};
  const widget={querySelector:selector=>selector==='[role="menu"]'?menu:trigger,appendChild(node){node.parentElement=this;}};
  const trigger={parentElement:widget,getBoundingClientRect:()=>({top:650,bottom:684,right:374}),focus(){focus++;},getAttribute:()=>expanded,setAttribute:(name,value)=>{expanded=value}};
  menu.parentElement=widget;
  test.document.querySelectorAll=selector=>selector==='.bolo-appearance'?[widget]:[];
  test.document.body.appendChild=node=>{node.parentElement=test.document.body;};
  test.window.innerWidth=390;test.window.innerHeight=740;
  const click=()=>test.listeners['document:click']({target:{closest:selector=>selector==='[data-bolo-theme-toggle]'?trigger:null},preventDefault(){},stopImmediatePropagation(){stopped++}});
  click();assert(!menu.hidden);assert.equal(expanded,'true');assert.equal(test.api.mode(),'auto','Opening a native appearance menu must preserve the preference');
  assert.equal(menu.parentElement,test.document.body,'An overflowing or transformed header must not contain the open menu');
  assert(parseFloat(menu.style.left)>=12&&parseFloat(menu.style.left)+parseFloat(menu.style.width)<=378);
  assert(parseFloat(menu.style.top)>=12&&parseFloat(menu.style.top)+260<=728,'A bottom-edge trigger must open above itself');
  click();assert(menu.hidden);assert.equal(menu.parentElement,widget);assert.equal(stopped,2,'An adopted native button must not also fire its old toggle handler');
  let escapeStopped=false;click();test.listeners['document:keydown']({target:{closest:()=>menu},key:'Escape',preventDefault(){},stopImmediatePropagation(){escapeStopped=true;}});assert(menu.hidden);assert.equal(focus,1);assert(escapeStopped,'Closing the appearance menu must not also close its parent drawer');
  click();test.listeners['window:bolo:before-navigate']();assert(menu.hidden);assert.equal(menu.parentElement,widget,'PJAX must not leave an orphaned popup in the document');
  click();test.listeners['window:scroll']();assert(menu.hidden);cases+=5;
}

// Execute the real login handler with synthetic DOM elements and fetch responses.
const account = fs.readFileSync('src/main/webapp/js/account-login.js','utf8');
function loginEnvironment(response) {
  const handlers={};const span={textContent:''};const attrs={};let now=0;let tick;let calls=0;
  const form={dataset:{mode:'1'},action:'https://example.test/oauth/bolo/login',classList:{toggle(){}},reportValidity:()=>true,
    addEventListener(type,listener){handlers[type]=listener;},setAttribute(name,value){attrs[name]=value;},removeAttribute(name){delete attrs[name];}};
  const status={textContent:'',hidden:true};const password={value:'wrong',type:'password',setAttribute(){},focus(){this.focused=true;}};
  const button={disabled:false,querySelector:()=>span};const nodes={loginForm:form,loginBtn:button,status,password,loginHeading:{},loginDescription:{},togglePassword:{addEventListener(){}}};
  const navigations=[];
  const sandbox={window:{location:{href:'https://example.test/start',origin:'https://example.test'},BoloNavigation:{onLeave(){},navigate:async (...args)=>navigations.push(args)}},document:{getElementById:id=>nodes[id]},URL,URLSearchParams,
    FormData:class { *[Symbol.iterator](){yield ['username','test-user'];yield ['password',password.value];}},
    Date:{now:()=>now},setInterval(callback){tick=callback;return 1;},clearInterval(){},fetch:async()=>{calls++;return typeof response === 'function' ? response() : response;}};
  vm.runInNewContext(account,sandbox);
  return {submit:()=>{const event={preventDefault(){event.prevented=true;}};return handlers.submit(event).then(()=>assert(event.prevented));},status,button,span,password,navigations,calls:()=>calls,advance(seconds){now+=seconds*1000;tick?.();}};
}
{
  const test=loginEnvironment({status:401,ok:false,json:async()=>({sc:false,msg:'用户名或密码错误。'})});await test.submit();
  assert.equal(test.status.textContent,'用户名或密码错误。');assert.equal(test.button.disabled,false);assert.equal(test.navigations.length,0);assert.equal(test.password.value,'wrong');cases++;
}
{
  const test=loginEnvironment({status:429,ok:false,json:async()=>({sc:false,msg:'登录尝试过于频繁',retryAfter:10})});await test.submit();
  assert(test.button.disabled);await test.submit();assert.equal(test.calls(),1);test.advance(10);assert.equal(test.button.disabled,false);cases++;
}
{
  const test=loginEnvironment({status:200,ok:true,json:async()=>({sc:true,redirect:'/admin-index.do#main'})});await test.submit();
  assert.equal(test.navigations.length,1);assert.equal(test.navigations[0][0],'https://example.test/admin-index.do#main');cases++;
}
{
  const test=loginEnvironment({status:200,ok:true,json:async()=>({sc:true,redirect:'https://untrusted.test/'})});await test.submit();assert.equal(test.navigations.length,0);assert(test.status.textContent);cases++;
}
{
  let finish;const test=loginEnvironment(()=>new Promise(resolve=>{finish=resolve;}));const first=test.submit();const second=test.submit();assert.equal(test.calls(),1);
  finish({status:401,ok:false,json:async()=>({sc:false})});await Promise.all([first,second]);cases++;
}

function luminance(hex){const rgb=hex.replace('#','').match(/../g).map(value=>parseInt(value,16)/255).map(value=>value<=.04045?value/12.92:((value+.055)/1.055)**2.4);return rgb[0]*.2126+rgb[1]*.7152+rgb[2]*.0722;}
function contrast(a,b){const values=[luminance(a),luminance(b)].sort((x,y)=>y-x);return (values[0]+.05)/(values[1]+.05);}
{
  let lazyLoads=0,inputFocus=0,triggerFocus=0;const listeners={},scrollListeners={},searchClasses=new Set(),top={style:{}};
  const search={classList:{contains:name=>searchClasses.has(name),add:name=>searchClasses.add(name),remove:name=>searchClasses.delete(name)},querySelector:()=>({focus(){inputFocus++;}})};
  const trigger={isConnected:true,classList:{contains:()=>false},focus(){triggerFocus++;},addEventListener:(type,callback)=>listeners.open=callback};
  const close={classList:{contains:name=>name==='search_close'},addEventListener:(type,callback)=>listeners.close=callback};
  const skinWindow={scrollY:300,innerWidth:1440,addEventListener:(type,callback)=>scrollListeners[type]=callback};
  const valid={dataset:{user:'Willh92',repo:'Volo-solo'},replaceChildren(link){this.link=link;}};
  const invalid={dataset:{user:'<script>',repo:'repo'},replaceChildren(){throw Error('Unsafe GitHub card should be ignored');}};
  vm.runInNewContext(fs.readFileSync('src/main/webapp/skins/bolo-sakura/js/common.js','utf8'),{
    window:skinWindow,LazyLoad:class{constructor(){lazyLoads++;}},document:{addEventListener:(type,callback)=>listeners[type]=callback,querySelectorAll:selector=>selector==='.github-card'?[valid,invalid]:selector==='.js-toggle-search, .search_close'?[trigger,close]:[],querySelector:selector=>selector==='.js-search'?search:selector==='#moblieGoTop'?top:null,createElement:()=>({})},$:()=>({hide(){}}),
  });
  assert.equal(lazyLoads,1);assert.equal(valid.link.href,'https://github.com/Willh92/Volo-solo');assert.equal(valid.link.rel,'noopener noreferrer');cases++;
  assert.equal(top.style.display,'grid','Desktop reading must expose the return-top button');skinWindow.scrollY=0;scrollListeners.scroll();assert.equal(top.style.display,'none');
  listeners.open();assert(searchClasses.has('is-visible'));assert.equal(inputFocus,1);listeners.keydown({key:'Escape'});assert(!searchClasses.has('is-visible'));assert.equal(triggerFocus,1);
  listeners.open();listeners.close();assert(!searchClasses.has('is-visible'));assert.equal(triggerFocus,2);cases++;
}
const css = fs.readFileSync('src/main/webapp/scss/appearance.css','utf8');
const root = postcss.parse(css);
const palettes=[];
root.walkRules(rule=>{
  if (!/^html\[data-bolo-accent=/.test(rule.selector))return;
  const declarations=Object.fromEntries(rule.nodes.filter(node=>node.type==='decl').map(node=>[node.prop,node.value]));
  if (!declarations['--bolo-button'])return;
  const ratio=contrast('#f7faf7',declarations['--bolo-button']);assert(ratio>=4.5,rule.selector+' button text contrast '+ratio);
  palettes.push({selector:rule.selector,buttonContrast:Number(ratio.toFixed(2))});
});
assert.equal(palettes.length,12,'Six palettes in both light and dark modes');
assert(contrast('#d6dfda','#242b2c')>=7);assert(contrast('#a1b1a8','#242b2c')>=4.5);assert(contrast('#60756a','#f6f7f3')>=4.5);
const skins=fs.readdirSync('src/main/webapp/skins').filter(name=>fs.existsSync('src/main/webapp/skins/'+name+'/skin.properties'));
for(const skin of skins)if(skin!=='volo-studio')assert(css.includes('[data-bolo-skin='+skin+']')||css.includes('[data-bolo-skin="'+skin+'"]'),skin+' requires a scoped adaptation');
assert(!fs.readFileSync('src/main/webapp/skins/bolo-butterfly/footer.ftl','utf8').includes('setInterval'));
assert(!fs.readFileSync('src/main/webapp/skins/bolo-fantastic/css/base.scss','utf8').includes('prefers-color-scheme'));
const report={cases,palettes,skins,checkedAt:new Date().toISOString(),scope:'JavaScript behavior, palette contrast and skin source coverage; browser review is separate'};
fs.mkdirSync('target/security-work',{recursive:true});fs.writeFileSync('target/security-work/appearance-checks.json',JSON.stringify(report,null,2));
console.log('Appearance checks passed:',cases,'behavior cases,',palettes.length,'palette modes,',skins.length,'skins');
