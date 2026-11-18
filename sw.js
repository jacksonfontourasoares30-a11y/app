const V='adcb-v2';
const SHELL=['./','index.html','supabase.js','manifest.json','icon-192.png','icon-512.png'];
self.addEventListener('install',e=>{e.waitUntil(caches.open(V).then(c=>c.addAll(SHELL)).then(()=>self.skipWaiting()))});
self.addEventListener('activate',e=>{e.waitUntil(caches.keys().then(k=>Promise.all(k.filter(x=>x!==V&&x!=='adcb-midia').map(x=>caches.delete(x)))).then(()=>self.clients.claim()))});
self.addEventListener('fetch',e=>{const u=new URL(e.request.url);if(e.request.method!=='GET')return;
  if(u.pathname.includes('/storage/v1/object/public/midia/')){
    if(e.request.headers.get('range'))return;
    e.respondWith(caches.open('adcb-midia').then(async c=>{const hit=await c.match(e.request);if(hit)return hit;const r=await fetch(e.request);if(r.ok&&r.status===200)c.put(e.request,r.clone());return r}));return}
  if(u.origin===location.origin){
    const fresco=e.request.mode==='navigate'||/\.(html|js|json)$/.test(u.pathname)||u.pathname.endsWith('/');
    const req=fresco?new Request(e.request.url,{cache:'no-cache',credentials:'same-origin',redirect:'follow'}):e.request;
    e.respondWith(fetch(req).then(r=>{if(r.ok){const cp=r.clone();caches.open(V).then(c=>c.put(e.request,cp))}return r})
      .catch(()=>fetch(e.request).catch(()=>caches.match(e.request).then(m=>m||caches.match('index.html')))))}
});
self.addEventListener('push',e=>{let d={};try{d=e.data?e.data.json():{}}catch(_){d={corpo:e.data?e.data.text():''}}
  e.waitUntil(self.registration.showNotification(d.titulo||'AD Carlos Barbosa',{body:d.corpo||'',icon:'icon-192.png',data:{url:d.url||'./'},lang:'pt-BR'}))});
self.addEventListener('notificationclick',e=>{e.notification.close();const u=new URL((e.notification.data&&e.notification.data.url)||'./',self.registration.scope).href;
  e.waitUntil(clients.matchAll({type:'window',includeUncontrolled:true}).then(l=>{for(const c of l){if(c.url.startsWith(self.registration.scope)&&'focus' in c){return c.focus().then(w=>w&&w.navigate?w.navigate(u):null).catch(()=>clients.openWindow(u))}}return clients.openWindow(u)}))});
