import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

{
  let clock=0,nextTimer=0;
  const timers=new Map(),observers=[];
  const target=()=>({listeners:new Map(),addEventListener(type,callback){this.listeners.set(type,callback);},removeEventListener(type,callback){if(this.listeners.get(type)===callback)this.listeners.delete(type);}});
  const notice=Object.assign(target(),{hidden:true,dataset:{}}),close=target(),title={textContent:''};
  notice.contains=node=>node===close;
  let message='';
  const output={get textContent(){return message;},set textContent(value){message=value;queueMicrotask(()=>observers.filter(observer=>observer.active).forEach(observer=>observer.callback()));}};
  const elements={consoleNotice:notice,consoleNoticeClose:close,consoleNoticeTitle:title,tipMsg:output};
  const $=()=>({});$.extend=Object.assign;
  const sandbox={$,Util:{},window:{},document:{getElementById:id=>elements[id]},Date:{now:()=>clock},
    setTimeout(callback,delay){const id=++nextTimer;timers.set(id,{callback,deadline:clock+delay});return id;},clearTimeout:id=>timers.delete(id),
    MutationObserver:class {constructor(callback){this.callback=callback;observers.push(this);}observe(){this.active=true;}disconnect(){this.active=false;}}};
  vm.runInNewContext(fs.readFileSync('src/main/webapp/js/admin/admin.js','utf8'),sandbox);
  async function advance(duration){
    const end=clock+duration;
    for(;;){const next=[...timers.entries()].sort((a,b)=>a[1].deadline-b[1].deadline)[0];if(!next||next[1].deadline>end)break;clock=next[1].deadline;timers.delete(next[0]);next[1].callback();await Promise.resolve();}
    clock=end;
  }
  const admin=sandbox.admin;
  admin.initNotices();admin.handleRequestError({status:400,responseJSON:{msg:'国家代码不能为空。'}},'');await Promise.resolve();
  assert.equal(notice.hidden,false);assert.equal(notice.dataset.tone,'error');assert.equal(title.textContent,'操作未完成');assert.equal(message,'400 · 国家代码不能为空。');
  await advance(4000);output.textContent='已保存';await Promise.resolve();
  assert.equal(notice.dataset.tone,'info','Legacy message writes share the notification lifecycle');
  await advance(4999);assert.equal(notice.hidden,false,'A previous error deadline must not clear a newer result');
  await advance(1);assert.equal(notice.hidden,true);
  admin.handleRequestError({status:400},'');await Promise.resolve();assert(message.includes('提交内容有误'));
  await advance(2000);notice.listeners.get('mouseenter')();await advance(20000);assert.equal(notice.hidden,false,'Hover pauses the remaining reading time');
  notice.listeners.get('mouseleave')();await advance(1000);notice.listeners.get('focusin')();await advance(10000);assert.equal(notice.hidden,false);
  notice.listeners.get('focusout')({relatedTarget:null});await advance(4999);assert.equal(notice.hidden,false);await advance(1);assert.equal(notice.hidden,true);
  admin.handleRequestError({status:0},'abort');await Promise.resolve();assert.equal(notice.hidden,true,'Cancelled navigation must not show a network error');
  admin.handleRequestError({status:0},'timeout');await Promise.resolve();assert(message.includes('请求超时'));
  close.listeners.get('click')();await Promise.resolve();assert.equal(notice.hidden,true);assert.equal(timers.size,0,'Closing a notice cancels its timer');
  admin.initNotices();admin.initNotices();assert.equal(observers.filter(observer=>observer.active).length,1,'Reinitializing must disconnect the earlier observer');
  admin.showNotice('新的提示');await Promise.resolve();admin.noticeCleanup();assert.equal(timers.size,0);assert.equal(close.listeners.size,0);
  output.textContent='离开后台后的旧请求';await Promise.resolve();assert.equal(notice.hidden,true,'Leaving the console must dispose notifications');
}

// Exercise the actual rendering callbacks with hostile data and observable output sinks.
for (const kind of ['preference','usite']) {
  const values=new Map(),requests=[],notices=[],errors=[];let reloads=0;
  const $=selector=>{
    if(!values.has(selector))values.set(selector,{value:'',text:'',disabled:false});
    const node=values.get(selector);
    return {val(value){if(value===undefined)return node.value;node.value=value;return this;},text(value){node.text=value;return this;},prop(key,value){if(value===undefined)return node[key];node[key]=value;return this;}};
  };
  $.ajax=options=>requests.push(options);
  const sandbox={$,Label:{servePath:''},admin:{register:{},showNotice:(...args)=>notices.push(args),handleRequestError:(...args)=>errors.push(args)},
    window:{location:{hash:'#tools/preference',reload:()=>reloads++}}};
  vm.runInNewContext(fs.readFileSync('src/main/webapp/js/admin/'+kind+'.js','utf8'),sandbox);
  const form=sandbox.admin[kind];form.loaded=true;form.validate=()=>true;
  form.update();assert.equal(requests.length,1);assert.equal(form.saving,true);
  assert.equal(requests[0].timeout,30000);assert.notEqual(requests[0].async,false,'Saving must not block the browser event loop');
  form.update();assert.equal(requests.length,1,'Repeated clicks must not send duplicate save requests');
  requests[0].error({status:0},'timeout');requests[0].complete();assert.equal(form.saving,false);assert.equal(errors.length,1);
  assert.equal(values.get('#loadMsg').text,'','Failure clears the pending state');
  form.update();requests[1].success(kind==='preference'?{sc:false,msg:'保存失败'}:{code:400,msg:'保存失败'});requests[1].complete();
  assert.equal(notices.at(-1)[1],'error');assert.equal(reloads,0);assert.equal(form.saving,false);
  form.update();requests[2].success(kind==='preference'?{sc:true}:{code:200});requests[2].complete();
  assert.equal(reloads,kind==='preference'?1:0,'Successful preference saves reload only once');assert.equal(form.saving,false);
  const buttonSelector=kind==='preference'?'#tabPreferencePanel .console-form-actions button':'#tabsPanel_usite .console-form-actions button';
  assert.equal(values.get(buttonSelector).disabled,false,'All save completion paths restore the action buttons');
}

// Saving preferences must work before the independent security page has ever loaded.
{
  const template=fs.readFileSync('src/main/webapp/admin/admin-preference.ftl','utf8');
  const ids=new Set([...template.matchAll(/\bid="([^"]+)"/g)].map(match=>match[1]));
  const tabs=[...new Set([...template.matchAll(/href="#tools\/preference\/([^"]+)"/g)].map(match=>match[1]))];
  const securityIds=['wafCurrentLimitTimes','wafCurrentLimitSecond','wafPower','spam','commentFilterBuiltin','commentFilterAllow'];
  const counts=['mostUsedTagDisplayCount','recentCommentDisplayCount','mostCommentArticleDisplayCount','mostViewArticleDisplayCount','articleListDisplayCount','articleListPaginationWindowSize','randomArticlesDisplayCount','relevantArticlesDisplayCount'];
  for(const cachedSecurity of [false,true]) {
    const values=new Map([...ids].map(id=>['#'+id,''])),requests=[],securityReads=[];
    counts.forEach(id=>values.set('#'+id,'8'));values.set('#myGitHubID','Willh92');
    if(cachedSecurity)securityIds.forEach(id=>values.set('#'+id,'invalid cached security value'));
    const $=selector=>({
      val(value){if(value===undefined){if(securityIds.includes(selector.slice(1)))securityReads.push(selector);return values.get(selector);}if(values.has(selector))values.set(selector,value);return this;},
      text(value){if(value!==undefined)values.set(selector,value);return this;},prop(){return this;},focus(){return this;}
    });
    $.ajax=options=>requests.push(options);
    const githubDisplay={username:'Willh92',includeForks:true,hidden:['Willh92/hidden-repo']};
    const sandbox={$,Label:{servePath:''},admin:{register:{},showNotice(){},handleRequestError(){},githubRepositories:{payload:()=>githubDisplay}},window:{location:{hash:'',reload(){}}}};
    vm.runInNewContext(fs.readFileSync('src/main/webapp/js/admin/preference.js','utf8'),sandbox);
    sandbox.admin.preference.loaded=true;
    for(const tab of tabs){
      sandbox.window.location.hash='#tools/preference/'+tab;
      const before=requests.length;sandbox.admin.preference.update();
      assert.equal(requests.length,before+1,'Save '+tab+' with cached security='+cachedSecurity+' uses the real validation');
      const submitted=JSON.parse(requests.at(-1).data);
      for(const key of securityIds)assert(!Object.hasOwn(submitted.preference,key),'Unrelated saves must not send security settings');
      assert.equal(!!submitted.githubDisplay,tab==='myGitHub');
      if(tab==='myGitHub')assert.deepEqual(submitted.githubDisplay,githubDisplay);
      requests.at(-1).complete();
    }
    assert.equal(securityReads.length,0,'Preference validation must not depend on cached security controls');
    values.set('#articleListDisplayCount','invalid');const before=requests.length;sandbox.admin.preference.update();
    assert.equal(requests.length,before,'Unrelated security cleanup must retain validation of preference counts');
  }
}

// A delayed or failed preference read cannot submit an empty form or reuse old checkbox states.
{
  const template=fs.readFileSync('src/main/webapp/admin/admin-preference.ftl','utf8');
  const values=new Map([...template.matchAll(/\bid="([^"]+)"/g)].map(match=>['#'+match[1],{value:'',checked:true}]));
  const requests=[],notices=[];
  const $=selector=>{
    if(!values.has(selector))values.set(selector,{value:''});
    const node=values.get(selector);
    return {val(value){if(value===undefined)return node.value;node.value=value;return this;},
      text(value){if(value===undefined)return node.text||'';node.text=value;return this;},
      prop(key,value){if(value===undefined)return node[key];node[key]=value;return this;},attr(){return this;},
      tabs(){return this;},not(){return this;},remove(){return this;},append(){return this;},focus(){return this;}};
  };
  $.ajax=options=>requests.push(options);
  const sandbox={$,Label:{servePath:''},admin:{register:{},showNotice:(...args)=>notices.push(args),handleRequestError(){},githubRepositories:{init(){},payload(){}}},window:{location:{hash:'#tools/preference/myGitHub',reload(){}}}};
  vm.runInNewContext(fs.readFileSync('src/main/webapp/js/admin/preference.js','utf8'),sandbox);
  const form=sandbox.admin.preference,button='#tabPreferencePanel .console-form-actions button';
  form.init();assert.equal(requests.length,1);assert.equal(requests[0].timeout,30000);assert.equal(values.get(button).disabled,true);
  form.update();assert.equal(requests.length,1,'Saving cannot run before the preference response');
  requests[0].success({sc:false,msg:'读取失败'});requests[0].complete();
  form.update();assert.equal(requests.length,1);assert.equal(values.get(button).disabled,true);
  assert.equal(notices.at(-1)[1],'error');
  form.init();const read=requests.at(-1);
  const preference=Object.fromEntries([...values.keys()].map(selector=>[selector.slice(1),'']));
  Object.assign(preference,{signs:'[]',editorMode:'sv',localeString:'zh_CN',kanbanniangSelector:'saved-model'});
  for(const id of ['mostUsedTagDisplayCount','recentCommentDisplayCount','mostCommentArticleDisplayCount','mostViewArticleDisplayCount','articleListDisplayCount','articleListPaginationWindowSize','randomArticlesDisplayCount','relevantArticlesDisplayCount'])preference[id]='8';
  read.success({sc:true,preference});read.complete();
  assert.equal(form.loaded,true);assert.equal(values.get(button).disabled,false);
  for(const key of ['enableAutoFlushGitHub','enableAutoFlushGitHubProfile','showCodeBlockLn','enableArticleUpdateHint','allowVisitDraftViaPermalink','commentable','syncGitHub','welfareLuteService'])assert.equal(values.get('#'+key).checked,false,'Reload clears unchecked '+key);
  assert.equal(values.get('#kanbanniangSelector').value,'saved-model','Model-list failures cannot clear the saved model');
  const models=requests.at(-1);values.get('#kanbanniangSelector').value='new-model';models.success({msg:'saved-model;new-model'});
  assert.equal(values.get('#kanbanniangSelector').value,'new-model','A late model response must not overwrite an edited choice');
  form.update();assert.equal(requests.at(-1).type,'PUT');
  const payload=JSON.parse(requests.at(-1).data).preference;
  assert.equal(payload.kanbanniangSelector,'new-model');
  assert(!Object.hasOwn(payload,'pullGitHub')&&!Object.hasOwn(payload,'syncGitHubProfile'),'Removed controls cannot write empty legacy options');
}

const payload='<img src=x onerror=alert(1)></option><script>alert(2)</script> & "quoted"';
const escape=value=>String(value).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
{
  let answer=false,calls=0;
  const sandbox={admin:{register:{}},swal:options=>{assert.equal(options.buttons.cancel,'取消');return Promise.resolve(answer);}};
  vm.runInNewContext(fs.readFileSync('src/main/webapp/js/admin/others.js','utf8'),sandbox);
  sandbox.admin.others.maintenance=()=>calls++;
  sandbox.admin.others.clearMailCache({});await Promise.resolve();assert.equal(calls,0,'Cancel must not clear notification data');
  answer=true;sandbox.admin.others.clearMailCache({});await Promise.resolve();assert.equal(calls,1,'Clear runs only after Promise-based confirmation');
}
{
  let output='';
  const $=selector=>({text(){return this;},html(value){if(value!==undefined)output=value;return this;}});
  $.bowknot={getDate:()=> '2026-11-01'};
  $.ajax=options=>options.success({sc:true,comments:[{oId:payload,commentName:payload,
    commentOriginalCommentName:payload,commentURL:'javascript:alert(1)',commentContent:'<p>Safe comment</p>',commentTime:0}]});
  const sandbox={admin:{},$,Label:{servePath:'',removeLabel:'删除',noCommentLabel:'无评论'},Util:{parseMarkdown(){},parseLanguage(){}}};
  vm.runInNewContext(fs.readFileSync('src/main/webapp/js/admin/comment.js','utf8'),sandbox);
  sandbox.admin.comment.getList('article-id','article');
  assert(output.includes('&lt;img'),'Historical nickname payloads remain readable text in the admin dialog');
  assert(!output.includes('<img')&&!output.includes('<script'),'The dialog must not parse names as markup');
  assert(!output.includes('href=\'javascript:'),'Legacy homepage protocols must not execute');
  assert(output.includes('this.dataset.commentId'),'Delete actions use data, never interpolated JavaScript');
}
for (const recent of [true,false]) {
  const rendered=[];
  const host={isConnected:true,setAttribute(){},removeAttribute(){}};
  const $=selector=>({value:'',text(value){this.value=value;return this;},html(value){if(value!==undefined)rendered.push(value);return escape(this.value);}});
  let response={page:1,pageCount:1,total:1,through:10,freeMemNow:1024,result:[{id:1,date:'2026-10-30 13:00:00',level:payload,name:payload,lineNumber:payload,message:payload,throwable:{class:payload,message:payload,stackTrace:[payload]}}]};
  $.ajax=options=>{assert.equal(options.dataType,'json');options.success(response);options.complete();};
  const sandbox={admin:{pagination:{render(){}}},window:{location:{hash:recent?'#main':'#tools/others/log'}},document:{getElementById:()=>host},$,Label:{servePath:''},Date};
  vm.runInNewContext(fs.readFileSync('src/main/webapp/js/admin/logs.js','utf8'),sandbox);
  sandbox.admin.logs.load(recent);
  assert.equal(rendered.length,1);
  assert(!rendered[0].includes('<img')&&!rendered[0].includes('<script'),'Every log field must be escaped');
  assert(rendered[0].includes('&lt;img'),'Log payloads remain readable text');
  for (response of [null,{}, {sc:false,msg:'Session expired'},'<html>Login</html>']) sandbox.admin.logs.load(recent);
  assert.equal(rendered.length,1,'Malformed or expired-session results must not replace the log view');
}

{
  const template=fs.readFileSync('src/main/webapp/admin/admin-others.ftl','utf8');
  const source=[...template.matchAll(/<script[^>]*>([\s\S]*?)<\/script>/g)].map(match=>match[1]).find(script=>script.includes('/article/commentSync/getList'))
    .replace(/<\/?#if[^>]*>/g,'');
  const options=[];
  const $=selector=>({value:'',label:'',val(value){this.value=value;return this;},text(value){this.label=value;return this;},append(value){assert.equal(typeof value,'object','Options must not parse HTML strings');options.push(value);}});
  $.ajax=settings=>settings.success({data:[{oId:'safe-id',title:payload}]});
  vm.runInNewContext(source,{$,Label:{servePath:''}});
  assert.equal(options.length,2);
  assert(options.every(option=>option.label===payload&&option.value==='safe-id'));
}

{
  const classes=()=>({values:new Set(),add(name){this.values.add(name);}});
  function cell(text='',control=false){return {tagName:'TD',colSpan:1,textContent:text,firstElementChild:null,dataset:{},classList:classes(),querySelector:()=>control?{}:null};}
  const cells=[cell('',true),cell('A long title'),cell(''),cell('https://example.test/a/long/path')];
  const row={children:cells,classList:classes(),parentElement:{classList:classes()}};
  const $=()=>({});$.extend=(target,source)=>Object.assign(target,source);
  const sandbox={$,document:{getElementById:()=>({querySelectorAll:()=>[row]})},Label:{uncategorizedLabel:'未分类'}};
  vm.runInNewContext(fs.readFileSync('src/main/webapp/js/admin/tablePaginate.js','utf8'),sandbox);
  const table=new sandbox.TablePaginate('article');
  table.columnModel=[{text:'',index:'linkOrder'},{text:'标题',index:'title'},{text:'分类',index:'category'},{text:'URL',index:'linkAddress'}];
  table.labelFields();
  assert.equal(cells[2].textContent,'未分类');
  assert.equal(cells[0].textContent,'','Sorting controls must not be replaced by empty-value text');
  assert.equal(cells[0].dataset.empty,'false');
  assert(cells[1].classList.values.has('console-data-primary'));
  assert(cells[3].classList.values.has('console-data-wide'),'Long addresses need the full card width');
}
for (const width of [390, 768, 1440]) {
  const created=[];
  const editorHost={classList:{add(){},remove(){}},setAttribute(){},removeAttribute(){}};
  const $=()=>({}); $.extend=(target,source)=>Object.assign(target,source);
  const sandbox={setTimeout,clearTimeout,admin:{}, $, Label:{staticServePath:'',servePath:'',editorMode:'sv'},
    document:{getElementById:()=>editorHost},Util:{loadVditorRuntime:async()=>({i18n:{}})},
    window:{innerWidth:width,innerHeight:740,BoloTheme:{current:()=> 'dark'}},BoloTheme:{current:()=> 'dark'},
    Vditor:function(id,options){this.options=options;this.setTheme=theme=>this.theme=theme;created.push(this);}};
  sandbox.Vditor.prototype.getValue=function(){};
  vm.runInNewContext(fs.readFileSync('src/main/webapp/js/admin/editor.js','utf8'),sandbox);
  const wrapper=new sandbox.SoloEditor({id:'articleContent',height:500,previewMode:'both',outline:true,typewriterMode:true,resize:false});
  await Promise.resolve();
  const editor=created[0];editor.options.after();await wrapper.ready;
  assert.equal(editor.theme,'dark','Editor must use the selected site theme');
  assert.equal(editor.options.preview.mode,width<=768?'editor':'both');
  assert.equal(editor.options.outline.enable,width>768);
  if(width<=768) {
    assert.equal(editor.options.preview.actions.length,0,'Desktop preview controls should not squeeze the phone editor');
    assert(editor.options.height>=260&&editor.options.height<500);
    assert(editor.options.toolbar.some(item=>item.toolbar?.includes('preview')),'Phone preview must remain available');
  }
}
{
  let rows, confirmation;
  const title='导航 "\' <img src=x onerror=alert(1)> & 中文';
  const page={oId:'123',pageTitle:title,pagePermalink:'https://example.test/?a=1&b=2',pageOpenTarget:'_self',pageIcon:'https://example.test/icon.svg?name=" onerror="bad'};
  const $=()=>({text(){return this;}});
  $.ajax=options=>options.success({sc:true,pages:[page],pagination:{paginationPageCount:1}});
  const sandbox={admin:{register:{}},$,Label:{servePath:'',PAGE_SIZE:20,WINDOW_SIZE:10,confirmRemoveLabel:'删除',navLabel:'导航'},
    TablePaginate:function(){this.keywordQuery=()=>'';this.updateTablePagination=data=>{rows=data;};},
    confirm:message=>{confirmation=message;return false;},encodeURIComponent,decodeURIComponent};
  vm.runInNewContext(fs.readFileSync('src/main/webapp/js/admin/pageList.js','utf8'),sandbox);
  sandbox.admin.pageList.getList(1);
  assert(rows[0].pageTitle.includes('&lt;img'));
  assert(!rows[0].pageTitle.includes('<img src=x'));
  assert(rows[0].pageTitle.includes('&quot; onerror=&quot;'));
  assert(rows[0].pagePermalink.includes('a=1&amp;b=2'));
  assert(!rows[0].expendRow.includes(title));
  sandbox.admin.pageList.del('123',encodeURIComponent(title));
  assert.equal(confirmation,'删除导航"'+title+'"?','Delete confirmation must preserve the literal title without parsing HTML');
}
{
  const sandbox={window:{},document:{},console,setTimeout,clearTimeout};
  vm.runInNewContext(fs.readFileSync('src/main/webapp/js/common.js','utf8'),sandbox);
  const avatar=sandbox.Util.generatedAvatar('读者 A');
  assert.equal(avatar,sandbox.Util.generatedAvatar('读者 A'),'The same visitor should keep the same generated avatar');
  assert.notEqual(avatar,sandbox.Util.generatedAvatar('读者 B'));
  assert(avatar.startsWith('data:image/svg+xml,'));
  const unsafe=decodeURIComponent(sandbox.Util.generatedAvatar('<script>alert(1)</script>').split(',')[1]);
  assert(!unsafe.includes('<script')&&!unsafe.includes('alert(1)'),'Avatar markup must not contain visitor input');
  const image=(src,complete,width)=>({src,complete,naturalWidth:width,getAttribute:name=>name==='src'?src:'读者 A'});
  const broken=image('https://unavailable.invalid/avatar.png',true,0);
  const custom=image('https://example.test/custom.png',true,25);
  sandbox.Util.bindCommentAvatars({querySelectorAll:()=>[broken,custom]});
  assert.equal(broken.src,avatar);assert.equal(broken.onerror,null,'A failing generated image must not loop');
  assert.equal(custom.src,'https://example.test/custom.png','Configured, working avatars must be preserved');
  custom.onerror();assert.equal(custom.src,avatar);assert.equal(custom.onerror,null);
}
{
  let rows;
  const dangerous='昵称\'\" <img src=x onerror=alert(1)>';
  const $=()=>({text(){return this;}});
  $.ajax=options=>options.success({sc:true,comments:[{oId:'123',commentName:dangerous,commentContent:'<p>Readable comment</p>',
    commentTitle:dangerous,commentSharpURL:'/post.html#123',commentThumbnailURL:'https://example.test/a\' onerror=\'bad',
    commentURL:'javascript:alert(1)',commentTime:0}],pagination:{}});
  $.bowknot={getDate:()=> '2026-10-31'};
  const sandbox={admin:{register:{}},$,Label:{servePath:'',PAGE_SIZE:20,WINDOW_SIZE:10},
    document:{getElementById:()=>null},Util:{bindCommentAvatars(){},parseMarkdown(){},parseLanguage(){}},
    TablePaginate:function(){this.keywordQuery=()=>'';this.updateTablePagination=data=>{rows=data;};}};
  vm.runInNewContext(fs.readFileSync('src/main/webapp/js/admin/commentList.js','utf8'),sandbox);
  sandbox.admin.commentList.getList(1);
  assert(rows[0].content.includes('<p>Readable comment</p>'),'Sanitized Markdown must remain formatted');
  assert(rows[0].title.includes('&lt;img')&&!rows[0].title.includes('<img src=x'));
  assert(!rows[0].title.includes('href=\'javascript:'));
  assert(!rows[0].title.includes("src='https://example.test/a' onerror="),'Avatar attributes must not be injectable');
}
{
  class Element {
    constructor(tag='div'){this.tagName=tag;this.children=[];this.events={};this.isConnected=true;this.disabled=false;this.dataset={};this.classList={add(){}};this.value='';}
    append(...nodes){this.children.push(...nodes);}
    replaceChildren(...nodes){this.children=nodes;}
    addEventListener(name,handler){this.events[name]=handler;}
    setAttribute(){}
    focus(){this.focused=true;}
    get lastElementChild(){return this.children.at(-1);}
    querySelector(selector){
      const all=this.children.flatMap(node=>node instanceof Element?[node,...node.descendants()]:[]);
      return selector.startsWith('button')?all.find(node=>node.tagName==='button'&&!node.disabled)
        :all.find(node=>(node.className||'').split(' ').includes(selector.split(' ')[0].slice(1)))?.children.find(node=>node.tagName==='input');
    }
    descendants(){return this.children.flatMap(node=>node instanceof Element?[node,...node.descendants()]:[]);}
  }
  const nodes=new Map(['geoAccessPagination','geoAccessRules','geoAccessRuleCount','geoAccessActive','geoAccessDefault','geoAccessUnknown','geoAccessCurrent','geoAccessMessage','geoAccessSave','geoAccessAdd'].map(id=>[id,new Element()]));
  const requests=[];
  const $=selector=>{
    const selected=selector.split(',').map(id=>nodes.get(id.slice(1))).filter(Boolean);
    return {prop(key,value){selected.forEach(node=>node[key]=value);return this;},val(value){if(value===undefined)return selected[0]?.value;selected.forEach(node=>node.value=value);return this;},text(value){selected.forEach(node=>node.textContent=value);return this;}};
  };
  $.ajax=options=>requests.push(options);
  const sandbox={admin:{register:{}},$,Label:{servePath:''},document:{getElementById:id=>nodes.get(id)||nodes.get('geoAccessRules').descendants().find(n=>n.id===id),createElement:tag=>new Element(tag),createTextNode:text=>({textContent:text})}};
  vm.runInNewContext(fs.readFileSync('src/main/webapp/js/admin/pagination.js','utf8'),sandbox);
  vm.runInNewContext(fs.readFileSync('src/main/webapp/js/admin/security.js','utf8'),sandbox);
  const preference=sandbox.admin.security;
  preference.loadGeoAccess();const stale=requests.shift();preference.loadGeoAccess();const latest=requests.shift();
  assert(nodes.get('geoAccessSave').disabled,'Do not save before configuration is loaded');
  stale.success({sc:true,rules:[],active:false,defaultAction:'deny',unknown:'deny'});
  assert(!preference.geoAccessLoaded,'Old reads must not overwrite a newer request');
  const rules=[{action:'allow',enabled:true,name:payload,countries:'CN',regions:''},{action:'deny',enabled:true,name:'拒绝美国',countries:'US',regions:''}];
  latest.success({sc:true,rules,active:true,defaultAction:'allow',unknown:'deny',enabled:true,currentCountry:'CN',currentRegion:'中国'});
  assert(preference.geoAccessLoaded);assert.equal(nodes.get('geoAccessRules').children.length,1);
  preference.geoEdit=0;preference.renderGeoAccess();
  const card=nodes.get('geoAccessRules').children[0];
  const name=nodes.get('geoAccessRules').descendants().find(node=>node.tagName==='input'&&node.value===payload);
  name.value='新备注';name.events.input();
  const down=card.descendants().find(node=>node.tagName==='button'&&node.title==='下移');down.events.click();
  assert.equal(preference.geoAccessRules[1].name,'新备注','Reordering keeps edits made in the form');
  assert.equal(preference.geoAccessRules[0].action,'deny');
  preference.saveGeoAccess();const write=requests.shift(),body=JSON.parse(write.data);
  assert.equal(write.type,'PUT');assert.equal(body.active,true);assert.equal(body.defaultAction,'allow');
  assert.deepEqual(body.rules.map(rule=>rule.action),['deny','allow']);
  write.success({sc:true,msg:'已保存'});write.complete();const reload=requests.shift();
  assert(nodes.get('geoAccessSave').disabled,'Saving must stay disabled while the canonical settings reload');
  reload.success({sc:true,rules:body.rules,active:true,defaultAction:'allow',unknown:'deny',enabled:true});
  preference.toggleGeoRule(0);const toggle=requests.shift();
  assert.equal(JSON.parse(toggle.data).rules[0].enabled,false,'Quick disable persists the existing rule and priority');
  assert.deepEqual(JSON.parse(toggle.data).rules.map(rule=>rule.countries),body.rules.map(rule=>rule.countries));
  toggle.error({responseJSON:{msg:'保存失败'}});toggle.complete();
  assert.equal(preference.geoAccessRules[0].enabled,true,'A failed toggle must restore the checkbox and row');
  assert.equal(nodes.get('geoAccessMessage').textContent,'保存失败');
  preference.toggleGeoRule(0);const enable=requests.shift(),toggled=JSON.parse(enable.data);
  enable.success({sc:true});enable.complete();requests.shift().success({sc:true,...toggled,enabled:true});
  assert.equal(preference.geoAccessRules[0].enabled,false);
  preference.geoAccessRules[0].onMiss='deny';preference.toggleGeoRule(0);
  assert.equal(requests.length,0,'Quick toggles cannot accidentally persist unrelated unsaved edits');
  preference.addGeoAccessRule();assert.equal(preference.geoAccessRules.length,3);
  assert.equal(preference.geoAccessRules[2].action,'deny');
  const remove=nodes.get('geoAccessRules').children[0].descendants().filter(node=>node.tagName==='button'&&node.textContent==='移除')[2];remove.events.click();
  assert.equal(preference.geoAccessRules.length,2);
  preference.geoAccessRules=Array.from({length:12},(_,index)=>({action:'deny',enabled:true,name:'Rule '+index,countries:'US',regions:''}));
  preference.geoPage=1;preference.geoEdit=-1;preference.renderGeoAccess();
  const crossPage=nodes.get('geoAccessRules').descendants().filter(node=>node.tagName==='button'&&node.title==='下移')[9];
  crossPage.events.click();
  assert.equal(preference.geoPage,2,'Move across a page boundary follows the moved rule');
  assert.equal(preference.geoAccessRules[10].name,'Rule 9');
  assert.equal(nodes.get('geoAccessRules').children[0].children[1].children.length,2,'Only the requested page renders');
}

{
  class Element {
    constructor(tag='div'){this.tagName=tag;this.children=[];this.events={};this.dataset={};this.className='';this.classList={add(){},toggle(){}};this.value='';}
    appendChild(node){this.children.push(node);return node;}
    append(...nodes){this.children.push(...nodes);}
    replaceChildren(...nodes){this.children=nodes;}
    setAttribute(name,value){this[name]=value;}
    addEventListener(name,handler){this.events[name]=handler;}
    descendants(){return this.children.flatMap(node=>[node,...(node.descendants?.()||[])]);}
  }
  const nodes=new Map(['githubRepositoryList','githubRepositoryPagination','githubIncludeForks','githubRepoRefresh','githubRepositoryMessage','githubRepositorySummary','githubRepositoryQuery','myGitHubID'].map(id=>[id,new Element()]));
  const requests=[];
  const $=selector=>{
    const selected=selector.split(',').map(id=>nodes.get(id.trim().slice(1))).filter(Boolean);
    return {off(){return this;},on(event,handler){selected.forEach(node=>node.events[event]=handler);return this;},
      prop(key,value){if(value===undefined)return selected[0]?.[key];selected.forEach(node=>node[key]=value);return this;},
      val(){return selected[0]?.value||'';},text(value){selected.forEach(node=>node.textContent=value);return this;}};
  };
  $.ajax=options=>requests.push(options);
  const sandbox={admin:{},$,Label:{servePath:''},document:{getElementById:id=>nodes.get(id),createElement:tag=>new Element(tag)}};
  vm.runInNewContext(fs.readFileSync('src/main/webapp/js/admin/pagination.js','utf8'),sandbox);
  vm.runInNewContext(fs.readFileSync('src/main/webapp/js/admin/githubRepositories.js','utf8'),sandbox);
  nodes.get('myGitHubID').value='Owner';
  const repositories=Array.from({length:15},(_,index)=>({fullName:'owner/repo-'+index,name:index===0?payload:'repo-'+index,description:payload,fork:index>6,stars:2,forks:1,selected:true}));
  const data={sc:true,synced:true,username:'Owner',includeForks:false,repositories};
  const ui=sandbox.admin.githubRepositories;ui.init();requests.shift().success(data);
  assert(nodes.get('githubRepositorySummary').textContent.startsWith('7 个展示项目 · 14 Stars'));
  const list=nodes.get('githubRepositoryList'), checkbox=list.descendants().find(node=>node.type==='checkbox');
  checkbox.checked=false;checkbox.events.change();
  assert.equal(list.descendants().find(node=>node.type==='checkbox'),checkbox,'Selecting a repository must preserve keyboard focus');
  assert(nodes.get('githubRepositorySummary').textContent.startsWith('6 个展示项目 · 12 Stars'));
  const hidden=JSON.parse(JSON.stringify(ui.payload())).hidden;assert.deepEqual(hidden,['owner/repo-0']);
  nodes.get('githubIncludeForks').checked=true;nodes.get('githubIncludeForks').events['change.githubRepos']();
  assert(nodes.get('githubRepositorySummary').textContent.startsWith('14 个展示项目 · 28 Stars'));
  ui.refresh(nodes.get('githubRepoRefresh'));assert.equal(requests.length,0,'Unsaved selections must be saved before network refresh');
  nodes.get('githubRepositoryPagination').children.find(node=>node.title==='下一页').events.click();assert.equal(ui.page,2);
  const lastCheckbox=list.descendants().filter(node=>node.type==='checkbox').at(-1);lastCheckbox.checked=false;lastCheckbox.events.change();
  nodes.get('githubRepositoryQuery').value='repo-0';nodes.get('githubRepositoryQuery').events['input.githubRepos']();
  assert.deepEqual(JSON.parse(JSON.stringify(ui.payload())).hidden,['owner/repo-0','owner/repo-14'],'Pagination and search retain hidden selections');
  assert(list.descendants().some(node=>node.textContent===payload),'Repository metadata must be rendered as literal text');
  nodes.get('myGitHubID').value='Another';assert.deepEqual(JSON.parse(JSON.stringify(ui.payload())).hidden,[],'An account change must not hide repositories from another owner');
  ui.load();requests.shift().error({});assert.equal(ui.payload(),null,'Read failures must not overwrite saved visibility settings');
}

{
  let built, updated;
  const $=()=>({addClass(){return this;},table(value,data){if(value==='update')updated=data;else built=value;return this;},paginate(){return this;},text(){return this;}});
  $.extend=Object.assign;
  const sandbox={admin:{pagination:{render(){}}},$,Label:{operationsLabel:'操作'},document:{getElementById:()=>null}};
  vm.runInNewContext(fs.readFileSync('src/main/webapp/js/admin/tablePaginate.js','utf8'),sandbox);
  const table=new sandbox.TablePaginate('plugin'), columns=[{text:'插件',index:'name'}];
  table.buildTable(columns);
  assert.equal(columns.length,1,'Building actions must not mutate callers column definitions');
  assert.equal(built.colModel.at(-1).index,'expendRow');
  assert(!built.expendRow,'Actions must not use the broken hover-only rows');
  table.buildTable([{text:'作者',index:'title'},{text:'操作',index:'actions'}]);
  assert.equal(built.colModel.length,2,'Tables with explicit action columns must not receive duplicate controls');
  table.buildTable(columns,true);assert.equal(built.colModel.length,1);
  table.keyword='a&b / 中文';assert.equal(table.keywordQuery(),'?k='+encodeURIComponent(table.keyword));
  const original={name:'测试插件',linkOrder:'sort',expendRow:'<a onclick="toggle()">禁用</a>'};
  table.updateTablePagination([original],1,{paginationPageCount:1,paginationPageNums:[1]});
  assert(updated.data[0].groupData[0].expendRow.includes('console-row-actions'));
  assert(updated.data[0].groupData[0].expendRow.includes('onclick="toggle()"'),'Original commands remain usable');
  assert.equal(updated.data[0].groupData[0].linkOrder,'','Filtered rows must not reorder against an unrelated full-list neighbor');
  assert.equal(original.linkOrder,'sort');assert(!original.expendRow.includes('console-row-actions'));
}
{
  let createdForm, requestedPage;
  const host={before(form){createdForm=form;}};
  const sandbox={Label:{searchLabel:'搜索'},$:()=>({}),window:{location:{hash:'#article/draft-list/3'}},
    document:{getElementById:id=>id==='draftTable'?host:null,createElement:tag=>({tagName:tag,children:[],append(...nodes){this.children.push(...nodes);},setAttribute(){}})},
    admin:{draftList:{getList:page=>{requestedPage=page;}},setHashByPage:page=>{sandbox.window.location.hash='#article/draft-list/'+page;}}};
  sandbox.$.extend=Object.assign;
  vm.runInNewContext(fs.readFileSync('src/main/webapp/js/admin/tablePaginate.js','utf8'),sandbox);
  const table=new sandbox.TablePaginate('draft');table.initSearch();
  createdForm.children[0].value='  草稿 & 内容  ';createdForm.onsubmit({preventDefault(){}});
  assert.equal(table.keyword,'草稿 & 内容');assert.equal(sandbox.window.location.hash,'#article/draft-list/1');
  createdForm.onsubmit({preventDefault(){}});assert.equal(requestedPage,1,'Searching on page 1 still refreshes the list');
}
{
  const nodes=new Map(); let pending, requested=0, initialized=0;
  function node(id){if(!nodes.has(id))nodes.set(id,{content:'',classes:new Set(),attrs:{}});return nodes.get(id);}
  const $=selector=>{
    const value=node(typeof selector==='string'?selector:'window');
    return {length:typeof selector==='string'&&selector.startsWith('#tabCategory')?0:1,
      scrollTop(){return this;},text(text){if(text===undefined)return value.content;value.content=text;return this;},
      html(html){if(html===undefined)return value.content;value.content=html;return this;},empty(){value.content='';return this;},
      tabs(){return this;},addClass(name){value.classes.add(name);return this;},removeClass(name){value.classes.delete(name);return this;},
      attr(key,data){value.attrs[key]=data;return this;},removeAttr(key){delete value.attrs[key];return this;}};
  };
  $.extend=Object.assign; $.ajax=options=>{pending=options;requested++;};
  const sandbox={$,Util:{},console,Label:{loadingLabel:'载入中'},window:{location:{hash:'#tools/category-list/1'}},
    document:{querySelector:()=>null,querySelectorAll:()=>[],documentElement:{dataset:{boloAdminPages:'/'}}}};
  vm.runInNewContext(fs.readFileSync('src/main/webapp/js/admin/admin.js','utf8'),sandbox);
  Object.assign(sandbox.admin,{editors:{articleEditor:{}},article:{status:{},isConfirm:false},plugin:{setCurByHash(){}},
    register:{'category-list':{obj:{},init(page){initialized++;assert.equal(page,'2');}}}});
  sandbox.admin.setCurByHash();assert.equal(requested,1);assert.equal(pending.timeout,30000);
  assert(node('#tabsPanel_category-list').classes.has('console-page-loading'),'Pending fragments must not expose an empty card');
  sandbox.window.location.hash='#tools/category-list/2';sandbox.admin.setCurByHash();assert.equal(requested,1,'Repeated visits share one fragment request');
  pending.error({},'timeout');pending.complete();assert.equal(node('#loadMsg').content,'');
  assert(node('#tipMsg').content.includes('超时'));assert(!sandbox.admin.panelRequests['category-list']);
  assert(!node('#tabsPanel_category-list').classes.has('console-page-loading'));
  sandbox.admin.setCurByHash();assert.equal(requested,2,'Failed pages can be retried');
  pending.success('<div>Loaded category list</div>');pending.complete();assert.equal(initialized,1);
  assert.equal(node('#tabsPanel_category-list').content,'<div>Loaded category list</div>');
}
{
  const template=fs.readFileSync('src/main/webapp/admin/admin-index.ftl','utf8');
  const ids=[...template.matchAll(/id="tabs_([\w-]+)"/g)].map(match=>match[1]);
  const nodes=new Map(),data=new Map(),root={id:'tabs'};
  function node(id){if(!nodes.has(id))nodes.set(id,{id,active:false,visible:false});return nodes.get(id);}
  function $(selector) {
    const selected=typeof selector==='string'?node(selector.replace(/^#/, '').replace(/ a$/,'')):selector;
    return {0:selected,find(kind){if(kind==='li')return ids.map(id=>({children:[{id:'tabs_'+id}]}));return $(selected);},
      hasClass:()=>selected.active,addClass(){selected.active=true;},removeClass(){selected.active=false;},show(){selected.visible=true;},hide(){selected.visible=false;}};
  }
  $.fn={extend:Object.assign};$.extend=Object.assign;$.data=(element,key,value)=>{if(value!==undefined)data.set(element,value);return data.get(element)};
  const legacy=fs.readFileSync('src/main/webapp/js/lib/jquery/jquery.bowknot.min.js','utf8').split('\n').find(line=>line.includes('tabs:{version:'));
  vm.runInNewContext(legacy,{jQuery:$,window:{}});
  $.tabs._attach(root);
  for(const tab of ['access','ip','geo','comments','draft-list','preference','access','article-list']) {
    assert(ids.includes(tab),'Every safety page must use a normal, distinct tabs ID');
    assert(template.includes('id="tabsPanel_'+tab+'"'));
    $.tabs._setCurrentTabs(root,tab);
    assert.deepEqual(ids.filter(id=>node('tabs_'+id).active),[tab],'The original tabs component must clear previous selections');
    assert.deepEqual(ids.filter(id=>node('tabsPanel_'+id).visible),[tab],'Only the matching original panel is visible');
  }
}
console.log('Admin checks passed: safe renderers, persistent list actions, paginated search, fragment retry, avatar fallback, responsive controls and ordered geo-access editing');

// Exercise shared pagination by typing and pressing Enter, including embedded settings forms.
{
  class Element {
    constructor(tag){this.tagName=tag;this.children=[];this.events={};this.attributes={};this.classList={add(){}};this.value='';}
    append(...nodes){this.children.push(...nodes);} replaceChildren(){this.children=[];}
    setAttribute(key,value){this.attributes[key]=value;} removeAttribute(key){delete this.attributes[key];}
    addEventListener(key,callback){this.events[key]=callback;} focus(){this.focused=true;}
  }
  const host=new Element('div'),pages=[],notices=[];
  const sandbox={admin:{showNotice:msg=>notices.push(msg)},document:{getElementById:()=>host,createElement:tag=>new Element(tag)}};
  vm.runInNewContext(fs.readFileSync('src/main/webapp/js/admin/pagination.js','utf8'),sandbox);
  sandbox.admin.pagination.render('test',1,7,page=>pages.push(page));
  const input=host.children.at(-1).children[0];input.value='4';let prevented=false,stopped=false;
  input.events.keydown({key:'Enter',preventDefault(){prevented=true;},stopPropagation(){stopped=true;}});
  assert.deepEqual(pages,[4]);assert(prevented&&stopped,'Enter must jump without submitting the settings form');
  input.value='8';host.children.at(-1).children[1].events.click();assert.deepEqual(pages,[4]);assert(input.focused);assert.equal(input.attributes['aria-invalid'],'true');
  input.value='2';input.events.input();assert(!input.attributes['aria-invalid']);host.children.at(-1).children[1].events.click();assert.deepEqual(pages,[4,2]);
  host.children.find(node=>node.title==='下一页').events.click();assert.deepEqual(pages,[4,2,2]);
  sandbox.admin.pagination.render('test',9,1,page=>pages.push(page));assert.equal(host.children.length,1);assert.equal(host.children[0].textContent,'1 / 1');
}
