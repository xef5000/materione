import { mount } from 'svelte';
import { confirmDisconnect, connection, handlePush, loadWorkspace, markConnectionLost, refreshDelivery, resetWorkspace, session } from '../src/lib/state.svelte';
import Workbench from '../src/lib/components/Workbench.svelte';
import '../src/app.css';
import { DeterministicRpcAdapter } from './rpc-adapter';
import { createCodemodeHarness } from './codemode-feed';

async function startFixture(): Promise<void> {
  const target = document.querySelector<HTMLElement>('#app');
  if (!target) throw new Error('Browser fixture root is missing.');

  resetWorkspace();
  const params = new URLSearchParams(window.location.search);
  const adapter = new DeterministicRpcAdapter({
    summaryError: params.has('summaryError'),
    modelGallery: params.has('modelGallery'),
    guided: params.has('guided'),
    catalog: params.has('catalog'),
    modules: params.has('modules'),
    gui: params.has('gui'),
    logic: params.has('logic'),
    codemode: params.has('codemode'),
    economy: params.has('economy'),
    noContainers: params.has('noContainers'),
    seed: params.get('seed') ?? '',
    rows: Number(params.get('rows')) || 0,
  });
  adapter.setPushHandler(handlePush);
  adapter.setDisconnectHandler(() => markConnectionLost('Deterministic browser fixture disconnected.'));
  session.channel = adapter;
  connection.connected = true;

  await loadWorkspace();
  await refreshDelivery();
  mount(Workbench, {
    target,
    props: {
      onDisconnect: () => {
        if (!confirmDisconnect()) return;
        adapter.simulateDisconnect();
      },
    },
  });
  window.__materioneTestControls = adapter;
  // `?codemode`: the seam that feeds a recorded corpus program in as a proposal (the product has no producer until the chat exists)
  if (params.has('codemode')) window.__materioneCodemode = createCodemodeHarness('fixture_pack');
  document.documentElement.dataset.fixtureReady = 'true';
}

void startFixture().catch((error: unknown) => {
  const target = document.querySelector<HTMLElement>('#app');
  const detail = error instanceof Error ? error.message : 'Fixture initialization failed.';
  if (target) {
    const failure = document.createElement('pre');
    failure.textContent = detail;
    target.replaceChildren(failure);
  }
  document.documentElement.dataset.fixtureError = 'true';
});
