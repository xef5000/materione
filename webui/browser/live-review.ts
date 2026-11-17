import { mount } from 'svelte';
import App from '../src/App.svelte';
import '../src/app.css';
import { createCodemodeHarness } from './codemode-feed';
import './contract';

const target = document.getElementById('app');
if (!target) throw new Error('Live review harness root is missing.');

window.__materioneCodemode = createCodemodeHarness('sample', { currentRevisions: true });
mount(App, { target });
