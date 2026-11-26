package dev.yamc.mcp.util;

import dev.yamc.mcp.YamcMcp;
import dev.yamc.mcp.mcp.ToolException;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Helpers to run code on the Minecraft client thread or the integrated server thread.
 * A queued task whose caller has already timed out is skipped, so a tool that reported a timeout
 * never acts later (a retry would otherwise run it twice).
 */
public final class ClientThread {
    private ClientThread() {}

    public static <T> T call(Callable<T> task) throws Exception {
        Minecraft mc = Minecraft.getInstance();
        if (mc.isSameThread()) return task.call();
        CompletableFuture<T> f = new CompletableFuture<>();
        mc.execute(() -> {
            if (f.isDone()) return; // the caller gave up waiting
            try {
                f.complete(task.call());
            } catch (Throwable t) {
                f.completeExceptionally(t);
            }
        });
        return await(f, YamcMcp.settings().clientThreadTimeoutMs(), "client thread");
    }

    public static <T> T callServer(MinecraftServer server, Callable<T> task, long timeoutMs) throws Exception {
        if (server.isSameThread()) return task.call();
        CompletableFuture<T> f = new CompletableFuture<>();
        server.execute(() -> {
            if (f.isDone()) return; // the caller gave up waiting
            try {
                f.complete(task.call());
            } catch (Throwable t) {
                f.completeExceptionally(t);
            }
        });
        return await(f, timeoutMs, "integrated server thread");
    }

    public static <T> T await(CompletableFuture<T> f, long timeoutMs, String what) throws Exception {
        try {
            return f.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            f.cancel(false);
            throw new ToolException("Timed out after " + timeoutMs + "ms waiting for the " + what + " (game frozen or loading?)");
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof Exception ex) throw ex;
            throw new RuntimeException(c);
        }
    }
}
