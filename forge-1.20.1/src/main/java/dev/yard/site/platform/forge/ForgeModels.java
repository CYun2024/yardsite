package dev.yard.site.platform.forge;

import com.mojang.blaze3d.platform.NativeImage;
import dev.yard.site.platform.ModelAccess;
import dev.yard.site.platform.ModelQuad;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Forge 1.20.1 的模型读取：BakedModel#getQuads 还是三参数，没有 ModelData。 */
public final class ForgeModels implements ModelAccess {

    public static final ForgeModels INSTANCE = new ForgeModels();

    private static final Direction[] SIDES = {
            null, Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
    };
    private static final Map<ResourceLocation, Integer> SPRITE_COLORS = new ConcurrentHashMap<>();

    private ForgeModels() {}

    @Override
    public List<ModelQuad> quads(BlockState state) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getModelManager() == null) return List.of();
        BakedModel model = minecraft.getModelManager().getBlockModelShaper().getBlockModel(state);
        if (model == null || model == minecraft.getModelManager().getMissingModel() || model.isCustomRenderer()) {
            return List.of();
        }
        List<ModelQuad> pieces = new ArrayList<>();
        RandomSource random = RandomSource.create(42L);
        for (Direction side : SIDES) {
            List<BakedQuad> quads;
            try {
                quads = model.getQuads(state, side, random);
            } catch (RuntimeException | LinkageError broken) {
                return List.of();
            }
            if (quads == null) continue;
            for (BakedQuad quad : quads) {
                ModelQuad piece = piece(quad);
                if (piece != null) pieces.add(piece);
            }
        }
        return pieces;
    }

    @Override
    public void invalidate() {
        SPRITE_COLORS.clear();
    }

    @Override
    public int cachedSprites() {
        return SPRITE_COLORS.size();
    }

    private static ModelQuad piece(BakedQuad quad) {
        int[] data = quad.getVertices();
        if (data.length < 32) return null;
        float[] corners = new float[12];
        for (int v = 0; v < 4; v++) {
            corners[v * 3] = Float.intBitsToFloat(data[v * 8]);
            corners[v * 3 + 1] = Float.intBitsToFloat(data[v * 8 + 1]);
            corners[v * 3 + 2] = Float.intBitsToFloat(data[v * 8 + 2]);
        }
        return new ModelQuad(corners, spriteColor(quad.getSprite()), quad.isTinted());
    }

    private static int spriteColor(TextureAtlasSprite sprite) {
        if (sprite == null || sprite.contents() == null) return 0;
        ResourceLocation key = sprite.contents().name();
        Integer cached = SPRITE_COLORS.get(key);
        if (cached != null) return cached;
        int rgb = sample(sprite);
        SPRITE_COLORS.put(key, rgb);
        return rgb;
    }

    private static int sample(TextureAtlasSprite sprite) {
        try {
            NativeImage image = sprite.contents().getOriginalImage();
            if (image == null) return 0;
            int width = image.getWidth();
            int height = image.getHeight();
            long r = 0;
            long g = 0;
            long b = 0;
            long weight = 0;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int pixel = image.getPixelRGBA(x, y);
                    int alpha = (pixel >>> 24) & 255;
                    if (alpha < 8) continue;
                    // NativeImage 的 RGBA 是 ABGR 打包：低 8 位才是红
                    r += (pixel & 255) * (long) alpha;
                    g += ((pixel >>> 8) & 255) * (long) alpha;
                    b += ((pixel >>> 16) & 255) * (long) alpha;
                    weight += alpha;
                }
            }
            if (weight == 0) return 0;
            int cr = (int) (r / weight);
            int cg = (int) (g / weight);
            int cb = (int) (b / weight);
            return (cr << 16) | (cg << 8) | cb;
        } catch (RuntimeException broken) {
            return 0;
        }
    }
}