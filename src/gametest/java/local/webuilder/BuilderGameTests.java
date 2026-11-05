package local.webuilder;

import com.mojang.authlib.GameProfile;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.forge.ForgeAdapter;
import com.sk89q.worldedit.history.change.BlockChange;
import com.sk89q.worldedit.history.changeset.ArrayListHistory;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import java.util.UUID;

@GameTestHolder("webuilder")
@PrefixGameTestTemplate(false)
public final class BuilderGameTests {
    static ServerPlayer player(GameTestHelper h, String name) {
        var p = FakePlayerFactory.get(h.getLevel(), new GameProfile(UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8)), name));
        p.setGameMode(GameType.CREATIVE);
        h.getLevel().getServer().getPlayerList().getOps().add(new net.minecraft.server.players.ServerOpListEntry(p.getGameProfile(), 4, false));
        h.assertTrue(ForgeAdapter.adaptPlayer(p).hasPermission("worldedit.setnbt"), "Test actor needs WorldEdit NBT permission");
        ServerController.session(p).clearHistory();
        return p;
    }
    static void ready(EditJob job) throws Exception {
        for (int n = 0; n < 1000 && job.phase != EditJob.Phase.READY; n++) job.tick();
        if (job.phase != EditJob.Phase.READY) throw new AssertionError("Preview did not finish: " + job.phase + ", heap=" + EditJob.freeHeap());
    }
    static void execute(EditJob job) throws Exception {
        job.confirm();
        for (int n = 0; n < 1000 && !job.finished; n++) {
            job.tick();
            if (job.phase == EditJob.Phase.APPLY && job.edit.getChangeSet().size() != 0)
                throw new AssertionError("Execution must not accumulate automatic history");
        }
        if (!job.finished) throw new AssertionError("Edit did not finish");
    }
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void geometryAirOnlyProtectsWaterAndBlocks(GameTestHelper h) throws Exception {
        var p = player(h, "air-only");
        var level = h.getLevel();
        var start = h.absolutePos(new BlockPos(1, 2, 1));
        var material = Blocks.STONE.defaultBlockState();
        for (Mode mode : new Mode[]{Mode.SOLID, Mode.HOLLOW, Mode.FRAME}) {
            level.setBlock(start, Blocks.GOLD_BLOCK.defaultBlockState(), 2);
            level.setBlock(start.east(), Blocks.WATER.defaultBlockState(), 2);
            level.setBlock(start.east(2), Blocks.AIR.defaultBlockState(), 2);
            var owner = new ServerController.State(); owner.mode = mode; owner.geometryAirOnly = true;
            var job = new EditJob(p, owner, start, start.east(2), material); owner.job = job;
            ready(job);
            h.assertTrue(job.planned == 1 && job.preview.size() == 1, "Preview must contain only the empty cell");
            execute(job);
            h.assertTrue(level.getBlockState(start.east(2)).equals(material) && level.getBlockState(start.east()).is(Blocks.WATER)
                && level.getBlockState(start).is(Blocks.GOLD_BLOCK), "Air-only geometry overwrote terrain");
            ServerController.session(p).undo(null, ForgeAdapter.adaptPlayer(p));
            h.assertTrue(level.getBlockState(start.east(2)).isAir(), "Air-only undo failed");
        }
        h.succeed();
    }
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void sameStateContainerIsNeverWritten(GameTestHelper h) throws Exception {
        var p = player(h, "same-chest");
        var level = h.getLevel();
        var pos = h.absolutePos(new BlockPos(2, 2, 2));
        var material = Blocks.CHEST.defaultBlockState();
        level.setBlock(pos, material, 2);
        var chest = (ChestBlockEntity) level.getBlockEntity(pos);
        chest.setItem(4, new ItemStack(Items.DIAMOND, 8));
        for (Mode mode : new Mode[]{Mode.SOLID, Mode.HOLLOW, Mode.FRAME, Mode.REPLACE}) {
            var owner = new ServerController.State(); owner.mode = mode; owner.source = material;
            var job = new EditJob(p, owner, pos, pos, material); owner.job = job;
            ready(job);
            h.assertTrue(job.planned == 0 && job.preview.isEmpty(), "Matching container scheduled for writing");
            execute(job);
            h.assertTrue(level.getBlockEntity(pos) == chest && chest.getItem(4).getCount() == 8, "Container was rewritten");
        }
        h.succeed();
    }
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void realPlacementClearAndExit(GameTestHelper h) throws Exception {
        var p = player(h, "placement");
        var level = h.getLevel();
        var a = h.absolutePos(new BlockPos(1, 2, 1));
        var owner = ServerController.state(p);
        try {
            ServerController.action(p, 0, Mode.CLEAR.ordinal());
            p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(Blocks.STONE, 8));
            for (int i = 0; i <= 2; i += 2) {
                var support = a.east(i).below();
                level.setBlock(support, Blocks.GOLD_BLOCK.defaultBlockState(), 3);
                var hit = new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(support),
                    Direction.UP, support, false);
                p.gameMode.useItemOn(p, level, p.getMainHandItem(), net.minecraft.world.InteractionHand.MAIN_HAND, hit);
                ServerController.tickPlayer(p, owner);
                if (i == 0) h.assertTrue(a.equals(owner.first), "Actual server placement did not select the first point");
            }
            var job = owner.job;
            h.assertTrue(job != null, "Second placement did not start a preview");
            ready(job); execute(job); ServerController.tickPlayer(p, owner);
            h.assertTrue(owner.mode == Mode.NONE && level.getBlockState(a).isAir() && level.getBlockState(a.east(2)).isAir(),
                "Clear must remove both markers and exit");
            ServerController.session(p).undo(null, ForgeAdapter.adaptPlayer(p));
            h.assertTrue(level.getBlockState(a).is(Blocks.STONE) && level.getBlockState(a.east(2)).is(Blocks.STONE), "Marker undo failed");
        } finally { ServerController.STATES.remove(p.getUUID()); }
        h.succeed();
    }
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void doorPlacementAndInteraction(GameTestHelper h) throws Exception {
        var p = player(h, "door-placement");
        var level = h.getLevel();
        var pos = h.absolutePos(new BlockPos(2, 2, 2));
        var hand = net.minecraft.world.InteractionHand.MAIN_HAND;
        var owner = ServerController.state(p);
        try {
            ServerController.action(p, 0, Mode.COPY.ordinal());
            level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), 3);
            p.setItemInHand(hand, new ItemStack(Items.OAK_DOOR));
            var support = new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(pos.below()), Direction.UP, pos.below(), false);
            p.gameMode.useItemOn(p, level, p.getMainHandItem(), hand, support);
            ServerController.tickPlayer(p, owner);
            h.assertTrue(pos.equals(owner.first) && owner.job == null && level.getBlockState(pos.above()).is(Blocks.OAK_DOOR), "Door must select exactly one point");
            p.setItemInHand(hand, new ItemStack(Blocks.STONE));
            var door = new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(pos), Direction.NORTH, pos, false);
            p.gameMode.useItemOn(p, level, p.getMainHandItem(), hand, door);
            ServerController.tickPlayer(p, owner);
            h.assertTrue(level.getBlockState(pos).getValue(net.minecraft.world.level.block.DoorBlock.OPEN) && owner.job == null, "Opening a door should not select a point");
        } finally { ServerController.STATES.remove(p.getUUID()); }
        h.succeed();
    }
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void clearRefreshesInventorySnapshot(GameTestHelper h) throws Exception {
        var p = player(h, "inventory");
        var pos = h.absolutePos(new BlockPos(2, 2, 2));
        var level = h.getLevel();
        level.setBlock(pos, Blocks.CHEST.defaultBlockState(), 3);
        var owner = new ServerController.State(); owner.mode = Mode.CLEAR; owner.continuous = true;
        var job = new EditJob(p, owner, pos, pos, Blocks.STONE.defaultBlockState()); owner.job = job;
        ready(job);
        ((ChestBlockEntity) level.getBlockEntity(pos)).setItem(3, new ItemStack(Items.EMERALD, 11));
        execute(job); ServerController.tickPlayer(p, owner);
        h.assertTrue(owner.mode == Mode.CLEAR && owner.job == null, "Continuous mode lost");
        ServerController.session(p).undo(null, ForgeAdapter.adaptPlayer(p));
        h.assertTrue(((ChestBlockEntity) level.getBlockEntity(pos)).getItem(3).getCount() == 11, "Stale inventory after undo");
        h.succeed();
    }
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void replaceReportsChangedMatch(GameTestHelper h) throws Exception {
        var p = player(h, "match");
        var pos = h.absolutePos(new BlockPos(2, 2, 2));
        var level = h.getLevel();
        level.setBlock(pos, Blocks.STONE.defaultBlockState(), 3);
        var owner = new ServerController.State(); owner.mode = Mode.REPLACE; owner.source = Blocks.STONE.defaultBlockState();
        var job = new EditJob(p, owner, pos, pos.east(), Blocks.GOLD_BLOCK.defaultBlockState()); owner.job = job;
        ready(job); level.setBlock(pos, Blocks.DIRT.defaultBlockState(), 3);
        job.confirm(); ready(job);
        h.assertTrue(job.highlights.contains(pos) && job.planned == 0 && level.getBlockState(pos).is(Blocks.DIRT), "Changed match not identified");
        execute(job); h.succeed();
    }
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void truncatedChestCopiesLatestContents(GameTestHelper h) throws Exception {
        var p = player(h, "partial");
        var pos = h.absolutePos(new BlockPos(2, 2, 2));
        var level = h.getLevel();
        var left = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH)
            .setValue(ChestBlock.TYPE, net.minecraft.world.level.block.state.properties.ChestType.LEFT);
        level.setBlock(pos, left, 2);
        level.setBlock(pos.east(), left.setValue(ChestBlock.TYPE, net.minecraft.world.level.block.state.properties.ChestType.RIGHT), 2);
        var owner = new ServerController.State(); owner.mode = Mode.COPY;
        var job = new EditJob(p, owner, pos, pos, left); owner.job = job;
        ready(job);
        ((ChestBlockEntity) level.getBlockEntity(pos)).setItem(0, new ItemStack(Items.DIAMOND, 6));
        execute(job);
        var clip = ServerController.session(p).getClipboard().getClipboard();
        var cell = EditJob.Snapshot.fromWorldEdit(clip.getFullBlock(ForgeAdapter.adapt(pos)));
        h.assertTrue(clip.getRegion().getVolume() == 1 && cell.nbt().getList("Items", 10).getCompound(0).getByte("Count") == 6,
            "Partial chest or confirmed contents incorrect");
        h.succeed();
    }
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void chestHistoryRoundTrip(GameTestHelper h) throws Exception {
        var level = h.getLevel();
        var p = player(h, "history-test");
        BlockPos pos = h.absolutePos(new BlockPos(2, 2, 2));
        level.setBlock(pos, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.EAST), 3);
        ((ChestBlockEntity) level.getBlockEntity(pos)).setItem(0, new ItemStack(Items.DIAMOND, 7));
        EditJob.Snapshot before = EditJob.Snapshot.read(level, pos);
        ((ChestBlockEntity) level.getBlockEntity(pos)).setItem(0, new ItemStack(Items.DIRT, 3));
        EditSession remembered;
        try (EditSession edit = ServerController.session(p).createEditSession(ForgeAdapter.adaptPlayer(p))) {
            remembered = edit;
            edit.setReorderMode(EditSession.ReorderMode.NONE);
            ArrayListHistory history = (ArrayListHistory) edit.getChangeSet();
            edit.setBlock(ForgeAdapter.adapt(pos), ForgeAdapter.adapt(Blocks.STONE.defaultBlockState()), EditSession.Stage.BEFORE_REORDER);
            edit.flushSession();
            h.assertTrue(level.getBlockState(pos).is(Blocks.STONE), "Test edit did not replace the chest");
            history.setRecordChanges(true);
            history.add(new BlockChange(ForgeAdapter.adapt(pos), before.toWorldEdit(), EditJob.Snapshot.read(level, pos).toWorldEdit()));
            h.assertTrue(history.size() == 1, "Manual history should contain one snapshot, actual=" + history.size());
            h.assertTrue(((BlockChange) history.backwardIterator().next()).getPrevious().getNbtData() != null, "Snapshot record lost NBT before remember");
            ServerController.session(p).remember(edit);
        }
        h.assertTrue(remembered.getChangeSet().size() == 1, "Closing edit changed history count=" + remembered.getChangeSet().size());
        var undone = ServerController.session(p).undo(null, ForgeAdapter.adaptPlayer(p));
        h.assertTrue(undone == remembered, "Undo selected a different edit session");
        var chest = (ChestBlockEntity) level.getBlockEntity(pos);
        h.assertTrue(chest != null && chest.getItem(0).is(Items.DIAMOND) && chest.getItem(0).getCount() == 7, "Chest contents snapshot was not restored");
        h.assertTrue(level.getBlockState(pos).getValue(ChestBlock.FACING) == Direction.EAST, "Chest facing was not restored");
        ServerController.session(p).redo(null, ForgeAdapter.adaptPlayer(p));
        h.assertTrue(level.getBlockState(pos).is(Blocks.STONE), "Redo failed");
        h.succeed();
    }
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void hollowPreservesInteriorAndSharesHistory(GameTestHelper h) throws Exception {
        var p = player(h, "hollow-test");
        BlockPos first = h.absolutePos(new BlockPos(1, 1, 1));
        BlockPos second = first.offset(2, 2, 2);
        h.getLevel().setBlock(first.offset(1, 1, 1), Blocks.GOLD_BLOCK.defaultBlockState(), 3);
        var owner = new ServerController.State(); owner.mode = Mode.HOLLOW;
        EditJob job = new EditJob(p, owner, first, second, Blocks.STONE.defaultBlockState());
        owner.job = job;
        ready(job); execute(job);
        h.assertTrue(h.getLevel().getBlockState(first.offset(1, 1, 1)).is(Blocks.GOLD_BLOCK), "Interior was destroyed");
        h.assertTrue(h.getLevel().getBlockState(first).is(Blocks.STONE), "Shell not placed");
        ServerController.session(p).undo(null, ForgeAdapter.adaptPlayer(p));
        h.assertTrue(h.getLevel().getBlockState(first).isAir(), "Shared undo did not restore air");
        h.assertTrue(h.getLevel().getBlockState(first.offset(1, 1, 1)).is(Blocks.GOLD_BLOCK), "Undo changed preserved interior");
        h.succeed();
    }
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void exactReplacementKeepsSlabState(GameTestHelper h) throws Exception {
        var p = player(h, "replace-test");
        BlockPos a = h.absolutePos(new BlockPos(1, 2, 1));
        BlockPos b = a.offset(1, 0, 0);
        var bottom = Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM);
        var top = bottom.setValue(SlabBlock.TYPE, SlabType.TOP);
        h.getLevel().setBlock(a, bottom, 3);
        h.getLevel().setBlock(b, top, 3);
        var owner = new ServerController.State(); owner.mode = Mode.REPLACE; owner.source = bottom;
        var material = Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP);
        var job = new EditJob(p, owner, a, b, material); owner.job = job;
        ready(job); execute(job);
        h.assertTrue(h.getLevel().getBlockState(a).equals(material), "Target state not preserved");
        h.assertTrue(h.getLevel().getBlockState(b).equals(top), "Exact match incorrectly replaced top slab");
        h.succeed();
    }
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void copyPasteChestDataAndIgnoreAir(GameTestHelper h) throws Exception {
        var p = player(h, "copy-test");
        BlockPos a = h.absolutePos(new BlockPos(1, 2, 1));
        h.getLevel().setBlock(a, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.WEST), 3);
        ((ChestBlockEntity) h.getLevel().getBlockEntity(a)).setItem(3, new ItemStack(Items.EMERALD, 12));
        var owner = new ServerController.State(); owner.mode = Mode.COPY;
        var copy = new EditJob(p, owner, a, a.offset(1, 0, 0), Blocks.STONE.defaultBlockState()); owner.job = copy;
        ready(copy); execute(copy);
        BlockPos target = a.offset(0, 0, 3);
        h.getLevel().setBlock(target, h.getLevel().getBlockState(a), 2);
        ((ChestBlockEntity) h.getLevel().getBlockEntity(target)).setItem(3, new ItemStack(Items.DIRT, 1));
        h.getLevel().setBlock(target.offset(1, 0, 0), Blocks.GOLD_BLOCK.defaultBlockState(), 3);
        owner.mode = Mode.PASTE;
        var paste = EditJob.paste(p, owner, target); owner.job = paste;
        ready(paste); execute(paste);
        ChestBlockEntity chest = (ChestBlockEntity) h.getLevel().getBlockEntity(target);
        h.assertTrue(chest != null && chest.getItem(3).is(Items.EMERALD) && chest.getItem(3).getCount() == 12, "Pasted NBT lost inventory");
        h.assertTrue(h.getLevel().getBlockState(target).getValue(ChestBlock.FACING) == Direction.WEST, "Pasted chest facing wrong");
        h.assertTrue(h.getLevel().getBlockState(target.offset(1, 0, 0)).is(Blocks.GOLD_BLOCK), "Ignore-air paste removed destination block");
        h.succeed();
    }
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void completeBedCopiesAsTwoParts(GameTestHelper h) throws Exception {
        var p = player(h, "bed-test");
        BlockPos foot = h.absolutePos(new BlockPos(1, 2, 1));
        BlockPos head = foot.east();
        var footState = Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.EAST).setValue(BedBlock.PART, BedPart.FOOT);
        var headState = footState.setValue(BedBlock.PART, BedPart.HEAD);
        h.getLevel().setBlock(foot.below(), Blocks.STONE.defaultBlockState(), 3);
        h.getLevel().setBlock(head.below(), Blocks.STONE.defaultBlockState(), 3);
        h.getLevel().setBlock(foot, footState, 2);
        h.getLevel().setBlock(head, headState, 2);
        var owner = new ServerController.State(); owner.mode = Mode.COPY;
        var copy = new EditJob(p, owner, foot, head, headState); owner.job = copy;
        ready(copy); execute(copy);
        BlockPos target = foot.south(3);
        h.getLevel().setBlock(target.below(), Blocks.STONE.defaultBlockState(), 3);
        h.getLevel().setBlock(target.east().below(), Blocks.STONE.defaultBlockState(), 3);
        owner.mode = Mode.PASTE;
        var paste = EditJob.paste(p, owner, target); owner.job = paste;
        ready(paste); execute(paste);
        h.assertTrue(h.getLevel().getBlockState(target).equals(footState), "Bed foot state wrong");
        h.assertTrue(h.getLevel().getBlockState(target.east()).equals(headState), "Bed head state wrong");
        h.assertTrue(h.getLevel().getBlockState(target.east(2)).isAir(), "Paste generated a duplicate bed");
        h.succeed();
    }
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void signDoorAndDoubleChestRoundTrip(GameTestHelper h) throws Exception {
        var p = player(h, "structure-test");
        var level = h.getLevel();
        BlockPos a = h.absolutePos(new BlockPos(1, 2, 1));
        for (int x = 0; x < 4; x++) {
            level.setBlock(a.offset(x, -1, 0), Blocks.STONE.defaultBlockState(), 3);
            level.setBlock(a.offset(x, -1, 3), Blocks.STONE.defaultBlockState(), 3);
        }
        var left = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH)
            .setValue(ChestBlock.TYPE, net.minecraft.world.level.block.state.properties.ChestType.LEFT);
        var right = left.setValue(ChestBlock.TYPE, net.minecraft.world.level.block.state.properties.ChestType.RIGHT);
        level.setBlock(a, left, 2); level.setBlock(a.east(), right, 2);
        ((ChestBlockEntity) level.getBlockEntity(a)).setItem(0, new ItemStack(Items.DIAMOND, 5));
        ((ChestBlockEntity) level.getBlockEntity(a.east())).setItem(0, new ItemStack(Items.EMERALD, 9));
        level.setBlock(a.east(2), Blocks.OAK_SIGN.defaultBlockState(), 3);
        var sign = (net.minecraft.world.level.block.entity.SignBlockEntity) level.getBlockEntity(a.east(2));
        sign.setText(sign.getFrontText().setMessage(0, net.minecraft.network.chat.Component.literal("WorldEdit 数据测试")), true);
        var lower = Blocks.OAK_DOOR.defaultBlockState().setValue(net.minecraft.world.level.block.DoorBlock.FACING, Direction.EAST);
        var upper = lower.setValue(net.minecraft.world.level.block.DoorBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER);
        level.setBlock(a.east(3), lower, 2); level.setBlock(a.east(3).above(), upper, 2);
        var owner = new ServerController.State(); owner.mode = Mode.COPY;
        var copy = new EditJob(p, owner, a, a.offset(3, 1, 0), Blocks.STONE.defaultBlockState()); owner.job = copy;
        ready(copy); execute(copy);
        owner.mode = Mode.PASTE;
        BlockPos target = a.south(3);
        var paste = EditJob.paste(p, owner, target); owner.job = paste;
        ready(paste); execute(paste);
        assertStructure(h, target, left, right, lower, upper);
        ServerController.session(p).undo(null, ForgeAdapter.adaptPlayer(p));
        h.assertTrue(level.getBlockState(target).isAir() && level.getBlockState(target.east(3).above()).isAir(), "Structure undo failed");
        ServerController.session(p).redo(null, ForgeAdapter.adaptPlayer(p));
        assertStructure(h, target, left, right, lower, upper);
        h.succeed();
    }
    static void assertStructure(GameTestHelper h, BlockPos p, net.minecraft.world.level.block.state.BlockState left,
            net.minecraft.world.level.block.state.BlockState right, net.minecraft.world.level.block.state.BlockState lower,
            net.minecraft.world.level.block.state.BlockState upper) {
        var level = h.getLevel();
        h.assertTrue(level.getBlockState(p).equals(left) && level.getBlockState(p.east()).equals(right), "Double chest states changed");
        var first = (ChestBlockEntity) level.getBlockEntity(p);
        var second = (ChestBlockEntity) level.getBlockEntity(p.east());
        h.assertTrue(first.getItem(0).is(Items.DIAMOND) && first.getItem(0).getCount() == 5
            && second.getItem(0).is(Items.EMERALD) && second.getItem(0).getCount() == 9, "Double chest inventories lost");
        var sign = (net.minecraft.world.level.block.entity.SignBlockEntity) level.getBlockEntity(p.east(2));
        h.assertTrue(sign.getFrontText().getMessage(0, false).getString().equals("WorldEdit 数据测试"), "Sign text lost");
        h.assertTrue(level.getBlockState(p.east(3)).equals(lower) && level.getBlockState(p.east(3).above()).equals(upper), "Door halves changed");
    }
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void warningCancelPreservesHistoryAndExecutionClearsIt(GameTestHelper h) throws Exception {
        var p = player(h, "warning-test");
        int previous = BuilderMod.HISTORY_BLOCKS.get();
        BuilderMod.HISTORY_BLOCKS.set(64);
        try {
            BlockPos marker = h.absolutePos(new BlockPos(7, 1, 7));
            try (var edit = ServerController.session(p).createEditSession(ForgeAdapter.adaptPlayer(p))) {
                edit.setBlock(ForgeAdapter.adapt(marker), ForgeAdapter.adapt(Blocks.GOLD_BLOCK.defaultBlockState()));
                edit.flushSession(); ServerController.session(p).remember(edit);
            }
            BlockPos first = h.absolutePos(new BlockPos(1, 1, 1));
            var owner = new ServerController.State(); owner.mode = Mode.SOLID;
            var canceled = new EditJob(p, owner, first, first.offset(4, 4, 4), Blocks.STONE.defaultBlockState()); owner.job = canceled;
            h.assertTrue(canceled.phase == EditJob.Phase.WARNING, "Large edit did not require confirmation");
            canceled.stop(false);
            h.assertTrue(ServerController.session(p).undo(null, ForgeAdapter.adaptPlayer(p)) != null, "Cancel cleared shared history");
            ServerController.session(p).redo(null, ForgeAdapter.adaptPlayer(p));
            var confirmed = new EditJob(p, owner, first, first.offset(4, 4, 4), Blocks.STONE.defaultBlockState()); owner.job = confirmed;
            confirmed.approveWarning(); ready(confirmed); execute(confirmed);
            h.assertTrue(h.getLevel().getBlockState(first).is(Blocks.STONE), "Confirmed edit did not run");
            h.assertTrue(ServerController.session(p).undo(null, ForgeAdapter.adaptPlayer(p)) == null, "No-history edit left shared undo entries");
            h.assertTrue(ServerController.session(p).redo(null, ForgeAdapter.adaptPlayer(p)) == null, "No-history edit left shared redo entries");
            h.succeed();
        } finally { BuilderMod.HISTORY_BLOCKS.set(previous); }
    }
}
