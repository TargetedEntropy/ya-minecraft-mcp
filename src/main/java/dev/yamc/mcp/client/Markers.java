package dev.yamc.mcp.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-world highlight boxes with optional labels, drawn through walls. Client thread only.
 * Rendering changed in almost every 1.21.x release; the version-specific parts are only
 * {@link #seeThroughLines()}, {@link #lines()}, {@link #box} and {@link #cameraPos}.
 */
public final class Markers {
    public record Marker(int id, AABB box, int argb, String label, long expiresAt, boolean throughWalls) {}

    private static final List<Marker> MARKERS = new ArrayList<>();
    private static final AtomicInteger IDS = new AtomicInteger();

    private static RenderType seeThrough;

    private Markers() {}

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

    /** Draws the markers; called by {@link ClientEvents#renderLevel} after (block) entities. */
    static void render(@Nullable PoseStack pose, Camera cam) {
        if (MARKERS.isEmpty()) return;
        long now = System.currentTimeMillis();
        MARKERS.removeIf(m -> m.expiresAt < now);
        if (seeThrough == null) seeThrough = seeThroughLines();
        Minecraft mc = Minecraft.getInstance();
        Vec3 c = cameraPos(cam);
        PoseStack ps = pose != null ? pose : new PoseStack();
        MultiBufferSource.BufferSource buf = mc.renderBuffers().bufferSource();
        ps.pushPose();
        ps.translate(-c.x, -c.y, -c.z);
        for (Marker m : MARKERS) {
            int argb = (m.argb >>> 24) == 0 ? m.argb | 0xFF000000 : m.argb;
            box(ps, buf.getBuffer(m.throughWalls ? seeThrough : lines()), m.box.inflate(0.002), argb);
        }
        buf.endBatch(seeThrough);
        buf.endBatch(lines());
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

    private static Vec3 cameraPos(Camera cam) {
        //? if >=1.21.11 {
        /*return cam.position();
        *///?} else
        return cam.getPosition();
    }

    /** Vanilla depth-tested lines. */
    private static RenderType lines() {
        //? if >=1.21.11 {
        /*return net.minecraft.client.renderer.rendertype.RenderTypes.lines();
        *///?} else
        return RenderType.lines();
    }

    private static void box(PoseStack ps, VertexConsumer vc, AABB box, int argb) {
        float a = ((argb >>> 24) & 0xFF) / 255f, r = ((argb >> 16) & 0xFF) / 255f, g = ((argb >> 8) & 0xFF) / 255f, b = (argb & 0xFF) / 255f;
        //? if >=1.21.11 {
        /*net.minecraft.client.renderer.ShapeRenderer.renderShape(ps, vc, net.minecraft.world.phys.shapes.Shapes.create(box), 0, 0, 0, argb, 2.5f);
        *///?} elif >=1.21.9 {
        /*net.minecraft.client.renderer.ShapeRenderer.renderLineBox(ps.last(), vc, box, r, g, b, a);
        *///?} elif >=1.21.2 {
        /*net.minecraft.client.renderer.ShapeRenderer.renderLineBox(ps, vc, box, r, g, b, a);
        *///?} else
        net.minecraft.client.renderer.LevelRenderer.renderLineBox(ps, vc, box, r, g, b, a);
    }

    /** Lines without depth test, so markers show through walls. */
    private static RenderType seeThroughLines() {
        //? if >=1.21.5 {
        /*// Not registered with RenderPipelines (private on NeoForge): the device compiles it on first use.
        var pipeline = com.mojang.blaze3d.pipeline.RenderPipeline.builder(net.minecraft.client.renderer.RenderPipelines.LINES_SNIPPET)
                .withLocation(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("yamcmcp", "pipeline/lines_see_through"))
                .withDepthTestFunction(com.mojang.blaze3d.platform.DepthTestFunction.NO_DEPTH_TEST)
                .withDepthWrite(false)
                .build();
        //? if >=1.21.11 {
        /^return RenderType.create("yamcmcp_lines_see_through", net.minecraft.client.renderer.rendertype.RenderSetup.builder(pipeline)
                .setLayeringTransform(net.minecraft.client.renderer.rendertype.LayeringTransform.VIEW_OFFSET_Z_LAYERING)
                .createRenderSetup());
        ^///?} else {
        return RenderType.create("yamcmcp_lines_see_through", 1536, false, false, pipeline,
                RenderType.CompositeState.builder()
                        .setLineState(new net.minecraft.client.renderer.RenderStateShard.LineStateShard(OptionalDouble.of(2.5)))
                        .setLayeringState(net.minecraft.client.renderer.RenderStateShard.VIEW_OFFSET_Z_LAYERING)
                        .createCompositeState(false));
        //?}
        *///?} else
        return Legacy.seeThroughLines();
    }

    //? if <1.21.5 {
    // Before 1.21.5 the render state shards are protected: a subclass reaches them without an access transformer.
    private abstract static class Legacy extends RenderType {
        private Legacy() {
            super(null, null, null, 0, false, false, null, null);
        }

        static RenderType seeThroughLines() {
            return create("yamcmcp_lines_see_through",
                    com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR_NORMAL, com.mojang.blaze3d.vertex.VertexFormat.Mode.LINES, 1536, false, false,
                    CompositeState.builder()
                            .setShaderState(RENDERTYPE_LINES_SHADER)
                            .setLineState(new LineStateShard(OptionalDouble.of(2.5)))
                            .setLayeringState(VIEW_OFFSET_Z_LAYERING)
                            .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                            .setWriteMaskState(COLOR_WRITE)
                            .setCullState(NO_CULL)
                            .setDepthTestState(NO_DEPTH_TEST)
                            .createCompositeState(false));
        }
    }
    //?}
}
