package dev.yamc.mcp.tools;

import dev.yamc.mcp.util.Inventories;
import dev.yamc.mcp.util.Mc;
import com.mojang.blaze3d.platform.InputConstants;
import com.google.gson.JsonObject;
import dev.yamc.mcp.client.EventLog;
import dev.yamc.mcp.client.Markers;
import dev.yamc.mcp.client.Ticker;
import dev.yamc.mcp.mcp.Args;
import dev.yamc.mcp.mcp.Schema;
import dev.yamc.mcp.mcp.ToolException;
import dev.yamc.mcp.mcp.ToolRegistry;
import dev.yamc.mcp.mcp.ToolResult;
import dev.yamc.mcp.json.Stacks;
import dev.yamc.mcp.util.Json;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;

/** Tools that change game state. Gated by config tools.enableActions. */
final class ActionTools {
    private ActionTools() {}

    static void register(ToolRegistry r) {
        r.add(Gates.action("send_chat").title("Send chat message")
                .desc("Send a chat message as the player (visible to everyone on the server). Messages starting with '/' are run as commands. Returns 'seq' for wait_for_event.")
                .schema(Schema.of().reqStr("message", "Text to send."))
                .handler(a -> {
                    var c = Ctx.connection();
                    String m = a.reqStr("message");
                    long seq = EventLog.latest();
                    if (m.startsWith("/")) c.sendCommand(m.substring(1));
                    else c.sendChat(m);
                    return seqResult("sent", seq);
                }));

        r.add(Gates.action("run_command").title("Run command")
                .desc("Run a command as the player (with or without leading '/'), e.g. 'time set day' in singleplayer with cheats. Client-side mod commands are handled too. Output arrives as system_message events: use wait_for_event with the returned seq.")
                .schema(Schema.of().reqStr("command", "Command text."))
                .handler(a -> {
                    var c = Ctx.connection();
                    String cmd = a.reqStr("command");
                    if (cmd.startsWith("/")) cmd = cmd.substring(1);
                    long seq = EventLog.latest();
                    c.sendCommand(cmd);
                    return seqResult("command sent", seq);
                }));

        r.add(Gates.action("look_at").title("Look at")
                .desc("Turn the player's view: toward a block position (x,y,z, looks at block center), toward an entity (entity_id), or to absolute yaw/pitch (yaw 0=south, 90=west, 180=north, -90=east; pitch -90=up, 90=down).")
                .schema(Schema.of().number("x", "Target X").number("y", "Target Y").number("z", "Target Z")
                        .integer("entity_id", "Entity to look at").number("yaw", "Absolute yaw").number("pitch", "Absolute pitch"))
                .handler(a -> {
                    LocalPlayer p = Ctx.player();
                    if (a.has("entity_id")) {
                        Entity e = Ctx.entity(a.reqInt("entity_id"));
                        p.lookAt(EntityAnchorArgument.Anchor.EYES, e.getEyePosition());
                    } else if (a.has("x") && a.has("y") && a.has("z")) {
                        double x = a.dbl("x", 0), y = a.dbl("y", 0), z = a.dbl("z", 0);
                        boolean whole = x == Math.floor(x) && y == Math.floor(y) && z == Math.floor(z);
                        p.lookAt(EntityAnchorArgument.Anchor.EYES, whole ? new Vec3(x + 0.5, y + 0.5, z + 0.5) : new Vec3(x, y, z));
                    } else if (a.has("yaw") || a.has("pitch")) {
                        p.setYRot((float) a.dbl("yaw", p.getYRot()));
                        p.setXRot(Mth.clamp((float) a.dbl("pitch", p.getXRot()), -90f, 90f));
                    } else throw new ToolException("Give x/y/z, entity_id, or yaw/pitch.");
                    p.yRotO = p.getYRot();
                    p.xRotO = p.getXRot();
                    p.setYHeadRot(p.getYRot());
                    JsonObject o = new JsonObject();
                    o.addProperty("yaw", Json.round(Mth.wrapDegrees(p.getYRot())));
                    o.addProperty("pitch", Json.round(p.getXRot()));
                    o.add("target", WorldTools.lookTarget(new Args(null)));
                    return ToolResult.json(o);
                }));

        r.add(Gates.action("select_slot").title("Select hotbar slot")
                .desc("Select hotbar slot 0-8.")
                .schema(Schema.of().reqInt("slot", "0-8"))
                .handler(a -> {
                    int s = a.reqInt("slot");
                    if (s < 0 || s > 8) throw new ToolException("slot must be 0-8");
                    LocalPlayer p = Ctx.player();
                    Inventories.select(p.getInventory(), s);
                    JsonObject o = new JsonObject();
                    o.addProperty("selected_slot", s);
                    o.add("item", Stacks.json(p.getMainHandItem(), false, p.registryAccess()));
                    return ToolResult.json(o);
                }));

        r.add(Gates.action("interact").title("Interact")
                .desc("Perform a single interaction like a mouse click. action: use_item (right-click air), use_on_block (right-click a block: x,y,z + face, or the crosshair block), attack (left-click entity by entity_id or crosshair entity), interact_entity (right-click entity), drop (drop one of held item), drop_stack, swap_hands, respawn (when dead). Server enforces reach.")
                .schema(Schema.of().enumStr("action", "What to do.", true, "use_item", "use_on_block", "attack", "interact_entity", "drop", "drop_stack", "swap_hands", "respawn")
                        .enumStr("hand", "main (default) or off.", false, "main", "off")
                        .integer("x", "Block X for use_on_block").integer("y", "Block Y").integer("z", "Block Z")
                        .enumStr("face", "Block face for use_on_block (default: face toward the player).", false, "up", "down", "north", "south", "east", "west")
                        .integer("entity_id", "Entity for attack / interact_entity (default: crosshair entity)."))
                .handler(ActionTools::interact));

        r.add(Gates.action("mine_block").title("Mine block")
                .desc("Look at and mine (break) the block at x,y,z with the held tool, waiting until it breaks or the timeout. Must be within reach. In singleplayer the game must not be paused (disable pause-on-focus-loss with F3+P).")
                .schema(Schema.of().reqInt("x", "X").reqInt("y", "Y").reqInt("z", "Z").integer("timeout_seconds", "Default 15, max 120."))
                .anyThread()
                .handler(ActionTools::mine));

        r.add(Gates.action("move").title("Move (hold keys)")
                .desc("Hold movement/action keys for a number of ticks (20 ticks = 1 second), like a player pressing them: forward, back, left, right, jump, sneak, sprint, attack, use. Optionally face a yaw first. Waits for completion and returns the new position. In singleplayer the game must not be paused (F3+P disables pause on focus loss).")
                .schema(Schema.of().strArray("keys", "Keys to hold, e.g. [\"forward\",\"sprint\"].")
                        .integer("ticks", "Duration in ticks (default 20, max 600).")
                        .number("yaw", "Face this yaw before moving (optional).")
                        .bool("wait", "Wait until finished (default true)."))
                .anyThread()
                .handler(ActionTools::move));

        r.add(Gates.action("container_click").title("Click container slot")
                .desc("Click a slot in the open container (slot index from open_container) or, with no screen open, the player inventory menu (slots: 0 craft result, 1-4 craft grid, 5-8 armor, 9-35 main, 36-44 hotbar, 45 offhand). click_type: pickup (default; button 0=left,1=right), quick_move (shift-click), swap (button = hotbar 0-8, or 40 offhand), throw (button 0 one, 1 stack), clone, pickup_all. Slot -999 clicks outside (drops carried).")
                .schema(Schema.of().reqInt("slot", "Slot index.").integer("button", "Mouse button / hotbar index (default 0).")
                        .enumStr("click_type", "Click type.", false, "pickup", "quick_move", "swap", "clone", "throw", "quick_craft", "pickup_all"))
                .handler(a -> {
                    Minecraft mc = Ctx.mc();
                    LocalPlayer p = Ctx.player();
                    ClickType type = ClickType.valueOf(a.str("click_type", "pickup").toUpperCase(Locale.ROOT));
                    var menu = p.containerMenu;
                    int slot = a.reqInt("slot");
                    if (slot != -999 && (slot < 0 || slot >= menu.slots.size())) throw new ToolException("Slot out of range 0-" + (menu.slots.size() - 1));
                    mc.gameMode.handleInventoryMouseClick(menu.containerId, slot, a.integer("button", 0), type, p);
                    JsonObject o = new JsonObject();
                    o.addProperty("container_id", menu.containerId);
                    if (slot >= 0) o.add("slot_now", Stacks.json(menu.getSlot(slot).getItem(), false, p.registryAccess()));
                    o.add("carried", Stacks.json(menu.getCarried(), false, p.registryAccess()));
                    return ToolResult.json(o);
                }));

        r.add(Gates.action("open_inventory").title("Open inventory")
                .desc("Open the player's inventory screen.")
                .handler(a -> {
                    LocalPlayer p = Ctx.player();
                    Ctx.mc().setScreen(new InventoryScreen(p));
                    return ToolResult.ok("inventory opened");
                }));

        r.add(Gates.action("close_screen").title("Close screen")
                .desc("Close the currently open GUI/container (like pressing Escape).")
                .handler(a -> {
                    Minecraft mc = Ctx.mc();
                    if (mc.screen == null) return ToolResult.ok("no screen open");
                    String name = mc.screen.getClass().getSimpleName();
                    if (mc.screen instanceof AbstractContainerScreen<?> && mc.player != null) mc.player.closeContainer();
                    else mc.setScreen(null);
                    return ToolResult.ok("closed " + name);
                }));

        r.add(Gates.action("highlight").title("Highlight in world")
                .desc("Draw a colored outline box (visible through walls) with an optional floating label at a block or region, to show the player where something is. Returns a marker id.")
                .schema(Schema.of().reqInt("x", "X").reqInt("y", "Y").reqInt("z", "Z")
                        .integer("x2", "Optional second corner X").integer("y2", "Y2").integer("z2", "Z2")
                        .str("color", "#RRGGBB or a name (red, green, blue, yellow, aqua, white, gold, purple). Default yellow.")
                        .str("label", "Floating text label.")
                        .integer("seconds", "Lifetime (default 120; 0 = until cleared).")
                        .bool("through_walls", "Visible through blocks (default true)."))
                .handler(a -> {
                    Ctx.player();
                    BlockPos p1 = Ctx.reqPos(a);
                    BlockPos p2 = new BlockPos(a.integer("x2", p1.getX()), a.integer("y2", p1.getY()), a.integer("z2", p1.getZ()));
                    AABB box = new AABB(Math.min(p1.getX(), p2.getX()), Math.min(p1.getY(), p2.getY()), Math.min(p1.getZ(), p2.getZ()),
                            Math.max(p1.getX(), p2.getX()) + 1, Math.max(p1.getY(), p2.getY()) + 1, Math.max(p1.getZ(), p2.getZ()) + 1);
                    int id = Markers.add(box, color(a.str("color", "yellow")), a.str("label", null), a.integer("seconds", 120, 0, 86400), a.bool("through_walls", true));
                    JsonObject o = new JsonObject();
                    o.addProperty("marker_id", id);
                    o.addProperty("active_markers", Markers.list().size());
                    return ToolResult.json(o);
                }));

        r.add(Gates.action("clear_highlights").title("Clear highlights")
                .desc("Remove one highlight marker by id, or all markers.")
                .schema(Schema.of().integer("marker_id", "Marker to remove (default: all)."))
                .handler(a -> {
                    if (a.has("marker_id")) return ToolResult.ok(Markers.remove(a.reqInt("marker_id")) ? "removed" : "no such marker");
                    return ToolResult.ok("removed " + Markers.clear() + " markers");
                }));

        r.add(Gates.action("notify_player").title("Notify player")
                .desc("Show a message to the local player only (not sent to the server): in chat, the action bar, as a title, or as a toast popup.")
                .schema(Schema.of().reqStr("message", "Text.").enumStr("mode", "Where (default chat).", false, "chat", "actionbar", "title", "toast")
                        .str("title", "Heading for toast/title modes."))
                .handler(a -> {
                    Minecraft mc = Ctx.mc();
                    String msg = a.reqStr("message");
                    String mode = a.str("mode", "chat");
                    switch (mode) {
                        case "actionbar" -> Ctx.player().displayClientMessage(Component.literal(msg), true);
                        case "title" -> {
                            mc.gui.setTimes(10, 70, 20);
                            if (a.has("title")) {
                                mc.gui.setTitle(Component.literal(a.str("title", "")));
                                mc.gui.setSubtitle(Component.literal(msg));
                            } else mc.gui.setTitle(Component.literal(msg));
                        }
                        case "toast" -> Mc.toast(mc, Component.literal(a.str("title", "Claude")), Component.literal(msg));
                        default -> Ctx.player().displayClientMessage(Component.literal("[Claude] ").withStyle(ChatFormatting.LIGHT_PURPLE)
                                .append(Component.literal(msg).withStyle(ChatFormatting.WHITE)), false);
                    }
                    return ToolResult.ok("shown");
                }));
    }

    // ------------------------------------------------------------------

    static ToolResult seqResult(String msg, long seq) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        o.addProperty("message", msg);
        o.addProperty("seq", seq);
        return ToolResult.json(o);
    }

    static int color(String s) {
        s = s.trim().toLowerCase(Locale.ROOT);
        return switch (s) {
            case "red" -> 0xFFFF4040;
            case "green" -> 0xFF40FF40;
            case "blue" -> 0xFF4080FF;
            case "aqua", "cyan" -> 0xFF40FFFF;
            case "white" -> 0xFFFFFFFF;
            case "gold", "orange" -> 0xFFFFAA00;
            case "purple", "magenta" -> 0xFFD040FF;
            case "yellow" -> 0xFFFFFF40;
            default -> {
                try {
                    String h = s.startsWith("#") ? s.substring(1) : s;
                    int v = (int) Long.parseLong(h, 16);
                    yield h.length() <= 6 ? 0xFF000000 | v : v;
                } catch (NumberFormatException e) {
                    throw new ToolException("Bad color '" + s + "'");
                }
            }
        };
    }

    static Direction faceToward(LocalPlayer p, BlockPos pos) {
        Vec3 d = p.getEyePosition().subtract(Vec3.atCenterOf(pos));
        return Mc.nearestDirection(d.x, d.y, d.z);
    }

    static ToolResult interact(Args a) {
        Minecraft mc = Ctx.mc();
        LocalPlayer p = Ctx.player();
        InteractionHand hand = "off".equals(a.str("hand", "main")) ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        String action = a.reqStr("action");
        JsonObject o = new JsonObject();
        o.addProperty("action", action);
        switch (action) {
            case "use_item" -> {
                InteractionResult res = mc.gameMode.useItem(p, hand);
                if (Mc.swings(res)) p.swing(hand);
                o.addProperty("result", Mc.name(res));
            }
            case "use_on_block" -> {
                BlockHitResult hit;
                if (a.has("x")) {
                    BlockPos pos = Ctx.reqPos(a);
                    Direction face = a.has("face") ? Direction.byName(a.str("face", "up")) : faceToward(p, pos);
                    if (face == null) throw new ToolException("Bad face");
                    Vec3 loc = Vec3.atCenterOf(pos).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
                    hit = new BlockHitResult(loc, face, pos, false);
                } else if (mc.hitResult instanceof BlockHitResult bh && bh.getType() == HitResult.Type.BLOCK) {
                    hit = bh;
                } else throw new ToolException("No block given and the crosshair is not on a block.");
                InteractionResult res = mc.gameMode.useItemOn(p, hand, hit);
                if (Mc.swings(res)) p.swing(hand);
                o.addProperty("result", Mc.name(res));
                o.add("pos", Json.pos(hit.getBlockPos()));
                o.addProperty("face", hit.getDirection().getName());
            }
            case "attack", "interact_entity" -> {
                Entity target;
                if (a.has("entity_id")) target = Ctx.entity(a.reqInt("entity_id"));
                else if (mc.hitResult instanceof EntityHitResult eh) target = eh.getEntity();
                else throw new ToolException("No entity_id given and the crosshair is not on an entity.");
                if (action.equals("attack")) {
                    mc.gameMode.attack(p, target);
                    p.swing(InteractionHand.MAIN_HAND);
                } else {
                    InteractionResult res = mc.gameMode.interact(p, target, hand);
                    if (Mc.swings(res)) p.swing(hand);
                    o.addProperty("result", Mc.name(res));
                }
                o.addProperty("target", target.getName().getString());
                o.addProperty("distance", Json.round(target.distanceTo(p)));
            }
            case "drop" -> {
                if (p.drop(false)) p.swing(InteractionHand.MAIN_HAND);
            }
            case "drop_stack" -> {
                if (p.drop(true)) p.swing(InteractionHand.MAIN_HAND);
            }
            case "swap_hands" -> KeyMapping.click(InputConstants.getKey(mc.options.keySwapOffhand.saveString()));
            case "respawn" -> {
                if (!p.isDeadOrDying()) throw new ToolException("Player is not dead.");
                p.respawn();
                mc.setScreen(null);
            }
            default -> throw new ToolException("Unknown action " + action);
        }
        o.addProperty("seq", EventLog.latest());
        return ToolResult.json(o);
    }

    static ToolResult mine(Args a) throws Exception {
        BlockPos pos = Ctx.reqPos(a);
        int timeout = a.integer("timeout_seconds", 15, 1, 120);
        return ToolResult.json(Ticker.run(() -> {
            LocalPlayer p = Ctx.player();
            Ctx.requireUnpaused();
            var state = Ctx.level().getBlockState(pos);
            if (state.isAir()) throw new ToolException("Block at " + pos.toShortString() + " is already air.");
            double dist = p.getEyePosition().distanceTo(Vec3.atCenterOf(pos));
            if (dist > p.blockInteractionRange() + 0.5) throw new ToolException("Block is out of reach (" + Json.round(dist) + " > " + p.blockInteractionRange() + ").");
            String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            p.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(pos));
            Direction face = faceToward(p, pos);
            Minecraft mc = Ctx.mc();
            mc.gameMode.startDestroyBlock(pos, face);
            long deadline = System.currentTimeMillis() + timeout * 1000L;
            return new Ticker.Job<JsonObject>() {
                int ticks;

                @Override
                public JsonObject step() {
                    ticks++;
                    LocalPlayer pl = mc.player;
                    if (pl == null || mc.level == null) return mineResult(false, "left world", blockId, ticks);
                    if (mc.level.getBlockState(pos).isAir()) return mineResult(true, "broken", blockId, ticks);
                    if (System.currentTimeMillis() > deadline) return mineResult(false, "timeout (wrong tool or unbreakable?)", blockId, ticks);
                    pl.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(pos));
                    mc.gameMode.continueDestroyBlock(pos, face);
                    pl.swing(InteractionHand.MAIN_HAND);
                    return null;
                }

                @Override
                public void end(boolean completed) {
                    if (mc.gameMode != null) mc.gameMode.stopDestroyBlock();
                }
            };
        }, (timeout + 5) * 1000L));
    }

    private static JsonObject mineResult(boolean ok, String msg, String block, int ticks) {
        JsonObject o = new JsonObject();
        o.addProperty("success", ok);
        o.addProperty("message", msg);
        o.addProperty("block", block);
        o.addProperty("ticks", ticks);
        return o;
    }

    private static KeyMapping key(Minecraft mc, String k) {
        return switch (k.toLowerCase(Locale.ROOT)) {
            case "forward", "w" -> mc.options.keyUp;
            case "back", "backward", "s" -> mc.options.keyDown;
            case "left", "a" -> mc.options.keyLeft;
            case "right", "d" -> mc.options.keyRight;
            case "jump", "space" -> mc.options.keyJump;
            case "sneak", "shift" -> mc.options.keyShift;
            case "sprint" -> mc.options.keySprint;
            case "attack" -> mc.options.keyAttack;
            case "use" -> mc.options.keyUse;
            default -> throw new ToolException("Unknown key '" + k + "'");
        };
    }

    static ToolResult move(Args a) throws Exception {
        List<String> keys = a.strList("keys");
        if (keys.isEmpty()) throw new ToolException("Give at least one key.");
        int ticks = a.integer("ticks", 20, 1, 600);
        Callable<Ticker.Job<JsonObject>> setup = () -> {
            LocalPlayer p = Ctx.player();
            Ctx.requireUnpaused();
            Minecraft mc = Ctx.mc();
            List<KeyMapping> maps = keys.stream().map(k -> key(mc, k)).toList();
            if (a.has("yaw")) {
                p.setYRot((float) a.dbl("yaw", 0));
                p.yRotO = p.getYRot();
            }
            Vec3 start = p.position();
            maps.forEach(m -> m.setDown(true));
            return new Ticker.Job<>() {
                int left = ticks;

                @Override
                public JsonObject step() {
                    if (--left > 0 && mc.player != null) {
                        maps.forEach(m -> m.setDown(true));
                        return null;
                    }
                    JsonObject o = new JsonObject();
                    if (mc.player != null) {
                        o.add("start", Json.vec(start));
                        o.add("position", Json.vec(mc.player.position()));
                        o.addProperty("distance_moved", Json.round(mc.player.position().distanceTo(start)));
                    }
                    o.addProperty("ticks", ticks);
                    return o;
                }

                @Override
                public void end(boolean completed) {
                    maps.forEach(m -> m.setDown(false));
                }
            };
        };
        if (!a.bool("wait", true)) {
            Ticker.start(setup);
            return ToolResult.ok("holding " + keys + " for " + ticks + " ticks");
        }
        return ToolResult.json(Ticker.run(setup, ticks * 50L + 10_000));
    }
}
