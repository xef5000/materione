package dev.materione.harness;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.http.WebSocket;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class EditorHandshakeClientTest {
    private final WebSocket socket = (WebSocket) Proxy.newProxyInstance(
            WebSocket.class.getClassLoader(), new Class<?>[] {WebSocket.class},
            (proxy, method, args) -> null);

    @Test
    void bufferedFramesCannotBeOvertakenDuringHandover() throws Exception {
        var client = new EditorHandshakeRunner.WsClient();
        client.onText(socket, "hello", true);
        client.onText(socket, "first", true);
        client.onText(socket, "second", true);
        var seen = new ArrayList<String>();
        var receiving = new CountDownLatch(1);
        Thread sender = new Thread(() -> {
            receiving.countDown();
            client.onText(socket, "third", true);
        });
        client.onFrame(frame -> {
            seen.add(frame);
            if (frame.equals("first")) {
                sender.start();
                try {
                    assertThat(receiving.await(5, TimeUnit.SECONDS)).isTrue();
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while (sender.isAlive() && sender.getState() != Thread.State.BLOCKED
                            && System.nanoTime() < deadline) {
                        Thread.sleep(1);
                    }
                    assertThat(sender.isAlive() && sender.getState() != Thread.State.BLOCKED).isFalse();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
        });
        sender.join(5_000);
        assertThat(sender.isAlive()).isFalse();
        assertThat(client.first.join()).isEqualTo("hello");
        assertThat(seen).containsExactly("first", "second", "third");
    }

    @Test
    void transportFailureBeforeHandoverReachesTheRpcConsumer() {
        var client = new EditorHandshakeRunner.WsClient();
        var cause = new IOException("connection lost");
        client.onError(socket, cause);
        var received = new AtomicReference<Throwable>();
        client.onFailure(received::set);
        assertThat(client.first.isCompletedExceptionally()).isTrue();
        assertThat(received.get()).isSameAs(cause);
    }
}
