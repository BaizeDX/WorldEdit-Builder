package local.webuilder;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

final class TreeSearch {
    static final TagKey<Block> EXTRA = TagKey.create(Registries.BLOCK,new ResourceLocation("webuilder","fellable"));
    final LongOpenHashSet visited = new LongOpenHashSet();
    final LongArrayList cells = new LongArrayList();
    final LongArrayList foliage = new LongArrayList(), trunks = new LongArrayList();
    BlockPos min, max;
    int head;
    boolean truncated;
    TreeSearch(BlockPos seed, ServerLevel level) {
        min=max=seed;
        if (level.hasChunkAt(seed) && matches(level.getBlockState(seed))) add(seed,level.getBlockState(seed));
    }
    static boolean matches(BlockState s) { return s.is(BlockTags.LOGS) || s.is(BlockTags.LEAVES) || s.is(EXTRA); }
    long position(int i) {
        return i < foliage.size() ? foliage.getLong(i) : trunks.getLong(i - foliage.size());
    }
    private void add(BlockPos p,BlockState state) {
        (state.is(BlockTags.LEAVES) ? foliage : trunks).add(p.asLong());
        visited.add(p.asLong()); cells.add(p.asLong());
        min=new BlockPos(Math.min(min.getX(),p.getX()), Math.min(min.getY(),p.getY()), Math.min(min.getZ(),p.getZ()));
        max=new BlockPos(Math.max(max.getX(),p.getX()), Math.max(max.getY(),p.getY()), Math.max(max.getZ(),p.getZ()));
    }
    void expand(ServerLevel level) {
        BlockPos center=BlockPos.of(cells.getLong(head++));
        for (int x=-1;x<=1;x++) for (int y=-1;y<=1;y++) for (int z=-1;z<=1;z++) {
            BlockPos p=center.offset(x,y,z);
            if (visited.contains(p.asLong()) || !level.isInWorldBounds(p)) continue;
            if (!level.hasChunkAt(p) || !level.getWorldBorder().isWithinBounds(p)) { truncated=true; continue; }
            var state=level.getBlockState(p);
            if (matches(state)) add(p,state);
        }
    }
}
