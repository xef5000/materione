package dev.materione.harness;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Writes console commands to the server's stdin pipe. */
public final class ConsoleDriver implements AutoCloseable {

    private final BufferedWriter writer;

    public ConsoleDriver(OutputStream stdin) {
        this.writer = new BufferedWriter(new OutputStreamWriter(stdin, StandardCharsets.UTF_8));
    }

    public void send(String command) throws IOException {
        Objects.requireNonNull(command, "command");
        writer.write(command);
        writer.write('\n');
        writer.flush();
    }

    @Override
    public void close() throws IOException {
        writer.close();
    }
}
