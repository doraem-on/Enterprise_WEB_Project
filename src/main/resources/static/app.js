'use strict';
document.querySelectorAll('[data-demo]').forEach(button=>button.addEventListener('click',()=>{document.querySelector('[name=username]').value=button.dataset.demo+'@reconnect.local';document.querySelector('[name=password]').value='ReConnect!2026';document.querySelector('[name=password]').focus();}));
document.querySelectorAll('[data-print]').forEach(button=>button.addEventListener('click',()=>window.print()));
document.querySelector('.mobile-menu')?.addEventListener('click',()=>{const sidebar=document.querySelector('.sidebar');sidebar.classList.toggle('open');document.querySelector('.mobile-menu').setAttribute('aria-expanded',sidebar.classList.contains('open'));});
document.querySelectorAll('form').forEach(form=>form.addEventListener('submit',event=>{if(form.dataset.confirm&&!window.confirm(form.dataset.confirm)){event.preventDefault();return;}if(form.method.toLowerCase()==='post'){form.querySelectorAll('button[type=submit],button:not([type])').forEach(button=>{button.disabled=true;button.setAttribute('aria-busy','true');});}}));
window.addEventListener('pageshow',()=>document.querySelectorAll('button[aria-busy]').forEach(button=>{button.disabled=false;button.removeAttribute('aria-busy');}));

document.querySelector('.mobile-close')?.addEventListener('click',()=>{document.querySelector('.sidebar').classList.remove('open');document.querySelector('.mobile-menu')?.setAttribute('aria-expanded','false');document.querySelector('.mobile-menu')?.focus();});
document.addEventListener('keydown',event=>{if(event.key==='Escape'){document.querySelector('.sidebar')?.classList.remove('open');document.querySelector('.mobile-menu')?.setAttribute('aria-expanded','false');}});
