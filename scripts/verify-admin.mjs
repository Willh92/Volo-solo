import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

// Exercise the actual rendering callbacks with hostile data and observable output sinks.
const payload='<img src=x onerror=alert(1)></option><script>alert(2)</script> & "quoted"';
const escape=value=>String(value).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
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
for(const [file,hash,which] of [['main.js','#main','main'],['others.js','#tools/others/log','others']]) {
  const rendered=[];
  const $=selector=>({value:'',text(value){this.value=value;return this;},html(){return escape(this.value);},
    prepend(value){rendered.push(value);return this;},children(){return this;},slice(){return this;},remove(){return this;}});
  let response={freeMemNow:1024,result:[{id:1,date:'2026-10-30 13:00:00',level:payload,name:payload,lineNumber:payload,message:payload,throwable:{class:payload,message:payload,stackTrace:[payload]}}]};
  $.ajax=options=>{assert.equal(options.dataType,'json');options.success(response)};
  const sandbox={admin:{register:{}},window:{location:{hash}},$,Label:{servePath:''},Date};
  vm.runInNewContext(fs.readFileSync('src/main/webapp/js/admin/'+file,'utf8'),sandbox);
  (which==='main'?sandbox.admin.register.main:sandbox.admin.others).getLog();
  assert.equal(rendered.length,1,file);
  assert(!rendered[0].includes('<img')&&!rendered[0].includes('<script'),file+' must escape every log field');
  assert(rendered[0].includes('&lt;img'),file+' should preserve readable payload text');
  for (response of [null,{}, {sc:false,msg:'Session expired'},'<html>Login</html>']) {
    (which==='main'?sandbox.admin.register.main:sandbox.admin.others).getLog();
    assert.equal(rendered.length,1,file+' must ignore malformed or expired-session responses');
  }
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
    TablePaginate:function(){this.updateTablePagination=data=>{rows=data;};},
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
    TablePaginate:function(){this.updateTablePagination=data=>{rows=data;};}};
  vm.runInNewContext(fs.readFileSync('src/main/webapp/js/admin/commentList.js','utf8'),sandbox);
  sandbox.admin.commentList.getList(1);
  assert(rows[0].content.includes('<p>Readable comment</p>'),'Sanitized Markdown must remain formatted');
  assert(rows[0].title.includes('&lt;img')&&!rows[0].title.includes('<img src=x'));
  assert(!rows[0].title.includes('href=\'javascript:'));
  assert(!rows[0].title.includes("src='https://example.test/a' onerror="),'Avatar attributes must not be injectable');
}
console.log('Admin checks passed: safe renderers, generated avatar fallback, navigation titles, category cards, sorting controls and responsive editor options');
