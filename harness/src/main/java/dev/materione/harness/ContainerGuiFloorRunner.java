package dev.materione.harness;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The product runtime opens a hopper and a dispenser on the 1.21.4 floor (gui.md 12.6, row P, #292). Two GUIs are
 * authored into the sample pack with {@code container: hopper} and {@code container: dispenser}; a protocol client
 * joins; {@code materione gui open} shows each to it through {@code PluginGui}, the product's own opener and window
 * host, not the probe's. The probe then reads the player's open top inventory: the window type and size, the menu
 * the server holds (its id is the one the client's open-window packet carries, so the client is shown the same
 * window), and that the holder is a {@code PluginGui}, so a click routes to Materione.
 *
 * <p>Opt-in ({@code -Pharness.containerGuiFloor=true}): the client needs {@code npm ci} in {@code harness/protocol},
 * which the default integration run does not do. The screen (title position, art) stays the 26.1.2 probe's (§12.2.6).
 */
public final class ContainerGuiFloorRunner {

    static final String PLAYER = "ContainerBot";

    /** One container under test: its GUI id, the container id, its window size, and the menu the server reports. */
    record Case(String gui, String container, String type, int slots, String menuKey) {}

    static final List<Case> CASES = List.of(
            new Case("sample:hopper_shop", "hopper", "HOPPER", 5, "minecraft:hopper"),
            new Case("sample:dispenser_shop", "dispenser", "DISPENSER", 9, "minecraft:generic_3x3"));

    /** The hopper's screen is 176 x 133; its one item sits in slot 2 (the five slots are 0..4). */
    static final String HOPPER_YAML = """
            hopper_shop:
              title: Hopper shop
              container: hopper
              layers:
                - id: frame
                  type: panel
                  width: 176
                  height: 133
                - id: wells
                  type: slots
                  region: container
                - id: gem
                  type: item
                  slot: 2
                  item: corpus:gem_red
                  amount: 1
            """;

    /** The dispenser's screen is 176 x 166; its one item sits in slot 4, the centre of the 3 x 3 grid. */
    static final String DISPENSER_YAML = """
            dispenser_shop:
              title: Dispenser shop
              container: dispenser
              layers:
                - id: frame
                  type: panel
                  width: 176
                  height: 166
                - id: wells
                  type: slots
                  region: container
                - id: gem
                  type: item
                  slot: 4
                  item: corpus:gem_red
                  amount: 1
            """;

    private static final Pattern FIELD = Pattern.compile("(?:^|\\s)(\\w+)=(\\S+)");

    public HarnessResult run(HarnessOptions options) {
        ServerWorkspace workspace;
        Path protocol = Path.of(System.getProperty("harness.protocolDir", ""));
        Path probe = Path.of(System.getProperty("harness.containerProbeJar", ""));
        try {
            if (!Files.isDirectory(protocol.resolve("node_modules").resolve("minecraft-protocol"))) {
                throw new IOException("protocol dependency missing: run npm ci in harness/protocol");
            }
            if (!Files.isRegularFile(probe)) {
                throw new IOException("harness.containerProbeJar is not set or missing: run via :harness:integrationTest");
            }
            workspace = ServerWorkspace.create(options, "container-gui-floor", Set.of("sample", "corpus"));
            Path guis = workspace.root.resolve("plugins").resolve("Materione").resolve("content")
                    .resolve("packs").resolve("sample").resolve("guis");
            Files.createDirectories(guis);
            Files.writeString(guis.resolve("hopper_shop.yml"), HOPPER_YAML);
            Files.writeString(guis.resolve("dispenser_shop.yml"), DISPENSER_YAML);
            Files.copy(probe, workspace.pluginsDir.resolve("MaterioneContainerProbe.jar"),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            return new HarnessResult(false, List.of("container GUI floor setup failed: " + e), -1, null, List.of());
        }

        List<String> problems = new ArrayList<>();
        Process server = null;
        Process bot = null;
        LogWatcher serverLog = new LogWatcher(workspace.logFile);
        Path botLogPath = workspace.root.resolve("container-bot.log");
        LogWatcher botLog = new LogWatcher(botLogPath);
        try {
            server = new ProcessBuilder(options.javaExecutable().toString(), "-Xmx2G", "-Dfile.encoding=UTF-8",
                    "-DIReallyKnowWhatIAmDoingISwear", "-jar", options.paperJar().toString(), "nogui")
                    .directory(workspace.root.toFile()).redirectErrorStream(true)
                    .redirectOutput(workspace.stdoutFile.toFile()).start();
            try (ConsoleDriver console = new ConsoleDriver(server.getOutputStream())) {
                require(serverLog, "Done (", options.bootTimeout(), 0);
                require(serverLog, "MATERIONE-BOOT-OK", options.commandTimeout(), 0);
                require(serverLog, "MATERIONE-CTPROBE ready", options.commandTimeout(), 0);

                int port = Integer.getInteger("harness.serverPort", 25599);
                bot = new ProcessBuilder("node", protocol.resolve("container-bot.mjs").toString(), Integer.toString(port))
                        .directory(protocol.toFile()).redirectErrorStream(true).redirectOutput(botLogPath.toFile()).start();
                try (ConsoleDriver client = new ConsoleDriver(bot.getOutputStream())) {
                    require(botLog, "MATERIONE-CTBOT stage=login ok=true", options.commandTimeout(), 0);
                    require(serverLog, PLAYER + " joined", options.commandTimeout(), 0);

                    for (Case c : CASES) {
                        problems.addAll(openAndCheck(console, serverLog, botLog, options, c));
                    }
                    client.send("stop");
                }
                console.send("stop");
                require(serverLog, "MATERIONE-BYE", options.stopTimeout(), 0);
                if (!server.waitFor(options.stopTimeout().toSeconds(), TimeUnit.SECONDS) || server.exitValue() != 0) {
                    problems.add("Paper did not stop successfully");
                }
            }
        } catch (Exception failure) {
            problems.add("container GUI floor scenario failed: " + failure);
        } finally {
            if (bot != null && bot.isAlive()) {
                PaperHarnessRunner.killTree(bot);
            }
            if (server != null && server.isAlive()) {
                PaperHarnessRunner.killTree(server);
            }
        }
        List<String> evidence = new ArrayList<>(serverLog.tail(60));
        evidence.addAll(botLog.tail(20));
        return new HarnessResult(problems.isEmpty(), problems, problems.isEmpty() ? 0 : -1, workspace.root, evidence);
    }

    /** Opens one GUI for the bot through the product, then checks the server's view and the client's. */
    private static List<String> openAndCheck(ConsoleDriver console, LogWatcher serverLog, LogWatcher botLog,
            HarnessOptions options, Case c) throws Exception {
        List<String> problems = new ArrayList<>();
        int serverMark = serverLog.mark();
        int botMark = botLog.mark();
        console.send("materione gui open " + c.gui() + " " + PLAYER);
        // slots= in this marker counts the filled slots (one item here), not the window's size: the size is the probe's
        String opened = require(serverLog, "MATERIONE-GUI-OPENED id=" + c.gui() + " player=" + PLAYER
                + " container=" + c.container(), options.commandTimeout(), serverMark);
        if (!field(opened, "container").equals(c.container())) {
            problems.add(c.gui() + ": the runtime reported container '" + field(opened, "container") + "'");
        }
        String clientOpen = require(botLog, "MATERIONE-CTBOT stage=open ok=true", options.commandTimeout(), botMark);

        int probeMark = serverLog.mark();
        console.send("ctprobe top " + PLAYER);
        String top = require(serverLog, "MATERIONE-CTPROBE top ok=true", options.commandTimeout(), probeMark);

        // the server's window: the type and size the profile names, the menu it maps to, a PluginGui holder
        expect(problems, c.gui(), top, "type", c.type());
        expect(problems, c.gui(), top, "size", Integer.toString(c.slots()));
        expect(problems, c.gui(), top, "menuType", c.menuKey());
        expect(problems, c.gui(), top, "holder", "GuiMenu");
        expect(problems, c.gui(), top, "product", "true");
        // the client was shown that window: its open-window packet carries the server's menu id
        String serverMenu = field(top, "menuId");
        String clientWindow = field(clientOpen, "windowId");
        if (serverMenu.isEmpty() || !serverMenu.equals(clientWindow)) {
            problems.add(c.gui() + ": the client's window id '" + clientWindow + "' is not the server's menu id '"
                    + serverMenu + "'");
        }
        return problems;
    }

    private static void expect(List<String> problems, String gui, String line, String key, String value) {
        String actual = field(line, key);
        if (!actual.equals(value)) {
            problems.add(gui + ": expected " + key + "=" + value + " but was '" + actual + "' in: " + line);
        }
    }

    /** A {@code key=value} field of a marker line, or the empty string when it is not there. */
    static String field(String line, String key) {
        Matcher matcher = FIELD.matcher(line);
        while (matcher.find()) {
            if (matcher.group(1).equals(key)) {
                return matcher.group(2);
            }
        }
        return "";
    }

    /**
     * The first line containing {@code marker} that is written at or after {@code from} (a {@link LogWatcher#mark()}).
     * {@link LogWatcher#awaitLine(String, Duration, int)} only gates on the cursor and then returns the first match in
     * the whole file, so a repeated marker (a second window's probe line) would match the first window's line; this
     * scan takes the text after the cursor, so each step reads its own answer.
     */
    private static String require(LogWatcher log, String marker, Duration timeout, int from) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            String content = log.readAll();
            if (content != null && content.length() > from) {
                for (String line : content.substring(from).split("\n", -1)) {
                    if (line.contains(marker)) {
                        return line;
                    }
                }
            }
            Thread.sleep(200);
        }
        throw new IOException("missing marker: " + marker);
    }
}
