/**
 * Entry point of the cloud editor's demo (web.md §6, cloud landing), loaded
 * lazily from App.svelte. The demo is the real editor; only its channel is
 * different — a DemoServer over the recorded snapshot instead of a sealed
 * session to a Minecraft server.
 */
import { handlePush } from '../state.svelte';
import { DemoServer } from './server';
import { loadSnapshot } from './snapshot';

export async function createDemoChannel(): Promise<DemoServer> {
  const server = new DemoServer(await loadSnapshot());
  server.setPushHandler(handlePush);
  return server;
}
