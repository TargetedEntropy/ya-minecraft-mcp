package dev.yamc.mcp.debug;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.yamc.mcp.util.Json;
import dev.yamc.mcp.util.Mc;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.gametest.framework.GameTestTicker;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs game tests through the vanilla /test command on the integrated server and reads their results from the test
 * runner it starts, so the outcome does not depend on parsing chat. The test API differs by version (tests became
 * registry entries in 1.21.5); those differences stay in this class.
 */
public final class GameTests {
    private GameTests() {}

    /** The command's own output, and the runner it started (null if it started none). Server thread. */
    public record Started(List<String> output, boolean commandOk, @Nullable GameTestRunner runner) {}

    public static Started start(MinecraftServer server, CommandSourceStack player, String command) {
        GameTestRunner before = GameTestTicker.SINGLETON.runner;
        List<String> output = new ArrayList<>();
        // Set by the command's result callback; a command that does not parse never runs, so it stays null.
        Boolean[] succeeded = {null};
        CommandSource capture = new CommandSource() {
            @Override
            public void sendSystemMessage(Component message) {
                output.add(message.getString());
            }

            @Override
            public boolean acceptsSuccess() {
                return true;
            }

            @Override
            public boolean acceptsFailure() {
                return true;
            }

            @Override
            public boolean shouldInformAdmins() {
                return false;
            }
        };
        CommandSourceStack source = Mc.asOwner(player.withSource(capture)).withCallback((ok, result) -> succeeded[0] = ok);
        server.getCommands().performPrefixedCommand(source, command);
        GameTestRunner after = GameTestTicker.SINGLETON.runner;
        return new Started(output, Boolean.TRUE.equals(succeeded[0]), after != before ? after : null);
    }

    /** Whether every test of the runner has finished. Server thread. */
    public static boolean done(GameTestRunner runner) {
        List<GameTestInfo> all = runner.getTestInfos();
        return !all.isEmpty() && all.stream().allMatch(GameTestInfo::isDone);
    }

    /** Each test's outcome. Server thread. */
    public static JsonObject results(GameTestRunner runner) {
        JsonArray tests = new JsonArray();
        int passed = 0, failed = 0, failedRequired = 0, running = 0;
        for (GameTestInfo t : runner.getTestInfos()) {
            JsonObject o = new JsonObject();
            o.addProperty("test", name(t));
            o.addProperty("required", t.isRequired());
            String status = !t.isDone() ? "running" : t.hasSucceeded() ? "passed" : "failed";
            o.addProperty("status", status);
            switch (status) {
                case "passed" -> passed++;
                case "failed" -> {
                    failed++;
                    if (t.isRequired()) failedRequired++;
                    String err = error(t);
                    if (err != null) o.addProperty("error", err);
                }
                default -> running++;
            }
            o.add("pos", Json.pos(pos(t)));
            o.addProperty("run_time_ms", t.getRunTime());
            tests.add(o);
        }
        JsonObject o = new JsonObject();
        o.addProperty("total", tests.size());
        o.addProperty("passed", passed);
        o.addProperty("failed", failed);
        o.addProperty("failed_required", failedRequired);
        if (running > 0) o.addProperty("still_running", running);
        o.addProperty("all_required_passed", failedRequired == 0 && running == 0);
        o.add("tests", tests);
        return o;
    }

    /** Every test the server knows. Server thread. */
    public static List<String> available(MinecraftServer server) {
        //? if >=1.21.5 {
        /*return Mc.registryOrThrow(server.registryAccess(), net.minecraft.core.registries.Registries.TEST_INSTANCE).keySet().stream()
                .map(Object::toString).sorted().toList();
        *///?} else
        return net.minecraft.gametest.framework.GameTestRegistry.getAllTestFunctions().stream().map(f -> f.testName()).sorted().toList();
    }

    private static String name(GameTestInfo t) {
        //? if >=1.21.5 {
        /*return t.id().toString();
        *///?} else
        return t.getTestName();
    }

    private static net.minecraft.core.BlockPos pos(GameTestInfo t) {
        //? if >=1.21.5 {
        /*return t.getTestOrigin();
        *///?} else
        return t.getStructureBlockPos();
    }

    private static @Nullable String error(GameTestInfo t) {
        //? if >=1.21.5 {
        /*var e = t.getError();
        return e == null ? null : e.getDescription().getString();
        *///?} else {
        Throwable e = t.getError();
        return e == null ? null : e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        //?}
    }
}
