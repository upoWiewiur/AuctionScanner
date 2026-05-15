const C='market-v2';
const A=['./','./index.html','./manifest.json'];
self.addEventListener('install',e=>e.waitUntil(caches.open(C).then(c=>c.addAll(A))));
self.addEventListener('fetch',e=>{
  if(e.request.url.includes('/api/'))return;
  e.respondWith(caches.match(e.request).then(r=>r||fetch(e.request)));
});
