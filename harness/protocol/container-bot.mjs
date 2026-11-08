// A protocol client for the container GUI floor scenario (gui.md 12.6, row P): it joins the 1.21.4 floor, reports every
// window the server opens for it (the client's view: the window id, the menu type id and the title's presence), and
// stops when the harness says so on stdin. It sends nothing else: no clicks, no chat. Opt-in: the harness runs it only
// with -Pharness.containerGuiFloor=true, after `npm ci` in this directory.
import readline from 'node:readline';
import minecraft from 'minecraft-protocol';

const port = Number(process.argv[2]);
if (!Number.isInteger(port) || port <= 0) {
  console.log('MATERIONE-CTBOT stage=args ok=false error=server port required');
  process.exit(2);
}

const client = minecraft.createClient({
  host: '127.0.0.1',
  port,
  username: 'ContainerBot',
  auth: 'offline',
  version: '1.21.4',
});

client.on('login', () => console.log('MATERIONE-CTBOT stage=login ok=true'));
client.on('error', (error) => {
  console.log(`MATERIONE-CTBOT stage=error ok=false error=${error}`);
  process.exit(1);
});
client.on('end', (reason) => {
  console.log(`MATERIONE-CTBOT stage=end reason=${reason}`);
  process.exit(0);
});
client.on('position', (packet) => client.write('teleport_confirm', { teleportId: packet.teleportId }));
client.on('open_window', (packet) => {
  console.log(`MATERIONE-CTBOT stage=open ok=true windowId=${packet.windowId} menuType=${packet.inventoryType}`);
});

const commands = readline.createInterface({ input: process.stdin, crlfDelay: Infinity });
for await (const line of commands) {
  if (line.trim() === 'stop') {
    client.end('harness stop');
    break;
  }
}
