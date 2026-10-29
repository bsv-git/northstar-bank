'use strict';
const $ = s => document.querySelector(s);
const savedTheme = localStorage.getItem('northstar-theme');
if(savedTheme==='dark')document.documentElement.dataset.theme='dark';
const escapeHtml = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const money = n => new Intl.NumberFormat('en-US',{style:'currency',currency:'USD'}).format(Number(n));
const date = value => new Date(value).toLocaleDateString('en-US',{month:'short',day:'numeric',year:'numeric'});
const time = value => new Date(value).toLocaleTimeString('en-US',{hour:'numeric',minute:'2-digit'});
const initials = name => name.split(' ').map(x=>x[0]).slice(0,2).join('');
let state = {user:null,page:'overview',accounts:[],transactions:[],recipients:[],cards:[],notifications:[],payments:[]};
let eventSource, toastTimer, transferDraft, refreshBusy=false;
function setTheme(theme){
  document.documentElement.dataset.theme=theme;
  localStorage.setItem('northstar-theme',theme);
  const dark=theme==='dark';
  document.querySelectorAll('.theme-toggle').forEach(button=>{button.textContent=dark?'☀':'☾';button.setAttribute('aria-label',dark?'Switch to light mode':'Switch to dark mode');button.title=dark?'Switch to light mode':'Switch to dark mode';});
}
async function api(path, options={}) {
  const method=(options.method||'GET').toUpperCase();
  if(method!=='GET'&&method!=='HEAD'&&method!=='OPTIONS'&&!document.cookie.split('; ').some(value=>value.startsWith('XSRF-TOKEN='))){
    await fetch('/api/csrf',{credentials:'same-origin',headers:{'X-Requested-With':'Northstar'}});
  }
  const csrf=document.cookie.split('; ').find(value=>value.startsWith('XSRF-TOKEN='))?.split('=').slice(1).join('=');
  const csrfHeader=method==='GET'||method==='HEAD'||method==='OPTIONS'?{}:{'X-XSRF-TOKEN':decodeURIComponent(csrf||'')};
  const res = await fetch('/api'+path,{credentials:'same-origin',...options,headers:{'Content-Type':'application/json','X-Requested-With':'Northstar',...csrfHeader,...(options.headers||{})}});
  const data = await res.json();
  if(!res.ok){if(res.status===401&&path!='/login'){showLogin();}throw new Error(data.error||'Something went wrong. Please try again.');}
  return data;
}
function toast(message,error=false){clearTimeout(toastTimer);$('#toast').textContent=message;$('#toast').className='toast'+(error?' error':'');toastTimer=setTimeout(()=>$('#toast').classList.add('hidden'),6500);}
function showLogin(){if(eventSource)eventSource.close();state.user=null;$('#app').classList.add('hidden');$('#login-screen').classList.remove('hidden');if($('#modal').open)$('#modal').close();}
async function loadData(){
  const [accounts,transactions,recipients,cards,notifications,payments]=await Promise.all(['/accounts','/transactions','/beneficiaries','/cards','/notifications','/payments'].map(p=>api(p)));
  Object.assign(state,{accounts,transactions,recipients,cards,notifications,payments});updateBadges();
}
function updateBadges(){const count=state.notifications.filter(n=>!n.read_at).length;$('#nav-count').textContent=count;$('#nav-count').classList.toggle('hidden',!count);$('#bell-dot').classList.toggle('hidden',!count);}
async function enter(user){
  state.user=user;state.page=user.role==='ADMIN'?'operations':'overview';
  $('#login-screen').classList.add('hidden');$('#app').classList.remove('hidden');$('#main').innerHTML='<div class="loading">Getting your banking space ready…</div>';
  $('#user-name').textContent=user.name;$('#user-avatar').textContent=initials(user.name);$('#top-avatar').textContent=initials(user.name);$('#ops-nav').classList.toggle('hidden',user.role!=='ADMIN');
  try{if(user.role!=='ADMIN'){await loadData();connectEvents();}render();}catch(e){$('#main').innerHTML=`<div class="panel empty"><strong>We couldn’t load your accounts</strong>${escapeHtml(e.message)}<br><button class="button section-space" data-action="reload">Try again</button></div>`;}
}
function connectEvents(){
  if(eventSource)eventSource.close();eventSource=new EventSource('/api/events');
  eventSource.onopen=()=>{$('#live-status').innerHTML='<i></i> Live updates';$('#live-status').classList.remove('offline');};
  eventSource.onerror=()=>{$('#live-status').innerHTML='<i></i> Reconnecting';$('#live-status').classList.add('offline');};
  eventSource.addEventListener('activity',async e=>{
    const notes=JSON.parse(e.data);const old=state.notifications[0]?.sequence;state.notifications=notes;updateBadges();
    if(notes[0]?.sequence!==old){
      if(old!==undefined&&notes.length)toast(notes[0].title+' · '+notes[0].message);
      if(!refreshBusy){refreshBusy=true;try{await loadData();if(['overview','accounts','activity','operations'].includes(state.page))render();}catch(err){toast(err.message,true);}finally{refreshBusy=false;}}
    }
  });
}
function heading(title,description,action=''){return `<div class="page-heading"><div><h1>${title}</h1><p>${description}</p></div>${action?`<div class="heading-actions">${action}</div>`:''}</div>`;}
const transferButton='<button class="button primary" data-page="transfers"><span>↗</span> Make a transfer</button>';
function accountCard(a){return `<article class="account-card"><div class="account-top"><span class="account-symbol ${a.type==='SAVINGS'?'savings':''}">${a.type==='SAVINGS'?'◈':'▤'}</span><span class="account-type">${escapeHtml(a.type)}</span></div><h3>${escapeHtml(a.label)}</h3><div class="account-number">${escapeHtml(a.id)}</div><div class="account-card-bottom"><div class="account-money">${money(a.balance)}</div><span>USD · Available</span></div></article>`;}
function cardVisual(c){return `<div class="debit-card"><div class="debit-card-top">northstar.<small>DEMO DEBIT</small></div><div class="chip"></div><div class="debit-number">•••• &nbsp; •••• &nbsp; ${escapeHtml(c.last_four)}</div><div class="debit-card-bottom"><span>${escapeHtml(state.user.name.toUpperCase())}</span><span>LOCAL ONLY</span></div></div>`;}
function transactionsTable(rows,limit=200){
  if(!rows.length)return '<div class="empty"><strong>A fresh start</strong>Your account activity will appear here after your first transfer.</div>';
  return `<div class="table-scroll"><table><thead><tr><th>Transaction</th><th>Date</th><th>Status</th><th class="align-right">Amount</th></tr></thead><tbody>${rows.slice(0,limit).map(t=>`<tr><td><div class="transaction-name"><span class="transaction-icon">${t.kind==='OPENING'?'◈':Number(t.amount)>0?'↙':'↗'}</span><div><b>${escapeHtml(t.description)}</b><small>${escapeHtml(t.label)} · ${escapeHtml(t.kind.toLowerCase())}</small></div></div></td><td class="muted">${date(t.created_at)}<br><small>${time(t.created_at)}</small></td><td><span class="tag green">Completed</span></td><td class="amount align-right ${Number(t.amount)>0?'positive':''}">${Number(t.amount)>0?'+':''}${money(t.amount)}</td></tr>`).join('')}</tbody></table></div>`;
}
function overview(){
  const total=state.accounts.reduce((s,a)=>s+Number(a.balance),0);const formatted=money(total).split('.');
  return heading(`Good ${new Date().getHours()<12?'morning':new Date().getHours()<17?'afternoon':'evening'}, ${escapeHtml(state.user.name.split(' ')[0])}.`,'Here’s where things stand. Let’s make your next move count.',`<button class="button" data-action="export">↓ Export activity</button>${transferButton}`)+
  `<div class="dashboard-grid"><section><div class="balance-card"><div class="balance-top"><span>Total available balance</span><span class="currency-label">USD ⌄</span></div><div class="balance-amount">${formatted[0]}<small>.${formatted[1]}</small></div><div class="balance-sub">Across ${state.accounts.length} personal accounts · Available to transfer</div><div class="balance-bottom"><span>Built for your everyday, and what’s next.</span><button class="button light" data-page="transfers">Move money <span>↗</span></button></div></div><div class="section-heading"><h3>Your accounts</h3><button class="button ghost" data-page="accounts">View all <span>↗</span></button></div><div class="account-grid">${state.accounts.slice(0,2).map(accountCard).join('')||'<div class="empty">No personal accounts</div>'}</div><div class="section-heading"><h3>Recent transactions</h3><button class="button ghost" data-page="activity">View activity <span>↗</span></button></div><div class="table-panel">${transactionsTable(state.transactions,5)}<div class="table-footer">Balances update after each completed transfer.</div></div></section><aside class="right-stack"><div class="panel"><h3>Quick actions</h3><div class="quick-actions"><button class="quick-action" data-page="transfers"><span>↗</span>Send money</button><button class="quick-action" data-action="add-recipient"><span>＋</span>Add recipient</button><button class="quick-action" data-action="open-account"><span>▤</span>New account</button></div></div>${state.cards[0]?`<div class="panel"><div class="section-heading" style=""><h3>Your debit card</h3><button class="button ghost" data-page="cards">↗</button></div>${cardVisual(state.cards[0])}<div class="card-caption"><span>Everyday essentials, covered.</span><span class="tag ${state.cards[0].status==='ACTIVE'?'green':'amber'}">${state.cards[0].status==='ACTIVE'?'Active':'Frozen'}</span></div></div>`:''}<div class="pipeline-panel"><h3>A transfer, behind the scenes.</h3><p>Explore a working event-driven bank, right on your machine.</p><div class="pipeline"><div class="pipeline-step"><span>1</span><div><b>Payment requested</b><small>Authenticated through the gateway</small></div></div><div class="pipeline-step"><span>2</span><div><b>Ledger balanced</b><small>Debit and credit saved together</small></div></div><div class="pipeline-step"><span>3</span><div><b>Event delivered</b><small>Kafka → notification → live update</small></div></div></div></div></aside></div>`;
}
function accounts(){return heading('Your accounts.','A place for your everyday spending and your bigger plans.','<button class="button primary" data-action="open-account">＋ Open an account</button>')+`<div class="account-grid">${state.accounts.map(accountCard).join('')}</div><div class="tip">New accounts start at $0. Move money from an existing account to fund them. All demo accounts use USD.</div>`;}
function transfers(){
  const destinations=[...state.accounts.map(a=>({id:a.id,label:a.label+' · Yours'})),...state.recipients.map(r=>({id:r.account_id,label:r.nickname}))];
  return heading('Make your next move.','Send money to your own accounts or a saved recipient.','<button class="button" data-action="add-recipient">＋ Add recipient</button>')+`<div class="split-page"><div class="panel transfer-form"><h3>New transfer</h3><form id="transfer-form"><label>From account<select name="fromAccount" id="from-account" required>${state.accounts.map(a=>`<option value="${escapeHtml(a.id)}">${escapeHtml(a.label)} · ${money(a.balance)}</option>`).join('')}</select></label><label>To account or recipient<select name="toAccount" id="to-account" required><option value="">Choose a destination</option>${destinations.map(a=>`<option value="${escapeHtml(a.id)}">${escapeHtml(a.label)} · ${escapeHtml(a.id)}</option>`).join('')}</select></label><div class="form-row"><label>Amount (USD)<input name="amount" type="number" min="0.01" max="10000" step="0.01" placeholder="0.00" required></label><label>Currency<input value="USD — US Dollar" disabled></label></div><label>What’s it for?<input name="description" maxlength="140" placeholder="e.g. Weekend plans" required></label><div class="form-summary"><span>Transfer fee</span><strong>$0.00 · Local demo</strong></div><div class="form-error" id="transfer-error" role="alert"></div><button class="button primary wide" type="submit">Review transfer <span>→</span></button></form></div><aside><div class="panel"><h3>Good to know</h3><ul class="info-list"><li><b>Ready in moments</b><small>Money moves between demo accounts as soon as the ledger confirms it.</small></li><li><b>Up to $10,000 per transfer</b><small>You can only send your available balance.</small></li><li><b>One request, one transfer</b><small>Retrying the same request won’t send your money twice.</small></li></ul></div><div class="tip">Try sending money to Alex Morgan, then sign in as Alex to see it arrive.</div></aside></div><div class="section-heading"><h3>Your transfer requests</h3><button class="button ghost" data-action="refresh">Refresh ↻</button></div><div class="table-panel">${paymentTable()}</div>`;
}
function paymentTable(){return !state.payments.length?'<div class="empty"><strong>All set for your first transfer</strong>Completed, pending and declined requests will appear here.</div>':`<div class="table-scroll"><table><thead><tr><th>Reference / description</th><th>Destination</th><th>Status</th><th class="align-right">Amount</th></tr></thead><tbody>${state.payments.map(p=>`<tr><td><div class="transaction-name"><div><b>${escapeHtml(p.description)}</b><small>${escapeHtml(p.id.slice(0,8))} · ${date(p.created_at)}</small>${p.error?`<small>${escapeHtml(p.error)}</small>`:''}</div></div></td><td class="account-number">${escapeHtml(p.to_account)}</td><td><span class="tag ${p.status==='COMPLETED'?'green':p.status==='FAILED'?'red':'amber'}">${escapeHtml(p.status)}</span></td><td class="align-right amount">${money(p.amount)}</td></tr>`).join('')}</tbody></table></div>`;}
function recipients(){return heading('Your people.','Save someone’s account once. Make the next transfer simpler.','<button class="button primary" data-action="add-recipient">＋ Add recipient</button>')+`<div class="panel">${state.recipients.map(r=>`<div class="list-row"><span class="avatar">${escapeHtml(initials(r.nickname))}</span><div class="list-body"><h3>${escapeHtml(r.nickname)}</h3><p class="account-number">${escapeHtml(r.account_id)} · USD</p></div><button class="button" data-send="${escapeHtml(r.account_id)}">Send ↗</button><button class="button ghost" data-remove-recipient="${escapeHtml(r.id)}" aria-label="Remove ${escapeHtml(r.nickname)}">Remove</button></div>`).join('')||'<div class="empty"><strong>Keep your people close</strong>Add a recipient using their demo account number.</div>'}</div><div class="tip">Demo directory: Maya — <b>NS10010001</b> · Alex — <b>NS20010001</b>. You can save the other customer as a recipient.</div>`;}
function cards(){return heading('Everyday, in your hands.','Manage the demo debit card linked to your everyday account.')+`<div class="cards-grid">${state.cards.map(c=>`<div class="panel">${cardVisual(c)}<div class="card-details"><div><span>Linked account</span><b>${escapeHtml(c.label)}</b></div><div><span>Card status</span><span class="tag ${c.status==='ACTIVE'?'green':'amber'}">${escapeHtml(c.status)}</span></div><div><span>Card type</span><b>Virtual demo debit</b></div></div><button class="button wide" data-card="${escapeHtml(c.id)}" data-status="${c.status==='ACTIVE'?'FROZEN':'ACTIVE'}">${c.status==='ACTIVE'?'❄ Freeze card':'↻ Unfreeze card'} <span>→</span></button></div>`).join('')||'<div class="empty">This profile has no debit cards.</div>'}</div><div class="tip">Card controls are a saved demo feature. These cards cannot make purchases, and freezing a card does not block account-to-account transfers.</div>`;}
function activity(){return heading('The full picture.','Follow your transactions and the latest account updates.','<button class="button" data-action="export">↓ Export CSV</button>')+`<div class="table-panel"><div class="table-tools"><h3>Transaction history</h3><input class="search-input" id="transaction-search" placeholder="Search description or account" aria-label="Search transactions"></div><div id="transactions-results">${transactionsTable(state.transactions)}</div><div class="table-footer">Showing up to 200 recent ledger entries. An internal transfer appears as a debit and a credit.</div></div><div class="section-heading"><h3>Notifications</h3><button class="button ghost" data-action="read-notifications">Mark all as read ✓</button></div><div class="panel">${state.notifications.map(n=>`<div class="list-row ${n.read_at?'':'unread'}"><span class="avatar">${n.title==='Money received'?'↙':'✓'}</span><div class="list-body"><h3>${escapeHtml(n.title)}</h3><p>${escapeHtml(n.message)}</p><small>${date(n.created_at)} at ${time(n.created_at)}</small></div>${n.read_at?'':'<span class="notification-mark"></span>'}</div>`).join('')||'<div class="empty">Your Kafka-delivered notifications will appear here.</div>'}</div>`;}
async function operations(){
  $('#main').innerHTML=heading('Bank operations.','A live view of service health, ledger integrity and the audit trail.')+'<div class="loading">Checking the services…</div>';
  try{const data=await api('/operations');if(state.page!=='operations')return;const l=data.ledger;$('#main').innerHTML=heading('Bank operations.','A live view of service health, ledger integrity and the audit trail.','<button class="button" data-action="refresh">↻ Refresh</button>')+`<div class="services-grid">${data.services.map(s=>`<div class="service-card"><div><b>${escapeHtml(s.name)}</b><small>Local port ${s.port}</small></div><span class="tag ${s.status==='UP'?'green':'red'}">${s.status}</span></div>`).join('')}</div><div class="metric-grid"><div class="panel"><span>Customer accounts</span><strong>${l.accounts}</strong></div><div class="panel"><span>Posted transfers</span><strong>${l.transfers}</strong></div><div class="panel"><span>Pending Kafka events</span><strong>${l.pendingEvents}</strong></div><div class="panel"><span>Ledger net · expected $0</span><strong>${money(l.ledgerNet)}</strong></div></div><div class="table-panel"><div class="table-tools"><h3>Audit trail</h3><span class="tag gray">Latest 50 events</span></div><div class="table-scroll"><table><thead><tr><th>Action</th><th>Actor</th><th>Detail</th><th>Time</th></tr></thead><tbody>${l.audit.map(a=>`<tr><td>${escapeHtml(a.action.replaceAll('_',' '))}</td><td>${escapeHtml(a.actor)}</td><td>${escapeHtml(a.detail)}</td><td class="muted">${date(a.created_at)} ${time(a.created_at)}</td></tr>`).join('')}</tbody></table></div>${!l.audit.length?'<div class="empty">Customer actions will appear here.</div>':''}</div><div class="tip">An empty outbox means all committed transfer events have been acknowledged by Kafka. The notification service deduplicates redelivered events before saving them.</div>`;}catch(e){toast(e.message,true);$('#main').innerHTML='<div class="empty">Operations unavailable. Check the service logs and refresh.</div>';}
}
function render(){
  if(!state.user)return;
  document.querySelectorAll('.nav-item').forEach(b=>b.classList.toggle('active',b.dataset.page===state.page));$('#breadcrumb').textContent=state.page[0].toUpperCase()+state.page.slice(1);
  if(state.page==='operations'){operations();return;}
  $('#main').innerHTML=({overview,accounts,transfers,recipients,cards,activity}[state.page]||overview)();
  if(state.page==='transfers')updateDestinations();
}
function updateDestinations(){const from=$('#from-account'),to=$('#to-account');if(!from||!to)return;for(const opt of to.options)opt.disabled=opt.value===from.value;if(to.value===from.value)to.value='';}
function navigate(page){state.page=page;render();window.scrollTo({top:0});}
function modal(title,html){$('#modal-title').textContent=title;$('#modal-body').innerHTML=html;$('#modal').showModal();}
function newAccount(){modal('Open an account',`<p class="muted tiny">Give your next goal a place to grow. New accounts start at $0.</p><form id="account-form"><label>Account name<input name="label" maxlength="60" placeholder="e.g. My next adventure" required></label><label>Account type<select name="type"><option value="SAVINGS">Savings</option><option value="CHECKING">Checking</option></select></label><div class="form-error" id="modal-error" role="alert"></div><div class="modal-actions"><button class="button" type="button" data-action="close">Cancel</button><button class="button primary" type="submit">Open account</button></div></form>`);}
function newRecipient(){modal('Add a recipient',`<p class="tiny muted">Use another customer's demo account number.</p><form id="recipient-form"><label>Recipient name<input name="nickname" maxlength="60" placeholder="e.g. Alex Morgan" required></label><label>Account number<input name="accountId" maxlength="24" placeholder="NS20010001" required><small>Maya: NS10010001 · Alex: NS20010001</small></label><div class="form-error" id="modal-error" role="alert"></div><div class="modal-actions"><button class="button" type="button" data-action="close">Cancel</button><button class="button primary" type="submit">Save recipient</button></div></form>`);}
function exportCsv(){
  const safe=v=>{let s=String(v??'');if(/^[=+@\-]/.test(s))s="'"+s;return '"'+s.replaceAll('"','""')+'"';};
  const rows=[['Date','Description','Account','Kind','Amount USD','Transfer ID'],...state.transactions.map(t=>[t.created_at,t.description,t.account_id,t.kind,t.amount,t.transfer_id])];
  const blob=new Blob([rows.map(r=>r.map(safe).join(',')).join('\r\n')],{type:'text/csv;charset=utf-8'});const url=URL.createObjectURL(blob);const link=document.createElement('a');link.href=url;link.download='northstar-transactions.csv';link.click();setTimeout(()=>URL.revokeObjectURL(url),1000);toast('Your transaction export is ready.');
}
$('#login-form').addEventListener('submit',async e=>{e.preventDefault();const b=e.submitter;b.disabled=true;$('#login-error').textContent='';try{const user=await api('/login',{method:'POST',body:JSON.stringify({email:$('#email').value,password:$('#password').value})});await enter(user);}catch(err){$('#login-error').textContent=err.message;}finally{b.disabled=false;}});
$('#theme-toggle').addEventListener('click',()=>setTheme(document.documentElement.dataset.theme==='dark'?'light':'dark'));
$('#theme-toggle-login').addEventListener('click',()=>setTheme(document.documentElement.dataset.theme==='dark'?'light':'dark'));
setTheme(document.documentElement.dataset.theme==='dark'?'dark':'light');
$('#show-register').addEventListener('click',()=>{$('#register-form').classList.toggle('hidden');$('#show-register').textContent=$('#register-form').classList.contains('hidden')?'Create a customer account':'Hide registration';});
$('#register-form').addEventListener('submit',async e=>{e.preventDefault();const b=e.submitter;b.disabled=true;$('#register-error').textContent='';const credentials={name:$('#register-name').value,email:$('#register-email').value,password:$('#register-password').value};try{await api('/users',{method:'POST',body:JSON.stringify(credentials)});const user=await api('/login',{method:'POST',body:JSON.stringify({email:credentials.email,password:credentials.password})});await enter(user);}catch(err){$('#register-error').textContent=err.message;}finally{b.disabled=false;}});
document.querySelectorAll('[data-profile]').forEach(b=>b.addEventListener('click',()=>{$('#email').value=b.dataset.profile+'@northstar.demo';$('#password').value='BankDemo!2026';document.querySelectorAll('[data-profile]').forEach(x=>x.classList.toggle('selected',x===b));}));
$('#logout').addEventListener('click',async()=>{try{await api('/logout',{method:'POST'});showLogin();}catch(e){toast(e.message,true);}});
$('#notifications-button').addEventListener('click',()=>navigate('activity'));
$('#close-modal').addEventListener('click',()=>$('#modal').close());
document.addEventListener('change',e=>{if(e.target.id==='from-account')updateDestinations();});
document.addEventListener('input',e=>{if(e.target.id==='transaction-search'){const q=e.target.value.toLowerCase();$('#transactions-results').innerHTML=transactionsTable(state.transactions.filter(t=>(t.description+' '+t.label+' '+t.account_id).toLowerCase().includes(q)));}});
document.addEventListener('click',async e=>{
  const b=e.target.closest('button');if(!b)return;
  try{
    if(b.dataset.page){navigate(b.dataset.page);return;}
    if(b.dataset.send){navigate('transfers');$('#to-account').value=b.dataset.send;return;}
    if(b.dataset.card){b.disabled=true;await api('/cards/'+b.dataset.card,{method:'PATCH',body:JSON.stringify({status:b.dataset.status})});await loadData();render();toast(b.dataset.status==='FROZEN'?'Your demo card is frozen.':'Your demo card is active.');return;}
    if(b.dataset.removeRecipient){modal('Remove recipient?',`<p class="muted">You can add this recipient again any time.</p><div class="modal-actions"><button class="button" data-action="close">Keep recipient</button><button class="button primary" data-confirm-remove="${b.dataset.removeRecipient}">Remove</button></div>`);return;}
    if(b.dataset.confirmRemove){b.disabled=true;await api('/beneficiaries/'+b.dataset.confirmRemove,{method:'DELETE'});$('#modal').close();await loadData();render();toast('Recipient removed.');return;}
    switch(b.dataset.action){
      case 'open-account':newAccount();break;case 'add-recipient':newRecipient();break;case 'close':$('#modal').close();break;case 'export':exportCsv();break;
      case 'reload':await enter(state.user);break;
      case 'refresh':b.disabled=true;if(state.page==='operations')render();else{await loadData();render();}break;
      case 'read-notifications':await api('/notifications/read',{method:'POST'});await loadData();render();toast('All caught up.');break;
      case 'confirm-transfer':await confirmTransfer(b);break;
    }
  }catch(err){toast(err.message,true);b.disabled=false;}
});
document.addEventListener('submit',async e=>{
  if(!['account-form','recipient-form','transfer-form'].includes(e.target.id))return;e.preventDefault();const data=Object.fromEntries(new FormData(e.target));
  if(e.target.id==='transfer-form'){
    const from=state.accounts.find(a=>a.id===data.fromAccount);if(data.fromAccount===data.toAccount){$('#transfer-error').textContent='Choose two different accounts.';return;}
    if(Number(data.amount)>Number(from?.balance||0)){$('#transfer-error').textContent='This amount exceeds your available balance.';return;}
    const to=state.accounts.find(a=>a.id===data.toAccount)?.label||state.recipients.find(a=>a.account_id===data.toAccount)?.nickname||data.toAccount;
    transferDraft={body:{...data,amount:data.amount},key:crypto.randomUUID()};
    modal('Review your transfer',`<div class="review-amount">${money(data.amount)}</div><div class="review-row"><span>From</span><b>${escapeHtml(from.label)}</b></div><div class="review-row"><span>To</span><b>${escapeHtml(to)}<br><small>${escapeHtml(data.toAccount)}</small></b></div><div class="review-row"><span>Description</span><b>${escapeHtml(data.description)}</b></div><div class="review-row"><span>Fee</span><b>$0.00</b></div><div class="form-error" id="modal-error" role="alert"></div><p class="tiny muted section-space">This moves demo money immediately. Confirm the destination before continuing.</p><div class="modal-actions"><button class="button" data-action="close">Go back</button><button class="button primary" data-action="confirm-transfer">Confirm transfer ↗</button></div>`);return;
  }
  const b=e.submitter;b.disabled=true;try{if(e.target.id==='recipient-form')data.accountId=data.accountId.trim().toUpperCase();await api(e.target.id==='account-form'?'/accounts':'/beneficiaries',{method:'POST',body:JSON.stringify(data)});$('#modal').close();await loadData();render();toast(e.target.id==='account-form'?'Your new account is ready.':'Recipient saved.');}catch(err){$('#modal-error').textContent=err.message;b.disabled=false;}
});
async function confirmTransfer(button){
  button.disabled=true;button.textContent='Sending…';$('#modal-error').textContent='';
  try{
    const payment=await api('/payments',{method:'POST',headers:{'Idempotency-Key':transferDraft.key},body:JSON.stringify(transferDraft.body)});
    if(payment.status==='FAILED'){$('#modal-error').textContent=payment.error||'Transfer declined.';button.textContent='Declined';await loadData();return;}
    $('#modal').close();await loadData();render();toast(payment.status==='COMPLETED'?'Transfer complete. Your balances are up to date.':'Transfer received and pending. Check transfer history for the final status.');
  }catch(err){$('#modal-error').textContent=err.message+' Use Retry to keep the same request ID.';button.disabled=false;button.textContent='Retry transfer';}
}
(async()=>{try{await enter(await api('/me'));}catch{showLogin();}})();
