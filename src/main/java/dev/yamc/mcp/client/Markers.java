package dev.yamc.mcp.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.concurrent.atomic.AtomicInteger;

/** In-world highlight boxes with optional labels, drawn through walls. Client thread only. */
public final class Markers {
    public record Marker(int id, AABB box, int argb, String label, long expiresAt, boolean throughWalls) {}

    private static final List<Marker> MARKERS = new ArrayList<>();
    private static final AtomicInteger IDS = new AtomicInteger();

    private static final RenderType LINES_SEE_THROUGH = RenderType.create("yamcmcp_lines_see_through",
            DefaultVertexFormat.POSITION_COLOR_NORMAL, VertexFormat.Mode.LINES, 1536, false, false,
            RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.RENDERTYPE_LINES_SHADER)
                    .setLineState(new RenderStateShard.LineStateShard(OptionalDouble.of(2.5)))
                    .setLayeringState(RenderStateShard.VIEW_OFFSET_Z_LAYERING)
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .setCullState(RenderStateShard.NO_CULL)
                    .setDepthTestState(RenderStateShard.NO_DEPTH_TEST)
                    .createCompositeState(false));

    public Markers() {}

    public static int add(AABB box, int argb, String label, int seconds, boolean throughWalls) {
        int id = IDS.incrementAndGet();
        long exp = seconds <= 0 ? Long.MAX_VALUE : System.currentTimeMillis() + seconds * 1000L;
        MARKERS.add(new Marker(id, box, argb, label, exp, throughWalls));
        while (MARKERS.size() > 256) MARKERS.remove(0);
        return id;
    }

    public static boolean remove(int id) {
        return MARKERS.removeIf(m -> m.id == id);
    }

    public static int clear() {
        int n = MARKERS.size();
        MARKERS.clear();
        return n;
    }

    public static List<Marker> list() {
        long now = System.currentTimeMillis();
        MARKERS.removeIf(m -> m.expiresAt < now);
        return new ArrayList<>(MARKERS);
    }

    /** Draws the markers; called by {@link ClientEvents#renderLevel} after block entities. */
    static void render(@Nullable PoseStack pose, Camera cam) {
        if (MARKERS.isEmpty()) return;
        long now = System.currentTimeMillis();
        MARKERS.removeIf(m -> m.expiresAt < now);
        Minecraft mc = Minecraft.getInstance();
        Vec3 c = cam.getPosition();
        PoseStack ps = pose != null ? pose : new PoseStack();
        MultiBufferSource.BufferSource buf = mc.renderBuffers().bufferSource();
        ps.pushPose();
        ps.translate(-c.x, -c.y, -c.z);
        for (Marker m : MARKERS) {
            float a = ((m.argb >>> 24) & 0xFF) / 255f, r = ((m.argb >> 16) & 0xFF) / 255f, g = ((m.argb >> 8) & 0xFF) / 255f, b = (m.argb & 0xFF) / 255f;
            VertexConsumer vc = buf.getBuffer(m.throughWalls ? LINES_SEE_THROUGH : RenderType.lines());
            LevelRenderer.renderLineBox(ps, vc, m.box.inflate(0.002), r, g, b, a == 0 ? 1f : a);
        }
        buf.endBatch(LINES_SEE_THROUGH);
        buf.endBatch(RenderType.lines());
        Font font = mc.font;
        for (Marker m : MARKERS) {
            if (m.label == null || m.label.isEmpty()) continue;
            Vec3 top = new Vec3(m.box.getCenter().x, m.box.maxY + 0.5, m.box.getCenter().z);
            double dist = Math.sqrt(top.distanceToSqr(c));
            ps.pushPose();
            ps.translate(top.x, top.y, top.z);
            ps.mulPose(cam.rotation());
            float s = (float) (0.025 * Math.max(1.0, dist / 8.0));
            ps.scale(s, -s, s);
            String text = m.label + " (" + (int) dist + "m)";
            float x = -font.width(text) / 2f;
            font.drawInBatch(text, x, 0, 0xFFFFFFFF, false, ps.last().pose(), buf, Font.DisplayMode.SEE_THROUGH, 0x80000000, LightTexture.FULL_BRIGHT);
            ps.popPose();
        }
        buf.endBatch();
        ps.popPose();
    }
}
