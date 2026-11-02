package dev.materione.harness;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Opt-in 1.21.4 server and protocol-client test; it is not a visual or physical-input test. */
public final class RichTitleFloorRunner {
    public HarnessResult run(HarnessOptions options) {
        ServerWorkspace workspace;
        Path protocol = Path.of(System.getProperty("harness.protocolDir"));
        Path helper = Path.of(System.getProperty("harness.richTitleProbeJar"));
        try {
            if (!Files.isDirectory(protocol.resolve("node_modules").resolve("minecraft-protocol"))) {
                throw new IOException("protocol dependency missing: run npm ci in harness/protocol");
            }
            String expected = System.getProperty("harness.paperSha256");
            if (!sha256(options.paperJar()).equals(expected)) {
                throw new IOException("Paper jar hash differs from the pinned floor");
            }
            workspace = ServerWorkspace.create(options, "rich-title-floor", Set.of("overlay", "corpus"));
            Files.copy(helper, workspace.pluginsDir.resolve("MaterioneFloorProbe.jar"), StandardCopyOption.REPLACE_EXISTING);
            Path guis = workspace.root.resolve("plugins").resolve("Materione").resolve("content")
                    .resolve("packs").resolve("overlay").resolve("guis");
            Files.copy(protocol.resolve("floor.yml"), guis.resolve("floor.yml"), StandardCopyOption.REPLACE_EXISTING);
            System.out.println("MATERIONE-FLOOR-ARTIFACT paper.sha256=" + expected
                    + " plugin.sha256=" + sha256(options.pluginJar()) + " helper.sha256=" + sha256(helper)
                    + " fixture.sha256=" + sha256(protocol.resolve("floor.yml"))
                    + " bot.sha256=" + sha256(protocol.resolve("floor-bot.mjs"))
                    + " lock.sha256=" + sha256(protocol.resolve("package-lock.json")));
            System.out.println("MATERIONE-FLOOR-SOURCE revision=" + git(protocol, "rev-parse", "HEAD")
                    + " tracked-diff.sha256=" + digest(gitBytes(protocol, "diff", "--binary", "HEAD"))
                    + " runner.sha256=" + sha256(protocol.getParent().resolve("src").resolve("main")
                            .resolve("java").resolve("dev").resolve("materione").resolve("harness")
                            .resolve("RichTitleFloorRunner.java"))
                    + " observer.sha256=" + sha256(protocol.getParent().resolve("src").resolve("richTitleProbe")
                            .resolve("java").resolve("dev").resolve("materione").resolve("harness")
                            .resolve("probe").resolve("RichTitleProbe.java")));
        } catch (Exception failure) {
            return new HarnessResult(false, List.of("floor setup failed: " + failure), -1, null, List.of());
        }

        Process server = null;
        Process bot = null;
        List<String> problems = new ArrayList<>();
        LogWatcher serverLog = new LogWatcher(workspace.logFile);
        Path botLogPath = workspace.root.resolve("floor-bot.log");
        LogWatcher botLog = new LogWatcher(botLogPath);
        try {
            server = new ProcessBuilder(options.javaExecutable().toString(), "-Xmx2G", "-Dfile.encoding=UTF-8",
                    "-DIReallyKnowWhatIAmDoingISwear", "-jar", options.paperJar().toString(), "nogui")
                    .directory(workspace.root.toFile()).redirectErrorStream(true)
                    .redirectOutput(workspace.stdoutFile.toFile()).start();
            System.out.println("MATERIONE-FLOOR-PROCESS role=server pid=" + server.pid());
            try (ConsoleDriver console = new ConsoleDriver(server.getOutputStream())) {
                System.out.println("MATERIONE-FLOOR-BOOT " + require(serverLog, "1.21.4",
                        options.bootTimeout().toSeconds(), 0));
                System.out.println("MATERIONE-FLOOR-BOOT " + require(serverLog, "Done (",
                        options.bootTimeout().toSeconds(), 0));
                System.out.println("MATERIONE-FLOOR-BOOT " + require(serverLog, "MATERIONE-BOOT-OK",
                        options.commandTimeout().toSeconds(), 0));
                int port = Integer.getInteger("harness.serverPort", 25599);
                bot = new ProcessBuilder("node", protocol.resolve("floor-bot.mjs").toString(), Integer.toString(port))
                        .directory(protocol.toFile()).redirectErrorStream(true).redirectOutput(botLogPath.toFile()).start();
                System.out.println("MATERIONE-FLOOR-PROCESS role=protocol-client pid=" + bot.pid());
                try (ConsoleDriver client = new ConsoleDriver(bot.getOutputStream())) {
                    require(botLog, "MATERIONE-FLOOR-BOT stage=login ok=true", options.commandTimeout().toSeconds(), 0);
                    require(serverLog, "FloorBot joined", options.commandTimeout().toSeconds(), 0);
                    command(console, serverLog, options, "materione gui open overlay:floor FloorBot",
                            "MATERIONE-FLOOR-OPEN");
                    String serverStart = command(console, serverLog, options, "floorprobe start",
                            "MATERIONE-FLOOR-PROBE stage=start ok=true");
                    String clientStart = botCommand(client, botLog, options, "baseline");
                    if (!field(serverStart, "id").equals(field(clientStart, "id"))) {
                        throw new IOException("client window id differs from the server native menu id");
                    }
                    botCommand(client, botLog, options, "held");
                    command(console, serverLog, options, "floorprobe held", "MATERIONE-FLOOR-PROBE stage=held ok=true");
                    botCommand(client, botLog, options, "reverted");
                    command(console, serverLog, options, "floorprobe held", "MATERIONE-FLOOR-PROBE stage=held ok=true");
                    botCommand(client, botLog, options, "held");
                    command(console, serverLog, options, "floorprobe held", "MATERIONE-FLOOR-PROBE stage=held ok=true");
                    botCommand(client, botLog, options, "release");
                    command(console, serverLog, options, "floorprobe shown", "MATERIONE-FLOOR-PROBE stage=shown ok=true");
                    botCommand(client, botLog, options, "steady");
                    botCommand(client, botLog, options, "hidden");
                    command(console, serverLog, options, "floorprobe hidden", "MATERIONE-FLOOR-PROBE stage=hidden ok=true");
                    botCommand(client, botLog, options, "steady");
                    command(console, serverLog, options, "floorprobe steady", "MATERIONE-FLOOR-PROBE stage=steady ok=true");
                    botCommand(client, botLog, options, "stop");
                    if (!bot.waitFor(options.commandTimeout().toSeconds(), TimeUnit.SECONDS) || bot.exitValue() != 0) {
                        throw new IOException("protocol client did not exit successfully");
                    }
                }
                command(console, serverLog, options, "stop", "MATERIONE-BYE");
                if (!server.waitFor(options.stopTimeout().toSeconds(), TimeUnit.SECONDS) || server.exitValue() != 0) {
                    throw new IOException("Paper did not stop successfully");
                }
            }
        } catch (Exception failure) {
            problems.add("rich-title floor scenario failed: " + failure);
        } finally {
            if (bot != null && bot.isAlive()) PaperHarnessRunner.killTree(bot);
            if (server != null && server.isAlive()) PaperHarnessRunner.killTree(server);
        }
        List<String> evidence = new ArrayList<>(serverLog.tail(60));
        evidence.addAll(botLog.tail(40));
        evidence.stream().filter(line -> line.contains("MATERIONE-FLOOR") || line.contains("1.21.4"))
                .forEach(System.out::println);
        return new HarnessResult(problems.isEmpty(), problems, problems.isEmpty() ? 0 : -1, workspace.root, evidence);
    }

    private static String command(ConsoleDriver console, LogWatcher log, HarnessOptions options,
            String command, String marker) throws Exception {
        int mark = log.mark();
        console.send(command);
        return require(log, marker, options.commandTimeout().toSeconds(), mark);
    }

    private static String botCommand(ConsoleDriver client, LogWatcher log, HarnessOptions options, String stage)
            throws Exception {
        return command(client, log, options, stage, "MATERIONE-FLOOR-BOT stage=" + stage + " ok=true");
    }

    private static String field(String line, String key) throws IOException {
        var match = java.util.regex.Pattern.compile("(?:^|\\s)" + key + "=(\\d+)(?:\\s|$)").matcher(line);
        if (!match.find()) throw new IOException("missing " + key + " in evidence marker: " + line);
        return match.group(1);
    }

    private static String require(LogWatcher log, String marker, long seconds, int mark) throws Exception {
        String line = log.awaitLine(marker, java.time.Duration.ofSeconds(seconds), mark);
        if (line == null) {
            throw new IOException("missing marker: " + marker);
        }
        return line;
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(file)) {
            byte[] chunk = new byte[8192];
            int count;
            while ((count = input.read(chunk)) != -1) digest.update(chunk, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String digest(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String git(Path directory, String... arguments) throws Exception {
        return new String(gitBytes(directory, arguments), java.nio.charset.StandardCharsets.UTF_8).trim();
    }

    private static byte[] gitBytes(Path directory, String... arguments) throws Exception {
        var command = new ArrayList<String>(List.of("git", "--no-pager"));
        command.addAll(List.of(arguments));
        Process git = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
        byte[] output = git.getInputStream().readAllBytes();
        if (!git.waitFor(10, TimeUnit.SECONDS) || git.exitValue() != 0) {
            throw new IOException("could not fingerprint source revision");
        }
        return output;
    }
}
