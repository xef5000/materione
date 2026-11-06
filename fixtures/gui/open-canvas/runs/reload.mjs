import { execFileSync } from 'node:child_process';
import { REPO, SCRATCH, exec, sleep } from './lib.mjs';
import fs from 'node:fs';
execFileSync('node', [`${REPO}/fixtures/gui/open-canvas/gen.mjs`, SCRATCH], { stdio: 'inherit' });
fs.rmSync(`${REPO}/harness/build/probe/client/resourcepacks/r275`, { recursive: true, force: true });
fs.cpSync(`${SCRATCH}/pack`, `${REPO}/harness/build/probe/client/resourcepacks/r275`, { recursive: true });
exec(`mc.reloadResourcePacks(); return 'r'`);
await sleep(9000);
