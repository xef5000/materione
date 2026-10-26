import { mount } from 'svelte';
import { confirmDisconnect, connection, handlePush, loadWorkspace, markConnectionLost, refreshDelivery, resetWorkspace, session } from '../src/lib/state.svelte';
import Workbench from '../src/lib/components/Workbench.svelte';
import '../src/app.css';
import { DeterministicRpcAdapter } from './rpc-adapter';

async function startFixture(): Promise<void> {
  const target = document.querySelector<HTMLElement>('#app');
  if (!target) throw new Error('Browser fixture root is missing.');

  resetWorkspace();
  const params = new URLSearchParams(window.location.search);
  const adapter = new DeterministicRpcAdapter({
    summaryError: params.has('summaryError'),
    modelGallery: params.has('modelGallery'),
    guided: params.has('guided'),
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
