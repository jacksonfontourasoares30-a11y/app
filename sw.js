const V='adcb-v4';
const SHELL=['./','index.html','supabase.js','manifest.json','icon-192.png','icon-512.png','badge-96.png'];
self.addEventListener('install',e=>{e.waitUntil(caches.open(V).then(c=>c.addAll(SHELL)).then(()=>self.skipWaiting()))});
self.addEventListener('activate',e=>{e.waitUntil(caches.keys().then(k=>Promise.all(k.filter(x=>x!==V&&x!=='adcb-midia').map(x=>caches.delete(x)))).then(()=>self.clients.claim()))});
// Fotos e cartazes: baixa uma vez e guarda no celular (os nomes dos arquivos nunca mudam).
// Busca em modo CORS para a resposta poder ser guardada (antes a <img> pedia em no-cors e nada ficava salvo).
async function midia(req,href){const c=await caches.open('adcb-midia');const hit=await c.match(href);if(hit)return hit;
  try{const r=await fetch(href,{mode:'cors',credentials:'omit'});if(r.ok&&r.status===200)c.put(href,r.clone());return r}
  catch(_){return fetch(req)}}
self.addEventListener('fetch',e=>{const u=new URL(e.request.url);if(e.request.method!=='GET')return;
  if(u.pathname.includes('/storage/v1/object/public/midia/')){
    if(/\.(mp4|webm|mov|m4v)$/i.test(u.pathname)){e.respondWith(new Response('',{status:204}));return}
    e.respondWith(midia(e.request,u.origin+u.pathname));return}
  if(u.origin===location.origin){
    const fresco=e.request.mode==='navigate'||/\.(html|js|json)$/.test(u.pathname)||u.pathname.endsWith('/');
    const req=fresco?new Request(e.request.url,{cache:'no-cache',credentials:'same-origin',redirect:'follow'}):e.request;
    e.respondWith(fetch(req).then(r=>{if(r.ok){const cp=r.clone();caches.open(V).then(c=>c.put(e.request,cp))}return r})
      .catch(()=>fetch(e.request).catch(()=>caches.match(e.request).then(m=>m||caches.match('index.html')))))}
});
self.addEventListener('push',e=>{let d={};try{d=e.data?e.data.json():{}}catch(_){d={corpo:e.data?e.data.text():''}}
  const t=String(d.titulo||'AD Carlos Barbosa'),c=String(d.corpo||''),urg=/urgente/i.test(t);
  e.waitUntil(self.registration.showNotification(t,{body:c,icon:'icon-192.png',badge:'badge-96.png',
    data:{url:d.url||'./'},lang:'pt-BR',
    tag:(t+'|'+c).slice(0,80),renotify:false,requireInteraction:urg,
    vibrate:urg?[120,60,120,60,240]:[80,40,80]}))});
self.addEventListener('notificationclick',e=>{e.notification.close();const u=new URL((e.notification.data&&e.notification.data.url)||'./',self.registration.scope).href;
  e.waitUntil(clients.matchAll({type:'window',includeUncontrolled:true}).then(l=>{for(const c of l){if(c.url.startsWith(self.registration.scope)&&'focus' in c){return c.focus().then(w=>w&&w.navigate?w.navigate(u):null).catch(()=>clients.openWindow(u))}}return clients.openWindow(u)}))});
