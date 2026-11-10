// A protocol client that joins the 1.21.4 floor and stays: it confirms teleports (the library answers keep-alives and
// the configuration phase itself) and leaves when the harness writes "stop" on stdin. It sends no clicks and no chat.
// Used by the Folia probe (gui-input-durability.md section 11, P1, row R2): Folia cannot host the fake-connection player
// the Paper rig uses (its join is a multi-step, region-scheduled sequence), so the player there is a real network
// client, and the probe plugin runs its experiment on that player's own region thread. Opt-in, like the other bots:
// the harness runs it only with -Pharness.folia=true, after `npm ci --ignore-scripts` in this directory.
//
//   node idle-bot.mjs <port> <username>
import readline from 'node:readline';
import minecraft from 'minecraft-protocol';

const port = Number(process.argv[2]);
const username = process.argv[3] || 'IdleBot';
if (!Number.isInteger(port) || port <= 0) {
  console.log('MATERIONE-IDLEBOT stage=args ok=false error=server port required');
  process.exit(2);
}

const client = minecraft.createClient({
  host: '127.0.0.1',
  port,
  username,
  auth: 'offline',
  version: '1.21.4',
});

client.on('login', () => console.log(`MATERIONE-IDLEBOT stage=login ok=true player=${username}`));
client.on('error', (error) => {
  console.log(`MATERIONE-IDLEBOT stage=error ok=false error=${error}`);
  process.exit(1);
});
client.on('end', (reason) => {
  console.log(`MATERIONE-IDLEBOT stage=end reason=${reason}`);
  process.exit(0);
});
client.on('position', (packet) => client.write('teleport_confirm', { teleportId: packet.teleportId }));

const commands = readline.createInterface({ input: process.stdin, crlfDelay: Infinity });
for await (const line of commands) {
  if (line.trim() === 'stop') {
    client.end('harness stop');
    break;
  }
}
