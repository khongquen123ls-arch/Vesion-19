const CACHE_NAME='ku-suu-pos-v6-pwa-1';
const APP_SHELL=['/','/index.html','/manifest.webmanifest','/icons/icon-192.png','/icons/icon-512.png','/ku-suu-logo.png'];
self.addEventListener('install',event=>{event.waitUntil(caches.open(CACHE_NAME).then(c=>c.addAll(APP_SHELL)).then(()=>self.skipWaiting()))});
self.addEventListener('activate',event=>{event.waitUntil(caches.keys().then(keys=>Promise.all(keys.filter(k=>k!==CACHE_NAME).map(k=>caches.delete(k)))).then(()=>self.clients.claim()))});
self.addEventListener('fetch',event=>{
  if(event.request.method!=='GET')return;
  const url=new URL(event.request.url);
  if(url.origin!==location.origin)return;
  event.respondWith(fetch(event.request).then(res=>{
    const copy=res.clone(); caches.open(CACHE_NAME).then(c=>c.put(event.request,copy)); return res;
  }).catch(()=>caches.match(event.request).then(r=>r||caches.match('/index.html'))));
});
