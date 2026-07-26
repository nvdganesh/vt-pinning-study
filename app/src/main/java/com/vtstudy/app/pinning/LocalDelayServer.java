package com.vtstudy.app.pinning;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.Executors;

/**
 * External-validity validation: a real blocking I/O call rather than
 * Thread.sleep().
 *
 * This component starts a local TCP server on a fixed port. The server
 * waits for a configurable delay before writing a single byte and closing
 * the connection. The /io-real-blocking endpoint connects to this server,
 * reads the byte, and returns -- producing a genuine socket read that
 * blocks the virtual thread without any synchronized involvement.
 *
 * Purpose: confirm that the baseline (no-lock) and JDK 24 (post-JEP 491)
 * results hold for actual network I/O, not just Thread.sleep(), strengthening
 * the external validity of the synthetic findings.
 *
 * The server runs on a platform thread pool so that it never exercises
 * virtual-thread scheduling itself -- it is a controlled stimulus source,
 * not a subject under test.
 */
@Component
public class LocalDelayServer {

    @Value("${vtstudy.delayserver.port:19000}")
    private int port;

    @Value("${vtstudy.delayserver.delayms:50}")
    private long delayMs;

    private ServerSocket serverSocket;
    private volatile boolean running;

    @PostConstruct
    public void start() throws Exception {
        serverSocket = new ServerSocket(port);
        running = true;
        // Use a platform-thread pool for the server -- it must not be a
        // virtual thread to avoid confounding the measurement subject.
        var pool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "delay-server-worker");
            t.setDaemon(true);
            return t;
        });
        Thread acceptor = new Thread(() -> {
            while (running) {
                try {
                    Socket client = serverSocket.accept();
                    pool.submit(() -> handleClient(client));
                } catch (Exception e) {
                    if (running) throw new RuntimeException(e);
                }
            }
        }, "delay-server-acceptor");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    private void handleClient(Socket client) {
        try (client) {
            // Discard any input; just wait, then signal completion.
            Thread.sleep(delayMs);
            OutputStream out = client.getOutputStream();
            out.write(1);
            out.flush();
        } catch (Exception ignored) {}
    }

    @PreDestroy
    public void stop() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
    }

    public int getPort()    { return port;    }
    public long getDelayMs(){ return delayMs; }
}
