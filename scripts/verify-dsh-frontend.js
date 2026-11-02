// CDP verification expression for scripts/inspect-webview.mjs --script.
// Calls the local, idempotent workspace initialization RPC; sends no model inference request.
(async () => {
  const response = await fetch('/api/workspace/initializeDefault', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ type: 'client-request', rpcId: crypto.randomUUID(),
      method: 'workspace/initializeDefault', payload: { args: {} } })
  });
  const result = await response.json();
  if (!response.ok || result.result?.ok !== true) throw new Error('Workspace initialization failed: ' + JSON.stringify(result));
  const workspace = result.result.value?.workspace;
  const height = document.documentElement.getBoundingClientRect().height;
  const controls = document.querySelectorAll('button,input,textarea,[role=button]').length;
  const subgrid = CSS.supports('grid-template-columns', 'subgrid');
  const lightDark = CSS.supports('color', 'light-dark(white,black)');
  if (height < 300 || controls < 8 || !subgrid || !lightDark) throw new Error('Frontend layout or controls are unavailable');
  if (!workspace?.path?.startsWith('/workspaces/')) throw new Error('Expected the default persistent project mount');
  return { httpStatus: response.status, workspaceReady: true, workspacePath: workspace.path,
    htmlHeight: height, controls, subgrid, lightDark };
})()
