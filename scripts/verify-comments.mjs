import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import {webcrypto} from 'node:crypto';

const classes=new Set(),attrs=new Map(),styles={setProperty(name,value){this[name]=value}};
const host=()=>({setAttribute(name,value){this[name]=value},removeAttribute(name){delete this[name]}});
let exitFullscreen=0,fullscreen=false,focus=0,value='保留评论草稿',request,requests=0,added=0,editorRendered=true,editorHost=host();
const trigger={setAttribute(name,value){this[name]=value},focus(options){assert(options.preventScroll);focus++}};
const editor={parentElement:{transformed:true},style:styles,classList:{contains:name=>classes.has(name),toggle(name,on){on?classes.add(name):classes.delete(name)}},
  setAttribute:(name,value)=>attrs.set(name,value),removeAttribute:name=>attrs.delete(name),querySelector:()=>fullscreen?{click(){fullscreen=false;exitFullscreen++}}:null};
const body={appendChild(node){node.parentElement=body}};
const output={},button={disabled:false};
function $(selector){
  if(selector==='#soloEditor')return {length:1,hasClass:name=>classes.has(name)};
  if(selector==='#soloEditorComment')return {hasClass:()=>editorRendered};
  if(selector===window)return {width:()=>390};
  if(selector==='#soloEditorAdd')return {attr(){button.disabled=true},removeAttr(){button.disabled=false},text(text){output[selector]=text}};
  if(selector==='#boloUser'||selector==='#boloSite')return {val:()=>''};
  return {text(text){output[selector]=text},html(){throw Error('Server error text must not be interpreted as HTML')}};
}
$.extend=Object.assign;$.cookie=()=>'';$.ajax=options=>{request=options;requests++};
const vditor={vditor:{wysiwyg:{}},focus(){},getValue:()=>value,setValue:next=>{value=next}};
const window={innerHeight:740,vditor,location:{href:'http://localhost/fixture.html'}};
const document={body,getElementById:id=>id==='soloEditor'?editor:id==='comment'?trigger:id==='soloEditorComment'?editorHost:null};
const sandbox={window,document,$,vditor,URL,swal(){},Label:{servePath:'http://localhost',staticServePath:'https://unused-static.invalid'},console,setTimeout,clearTimeout,requestAnimationFrame:callback=>setTimeout(callback,0)};
vm.runInNewContext(fs.readFileSync('src/main/webapp/js/common.js','utf8'),sandbox);
{
  const makeImage=(source,complete=false,width=0)=>({src:source,complete,naturalWidth:width,getAttribute(name){return name==='src'?this.src:'访客甲'}});
  const missing=makeImage(''),placeholder=makeImage('/images/volo-mark.svg'),broken=makeImage('/missing-avatar.png',true),
    pending=makeImage('https://example.test/avatar.png'),existing=makeImage('/avatar.jpg',true,48);
  sandbox.Util.bindCommentAvatars({querySelectorAll:()=>[missing,placeholder,broken,pending,existing]});
  for(const image of [missing,placeholder,broken]) assert(image.src.startsWith('data:image/svg+xml,'),'Missing avatars must generate local nickname initials');
  assert.equal(existing.src,'/avatar.jpg','A working custom avatar must be preserved');
  assert.equal(pending.src,'https://example.test/avatar.png');pending.onerror();assert(pending.src.startsWith('data:image/svg+xml,'));assert.equal(pending.onerror,null);
  assert.equal(missing.src,placeholder.src,'The same nickname must produce the same avatar');
  assert.notEqual(sandbox.Util.generatedAvatar('访客甲'),sandbox.Util.generatedAvatar('访客乙'));
  const avatar = name => decodeURIComponent(sandbox.Util.generatedAvatar(name).split(',',2)[1]);
  assert(avatar('访客甲').includes('>访</text>'));
  assert(avatar('Allen').includes('>AL</text>'));
  assert(avatar('Alice Smith').includes('>AS</text>'));
  assert(avatar('visualGuest').includes('>VG</text>'));
  assert(avatar('visualReply').includes('>VR</text>'));
  assert.notEqual(avatar('visualGuest').match(/<circle[^>]+fill="([^"]+)"/)[1],avatar('visualReply').match(/<circle[^>]+fill="([^"]+)"/)[1]);
  assert(avatar('👋').includes('>访</text>'));
  assert(!avatar('<script>alert(1)</script>').includes('<script>'));
}
sandbox.Util.loadVditorRuntime=()=>sandbox.Util.loadVditor(); // Asset loading has its own concurrent-runtime checks.
vm.runInNewContext(fs.readFileSync('src/main/webapp/js/page.js','utf8'),sandbox);
const page=new sandbox.Page({commentContentCannotEmptyLabel:'评论内容只能为 2 到 500 个字符!',oId:'fixture-article'});
page.commentEditorHost=editorHost;page.commentEditorReady=true;
page.addCommentAjax=()=>added++;

page.toggleEditor();assert(classes.has('is-open'));assert.equal(editor.parentElement,body);assert.equal(styles.bottom,'0px');assert.equal(attrs.get('aria-hidden'),'false');assert(!attrs.has('inert'));
window.visualViewport={height:420,offsetTop:30};page.updateEditorViewport();assert.equal(styles.bottom,'290px');assert.equal(styles.maxHeight,'404px');
fullscreen=true;page.setEditorOpen(false);assert(!classes.has('is-open'));assert.equal(exitFullscreen,1);assert(attrs.has('inert'));assert.equal(focus,1);assert.equal(value,'保留评论草稿');
page.toggleEditor('reply-id','读者');assert.equal(page.currentCommentId,'reply-id');assert.equal(output['#soloEditorReplyTarget'],'@读者');

document.documentElement={dataset:{boloSkin:'bolo-nexmoe'}};
value='x'.repeat(500);page.submitComment();page.submitComment();assert.equal(requests,1,'Pending submissions must not be duplicated');assert(button.disabled);assert.equal(JSON.parse(request.data).commentContent.length,500);
assert.equal(request.url,'http://localhost/article/comments?skin=bolo-nexmoe','New comments must use the skin currently being previewed');
assert.equal(sandbox.Page.prototype.addCommentAjax.call(page,undefined),false,'An absent fragment must never insert the text undefined');
assert.equal(sandbox.Page.prototype.addCommentAjax.call(page,''),false);
request.success({sc:false,msg:'<img src=x onerror=alert(1)>'});assert.equal(output['#soloEditorError'],'<img src=x onerror=alert(1)>');request.complete();assert(!button.disabled);assert(!page.commentPending);
page.submitComment();request.error();request.complete();assert(output['#soloEditorError'].includes('重试'));assert(!page.commentPending);assert.equal(value.length,500);
page.submitComment();request.error({status:429,responseJSON:{msg:'留言过于频繁。'},getResponseHeader:()=> '15'});request.complete();assert(output['#soloEditorError'].includes('15 秒'));assert.equal(value.length,500,'A rate limit must preserve the draft');assert(!page.commentPending);
page.submitComment();request.success({sc:true,cmtTpl:'fixture'});request.complete();assert.equal(value,'');assert(!classes.has('is-open'));assert.equal(added,1);
const before=requests;value='x'.repeat(501);page.submitComment();assert.equal(requests,before,'An oversized comment must not be submitted');

let constructions=0,readyOptions,editorFocus=0;
const pendingEditor={focus(){editorFocus++},getValue(){throw Error('Uninitialized editor was read')}};
sandbox.Util.addScript=function(){};
sandbox.Vditor=function(id,options){assert.equal(id,'soloEditorComment');constructions++;readyOptions=options;return pendingEditor};
sandbox.Vditor.prototype.getValue=function(){};
editorRendered=false;window.vditor=null;page.commentEditorReady=false;page.setEditorOpen(false);
page.toggleEditor();assert.equal(constructions,0,'The cold constructor must yield so the dialog can paint first');assert.equal(editorFocus,0);
assert(button.disabled,'Loading must disable the submit button before the editor appears');
let emailPrompts=0;sandbox.swal=()=>{emailPrompts++;return Promise.resolve(null)};
page.requestCommentSubmission(true);assert.equal(emailPrompts,0,'Loading must not open the email prompt');
page.toggleEditor('pending-reply','读者');assert.equal(constructions,0,'Opening a reply during loading must reuse the pending task');
page.submitComment();assert.equal(requests,before,'An uninitialized editor must not submit');assert(output['#soloEditorError'].includes('正在加载'));
await page.commentEditorPromise;await Promise.resolve();assert.equal(constructions,1);
assert.equal(readyOptions.cdn,'http://localhost/js/lib/vditor');
assert.equal(readyOptions.hint.emojiPath,'http://localhost/js/lib/vditor/dist/images/emoji');
pendingEditor.vditor={wysiwyg:{}};
page.requestCommentSubmission(true);assert.equal(emailPrompts,0,'Constructing the editor is not equivalent to its after callback');
readyOptions.after();assert.equal(editorFocus,1);assert(!button.disabled,'The ready callback enables submission');
editorHost=host();readyOptions.after();assert.equal(editorFocus,1,'An old initialization callback must not focus a different page');

page.setEditorOpen(false);window.vditor=null;delete pendingEditor.vditor;
const warm=new sandbox.Page({oId:'warm'});await warm.prepareCommentEditor();readyOptions.after();
assert.equal(constructions,2);assert(!classes.has('is-open'));assert.equal(editorFocus,1,'Idle warmup must not open or focus the editor');
assert(!editorHost['aria-busy']);
{
  let submitted=0,prompts=0;
  const mailPage=new sandbox.Page({oId:'mail',commentAdministrator:true,commentContentCannotEmptyLabel:'评论长度不正确'});
  window.vditor={vditor:{wysiwyg:{}},getValue:()=> '已经就绪的评论'};
  mailPage.commentEditorHost=editorHost;mailPage.commentEditorReady=true;
  mailPage.submitComment=()=>submitted++;
  sandbox.swal=()=>{prompts++};
  mailPage.requestCommentSubmission(true);
  assert.equal(submitted,1,'Administrators send directly without guest identity or notification prompts');
  assert.equal(prompts,0);

  const storage=new Map();window.crypto=webcrypto;window.localStorage={getItem:key=>storage.get(key),setItem:(key,value)=>storage.set(key,value)};
  const node=value=>({value:value||'',dataset:{},hidden:false,removeAttribute(){},focus(){this.focused=true},appendChild(input){input.parentElement=this}});
  let elements;
  const reset=()=>{ elements={soloEditorIdentity:node(),soloEditorNameSlot:node(),soloEditorSiteSlot:node(),
    boloUser:node(),boloSite:node(),soloEditorEmail:node(),soloEditorVisitorCard:node(),soloEditorVisitorName:node(),soloEditorVisitorAvatar:node(),soloEditorCaptcha:node()}; };
  reset();const originalGet=document.getElementById;
  document.getElementById=id=>elements[id]||originalGet(id);
  const guest=new sandbox.Page({oId:'guest',commentContentCannotEmptyLabel:'评论长度不正确'});
  guest.commentEditorHost=editorHost;guest.commentEditorReady=true;guest.submitComment=()=>submitted++;
  let captchaValid=true,mounted=0;
  window.VoloCaptcha={mount:(host,purpose,changed)=>{
    assert.equal(host,elements.soloEditorCaptcha,'The challenge belongs inside the existing reply composer');
    assert.equal(purpose,'comment');mounted++;changed({ready:true,required:true});
    return {valid:()=>captchaValid,payload:()=>({captchaId:'test-id',captchaAnswer:'AB234'}),ensureFresh(){},destroy(){}};
  }};
  guest.prepareCommentIdentity(false);
  assert(!elements.soloEditorIdentity.hidden);assert(elements.soloEditorVisitorCard.hidden);
  guest.requestCommentSubmission(false);await Promise.resolve();assert.equal(submitted,1,'A guest must provide a nickname');
  elements.boloUser.value='本地访客';elements.boloSite.value='https://example.test/';elements.soloEditorEmail.value='invalid';
  guest.requestCommentSubmission(false);await Promise.resolve();assert.equal(submitted,1,'Invalid notification emails stay inline without submitting');
  elements.soloEditorEmail.value='visitor@example.test';guest.requestCommentSubmission(false);await Promise.resolve();
  assert.equal(submitted,2);assert.equal(prompts,0,'Guest email is inline, never a second dialog');
  assert.equal(mounted,1);assert.equal(guest.commentCaptcha.captchaId,'test-id');
  captchaValid=false;guest.requestCommentSubmission(false);
  assert.equal(submitted,2,'An incomplete inline challenge must not send a comment');
  assert.equal(mounted,1,'Repeated clicks reuse the inline challenge instead of opening another dialog');
  captchaValid=true;
  assert.equal(JSON.parse(storage.get('volo.comment.profile.v1')).name,'本地访客');
  assert.equal(elements.boloUser.maxLength,20);assert.equal(elements.boloSite.maxLength,255);
  for (const [field,invalid] of [['boloUser','读'.repeat(21)],['boloSite','https://example.test/'+ 'a'.repeat(240)],['boloSite','javascript:alert(1)'],['soloEditorEmail','a'.repeat(245)+'@example.test'],['soloEditorEmail','a;b@example.test']]) {
    const input=elements[field],previous=input.value;input.value=invalid;
    guest.requestCommentSubmission(false);await Promise.resolve();assert.equal(submitted,2,'Invalid '+field+' must be rejected before sending');input.value=previous;
  }
  const credential=JSON.parse(storage.get('volo.comment.profile.v1')).token;
  assert.match(credential,/^[a-f0-9]{64}$/,'Guest ownership uses a browser-generated credential');
  reset();const returning=new sandbox.Page({oId:'returning'});returning.prepareCommentIdentity(false);
  assert.equal(elements.boloUser.value,'本地访客');assert.equal(elements.soloEditorEmail.value,'visitor@example.test');
  assert(elements.soloEditorIdentity.hidden);assert(!elements.soloEditorVisitorCard.hidden);
  assert.equal(elements.soloEditorVisitorName.textContent,'本地访客');
  assert.equal(returning.guestProfile.token,credential,'Returning guests retain the original editing credential');
  assert(elements.soloEditorVisitorAvatar.src.startsWith('data:image/svg+xml,'));
  returning.editingGuestIdentity=true;returning.prepareCommentIdentity(false);
  assert(!elements.soloEditorIdentity.hidden);assert(elements.soloEditorVisitorCard.hidden);
  returning.prepareCommentIdentity(true);assert(elements.soloEditorIdentity.hidden);assert(elements.soloEditorVisitorCard.hidden);
  document.getElementById=originalGet;
}
editorHost=host();window.vditor=null;
const stale=new sandbox.Page({oId:'stale'}),task=stale.prepareCommentEditor();stale.commentDisposed=true;
await task;assert.equal(constructions,2,'Leaving during warmup must prevent construction on the next page');

// Real asynchronous script-loader events: share one request, then retry failures.
window.BoloEditorCore={}; // A fresh document with no previously cached editing constructor.
const scripts=[];
document.createElement=()=>({setAttribute(){},remove(){this.removed=true}});
document.head={appendChild:script=>scripts.push(script)};
sandbox.Vditor=function RenderOnlyVditor(){};
const load=sandbox.Util.loadVditor(),duplicate=sandbox.Util.loadVditor();
assert.equal(load,duplicate);assert.equal(scripts.length,1);assert(scripts[0].async);
assert.equal(constructions,2,'A render-only Vditor must not be used as an editor constructor');
assert.equal(scripts[0].src,'http://localhost/js/lib/vditor/dist/index.min.js');
const rejected=assert.rejects(load,/load failed/);scripts[0].onerror();await rejected;
assert(scripts[0].removed);
const retry=sandbox.Util.loadVditor();assert.equal(scripts.length,2);
sandbox.Vditor=function(){};sandbox.Vditor.prototype.getValue=function(){};scripts[1].onload();await retry;
assert.equal(scripts[1].onload,null);
await sandbox.Util.loadVditor();assert.equal(scripts.length,2,'A loaded constructor must not fetch another script');
{
  const handlers=new Map(),cleanups=[];let open=true,focused=0;
  const contact={get open(){return open;},set open(value){open=value;},contains:()=>false,querySelector:()=>({focus(){focused++;}})};
  const document={addEventListener:(name,callback)=>handlers.set(name,callback),removeEventListener:name=>handlers.delete(name),querySelectorAll:()=>open?[contact]:[]};
  const window={BoloNavigation:{onLeave:callback=>cleanups.push(callback)}};
  const fixture={window,document};
  const common=fs.readFileSync('src/main/webapp/js/common.js','utf8');
  vm.runInNewContext(common,fixture);assert.equal(handlers.size,2);
  handlers.get('click')({target:{}});assert(!open);
  cleanups[0]();assert.equal(handlers.size,0);assert.equal(window.voloContactDisclosureBound,false);
  vm.runInNewContext(common,fixture);assert.equal(handlers.size,2,'Contact controls must rebind after PJAX');
  open=true;handlers.get('keydown')({key:'Escape'});assert(!open);assert.equal(focused,1);
}
{
  let raw='未发送的草稿',opened=false,parsed=0,cacheEnabled=true;
  window.vditor={getValue:()=>raw,setValue:next=>{raw=next},disabledCache(){cacheEnabled=false},enableCache(){cacheEnabled=true}};
  const owner=new sandbox.Page({oId:'owned'});
  owner.guestProfile={token:'a'.repeat(64)};owner.currentCommentId='earlier-reply';
  owner.isCommentEditorReady=()=>true;owner.prepareCommentEditor=()=>Promise.resolve(window.vditor);
  owner.setEditorOpen=next=>{opened=next};
  const content={innerHTML:'',textContent:'',classList:{add(){}}};
  const item={dataset:{},querySelector:()=>content,querySelectorAll:()=>[]};
  owner.findCommentElement=()=>item;
  const parse=sandbox.Util.parseMarkdown;sandbox.Util.parseMarkdown=()=>{parsed++};
  owner.editComment('owned-comment');assert.equal(request.type,'GET');assert.equal(request.headers['X-Volo-Visitor-Key'],'a'.repeat(64));
  request.success({sc:true,commentContent:'原始 Markdown'});request.complete();await Promise.resolve();
  assert.equal(raw,'原始 Markdown');assert(opened);assert.equal(owner.editingCommentId,'owned-comment');
  assert(!cacheEnabled,'Editing an existing comment must not overwrite the local new-comment draft');
  raw='修改后的 Markdown';owner.saveCommentEdit();assert.equal(request.type,'PUT');assert.equal(JSON.parse(request.data).commentContent,raw);
  request.error({responseJSON:{msg:'请稍后重试'}});request.complete();assert.equal(raw,'修改后的 Markdown');assert.equal(owner.editingCommentId,'owned-comment');
  owner.saveCommentEdit();request.success({sc:true,commentHTML:'<p>安全渲染的评论</p>'});request.complete();
  assert.equal(content.innerHTML,'<p>安全渲染的评论</p>');assert.equal(raw,'未发送的草稿');assert.equal(owner.currentCommentId,'earlier-reply');assert(!opened);assert.equal(parsed,1);
  assert(cacheEnabled,'Restoring the original draft re-enables new-comment caching');
  window.confirm=()=>true;owner.deleteOwnComment('owned-comment');assert.equal(request.type,'DELETE');assert(!request.url.includes('scope=thread'));
  request.success({sc:true});assert.equal(item.dataset.commentDeleted,'true');assert.equal(content.textContent,'这条评论已删除');
  owner.editingCommentId='owned-comment';owner.saveCommentEdit();owner.commentDisposed=true;
  const previous=output['#soloEditorError'];request.error({responseJSON:{msg:'旧页面错误'}});request.success({sc:true,commentHTML:'stale'});
  assert.equal(output['#soloEditorError'],previous);assert.equal(content.innerHTML,'<p>安全渲染的评论</p>');
  sandbox.Util.parseMarkdown=parse;
}
{
  const nodes=[];
  function node(tag='span') {
    const result={tagName:tag.toUpperCase(),children:[],dataset:{},events:{},className:'',classList:{add(){}},
      setAttribute(){},addEventListener(name,callback){this.events[name]=callback},
      append(...children){for(const child of children){if(child.parent)child.parent.children.splice(child.parent.children.indexOf(child),1);child.parent=this;this.children.push(child)}},
      before(child){child.parent=this.parent;this.parent.children.splice(this.parent.children.indexOf(this),0,child)}};
    Object.defineProperty(result,'textContent',{get(){return this.text||''},set(value){this.text=value;this.children=[]}});
    nodes.push(result);return result;
  }
  const create=document.createElement;document.createElement=node;
  const moderation=new sandbox.Page({oId:'moderated'});moderation.commentLoggedIn=true;
  const header=node(),reply=node('a'),location=node('span');reply.events.click=()=>{};header.append(reply);
  const item={querySelector:selector=>selector==='.comment-location'?location:selector.includes('page.toggleEditor')?reply:null};
  moderation.findCommentElement=id=>id==='visitor'?item:null;
  moderation.refreshCommentPermissions();
  request.success({sc:true,editable:['visitor'],moderation:[{id:'visitor',address:'2001:db8::1234',region:'中国 · 广东省 · 深圳市'}]});
  const group=header.children[0];
  assert.equal(group.className,'comment-management-actions');
  assert.deepEqual(group.children.map(child=>child.textContent),['编辑','删除','']);
  assert.equal(group.children[2],reply,'Grouping must preserve the original reply control and its native handlers');
  assert.equal(header.children.length,1,'Moving the native reply must not leave a separate empty slot');
  assert.equal(location.textContent,'2001:db8::1234 · 中国 · 广东省 · 深圳市');
  assert.equal(location.children[0].dataset.commentBan,'visitor');
  moderation.applyCommentModeration([]);
  const previous=requests,originalSwal=sandbox.swal;sandbox.swal=()=>Promise.resolve(false);
  moderation.banCommentSource('visitor');await new Promise(resolve=>setImmediate(resolve));assert.equal(requests,previous,'Cancelling a ban must not mutate access rules');
  sandbox.swal=()=>Promise.resolve(true);moderation.banCommentSource('visitor');await new Promise(resolve=>setImmediate(resolve));
  assert.equal(request.url,'http://localhost/console/ip-bans');
  assert.equal(JSON.parse(request.data).commentId,'visitor');assert.equal(JSON.parse(request.data).durationSeconds,0);
  assert(!request.data.includes('2001:db8'),'The server must derive the source IP from the stored comment');
  request.complete();document.createElement=create;sandbox.swal=originalSwal;
}
for(const skin of fs.readdirSync('src/main/webapp/skins')) {
  for(const file of ['common-comment.ftl','macro-comment.ftl']) {
    const path='src/main/webapp/skins/'+skin+'/'+file;if(!fs.existsSync(path))continue;
    assert(!/(?:onclick|href)="[^"\n]*page\.toggleEditor\([^"\n]*\$\{comment\.commentName/.test(fs.readFileSync(path,'utf8')),skin+' must pass comment names as data, never JavaScript source');
  }
}
{
  const nodes=[];let now=1000,requests=0,reply={sc:true,required:true,captchaId:'fixture',image:'data:image/png;base64,fixture',expiresAt:181000};
  function element(tag) {
    const node={tag,children:[],dataset:{context:'/blog'},attrs:{},events:{},classList:{add(){}},value:'',
      append(...children){this.children.push(...children)},replaceChildren(...children){this.children=children},
      setAttribute(key,value){this.attrs[key]=value},removeAttribute(key){delete this.attrs[key]},
      addEventListener(name,callback){this.events[name]=callback},focus(){},reportValidity(){return /^[A-Za-z0-9]{5}$/.test(this.value)}};
    nodes.push(node);return node;
  }
  const states=[],host=element('div'),sandbox={window:{},document:{createElement:element},AbortController,Date:{now:()=>now},setTimeout:()=>1,clearTimeout(){},
    fetch:async(url,options)=>{requests++;assert.equal(url,'/blog/captcha?purpose=comment');assert.equal(options.credentials,'same-origin');return {ok:reply.sc,json:async()=>reply}}};
  vm.runInNewContext(fs.readFileSync('src/main/webapp/js/captcha.js','utf8'),sandbox);
  const widget=sandbox.window.VoloCaptcha.mount(host,'comment',state=>states.push(state));
  assert.equal(widget.valid(),false,'A pending image cannot be submitted');await widget.ready;
  assert.equal(nodes.filter(node=>node.tag==='dialog').length,0,'CAPTCHA must not create a separate confirmation dialog');
  const input=nodes.find(node=>node.tag==='input');assert.equal(widget.valid(),false);input.value='AB234';assert(widget.valid());
  assert.equal(widget.payload().captchaAnswer,'AB234');assert.equal(host.attrs['aria-busy'],undefined);
  widget.ensureFresh();assert.equal(requests,1,'An unexpired challenge survives closing and reopening the composer');
  now=181001;widget.ensureFresh();await Promise.resolve();await Promise.resolve();
  assert.equal(requests,2,'Expired challenges refresh on reopening');assert.equal(input.value,'');
  reply={sc:false,msg:'请稍后重试'};await widget.refresh();assert.equal(widget.valid(),false);assert(!states.at(-1).ready);
  reply={sc:true,required:false};await widget.refresh();assert(host.hidden);assert(widget.valid(),'Turning CAPTCHA off must immediately allow ordinary submission');
  assert.equal(Object.keys(widget.payload()).length,0);widget.destroy();
}
{
  const events=new Map(),frames=new Map(),cleanups=[];let serial=0;
  const trigger={value:'server { listen 8080; }',defaultValue:'restored code',setAttribute(key,value){this[key]=value;}};
  const draft='这是访客自己输入的草稿，必须保留';
  const navigation={onLeave:callback=>cleanups.push(callback)};
  const window={BoloNavigation:navigation,vditor:{getValue:()=>draft},addEventListener:(name,callback)=>events.set(name,callback),removeEventListener:name=>events.delete(name)};
  const $={extend:Object.assign};
  const sandbox={window,BoloNavigation:navigation,document:{getElementById:id=>id==='comment'?trigger:null},$,
    requestAnimationFrame:callback=>{frames.set(++serial,callback);return serial;},cancelAnimationFrame:id=>frames.delete(id)};
  vm.runInNewContext(fs.readFileSync('src/main/webapp/js/page.js','utf8'),sandbox);
  const page=new sandbox.Page({oId:'restored-article'});page.bindCommentTrigger();
  assert.equal(trigger.value,'');assert.equal(trigger.defaultValue,'');assert.equal(trigger.readOnly,true);assert.equal(trigger.autocomplete,'off');
  for(const persisted of [false,true]){
    trigger.value='restored hidden code';events.get('pageshow')({persisted});assert.equal(trigger.value,'');
    trigger.value='late form restoration';for(const callback of [...frames.values()])callback();frames.clear();
    assert.equal(trigger.value,'','Form restoration after pageshow must not leak code into the comment trigger');
    assert.equal(window.vditor.getValue(),draft,'Session restoration must preserve the actual editor draft');
  }
  events.get('pageshow')({persisted:true});cleanups[0]();assert(!events.has('pageshow'));assert.equal(frames.size,0,'PJAX disposes restoration callbacks');
  const templates=fs.readdirSync('src/main/webapp/skins').flatMap(skin=>fs.readdirSync('src/main/webapp/skins/'+skin).filter(file=>file.endsWith('.ftl')).map(file=>'src/main/webapp/skins/'+skin+'/'+file));
  const triggers=templates.flatMap(file=>[...fs.readFileSync(file,'utf8').matchAll(/<textarea\b[^>]*\bid="comment"[^>]*>/g)].map(match=>({file,tag:match[0]})));
  assert.equal(triggers.length,16);for(const {file,tag} of triggers){assert(/\breadonly(?:\s|=|>)/.test(tag),file);assert(tag.includes('autocomplete="off"'),file);assert(tag.includes('name="volo-comment-trigger"'),file);}
}
console.log('Comment checks passed: drafts, session/BFCache restoration, all-skin triggers, identity/permissions, inline CAPTCHA, safe templates and PJAX lifecycle.');
