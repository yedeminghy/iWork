package dev.agentdesktop.host;

import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Keeps the Host lifetime bounded by the Electron Main bootstrap pipe. */
@Component
public final class ParentLivenessMonitor implements SmartLifecycle {
    private final ParentInputChannel input;
    private final ConfigurableApplicationContext context;
    private final AtomicBoolean running = new AtomicBoolean();
    private Thread monitor;

    public ParentLivenessMonitor(ParentInputChannel input, ConfigurableApplicationContext context) {
        this.input = input;
        this.context = context;
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) return;
        monitor = Thread.ofVirtual().name("electron-parent-liveness").start(() -> {
            try {
                while (running.get()) {
                    String command = input.reader().readLine();
                    if (command == null) {
                        if (running.compareAndSet(true, false)) context.close();
                        return;
                    }
                    // The bootstrap channel is intentionally not a command channel.
                }
            } catch (IOException ignored) {
                if (running.compareAndSet(true, false)) context.close();
            }
        });
    }

    @Override
    public void stop() {
        running.set(false);
        if (monitor != null) monitor.interrupt();
    }

    @Override public boolean isRunning() { return running.get(); }
    @Override public boolean isAutoStartup() { return true; }
    @Override public int getPhase() { return Integer.MAX_VALUE; }
}
