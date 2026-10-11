import React from 'react';
import ReactDOM from 'react-dom/client';
import App from './App';
import './styles.css';
import { resourceCacheName } from './lib/cache';
if ('serviceWorker' in navigator && window.isSecureContext) void navigator.serviceWorker.register('/sw.js').then(() => navigator.serviceWorker.ready).then((registration) => registration.active?.postMessage({ type: 'scope', cache: resourceCacheName() })).catch(() => {});
ReactDOM.createRoot(document.getElementById('root')!).render(<React.StrictMode><App /></React.StrictMode>);
