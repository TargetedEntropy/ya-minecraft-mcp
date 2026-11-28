package dev.yamc.mcp.tools;

import com.google.gson.JsonObject;
import dev.yamc.mcp.debug.GameTests;
import dev.yamc.mcp.mcp.Args;
import dev.yamc.mcp.mcp.Schema;
import dev.yamc.mcp.mcp.ToolException;
import dev.yamc.mcp.mcp.ToolRegistry;
import dev.yamc.mcp.mcp.ToolResult;
import dev.yamc.mcp.util.IntegratedServer;
import dev.yamc.mcp.util.Json;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerTickRateManager;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Locale;

/** Mod development: running game tests, and freezing / stepping / sprinting the game clock. Singleplayer only. */
final class TestTools {
    private TestTools() {}

    private static final long POLL_MS = 100;

    /** gametest_run modes: each but LIST is the /test subcommand of the same name. */
    private enum Mode { RUNALL, RUN, RUNCLOSEST, RUNTHESE, RUNTHAT, RUNFAILED, LIST }

    private enum TickAction { STATUS, FREEZE, UNFREEZE, STEP, SPRINT, RATE }

    static void register(ToolRegistry r) {
        r.add(Gates.action("gametest_run").title("Run game tests")
                .desc("Singleplayer only. Runs game tests (the /test command) and waits for the results: pass/fail of each test, "
                        + "its error message and position. mode=list lists the tests the game knows. Tests are built in the world "
                        + "near the player. On NeoForge before 1.21.5 and on Fabric the test command may need the game test "
                        + "framework enabled (dev environments usually have it).")
                .schema(Schema.of().choice("mode", "What to run (default runall).", Mode.class)
                        .str("tests", "For mode=run: the test name or selector, e.g. mymod:my_test (wildcards allowed from 1.21.5).")
                        .integer("timeout_seconds", "Longest to wait for the tests to finish (default 120, max 540)."))
                .gate(Gates.INTEGRATED_SERVER)
                .anyThread()
                .handler(TestTools::gametest));

        r.add(Gates.action("tick_control").title("Tick control")
                .desc("Singleplayer only. The game clock (like /tick): status, freeze, unfreeze, step N ticks while frozen (waits "
                        + "until they ran), sprint N ticks as fast as possible (waits), or set the tick rate. Use freeze + step to "
                        + "advance a machine or mob exactly, then inspect it.")
                .schema(Schema.of().choice("action", "What to do (default status).", TickAction.class)
                        .integer("ticks", "For step (default 1, max 72000) and sprint (default 20, max 72000).")
                        .number("rate", "For rate: ticks per second (1-10000, normal 20)."))
                .gate(Gates.INTEGRATED_SERVER)
                .anyThread()
                .handler(TestTools::tick));
    }

    // ------------------------------------------------------------------ game tests

    private static ToolResult gametest(Args a) throws Exception {
        Mode mode = a.choice("mode", Mode.RUNALL);
        if (mode == Mode.LIST) {
            List<String> tests = IntegratedServer.call(GameTests::available);
            JsonObject o = new JsonObject();
            o.addProperty("count", tests.size());
            o.add("tests", Json.strings(tests));
            return ToolResult.json(o);
        }
        String command = "test " + mode.name().toLowerCase(Locale.ROOT);
        if (mode == Mode.RUN) command += " " + a.reqStr("tests").trim();
        else if (a.has("tests")) throw new ToolException("tests is only for mode=run.");
        long timeout = a.integer("timeout_seconds", 120, 1, 540) * 1000L;
        String cmd = command;
        GameTests.Started started = IntegratedServer.call(s -> GameTests.start(s, player(s).createCommandSourceStack(), cmd));
        JsonObject o = new JsonObject();
        o.addProperty("command", "/" + command);
        o.add("output", Json.strings(started.output()));
        if (started.runner() == null) {
            o.addProperty("started", false);
            o.addProperty("note", started.commandOk() ? "No tests were started (none matched?)." : "The command failed: see output.");
            return ToolResult.json(o);
        }
        long deadline = System.currentTimeMillis() + timeout;
        boolean finished;
        while (!(finished = IntegratedServer.call(s -> GameTests.done(started.runner()))) && System.currentTimeMillis() < deadline)
            Thread.sleep(POLL_MS);
        o.addProperty("finished", finished);
        if (!finished) o.addProperty("note", "Timed out: these are the results so far. The game must not be paused for tests to run.");
        o.add("results", IntegratedServer.call(s -> GameTests.results(started.runner())));
        return ToolResult.json(o);
    }

    private static ServerPlayer player(MinecraftServer s) {
        var players = s.getPlayerList().getPlayers();
        if (players.isEmpty()) throw new ToolException("No player on the integrated server.");
        return players.getFirst();
    }

    // ------------------------------------------------------------------ tick control

    private static ToolResult tick(Args a) throws Exception {
        JsonObject o = new JsonObject();
        switch (a.choice("action", TickAction.STATUS)) {
            case STATUS -> {}
            case FREEZE -> IntegratedServer.call(s -> setFrozen(s, true));
            case UNFREEZE -> IntegratedServer.call(s -> setFrozen(s, false));
            case RATE -> {
                if (!a.has("rate")) throw new ToolException("Give rate (ticks per second).");
                float rate = (float) Math.max(1, Math.min(10000, a.dbl("rate", 20)));
                IntegratedServer.call(s -> {
                    s.tickRateManager().setTickRate(rate);
                    return true;
                });
            }
            case STEP -> {
                int n = a.integer("ticks", 1, 1, 72000);
                if (!IntegratedServer.call(s -> s.tickRateManager().stepGameIfPaused(n)))
                    throw new ToolException("Can only step while frozen: use action=freeze first.");
                o.addProperty("stepped", n);
                o.addProperty("finished", waitWhile(s -> s.tickRateManager().isSteppingForward(), n));
            }
            case SPRINT -> {
                int n = a.integer("ticks", 20, 1, 72000);
                long t0 = System.currentTimeMillis();
                int tick0 = IntegratedServer.call(s -> {
                    s.tickRateManager().requestGameToSprint(n);
                    return s.getTickCount();
                });
                o.addProperty("finished", waitWhile(s -> s.tickRateManager().isSprinting(), n));
                o.addProperty("ticks_run", IntegratedServer.call(MinecraftServer::getTickCount) - tick0);
                o.addProperty("duration_ms", System.currentTimeMillis() - t0);
            }
        }
        o.add("status", IntegratedServer.call(TestTools::status));
        return ToolResult.json(o);
    }

    private static boolean setFrozen(MinecraftServer s, boolean frozen) {
        s.tickRateManager().setFrozen(frozen);
        return frozen;
    }

    /** Waits until the condition is false (or about ten seconds more than {@code ticks} at 20 per second); returns whether it ended. */
    private static boolean waitWhile(IntegratedServer.Task<Boolean> cond, int ticks) throws Exception {
        long deadline = System.currentTimeMillis() + ticks * 50L + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (!IntegratedServer.call(cond)) return true;
            Thread.sleep(POLL_MS);
        }
        return false;
    }

    private static JsonObject status(MinecraftServer s) {
        ServerTickRateManager t = s.tickRateManager();
        JsonObject o = new JsonObject();
        o.addProperty("frozen", t.isFrozen());
        o.addProperty("stepping", t.isSteppingForward());
        o.addProperty("sprinting", t.isSprinting());
        o.addProperty("tick_rate", t.tickrate());
        o.addProperty("server_tick", s.getTickCount());
        o.addProperty("mspt", Json.round(s.getAverageTickTimeNanos() / 1_000_000.0));
        o.addProperty("paused", s.isPaused());
        return o;
    }
}
