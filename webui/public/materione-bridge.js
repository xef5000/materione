/*
 * Materione bridge (runs INSIDE the pinned Blockbench iframe).
 *
 * Served at /materione-bridge.js next to the vendored build and injected
 * into the iframe's DOM by the editor SPA after load (same origin). All
 * UI lives in the parent workbench; this script only exposes two
 * capabilities across the postMessage boundary (architecture.md §9.3):
 *
 *   collect        → gather the current project as a Java block/item model
 *                    JSON + referenced textures + the .bbmodel source
 *   importProject  → load an external .bbmodel into Blockbench
 *   thumbnail      → a render of the current model, when the build offers one
 *
 * Every message carries the __materione marker and an id echoed back, so
 * the parent can correlate replies. Only messages from window.parent are
 * accepted. Uses Blockbench's own global API (window.Blockbench, Codecs)
 * — the pinned build itself stays unmodified (D9).
 */
(function () {
  'use strict';

  var MARK = '__materione';
  var parentOrigin = '*';
  var booted = false;

  function post(type, payload) {
    var message = { __materione: true, type: type };
    if (payload) {
      Object.keys(payload).forEach(function (key) {
        message[key] = payload[key];
      });
    }
    window.parent.postMessage(message, parentOrigin);
  }

  function fail(id, error) {
    post('bridgeError', { id: id, error: String(error && error.message ? error.message : error) });
  }

  function sanitize(text) {
    return String(text || 'model')
      .toLowerCase()
      .replace(/[^a-z0-9_\/-]+/g, '_')
      .replace(/^_+|_+$/g, '') || 'model';
  }

  function collect(id) {
    var project = window.Project;
    if (!project) {
      // Blockbench's global Project is a falsy 0 when no project is open
      fail(id, 'no project is open in Blockbench');
      return;
    }
    var model = window.Codecs.java_block.compile({ raw: true, prevent_dialog: true });
    var textures = [];
    window.Texture.all.forEach(function (texture) {
      try {
        textures.push({
          id: texture.id,
          link: texture.javaTextureLink(),
          name: texture.name || texture.id + '.png',
          dataUrl: texture.getDataURL(),
        });
      } catch (e) {
        // a texture without pixels must not sink the whole save
      }
    });
    post('saveData', {
      id: id,
      projectName: sanitize(project.name || project.geometry_name || 'model'),
      model: JSON.stringify(model),
      textures: textures,
      project: JSON.stringify(window.Codecs.project.compile()),
    });
  }

  function importProject(id, source) {
    var parsed = JSON.parse(source);
    // load() = setupProject + parse: replaces whatever project is open
    window.Codecs.project.load(parsed, { path: 'materione.bbmodel' });
    post('imported', { id: id, ok: true });
  }

  /*
   * A render of the current model, used to show a model in the editor without
   * booting this application again. Screencam is not a contracted API, so a
   * build that does not expose it simply reports no thumbnail: the caller
   * falls back to the texture rather than failing the save.
   */
  function thumbnail(id) {
    var camera = window.Screencam;
    if (!camera || typeof camera.screenshotPreview !== 'function' || !window.Preview) {
      post('thumbnail', { id: id, ok: false });
      return;
    }
    var answered = false;
    try {
      camera.screenshotPreview(
        window.Preview.selected,
        { width: 128, height: 128, crop: true },
        function (dataUrl) {
          if (answered) return;
          answered = true;
          post('thumbnail', { id: id, ok: true, dataUrl: String(dataUrl || '') });
        },
      );
    } catch (e) {
      post('thumbnail', { id: id, ok: false });
      return;
    }
    // Some builds render without ever invoking the callback; never hang a save.
    setTimeout(function () {
      if (answered) return;
      answered = true;
      post('thumbnail', { id: id, ok: false });
    }, 4000);
  }

  window.addEventListener('message', function (event) {
    if (event.source !== window.parent) {
      return;
    }
    var data = event.data;
    if (!data || data.__materione !== true || typeof data.type !== 'string') {
      return;
    }
    if (!booted && data.type !== 'ping') {
      fail(data.id, 'bridge not ready');
      return;
    }
    try {
      if (data.type === 'ping') {
        post('pong', { id: data.id });
      } else if (data.type === 'configure') {
        if (typeof data.origin === 'string' && data.origin !== '') {
          parentOrigin = data.origin;
        }
      } else if (data.type === 'collect') {
        collect(data.id);
      } else if (data.type === 'importProject') {
        importProject(data.id, String(data.project || ''));
      } else if (data.type === 'thumbnail') {
        thumbnail(data.id);
      }
    } catch (e) {
      fail(data.id, e);
    }
  });

  function awaitBoot() {
    if (window.Blockbench && window.Codecs && window.Codecs.project
        && window.Blockbench.setup_successful === true) {
      booted = true;
      post('ready', {});
      return;
    }
    setTimeout(awaitBoot, 250);
  }

  // Blockbench boots asynchronously; also retry after a soft reload
  awaitBoot();
})();
