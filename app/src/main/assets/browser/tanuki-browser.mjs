#!/usr/bin/env node
// 原生浏览器适配器：读取私有能力配置，绝不输出其中的值。
import { readFile } from 'node:fs/promises';
import { request } from 'node:http';
import { fileURLToPath } from 'node:url';

const methods = new Set(['status', 'request_control', 'navigate', 'snapshot', 'click', 'type', 'scroll', 'screenshot', 'back', 'forward', 'reload', 'tabs', 'new_tab', 'select_tab', 'close_tab', 'find', 'zoom']);
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
export async function callBrowser(method, params = {}, clientId = undefined, connectionFile = new URL('./connection.json', import.meta.url)) {
  if (!methods.has(method) || (clientId !== undefined && !uuid.test(clientId)) || !params || Array.isArray(params) || typeof params !== 'object') throw new Error('invalid_request');
  const connection = JSON.parse(await readFile(connectionFile, 'utf8'));
  if (!Number.isInteger(connection.port) || connection.port < 1 || connection.port > 65535 || !/^[A-Za-z0-9_-]{43}$/.test(connection.token)) throw new Error('browser_unavailable');
  clientId ??= connection.clientId;
  if (!uuid.test(clientId ?? '')) throw new Error('browser_unavailable');
  const body = Buffer.from(JSON.stringify({ clientId, method, params }));
  if (body.length > 65536) throw new Error('request_too_large');
  const result = await new Promise((resolve, reject) => {
    const req = request({ host: '127.0.0.1', port: connection.port, path: '/rpc', method: 'POST',
      headers: { Authorization: `Bearer ${connection.token}`, 'Content-Type': 'application/json', 'Content-Length': body.length },
      agent: false }, response => {
      const chunks = []; let size = 0;
      response.on('data', chunk => { size += chunk.length; if (size > 3 * 1024 * 1024) req.destroy(new Error('response_too_large')); else chunks.push(chunk); });
      response.on('error', reject);
      response.on('end', () => { try { resolve(JSON.parse(Buffer.concat(chunks).toString('utf8'))); } catch { reject(new Error('invalid_response')); } });
    });
    const timer = setTimeout(() => req.destroy(new Error('browser_timeout')), 12000);
    req.on('close', () => clearTimeout(timer)); req.on('error', reject); req.end(body);
  });
  return { clientId, ...result };
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const [method, json = '{}', client] = process.argv.slice(2);
  try {
    if (!methods.has(method)) throw new Error('usage');
    const result = await callBrowser(method, JSON.parse(json), client);
    process.stdout.write(JSON.stringify(result) + '\n');
    if (!result.ok) process.exitCode = 1;
  } catch {
    // 不返回异常原文，避免路径或 HTTP 错误泄露凭据。
    process.stderr.write('Browser unavailable or invalid request. Check Tanuki browser status and ask the user to explicitly authorize this connection.\n');
    process.exitCode = 1;
  }
}
