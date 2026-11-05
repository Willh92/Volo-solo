import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

// Minimal DOM fixture: exercise keyboard/events and safe text sinks, not CSS rendering.
function fixture() {
  let document;
  class Element {
    constructor(tag) {
      this.tagName=tag.toUpperCase();this.children=[];this.attributes=new Map();this.dataset={};this.listeners=new Map();
      this.hidden=false;this.disabled=false;this.required=false;this.value='';this.selectedIndex=0;this.size=0;this.style={cssText:'',setProperty(){},removeProperty(){}};
      this.className='';this._text='';this.scrollHeight=200;
      this.classList={contains:value=>this.className.split(' ').includes(value),add:value=>{if(!this.classList.contains(value))this.className+=' '+value;},toggle:(value,on)=>{this.className=this.className.split(' ').filter(item=>item!==value).concat(on?[value]:[]).join(' ');}};
    }
    get textContent(){return this._text+this.children.map(child=>child.textContent).join('');}
    set textContent(value){this._text=String(value);this.replaceChildren();}
    get childNodes(){return this.children;}
    get parentElement(){return this.parent;}
    get previousElementSibling(){return this.parent?.children[this.parent.children.indexOf(this)-1];}
    get isConnected(){return this===document.body||!!this.parent?.isConnected;}
    get options(){return this.children.flatMap(child=>child.tagName==='OPTION'?[child]:child.tagName==='OPTGROUP'?child.options:[]);}
    get labels(){return [this.closest('label')].filter(Boolean);}
    get control(){return this.querySelector('select');}
    append(...children){for(const child of children){child.remove();child.parent=this;this.children.push(child);}}
    before(child){child.remove();child.parent=this.parent;this.parent.children.splice(this.parent.children.indexOf(this),0,child);}
    remove(){if(this.parent){this.parent.children.splice(this.parent.children.indexOf(this),1);this.parent=null;}}
    replaceChildren(...children){for(const child of this.children)child.parent=null;this.children=[];this.append(...children);}
    contains(child){return child===this||this.children.some(item=>item.contains(child));}
    matches(selector){if(selector.startsWith('.'))return this.classList.contains(selector.slice(1));if(selector.startsWith('[')){const [,key,value]=selector.match(/^\[([^=\]]+)(?:=([^\]]+))?\]$/);return value==null?this.hasAttribute(key):this.getAttribute(key)===value;}return this.tagName===selector.toUpperCase();}
    closest(selector){return this.matches(selector)?this:this.parent?.closest(selector)||null;}
    querySelectorAll(selector){return this.children.flatMap(child=>[...(child.matches(selector)?[child]:[]),...child.querySelectorAll(selector)]);}
    querySelector(selector){return this.querySelectorAll(selector)[0]||null;}
    setAttribute(key,value){this.attributes.set(key,String(value));if(key==='data-index')this.dataset.index=String(value);}
    getAttribute(key){return this.attributes.get(key)??null;}
    hasAttribute(key){return this.attributes.has(key);}
    removeAttribute(key){this.attributes.delete(key);}
    addEventListener(type,fn){const list=this.listeners.get(type)||[];list.push(fn);this.listeners.set(type,list);}
    removeEventListener(type,fn){this.listeners.set(type,(this.listeners.get(type)||[]).filter(item=>item!==fn));}
    dispatchEvent(event){event.target??=this;for(const fn of this.listeners.get(event.type)||[])fn(event);return !event.defaultPrevented;}
    focus(){document.activeElement=this;this.dispatchEvent({type:'focus'});}
    scrollIntoView(){this.scrolled=true;}
    getBoundingClientRect(){return {top:50,bottom:92,left:20,width:240};}
    getClientRects(){return this.hidden||this.parent?.hidden?[]:[this.getBoundingClientRect()];}
  }
  document=new Element('document');document.body=new Element('body');document.body.className='bolo-console';
  document.createElement=tag=>new Element(tag);
  document.querySelectorAll=selector=>document.body.querySelectorAll(selector);
  document.getElementById=id=>[document.body,...document.body.querySelectorAll('input'),...document.body.querySelectorAll('div'),...document.body.querySelectorAll('section'),...document.body.querySelectorAll('button'),...document.body.querySelectorAll('span')].find(node=>node.id===id);
  const window=new Element('window'),leave=[];window.BoloNavigation={onLeave:fn=>leave.push(fn)};
  const observers=[];
  const jquery=function(){return {on(type,fn){jquery.events.set(type,fn);},off(type){jquery.events.delete(type);}};};jquery.events=new Map();
  jquery.valHooks={select:{set(select,value){select.selectedIndex=select.options.findIndex(option=>option.value===value);return value;}}};window.jQuery=jquery;
  class Event {constructor(type){this.type=type;}preventDefault(){this.defaultPrevented=true;}}
  const sandbox={document,window,jQuery:jquery,Event,innerWidth:390,innerHeight:740,queueMicrotask,
    getComputedStyle:node=>({maxWidth:'500px',display:node.hidden?'none':'block'}),
    MutationObserver:class{constructor(fn){this.callback=fn;observers.push(this);}observe(){}disconnect(){this.disposed=true;}}};
  return {Element,document,window,leave,observers,sandbox,jquery,Event};
}
function key(element,value){const event={type:'keydown',key:value,preventDefault(){this.defaultPrevented=true;}};element.dispatchEvent(event);return event;}
function click(element){element.dispatchEvent({type:'click',target:element});}

{
  const f=fixture(),select=new f.Element('select'),label=new f.Element('label');
  label.id='country-label';label.append(select);f.document.body.append(label);
  for(const [value,text] of [['a','允许访问'],['b','拒绝访问'],['c','<img onerror=alert(1)>']]){const option=new f.Element('option');option.value=value;option.textContent=text;select.append(option);}
  const skipped=new f.Element('select');skipped.multiple=true;f.document.body.append(skipped);
  const original=f.jquery.valHooks.select.set;
  vm.runInNewContext(fs.readFileSync('src/main/webapp/js/console-select.js','utf8'),f.sandbox);
  const button=f.document.body.querySelector('button');assert(button);assert.equal(f.document.body.querySelectorAll('button').length,1);
  assert.equal(button.textContent,'允许访问');assert.equal(button.getAttribute('aria-expanded'),'false');assert.equal(select.tabIndex,-1);
  let changed=0,input=0;select.addEventListener('change',()=>changed++);select.addEventListener('input',()=>input++);
  key(button,'ArrowDown');key(button,'End');key(button,'Enter');
  assert.equal(select.selectedIndex,2);assert.equal(changed,1);assert.equal(input,1);assert.equal(button.textContent,'<img onerror=alert(1)>');
  assert.equal(f.document.body.querySelectorAll('img').length,0,'Option labels must never become HTML');
  key(button,'Enter');key(button,'Home');key(button,'Escape');assert.equal(select.selectedIndex,2,'Escape must not commit the highlighted option');
  f.jquery.valHooks.select.set(select,'b');assert.equal(button.textContent,'拒绝访问','Programmatic form loads update the displayed value');
  select.selectedIndex=0;f.jquery.events.get('ajaxComplete.voloSelect')();await Promise.resolve();assert.equal(button.textContent,'允许访问');
  select.disabled=true;f.observers[0].callback([]);await Promise.resolve();assert.equal(button.disabled,true);click(button);assert.equal(button.getAttribute('aria-expanded'),'false');
  select.disabled=false;f.observers[0].callback([]);await Promise.resolve();click(button);
  f.document.dispatchEvent({type:'click',target:f.document.body});assert.equal(button.getAttribute('aria-expanded'),'false');
  click(button);f.window.dispatchEvent({type:'hashchange'});assert.equal(button.getAttribute('aria-expanded'),'false');
  click(button);label.hidden=true;f.observers[0].callback([]);await Promise.resolve();assert.equal(button.getAttribute('aria-expanded'),'false','Hiding an ancestor must dismiss its floating options');label.hidden=false;
  select.hidden=true;f.observers[0].callback([]);await Promise.resolve();assert.equal(button.hidden,true,'Plugin-hidden controls must stay hidden');
  for(const dispose of f.leave)dispose();assert.equal(f.jquery.valHooks.select.set,original);assert.equal(f.jquery.events.size,0);assert(f.observers[0].disposed);
}
{
  const f=fixture(),field=new f.Element('div');field.className='console-tag-field';f.document.body.append(field);
  const elements={};for(const [id,tag] of Object.entries({tag:'input',articleTagInput:'input',articleTagToggle:'button',articleTagPanel:'section',articleTagSelection:'div',articleTagSearch:'input',articleTagOptions:'div',articleTagCount:'span'})){
    const element=new f.Element(tag);element.id=id;field.append(element);elements[id]=element;
  }
  elements.articleTagPanel.append(elements.articleTagSearch,elements.articleTagOptions,elements.articleTagCount);
  f.sandbox.admin={};vm.runInNewContext(fs.readFileSync('src/main/webapp/js/admin/tagPicker.js','utf8'),f.sandbox);
  const picker=f.sandbox.admin.tagPicker;picker.init();assert.equal(elements.articleTagToggle.disabled,true);
  picker.setTags([{tagTitle:'Volo'},{tagTitle:'Security'},{tagTitle:'<img onerror=alert(1)>'}]);
  click(elements.articleTagToggle);assert.equal(elements.articleTagPanel.hidden,false);assert.equal(elements.articleTagOptions.children.length,3);
  const selected=elements.articleTagOptions.children[0];click(selected);assert.equal(elements.tag.value,'Volo');assert.equal(elements.articleTagSelection.children.length,1);
  f.document.dispatchEvent({type:'click',target:selected,composedPath:()=>[selected,elements.articleTagOptions,elements.articleTagPanel,field,f.document.body]});
  assert.equal(elements.articleTagPanel.hidden,false,'Rebuilding candidates must not turn an internal click into an outside click');
  elements.articleTagSearch.value='sec';elements.articleTagSearch.dispatchEvent(new f.Event('input'));assert.equal(elements.articleTagOptions.children.length,1);
  click(elements.articleTagOptions.children[0]);assert.equal(elements.tag.value,'Volo,Security');
  key(elements.articleTagSearch,'ArrowDown');assert.equal(f.document.activeElement.textContent,'Security');
  key(elements.articleTagPanel,'Escape');assert.equal(elements.articleTagPanel.hidden,true);
  click(elements.articleTagSelection.children[0]);assert.equal(elements.tag.value,'Security');
  elements.tag.value=' Security, SECURITY, 新标签 ';picker.refresh();assert.equal(elements.tag.value,'Security,新标签');
  elements.articleTagInput.value='volo';key(elements.articleTagInput,'Enter');assert.equal(elements.tag.value,'Security,新标签,Volo');assert.equal(elements.articleTagInput.value,'');
  elements.articleTagInput.value='VOLO';key(elements.articleTagInput,'Enter');assert.equal(elements.tag.value,'Security,新标签,Volo','Typing an existing tag reuses it without toggling or duplicating it');
  elements.articleTagInput.value='NewTag';key(elements.articleTagInput,'Enter');elements.articleTagInput.value='newtag';key(elements.articleTagInput,'Enter');assert.equal(elements.tag.value,'Security,新标签,Volo,NewTag');
  elements.articleTagInput.value='输入法';elements.articleTagInput.dispatchEvent({type:'keydown',key:'Enter',isComposing:true});assert.equal(elements.articleTagInput.value,'输入法');
  elements.articleTagInput.dispatchEvent({type:'blur',relatedTarget:null});assert.equal(elements.tag.value,'Security,新标签,Volo,NewTag,输入法');
  assert.equal(f.document.body.querySelectorAll('img').length,0);
  picker.destroy();assert.equal(elements.tag.listeners.get('input').length,0);assert.equal(elements.articleTagPanel.hidden,true);
  picker.init();picker.setTags(['Volo']);assert.equal(elements.articleTagToggle.disabled,false);picker.destroy();
}
{
  const values=new Map([['#ipBanRange','203.0.113.2'],['#ipBanReason','reviewed'],['#ipBanDuration','keep'],['#ipBanOccurrences','0']]),requests=[];
  const $=selector=>({val(value){if(value===undefined)return values.get(selector);values.set(selector,value);return this;},prop(){return this;},text(value){values.set(selector,value);return this;}});
  $.ajax=request=>requests.push(request);
  const sandbox={$,Label:{servePath:''},admin:{register:{}},document:{},window:{}};
  vm.runInNewContext(fs.readFileSync('src/main/webapp/js/admin/security.js','utf8'),sandbox);
  const security=sandbox.admin.security;
  security.ipBanEditing={range:'203.0.113.2',source:'automatic',active:true};
  security.addIpBan();assert.equal(requests[0].type,'PUT');
  assert.deepEqual(JSON.parse(requests[0].data),{range:'203.0.113.2',note:'reviewed',occurrences:0},'Editing a note/count must omit the duration to preserve the deadline');
  security.addIpBan();assert.equal(requests.length,1,'Double submission is suppressed');requests[0].complete();
  values.set('#ipBanDuration','259200');security.ipBanEditing.active=false;security.addIpBan();
  assert.equal(JSON.parse(requests[1].data).durationSeconds,259200,'Reactivation sends a fresh duration');requests[1].complete();
  values.set('#ipBanOccurrences','-1');security.addIpBan();assert.equal(requests.length,2,'Invalid counts must not submit');
  security.ipBanEditing=null;values.set('#ipBanDuration','0');security.addIpBan();
  assert.equal(requests[2].type,'POST');assert.deepEqual(JSON.parse(requests[2].data),{range:'203.0.113.2',reason:'reviewed',durationSeconds:0});
}
{
  const f=fixture(),values=new Map([['#totpEnabled','false'],['#totpScope','admin'],['#totpWindowSteps','1'],['#totpRememberDays','0'],['#totpSetupCode',''],['#totpCurrentCode',''],['#totpSettingsVerifyCode',''],['#totpActionCode','']]),requests=[];
  const ids=['totpStatusBadge','totpSummary','totpStartSetup','totpRegenRecoveryBtn','totpUnbindBtn','totpRevokeDevicesBtn','totpIssuer','totpToggleVerifyWrap','totpSettingsVerifyCode','totpQrCode','totpRecoveryPanel','totpRecoveryList','totpSetupPanel','totpSecretText','totpSetupMeta','totpCurrentCodeWrap','totpCurrentCode','totpSetupCode','totpSetupMessage','totpSettingsMessage','totpActionPanel','totpActionTitle','totpActionDesc','totpActionSubmit','totpActionCode','totpActionMessage','totpSaveSettings','totpConfirmBtn'];
  for(const id of ids){const el=f.document.createElement(id.endsWith('Code')||id==='totpIssuer'?'input':'div');el.id=id;f.document.body.append(el);}
  let qrPayload=null;
  const $=selector=>{
    if(typeof selector==='object'&&selector&&selector.id==='totpQrCode')return{qrcode(opts){qrPayload=opts;}};
    const id=typeof selector==='string'&&selector.startsWith('#')&&!selector.includes(',')?selector.slice(1):null;
    const node=id?f.document.getElementById(id):null;
    return{
      val(v){if(v===undefined)return node&&node.tagName==='INPUT'&&!values.has(selector)?node.value:values.get(selector);values.set(selector,v);if(node)node.value=v;return this;},
      prop(k,v){if(node&&k==='disabled')node.disabled=!!v;return this;},
      text(v){if(node)node.textContent=v;values.set(selector,v);return this;}
    };
  };
  $.fn={qrcode(){}};
  $.ajax=req=>requests.push(req);
  const notices=[];
  const sandbox={$,Label:{servePath:'',staticServePath:''},admin:{register:{},showNotice:m=>notices.push(m)},document:f.document,window:{Label:{staticServePath:''}},navigator:{},confirm:()=>true,btoa,atob,Uint8Array};
  vm.runInNewContext(fs.readFileSync('src/main/webapp/js/admin/security.js','utf8'),sandbox);
  assert.ok(sandbox.admin.register.totp,'TOTP security console module must register with admin router');
  const sec=sandbox.admin.security;
  sec.applyTotpState({enabled:false,bound:false,scope:'admin',issuer:'',defaultIssuer:'Volo',windowSteps:1,rememberDays:0,recoveryRemaining:0});
  assert.equal(f.document.getElementById('totpStatusBadge').dataset.state,'unbound');
  values.set('#totpEnabled','true');
  sec.saveTotpSettings();
  assert.equal(requests.length,0,'Cannot enable TOTP before binding a secret');
  sec.startTotpSetup();
  assert.equal(requests[0].url,'/console/security/totp/setup');
  requests[0].complete();
  requests[0].success({sc:true,setup:{setupId:'s1',secret:'JBSWY3DPEHPK3PXP',formattedSecret:'JBSW Y3DP EHPK 3PXP',otpauthUri:'otpauth://totp/Volo:admin?secret=JBSWY3DPEHPK3PXP&issuer=Volo',issuer:'Volo',account:'admin',requireCurrentCode:false}});
  assert.equal(f.document.getElementById('totpSetupPanel').hidden,false);
  assert.equal(qrPayload.text,'otpauth://totp/Volo:admin?secret=JBSWY3DPEHPK3PXP&issuer=Volo');
  assert.equal(qrPayload.correctLevel,0,'Local QR renderer must use M error correction without external network requests');
  values.set('#totpSetupCode','12345');
  sec.confirmTotpSetup();
  assert.equal(requests.length,1,'Non-6-digit setup code must be rejected client-side');
  values.set('#totpSetupCode','123456');
  sec.confirmTotpSetup();
  assert.equal(requests[1].url,'/console/security/totp/confirm');
  requests[1].complete();
  requests[1].success({sc:true,msg:'ok',recoveryCodes:['ABCD-2345','<img onerror=alert(1)>'],totp:{enabled:true,bound:true,boundUser:'admin',boundAt:1700000000000,scope:'admin',issuer:'Volo',defaultIssuer:'Volo',windowSteps:1,rememberDays:14,recoveryRemaining:8}});
  assert.equal(f.document.getElementById('totpStatusBadge').dataset.state,'enabled');
  assert.equal(f.document.getElementById('totpRevokeDevicesBtn').hidden,false);
  assert.equal(f.document.getElementById('totpRecoveryList').querySelectorAll('img').length,0,'Recovery codes must render via textContent only');
  values.set('#totpEnabled','false');
  sec.syncTotpToggleVerify();
  assert.equal(f.document.getElementById('totpToggleVerifyWrap').hidden,false,'Toggling enabled state on a bound key must prompt for current OTP');
  sec.saveTotpSettings();
  assert.equal(requests.length,2,'Disabling active TOTP without verification code must not submit');
  values.set('#totpSettingsVerifyCode','654321');
  f.document.getElementById('totpIssuer').value='MyBlog';
  values.set('#totpScope','all');
  values.set('#totpWindowSteps','2');
  values.set('#totpRememberDays','30');
  sec.saveTotpSettings();
  assert.deepEqual(JSON.parse(requests[2].data),{enabled:'false',scope:'all',issuer:'MyBlog',windowSteps:2,rememberDays:30,verifyCode:'654321'});
  requests[2].complete();
  requests[2].success({sc:true,msg:'saved',totp:{enabled:false,bound:true,boundUser:'admin',scope:'all',issuer:'MyBlog',defaultIssuer:'Volo',windowSteps:2,rememberDays:30,recoveryRemaining:8}});
  assert.equal(f.document.getElementById('totpSettingsMessage').dataset.tone,'ok');
  const consoleCss=fs.readFileSync('src/main/webapp/scss/console-theme.css','utf8');
  assert(consoleCss.includes('.console-smtp-grid button.console-select-trigger'),'Grid select triggers must match adjacent input width and 44px height');

  // Passkey Base64URL helpers & DOM-safe credential rendering.
  for(const id of ['passkeyStatusBadge','passkeyDeviceName','passkeyListBody','passkeyMessage','passkeyRegisterBtn']){
    const el=f.document.createElement(id==='passkeyDeviceName'?'input':'div');el.id=id;f.document.body.append(el);
  }
  const sampleBytes=new Uint8Array([0,255,254,62,63,128,1]);
  const encoded=sec._bytesToB64Url(sampleBytes.buffer);
  assert(!encoded.includes('+')&&!encoded.includes('/')&&!encoded.includes('='),'Base64URL must not contain +, /, or =');
  assert.deepEqual([...sec._b64UrlToBytes(encoded)],[...sampleBytes]);
  sec.renderPasskeys([{id:'cred-1',name:'MacBook <img src=x onerror=alert(1)>',alg:-7,createdAt:1700000000000,lastUsedAt:0,signCount:3}]);
  assert.equal(f.document.getElementById('passkeyListBody').querySelectorAll('img').length,0,'Passkey names must render via textContent without HTML parsing');
  assert.equal(f.document.getElementById('passkeyStatusBadge').dataset.state,'enabled');
}
console.log('Console controls: selects, tags, IP edits and TOTP console controls passed keyboard, events, safe labels, dynamic values, hidden controls, cleanup and submission checks.');
