/**
 * Where the surface document comes from at run time (codemode.md section 3.1): the editor reads the
 * connected plugin's `authoring.describe` over its existing channel and the session's capability list, and
 * assembles the document the SDK generator reads. No new endpoint, no new permission: a read that needs
 * `materione.editor` alone, the same call the studios make.
 */
import { ops, unwrap, type RpcChannel } from '../api';
import { assembleSurface, type SurfaceDocument } from './generate/surface';

/** The surface document of the plugin behind `channel`. */
export async function fetchSurface(channel: RpcChannel): Promise<SurfaceDocument> {
  const describe = unwrap(await ops.describe(channel));
  return assembleSurface(describe, channel.blob?.capabilities);
}
