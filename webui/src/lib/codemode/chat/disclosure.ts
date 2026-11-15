/**
 * What leaves the page (issue 348; codemode.md section 12.1, "What is sent"; web.md section 10). The panel shows this before
 * the first message of a session and keeps it within reach. It is written from one fact, `ProviderInfo.remote`, so a
 * provider cannot be wired without the statement following it.
 */
import type { ProviderInfo } from './provider';
import { TOOL_RESULT_MAX_CHARS } from './tool';

export interface Disclosure {
  /** One sentence: does anything leave. */
  headline: string;
  /** What is sent, item by item. Empty when nothing leaves. */
  sent: string[];
  /** What is not sent, or what stays true. */
  kept: string[];
}

export function disclosure(info: ProviderInfo): Disclosure {
  if (!info.remote) {
    return {
      headline: 'Nothing leaves this page.',
      sent: [],
      kept: [
        'No model is connected: the answers are a recording replayed in your browser.',
        'The programs it runs are real. They talk to your server only, over the editor\'s own encrypted channel, like the rest of the editor.',
      ],
    };
  }
  return {
    headline: 'Sending a message sends the following to the model provider, outside this page and outside the editor\'s end-to-end encryption.',
    sent: [
      'The conversation: what you type and what the assistant answers.',
      'The part of this server\'s SDK description that is in the assistant\'s context: the names and short descriptions of its content types, actions, conditions, triggers and presets.',
      'Type-check and dry-run diagnostics of the programs the assistant writes.',
      `What a program returns and logs, cut to ${TOOL_RESULT_MAX_CHARS.toLocaleString('en-US')} characters per result. A program that reads your content can return parts of it.`,
    ],
    kept: [
      'Your files are not uploaded as a whole, and the session\'s keys never leave this page.',
      'The relay still sees only encrypted traffic.',
    ],
  };
}
