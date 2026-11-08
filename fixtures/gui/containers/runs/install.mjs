// Generates the #289 pack, installs it into the probe client and the titles into the probe plugin's data folder.
import { install } from './lib.mjs';
await install();
console.log('installed');
