'use strict';
document.querySelectorAll('[data-demo]').forEach(button=>button.addEventListener('click',()=>{document.querySelector('[name=username]').value=button.dataset.demo+'@reconnect.local';document.querySelector('[name=password]').value='ReConnect!2026';document.querySelector('[name=password]').focus();}));
document.querySelectorAll('[data-print]').forEach(button=>button.addEventListener('click',()=>window.print()));
document.querySelector('.mobile-menu')?.addEventListener('click',()=>{const sidebar=document.querySelector('.sidebar');sidebar.classList.toggle('open');document.querySelector('.mobile-menu').setAttribute('aria-expanded',sidebar.classList.contains('open'));});
document.querySelectorAll('form').forEach(form=>form.addEventListener('submit',event=>{if(form.dataset.confirm&&!window.confirm(form.dataset.confirm)){event.preventDefault();return;}if(form.method.toLowerCase()==='post'){form.querySelectorAll('button[type=submit],button:not([type])').forEach(button=>{button.disabled=true;button.setAttribute('aria-busy','true');});}}));
window.addEventListener('pageshow',()=>document.querySelectorAll('button[aria-busy]').forEach(button=>{button.disabled=false;button.removeAttribute('aria-busy');}));

document.querySelector('.mobile-close')?.addEventListener('click',()=>{document.querySelector('.sidebar').classList.remove('open');document.querySelector('.mobile-menu')?.setAttribute('aria-expanded','false');document.querySelector('.mobile-menu')?.focus();});
document.addEventListener('keydown',event=>{if(event.key==='Escape'){document.querySelector('.sidebar')?.classList.remove('open');document.querySelector('.mobile-menu')?.setAttribute('aria-expanded','false');}});
for(const [selector,key,style] of [['[data-text-size]','reconnect-large','large-text'],['[data-contrast]','reconnect-contrast','high-contrast']]){
 const button=document.querySelector(selector);let enabled=false;try{enabled=localStorage.getItem(key)==='true';}catch{}
 const apply=()=>{document.documentElement.classList.toggle(style,enabled);button?.setAttribute('aria-pressed',String(enabled));};apply();
 button?.addEventListener('click',()=>{enabled=!enabled;try{localStorage.setItem(key,String(enabled));}catch{}apply();});
}
const kioskLogout=document.querySelector('#kiosk-logout');
if(kioskLogout){let timer;const reset=()=>{clearTimeout(timer);timer=setTimeout(()=>kioskLogout.requestSubmit(),5*60*1000);};['pointerdown','keydown','touchstart'].forEach(e=>document.addEventListener(e,reset,{passive:true}));reset();}
