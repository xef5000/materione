import type { TypeInfo, TypeParam } from './api';
import type { CurrencyIntegrationInfo } from './behavior';

/**
 * The currency form drawn from the integration registry (logic.md section 18).
 *
 * A currency file names an integration (`integration: vault`) and carries that integration's own keys beside its
 * own (`currency: coins`). Which integrations exist, and which keys each reads, is the server's registry, so the
 * form is built from it instead of from a list kept here: `integration` becomes a choice among the registered
 * integrations, and the keys drawn beneath it are the chosen integration's own, from its self-description.
 *
 * The result is an ordinary schema, so every form that renders a schema (the studio and the file editor) shows the
 * same thing without knowing about currencies. Nothing here names a particular integration, so one an addon
 * registers appears with no change to the editor.
 *
 * A key an integration declares is shown only through that integration: a currency type's own copy of the same key
 * (the schema still lists the `custom` integration's three, for the code-mode SDK that types from it) is dropped
 * from the form, and the key of an integration that is not the chosen one stays with the file's other unrendered
 * keys. A schema that is not the currency type, or a server that sends no integrations, is returned unchanged.
 */
export function withCurrencyIntegrations(
  schema: TypeInfo | undefined,
  integrations: readonly CurrencyIntegrationInfo[] | undefined,
  chosen: unknown,
): TypeInfo | undefined {
  if (!schema || schema.directory !== 'currencies' || !integrations || integrations.length === 0) return schema;

  const chosenId = typeof chosen === 'string' ? chosen : '';
  const entry = integrations.find((candidate) => candidate.id === chosenId);
  const owned = new Set(integrations.flatMap((candidate) => (candidate.parameters ?? []).map((param) => param.id)));

  const options = integrations.map((candidate) => ({
    value: candidate.id,
    label: candidate.displayName || candidate.id,
    description: candidate.description ?? '',
  }));
  if (chosenId && !entry) {
    // a value the file has but this server does not register: shown, never blanked, and its keys stay in the YAML
    options.push({
      value: chosenId,
      label: `${chosenId} (not registered on this server)`,
      description: 'No plugin on this server provides this integration, so its keys cannot be shown here.',
    });
  }

  const own: TypeParam[] = schema.parameters
    .filter((field) => !owned.has(field.id))
    // `options` carry the names and help the studio's select shows; `allowedValues` is what the file editor's reads
    .map((field) => (field.id === 'integration'
      ? { ...field, options, allowedValues: options.map((option) => option.value) }
      : field));
  const keys: TypeParam[] = entry
    ? (entry.parameters ?? []).map((param) => ({
        ...param,
        advanced: false,
        section: `${entry.displayName || entry.id} keys`,
      }))
    : [];
  return { ...schema, parameters: [...own, ...keys] };
}
