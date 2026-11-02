#!/usr/bin/env node
// Local developer inspection through an explicitly selected debug WebView. No app credentials are read.
import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';
const args = process.argv.slice(2);
const option = name => { const i = args.indexOf(name); return i < 0 ? null : args[i + 1]; };
const device = option('--device');
if (!device) throw new Error('Pass --device <serial> explicitly');
const adb = join(process.env.ANDROID_SDK_ROOT || join(homedir(), 'Library/Android/sdk'), 'platform-tools/adb');
const call = (...argv) => execFileSync(adb, ['-s', device, ...argv], { encoding: 'utf8', timeout: 15000 }).trim();
const pid = call('shell', 'pidof', 'com.tanuki.modern');
if (!/^\d+$/.test(pid)) throw new Error('Expected one Tanuki debug process');
const port = call('forward', 'tcp:0', `localabstract:webview_devtools_remote_${pid}`);
const redact = text => String(text)
  .replace(/([?&](?:auth|token|key|secret|api_key)=)[^\s&"<>]+/gi, '$1<redacted>')
  .replace(/sk-[A-Za-z0-9_-]{8,}/g, '<redacted-key>')
  .replace(/("(?:apiKey|api_key|accessToken|secret)"\s*:\s*")[^"]*/gi, '$1<redacted>');
let socket;
try {
  const pages = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
  const page = pages.find(p => p.type === 'page' && /^http:\/\/127\.0\.0\.1:/.test(p.url)) || pages.find(p => p.type === 'page');
  if (!page) throw new Error('No debuggable WebView page; use a debug APK');
  const endpoint = new URL(page.webSocketDebuggerUrl);
  endpoint.hostname = '127.0.0.1'; endpoint.port = port;
  socket = new WebSocket(endpoint.href);
  await new Promise((resolve, reject) => { socket.addEventListener('open', resolve, { once: true }); socket.addEventListener('error', reject, { once: true }); });
  let nextId = 0;
  const pending = new Map(), events = [];
  socket.addEventListener('message', event => {
    const message = JSON.parse(event.data);
    if (message.id && pending.has(message.id)) {
      const { resolve, reject, timeout } = pending.get(message.id); clearTimeout(timeout); pending.delete(message.id);
      message.error ? reject(new Error(message.error.message)) : resolve(message.result);
    } else if (['Runtime.exceptionThrown', 'Log.entryAdded', 'Network.loadingFailed'].includes(message.method)) events.push(message);
    else if (message.method === 'Network.requestWillBeSent' && message.params.request.url.includes('/api/workspace/')) {
      const request = message.params.request;
      events.push({workspaceRequest: {url:request.url, method:request.method, postData:request.postData}});
    } else if (message.method === 'Network.responseReceived' && args.includes('--reload') && message.params.response.url.includes('/api/workspace/')) {
      const requestId=message.params.requestId;
      setTimeout(() => send('Network.getResponseBody',{requestId}).then(body => events.push({workspaceResponse:body})).catch(error => events.push({workspaceError:error.message})),100);
    }
  });
  const send = (method, params = {}) => new Promise((resolve, reject) => {
    const id = ++nextId, timeout = setTimeout(() => { pending.delete(id); reject(new Error(`CDP timeout: ${method}`)); }, 10000);
    pending.set(id, { resolve, reject, timeout }); socket.send(JSON.stringify({ id, method, params }));
  });
  await send('Runtime.enable'); await send('Log.enable'); await send('Network.enable'); await send('Page.enable');
  if (args.includes('--reload')) { await send('Page.reload', { ignoreCache: true }); await new Promise(resolve => setTimeout(resolve, 4000)); }
  const expression = option('--script') ? readFileSync(option('--script'), 'utf8') : `(() => ({
    title: document.title, path: location.origin + location.pathname, ready: document.readyState,
    dimensions: { innerWidth, innerHeight, width: document.documentElement.clientWidth, height: document.documentElement.clientHeight },
    bodyLength: document.body?.innerText.length, body: document.body?.innerText.slice(0, 3500),
    bodyBackground: getComputedStyle(document.body).backgroundColor,
    children: document.body?.children.length,
    controls: [...document.querySelectorAll('button,input,textarea,[role=button]')].map(e => ({
      tag: e.tagName, type: e.getAttribute('type'), text: e.innerText?.slice(0, 100), placeholder: e.getAttribute('placeholder'),
      label: e.getAttribute('aria-label')
    })).slice(0, 60),
    resources: performance.getEntriesByType('resource').map(e => ({ path: new URL(e.name, location.href).pathname, transfer: e.transferSize, duration: Math.round(e.duration) })).slice(-30),
    css: { subgrid: CSS.supports('grid-template-columns', 'subgrid'), lightDark: CSS.supports('color', 'light-dark(white,black)') }
  }))()`;
  if (option('--screenshot')) {
    const screenshot=await send('Page.captureScreenshot',{format:'png',captureBeyondViewport:false});
    writeFileSync(option('--screenshot'),Buffer.from(screenshot.data,'base64'));
  }
  const result = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
  console.log(redact(JSON.stringify({ inspection: result, events }, null, 2)));
  if(result.exceptionDetails)throw new Error(redact(result.exceptionDetails.exception?.description || result.exceptionDetails.text));
} finally {
  socket?.close(); call('forward', '--remove', `tcp:${port}`);
}
