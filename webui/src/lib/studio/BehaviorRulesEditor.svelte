<script lang="ts">
  /**
   * A resource's `behavior:` rules, each read as a sentence and edited as
   * its `on` / `if` / `do` parts — the existing rule format, nothing else
   * (D4). Events, actions and options come from the server's
   * self-description; an event this content type never fires is called out
   * instead of being offered as if it worked. Keys the form does not know
   * stay in the rule exactly as written.
   */
  import Field from './Field.svelte';
  import type { TypeParam } from '../api';
  import {
    actionInfo,
    actionView,
    actionsOf,
    activationOf,
    blankRule,
    conditionsOf,
    eventInfo,
    eventsFor,
    formatSelector,
    parseSelector,
    presetsFor,
    removeAt,
    replaceAt,
    ruleSentence,
    setRuleKey,
    withConditions,
    writeAction,
    type BehaviorVocabulary,
    type Json,
  } from '../behavior';

  interface Props {
    field: TypeParam;
    value: unknown;
    pack: string;
    /** Content type name of the owner (`item`, `block`, `furniture`). */
    host: string;
    vocabulary: BehaviorVocabulary | null;
    onChange: (value: unknown) => void;
  }

  let { field, value, pack, host, vocabulary, onChange }: Props = $props();
  let expanded = $state<number | null>(null);
  let preset = $state('');

  const rules = $derived(Array.isArray(value) ? value : []);
  const fired = $derived(eventsFor(vocabulary, host));
  const presets = $derived(presetsFor(vocabulary, host));

  function rule(index: number): Json {
    const entry = rules[index];
    return entry !== null && typeof entry === 'object' && !Array.isArray(entry) ? entry as Json : {};
  }

  function update(index: number, next: Json) {
    onChange(replaceAt(value, index, next));
  }

  function setEvent(index: number, type: string, detail: string) {
    update(index, setRuleKey(rule(index), 'on', formatSelector({ type, detail })));
  }

  function setAction(index: number, actionIndex: number, next: unknown) {
    const current = rule(index);
    const list = [...actionsOf(current)];
    list[actionIndex] = next;
    update(index, setRuleKey(current, 'do', list));
  }

  function removeAction(index: number, actionIndex: number) {
    const current = rule(index);
    update(index, setRuleKey(current, 'do', actionsOf(current).filter((_, i) => i !== actionIndex)));
  }

  function changeActionType(index: number, actionIndex: number, type: string) {
    const view = actionView(actionsOf(rule(index))[actionIndex], vocabulary);
    setAction(index, actionIndex, writeAction({ ...view, type, params: {}, scalar: false, valid: true }, vocabulary));
  }

  function setActionParam(index: number, actionIndex: number, key: string, next: unknown) {
    const view = actionView(actionsOf(rule(index))[actionIndex], vocabulary);
    const params = { ...view.params };
    if (next === undefined || next === null || next === '') delete params[key];
    else params[key] = next;
    setAction(index, actionIndex, writeAction({ ...view, params }, vocabulary));
  }

  function setActionOption(index: number, actionIndex: number, key: string, next: unknown) {
    const view = actionView(actionsOf(rule(index))[actionIndex], vocabulary);
    const options = { ...view.options };
    if (next === undefined || next === null || next === '') delete options[key];
    else options[key] = next;
    setAction(index, actionIndex, writeAction({ ...view, options }, vocabulary));
  }

  function addAction(index: number) {
    const current = rule(index);
    const first = actionInfo(vocabulary, 'message') ?? vocabulary?.actions[0];
    if (!first) return;
    update(index, setRuleKey(current, 'do', [...actionsOf(current), { [first.id]: {} }]));
  }

  function addRule() {
    const chosen = presets.find((entry) => entry.id === preset);
    const index = rules.length;
    onChange([...rules, chosen ? chosen.rule : blankRule(vocabulary, host)]);
    expanded = index;
    preset = '';
  }

  function eventChoices(current: string) {
    const known = eventInfo(vocabulary, current);
    return known && !fired.includes(known) ? [...fired, known] : fired;
  }
</script>

<section class="rules" data-field={field.id} aria-label={field.label || field.id}>
  <h3>{field.label || 'Behaviour rules'}</h3>
  {#if !vocabulary}
    <p class="note">This server does not describe its behaviour vocabulary, so rules can only be edited in Files.</p>
  {:else}
    {#each rules as _entry, index (index)}
      {@const current = rule(index)}
      {@const selector = parseSelector(current.on)}
      {@const event = eventInfo(vocabulary, selector.type)}
      {@const activation = activationOf(current, vocabulary, host)}
      <article class="rule" class:never={!activation.fires} data-rule={index}>
        <button type="button" class="sentence" aria-expanded={expanded === index}
          onclick={() => (expanded = expanded === index ? null : index)}>
          {ruleSentence(current, vocabulary)}
        </button>
        <p class="activation" class:never={!activation.fires}>{activation.text}</p>
        {#if expanded === index}
          <div class="editor">
            <div class="row">
              <label>
                <span>When</span>
                <select value={selector.type} onchange={(e) => setEvent(index, e.currentTarget.value,
                  (eventInfo(vocabulary, e.currentTarget.value)?.details ?? []).includes(selector.detail) ? selector.detail : '')}>
                  {#if !event}<option value={selector.type}>{selector.type || 'choose…'} (unknown)</option>{/if}
                  {#each eventChoices(selector.type) as choice (choice.id)}
                    <option value={choice.id}>{choice.displayName}{choice.firedBy.includes(host) ? '' : ' (never fires here)'}</option>
                  {/each}
                </select>
              </label>
              {#if (event?.details.length ?? 0) > 0 || selector.detail}
                <label>
                  <span>Side</span>
                  <select value={selector.detail} onchange={(e) => setEvent(index, selector.type, e.currentTarget.value)}>
                    <option value="">any</option>
                    {#each event?.details ?? [] as detail (detail)}<option value={detail}>{detail}</option>{/each}
                    {#if selector.detail && !(event?.details ?? []).includes(selector.detail)}
                      <option value={selector.detail}>{selector.detail} (never fires)</option>
                    {/if}
                  </select>
                </label>
              {/if}
            </div>
            <label class="conditions">
              <span>Only if (one expression per line, all must be true)</span>
              <textarea rows="2" spellcheck="false" value={conditionsOf(current).join('\n')}
                placeholder="player.health > 10"
                onblur={(e) => update(index, withConditions(current, e.currentTarget.value.split('\n')))}></textarea>
            </label>
            <div class="actions">
              <span class="label">Then</span>
              {#each actionsOf(current) as action, actionIndex (actionIndex)}
                {@const view = actionView(action, vocabulary)}
                {@const info = actionInfo(vocabulary, view.type)}
                <div class="action" data-action={view.type}>
                  <div class="row">
                    <select aria-label="Action" value={view.type}
                      onchange={(e) => changeActionType(index, actionIndex, e.currentTarget.value)}>
                      {#if !info}<option value={view.type}>{view.type || 'unreadable'} (not registered)</option>{/if}
                      {#each vocabulary.actions as choice (choice.id)}
                        <option value={choice.id}>{choice.displayName ?? choice.id}</option>
                      {/each}
                    </select>
                    <button type="button" class="small" onclick={() => removeAction(index, actionIndex)}>Remove</button>
                  </div>
                  {#if info?.description}<small>{info.description}</small>{/if}
                  {#if !view.valid}
                    <p class="note">This action is not one type key with settings; edit it in Files.</p>
                  {:else if info?.parameters}
                    {#each info.parameters as param (param.id)}
                      <Field field={param} value={view.params[param.id]} {pack}
                        onChange={(next) => setActionParam(index, actionIndex, param.id, next)} />
                    {/each}
                  {:else}
                    <p class="note">This action does not describe its settings; they are kept as written.</p>
                  {/if}
                  {#if view.valid}
                    <details>
                      <summary>Timing</summary>
                      {#each vocabulary.actionOptions as option (option.id)}
                        <Field field={option} value={view.options[option.id]} {pack}
                          onChange={(next) => setActionOption(index, actionIndex, option.id, next)} />
                      {/each}
                    </details>
                  {/if}
                </div>
              {/each}
              <button type="button" class="small" onclick={() => addAction(index)}>Add action</button>
            </div>
            <details>
              <summary>Limits</summary>
              {#each vocabulary.ruleOptions as option (option.id)}
                <Field field={option} value={current[option.id]} {pack}
                  onChange={(next) => update(index, setRuleKey(current, option.id, next))} />
              {/each}
            </details>
            <button type="button" class="small danger" onclick={() => { onChange(removeAt(value, index)); expanded = null; }}>
              Remove rule
            </button>
          </div>
        {/if}
      </article>
    {/each}
    {#if rules.length === 0}
      <p class="note">No rules yet.</p>
    {/if}
    <div class="add">
      <select aria-label="Rule starting point" bind:value={preset}>
        <option value="">Blank rule</option>
        {#each presets as entry (entry.id)}<option value={entry.id}>{entry.label}</option>{/each}
      </select>
      <button type="button" class="small" disabled={fired.length === 0} onclick={addRule}>Add rule</button>
    </div>
    {#if fired.length === 0}
      <p class="note">Nothing on this server fires events for {host} content yet.</p>
    {/if}
  {/if}
</section>

<style>
  .rules {
    display: grid;
    gap: 8px;
    margin-bottom: 14px;
    min-width: 0;
  }
  h3 {
    margin: 0;
    font-size: 12px;
    font-weight: normal;
    color: var(--muted);
  }
  .rule {
    border: 1px solid var(--border);
    border-radius: var(--radius);
    padding: 8px 10px;
    min-width: 0;
  }
  .rule.never {
    border-color: var(--warn);
  }
  .sentence {
    display: block;
    width: 100%;
    text-align: left;
    background: none;
    border: none;
    color: inherit;
    font-size: 13px;
    padding: 0;
    cursor: pointer;
    overflow-wrap: anywhere;
  }
  .activation,
  .note {
    margin: 4px 0;
    font-size: 12px;
    color: var(--muted);
  }
  .activation.never {
    color: var(--warn);
  }
  .editor {
    display: grid;
    gap: 8px;
    margin-top: 8px;
    min-width: 0;
  }
  .row {
    display: flex;
    gap: 8px;
    align-items: end;
    min-width: 0;
  }
  label {
    display: grid;
    gap: 4px;
    font-size: 12px;
    color: var(--muted);
    min-width: 0;
    flex: 1;
  }
  .label {
    font-size: 12px;
    color: var(--muted);
  }
  .actions {
    display: grid;
    gap: 6px;
  }
  .action {
    border-left: 2px solid var(--border-strong);
    padding-left: 8px;
    min-width: 0;
  }
  select,
  textarea {
    background: var(--bg-input);
    color: inherit;
    border: 1px solid var(--border-strong);
    border-radius: var(--radius);
    padding: 5px 8px;
    font-size: 13px;
    font-family: inherit;
    min-width: 0;
    width: 100%;
    box-sizing: border-box;
  }
  .action .row select {
    flex: 1;
  }
  textarea {
    font-family: var(--mono);
    resize: vertical;
  }
  small {
    display: block;
    color: var(--muted);
    font-size: 11px;
    margin: 4px 0;
  }
  .small {
    justify-self: start;
    border: 1px solid var(--border-strong);
    background: none;
    color: inherit;
    border-radius: var(--radius);
    padding: 2px 8px;
    font-size: 12px;
    cursor: pointer;
    white-space: nowrap;
  }
  .danger {
    color: var(--error);
  }
  details summary {
    font-size: 12px;
    color: var(--muted);
    cursor: pointer;
    margin-bottom: 6px;
  }
  .add {
    display: flex;
    gap: 6px;
  }
  .add select {
    flex: 1;
  }
</style>
