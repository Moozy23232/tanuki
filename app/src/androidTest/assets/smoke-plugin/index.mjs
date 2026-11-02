import { readFile, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { createRequire } from 'node:module';
export const name = 'tanuki-smoke';
export const inject = ['skills'];
// No network, model calls, shell, or project modifications. Only dedicated marker files.
export async function apply(ctx) {
  const root = process.env.DSH_HOME;
  const counter = join(root, 'tanuki-smoke-counter.json');
  const previous = await readFile(counter, 'utf8').then(JSON.parse).catch(() => 0);
  await writeFile(counter, JSON.stringify(previous + 1));
  // Web presets own their filesystem providers. Mount the same official provider
  // in this QA plugin's scope to verify parsing without making a model request.
  const fromInstallation = createRequire(process.argv[1]);
  const filesystem = await import(fromInstallation.resolve('@deepseek-ai/dsh-skill-filesystem'));
  await ctx.plugin(filesystem, { providerName: 'tanuki-smoke-filesystem', watch: false });
  let attempts = 0;
  const timer = setInterval(async () => {
    try {
      const skill = await ctx.skills.get('tanuki-smoke', { cwd: '/workspaces' });
      if (skill?.content.includes('TANUKI_SKILL_BODY')) {
        await writeFile(join(root, 'tanuki-skill-loaded.json'), JSON.stringify({ name: skill.name, provider: skill.provider, source: skill.source, contentLoaded: true, startup: previous + 1 }));
        clearInterval(timer);
      }
    } finally { if (++attempts >= 8) clearInterval(timer); }
  }, 500);
  ctx.effect(() => () => clearInterval(timer));
}
