package local.webuilder;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.EmptyClipboardException;
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.forge.ForgeAdapter;
import com.sk89q.worldedit.forge.internal.NBTConverter;
import com.sk89q.worldedit.history.change.BlockChange;
import com.sk89q.worldedit.history.changeset.ArrayListHistory;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.session.ClipboardHolder;
import com.sk89q.worldedit.world.block.BaseBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.*;
import java.util.*;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;

/** Bounded server-thread preparation and execution. WorldEdit owns the final shared history. */
final class EditJob {
    enum Phase { WARNING, PREPARE, READY, VALIDATE, NEIGHBORS, APPLY, HISTORY, DONE }
    final ServerPlayer player;
    final ServerLevel level;
    final ServerController.State owner;
    final Mode mode;
    final BlockPos min;
    final BlockPos max;
    final BlockPos origin;
    final BlockState material;
    final long volume;
    final int width;
    final int depth;
    final boolean includeAir;
    boolean noHistory;
    final Map<BlockPos, Snapshot> before = new LinkedHashMap<>();
    final ListTag preview = new ListTag();
    Phase phase;
    Clipboard clipboard;
    BlockPos pasteOffset;
    EditSession edit;
    Iterator<Map.Entry<BlockPos, Snapshot>> historyIterator;
    Iterator<Map.Entry<BlockPos, Snapshot>> validationIterator;
    long cursor;
    long changed;
    long planned;
    long snapshotBytes;
    long clipboardBytes;
    long signature;
    long validationSignature;
    int lastPercent = -1;
    boolean finished;
    boolean historyCommitted;
    Path planFile;
    PlanFiles planFiles;
    DataOutputStream planOutput;
    DataInputStream planInput;
    long appliedEntries;

    record Snapshot(BlockState state, CompoundTag nbt) {
        static Snapshot read(ServerLevel level, BlockPos pos) {
            BlockEntity entity = level.getBlockEntity(pos);
            return new Snapshot(level.getBlockState(pos), entity == null ? null : entity.saveWithFullMetadata().copy());
        }
        BaseBlock toWorldEdit() {
            var stateValue = ForgeAdapter.adapt(state);
            return nbt == null ? stateValue.toBaseBlock() : stateValue.toBaseBlock(NBTConverter.fromNative(nbt.copy()));
        }
        static Snapshot fromWorldEdit(BaseBlock block) {
            return new Snapshot(ForgeAdapter.adapt(block.toImmutableState()),
                block.getNbtData() == null ? null : NBTConverter.toNative(block.getNbtData()).copy());
        }
        boolean same(Snapshot other) { return state.equals(other.state) && Objects.equals(nbt, other.nbt); }
    }

    EditJob(ServerPlayer player, ServerController.State owner, BlockPos first, BlockPos second, BlockState material) {
        this(player, owner, first, second, material, null, null);
    }
    private EditJob(ServerPlayer player, ServerController.State owner, BlockPos first, BlockPos second,
                    BlockState material, Clipboard clipboard, BlockPos offset) {
        this.player = player;
        if (ServerController.session(player).hasWorldOverride()) {
            throw new IllegalArgumentException("请先清除 WorldEdit 的世界覆盖设置，确保预览与实际编辑处于当前维度。");
        }
        this.level = player.serverLevel();
        this.owner = owner;
        this.mode = owner.mode;
        this.origin = first.immutable();
        this.material = material;
        this.includeAir = owner.includeAir;
        this.clipboard = clipboard;
        this.pasteOffset = offset;
        min = new BlockPos(Math.min(first.getX(), second.getX()), Math.min(first.getY(), second.getY()), Math.min(first.getZ(), second.getZ()));
        max = new BlockPos(Math.max(first.getX(), second.getX()), Math.max(first.getY(), second.getY()), Math.max(first.getZ(), second.getZ()));
        width = max.getX() - min.getX() + 1;
        depth = max.getZ() - min.getZ() + 1;
        volume = RegionMath.volume(width, max.getY() - min.getY() + 1, depth);
        if (volume > BuilderMod.MAX_SCAN.get()) throw new IllegalArgumentException("范围超过扫描容量；可在 webuilder-common.toml 调整，但仍受实际内存限制。");
        if (!level.isInWorldBounds(min) || !level.isInWorldBounds(max)) throw new IllegalArgumentException("选区超出世界高度或边界。");
        if (material != null && isMulti(material) && mode != Mode.COPY && mode != Mode.CUT) {
            throw new IllegalArgumentException("第一版支持床、门复制粘贴；暂不支持以它们批量填充或替换。");
        }
        if (material != null && material.getBlock() instanceof ChestBlock
            && material.getValue(ChestBlock.TYPE) != ChestType.SINGLE && mode != Mode.COPY && mode != Mode.CUT) {
            throw new IllegalArgumentException("第二点已连接为大箱子，请使用单箱作为填充材料，或复制完整大箱子。");
        }
        noHistory = mode != Mode.COPY && volume > BuilderMod.HISTORY_BLOCKS.get();
        if (mode == Mode.COPY || mode == Mode.CUT) checkClipboardCapacity();
        phase = volume > BuilderMod.HISTORY_BLOCKS.get() ? Phase.WARNING : Phase.PREPARE;
        if (phase == Phase.PREPARE) allocateClipboard();
    }
    static EditJob paste(ServerPlayer p, ServerController.State owner, BlockPos anchor) throws EmptyClipboardException {
        ClipboardHolder holder = ServerController.session(p).getClipboard();
        if (!holder.getTransform().isIdentity()) throw new IllegalArgumentException("剪贴板含旋转或镜像变换；第一版暂不支持，请使用未变换的剪贴板。");
        Clipboard clipboard = holder.getClipboard();
        BlockPos offset = anchor.subtract(ForgeAdapter.toBlockPos(clipboard.getOrigin()));
        BlockPos low = ForgeAdapter.toBlockPos(clipboard.getMinimumPoint()).offset(offset);
        BlockPos high = ForgeAdapter.toBlockPos(clipboard.getMaximumPoint()).offset(offset);
        return new EditJob(p, owner, low, high, null, clipboard, offset);
    }
    static long freeHeap() {
        Runtime r = Runtime.getRuntime();
        return r.maxMemory() - (r.totalMemory() - r.freeMemory());
    }
    void checkClipboardCapacity() {
        if (volume > BuilderMod.CLIPBOARD_BLOCKS.get() || volume * 64L > freeHeap() / 4) {
            throw new IllegalArgumentException("剪贴板容量不足。复制/剪切不能通过关闭撤销绕过内存限制，尚未修改世界。");
        }
    }
    void allocateClipboard() {
        if ((mode == Mode.COPY || mode == Mode.CUT) && clipboard == null) {
            checkClipboardCapacity();
            var region = new CuboidRegion(ForgeAdapter.adapt(level), ForgeAdapter.adapt(min), ForgeAdapter.adapt(max));
            clipboard = new BlockArrayClipboard(region);
            clipboard.setOrigin(ForgeAdapter.adapt(origin));
        }
    }
    void approveWarning() {
        if (phase != Phase.WARNING) return;
        allocateClipboard();
        phase = Phase.PREPARE;
        ServerController.message(player, "已同意大范围方案，开始分批检查；尚未清理历史或修改世界。检查后仍需右键确认。");
        ServerController.status(player, owner);
    }
    void confirm() {
        if (phase != Phase.READY) return;
        if (ServerController.busy()) { ServerController.message(player, "另一个任务执行中，请稍后确认。"); return; }
        phase = Phase.VALIDATE;
        cursor = 0;
        validationSignature = 1;
        ServerController.status(player, owner);
    }
    boolean isWriting() { return phase == Phase.APPLY || phase == Phase.HISTORY || phase == Phase.VALIDATE || phase == Phase.NEIGHBORS; }
    BlockPos position(long index) {
        int x = (int) (index % width);
        long rest = index / width;
        return new BlockPos(min.getX() + x, min.getY() + (int) (rest / depth), min.getZ() + (int) (rest % depth));
    }
    boolean contains(BlockPos p) {
        return p.getX() >= min.getX() && p.getX() <= max.getX() && p.getY() >= min.getY() && p.getY() <= max.getY() && p.getZ() >= min.getZ() && p.getZ() <= max.getZ();
    }
    boolean geometric(BlockPos p) {
        return RegionMath.includes(mode, p.getX() - min.getX(), p.getY() - min.getY(), p.getZ() - min.getZ(),
            width, max.getY() - min.getY() + 1, depth);
    }
    Snapshot desired(BlockPos pos, Snapshot existing) {
        if (!geometric(pos)) return null;
        return switch (mode) {
            case COPY -> null;
            case CUT -> new Snapshot(Blocks.AIR.defaultBlockState(), null);
            case PASTE -> {
                var source = ForgeAdapter.adapt(pos.subtract(pasteOffset));
                if (!clipboard.getRegion().contains(source)) yield null;
                Snapshot block = Snapshot.fromWorldEdit(clipboard.getFullBlock(source));
                if (!includeAir && block.state.isAir()) yield null;
                yield block;
            }
            case REPLACE -> existing.state.equals(owner.source) ? new Snapshot(material, null) : null;
            case SOLID, HOLLOW, FRAME -> new Snapshot(material, null);
            default -> null;
        };
    }
    static boolean isMulti(BlockState state) { return state.getBlock() instanceof BedBlock || state.getBlock() instanceof DoorBlock || state.getBlock() instanceof DoublePlantBlock; }
    static BlockPos partner(BlockPos pos, BlockState state) {
        if (state.getBlock() instanceof BedBlock) {
            Direction facing = state.getValue(BedBlock.FACING);
            return pos.relative(state.getValue(BedBlock.PART) == BedPart.FOOT ? facing : facing.getOpposite());
        }
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            return state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER ? pos.above() : pos.below();
        }
        if (state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            return pos.relative(ChestBlock.getConnectedDirection(state));
        }
        return null;
    }
    void validateStructure(BlockPos pos, Snapshot old, Snapshot next) {
        BlockPos oldPartner = partner(pos, old.state);
        if (oldPartner != null && (mode == Mode.COPY || mode == Mode.CUT || next != null)) {
            if (!contains(oldPartner) || !geometric(oldPartner)) throw new IllegalArgumentException("选区仅包含部分床、门、大箱子等结构，请扩大范围：" + pos.toShortString());
            if (mode != Mode.COPY && mode != Mode.CUT && desired(oldPartner, Snapshot.read(level, oldPartner)) == null) {
                throw new IllegalArgumentException("目标多格结构未被完整覆盖，请调整选区或匹配条件。");
            }
        }
        if (mode == Mode.PASTE && next != null) {
            BlockPos other = partner(pos, next.state);
            if (other != null) {
                if (!contains(other)) throw new IllegalArgumentException("剪贴板包含不完整的床、门或大箱子。");
                Snapshot otherNext = desired(other, Snapshot.read(level, other));
                if (otherNext == null || otherNext.state.getBlock() != next.state.getBlock()) {
                    throw new IllegalArgumentException("剪贴板多格结构缺少另一部分。");
                }
            }
        }
    }
    boolean capture(BlockPos pos) {
        if (!level.isInWorldBounds(pos) || before.containsKey(pos)) return true;
        if (!level.hasChunkAt(pos)) throw new IllegalArgumentException("邻接区块未加载，请靠近选区后重试。");
        Snapshot value = Snapshot.read(level, pos);
        before.put(pos.immutable(), value);
        snapshotBytes += 256L + (value.nbt == null ? 0 : value.nbt.toString().length() * 2L);
        if (snapshotBytes > 96L * 1024 * 1024 || freeHeap() < 192L * 1024 * 1024) {
            return false;
        }
        return true;
    }
    long hash(long current, BlockPos pos, Snapshot value) {
        return current * 31 + pos.hashCode() * 17L + value.state.hashCode() + Objects.hashCode(value.nbt);
    }
    void tick() throws Exception {
        if (finished || phase == Phase.WARNING || phase == Phase.READY) return;
        long deadline = System.nanoTime() + 3_000_000;
        int remaining = BuilderMod.BATCH.get();
        if (phase == Phase.HISTORY) { finishHistory(deadline, remaining); return; }
        if (phase == Phase.APPLY) {
            while (appliedEntries < planned && remaining-- > 0 && System.nanoTime() < deadline) {
                BlockPos pos = BlockPos.of(planInput.readLong());
                BlockState state = Block.stateById(planInput.readInt());
                CompoundTag tag = planInput.readBoolean() ? NbtIo.read(planInput) : null;
                if (!level.hasChunkAt(pos)) throw new IllegalStateException("执行范围的区块已卸载，任务中止。");
                Snapshot next = new Snapshot(state, tag);
                // 7.2.15 BlockOptimizedHistory ignores ArrayListHistory.setRecordChanges(false)
                // for block changes. The native stage API skips automatic history entirely.
                // Masks are refused and the change limit is checked against the frozen plan.
                if (edit.setBlock(ForgeAdapter.adapt(pos), next.toWorldEdit(), EditSession.Stage.BEFORE_REORDER)) changed++;
                appliedEntries++;
            }
            if (freeHeap() < 128L * 1024 * 1024) throw new IllegalStateException("内存余量不足，已中止执行。");
            int percent = (int) (appliedEntries * 100 / planned);
            if (percent / 10 != lastPercent / 10) { lastPercent = percent; ServerController.status(player, owner); }
            if (appliedEntries == planned) {
                planInput.close(); planInput = null;
                edit.flushSession();
                phase = Phase.HISTORY;
                historyIterator = before.entrySet().iterator();
                ServerController.status(player, owner);
            }
            return;
        }
        if (phase == Phase.NEIGHBORS) {
            while (validationIterator.hasNext() && remaining-- > 0 && System.nanoTime() < deadline) {
                var entry = validationIterator.next();
                if (!level.hasChunkAt(entry.getKey()) || !entry.getValue().same(Snapshot.read(level, entry.getKey()))) {
                    refresh(); return;
                }
            }
            if (!validationIterator.hasNext()) startWrite();
            return;
        }
        if (cursor == 0 && phase == Phase.PREPARE) {
            signature = 1;
            if (mode != Mode.COPY) {
                planFiles = PlanFiles.create();
                planFile = planFiles.path;
                planOutput = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(planFile)));
            }
        }
        while (cursor < volume && remaining-- > 0 && System.nanoTime() < deadline) {
            BlockPos pos = position(cursor++);
            if (!level.hasChunkAt(pos)) throw new IllegalArgumentException("选区有未加载区块，请靠近或缩小范围。");
            if (!level.getWorldBorder().isWithinBounds(pos)) throw new IllegalArgumentException("选区超出世界边界。");
            Snapshot existing = Snapshot.read(level, pos);
            if (phase == Phase.VALIDATE) {
                validationSignature = hash(validationSignature, pos, existing);
                continue;
            }
            Snapshot next = desired(pos, existing);
            if (phase == Phase.PREPARE) {
                signature = hash(signature, pos, existing);
                validateStructure(pos, existing, next);
                if (mode == Mode.COPY || mode == Mode.CUT) {
                    clipboardBytes += 64L + (existing.nbt == null ? 0 : existing.nbt.toString().length() * 2L);
                    if (clipboardBytes > 128L * 1024 * 1024) throw new IllegalStateException("剪贴板实际数据超过内存预算，复制/剪切已停止，世界尚未修改。");
                    clipboard.setBlock(ForgeAdapter.adapt(pos), existing.toWorldEdit());
                }
                if (next != null && !sameAtPosition(existing, next, pos)) {
                    planned++;
                    // Freeze the intended result before any neighbor updates can change replacement matches.
                    planOutput.writeLong(pos.asLong());
                    planOutput.writeInt(Block.getId(next.state));
                    planOutput.writeBoolean(next.nbt != null);
                    if (next.nbt != null) NbtIo.write(next.nbt, planOutput);
                    if (!noHistory) {
                        if (!capture(pos)) { warnSnapshotCapacity(); return; }
                        for (Direction d : Direction.values()) {
                            if (!capture(pos.relative(d))) { warnSnapshotCapacity(); return; }
                        }
                    }
                    if (preview.size() < 512 && volume <= 8192) {
                        CompoundTag item = new CompoundTag();
                        item.putLong("pos", pos.asLong());
                        item.putInt("state", Block.getId(next.state));
                        preview.add(item);
                    }
                }
            }
        }
        if (freeHeap() < 128L * 1024 * 1024) throw new IllegalStateException("内存余量不足，任务已中止。");
        int percent = (int) (cursor * 100 / volume);
        if (percent / 10 != lastPercent / 10) { lastPercent = percent; ServerController.status(player, owner); }
        if (cursor < volume) return;
        switch (phase) {
            case PREPARE -> {
                if (planOutput != null) { planOutput.close(); planOutput = null; }
                phase = Phase.READY;
                ServerController.message(player, mode == Mode.COPY ? "复制范围已就绪，右键确认，左键取消。" :
                    "预览已就绪，预计修改 " + planned + " 个方块。右键确认，左键取消。" + (noHistory ? "【无法撤销，确认执行将清空共享历史】" : ""));
                if (mode != Mode.COPY && planned > preview.size()) {
                    ServerController.message(player, "本次仅显示 " + preview.size() + " 个位置的详细预览，其余以范围边框和修改数量表示。");
                }
                ServerController.status(player, owner);
            }
            case VALIDATE -> {
                if (signature != validationSignature) { refresh(); return; }
                phase = Phase.NEIGHBORS;
                validationIterator = before.entrySet().iterator();
            }
            default -> { }
        }
    }
    static boolean sameAtPosition(Snapshot old, Snapshot desired, BlockPos pos) {
        if (!old.state.equals(desired.state)) return false;
        if (old.nbt == null && desired.nbt == null) return true;
        if (old.nbt == null || desired.nbt == null) return false;
        CompoundTag target = desired.nbt.copy();
        target.putInt("x", pos.getX()); target.putInt("y", pos.getY()); target.putInt("z", pos.getZ());
        return old.nbt.equals(target);
    }
    void refresh() {
        closePlan();
        before.clear(); preview.clear(); snapshotBytes = 0; clipboardBytes = 0; planned = 0; cursor = 0;
        validationIterator = null;
        phase = Phase.PREPARE;
        ServerController.message(player, "选区或邻接方块已变化，正在刷新预览；请再次确认。");
        ServerController.status(player, owner);
    }
    void warnSnapshotCapacity() {
        closePlan();
        before.clear(); preview.clear(); snapshotBytes = 0; clipboardBytes = 0; planned = 0; cursor = 0;
        noHistory = true;
        phase = Phase.WARNING;
        ServerController.message(player, "快照数据量过大，尚未修改世界。按 ~ 查看无撤销执行及清空共享历史的警告，或左键取消。");
        ServerController.status(player, owner);
    }
    void startWrite() {
        if (mode == Mode.COPY) {
            ServerController.session(player).setClipboard(new ClipboardHolder(clipboard));
            ServerController.message(player, "复制成功，共 " + volume + " 个位置；第一点为粘贴锚点。");
            finish(); return;
        }
        if (planned == 0) { ServerController.message(player, "检查完成，没有需要修改的方块。"); finish(); return; }
        if (mode == Mode.CUT) ServerController.session(player).setClipboard(new ClipboardHolder(clipboard));
        edit = ServerController.session(player).createEditSession(ForgeAdapter.adaptPlayer(player));
        if (edit.getMask() != null) {
            edit.close(); edit = null;
            throw new IllegalStateException("当前 WorldEdit 设置了全局遮罩，请先用 //gmask 清除后重试，避免预览与执行不一致。");
        }
        if (edit.getBlockChangeLimit() >= 0 && planned > edit.getBlockChangeLimit()) {
            edit.close(); edit = null;
            throw new IllegalStateException("预计修改量超过 WorldEdit 的方块修改上限。");
        }
        edit.setReorderMode(EditSession.ReorderMode.NONE);
        if (!(edit.getChangeSet() instanceof ArrayListHistory history)) throw new IllegalStateException("WorldEdit 历史实现已被其他插件替换，无法安全接入。");
        try {
            planInput = new DataInputStream(new BufferedInputStream(Files.newInputStream(planFile)));
        } catch (IOException ex) {
            edit.close(); edit = null;
            throw new IllegalStateException("无法读取执行计划，世界尚未修改。", ex);
        }
        if (noHistory) {
            ServerController.session(player).clearHistory();
            ServerController.message(player, "已按确认清空你的全部 WorldEdit 共享历史（含其他维度和手动操作）。本次无法撤销。");
        }
        phase = Phase.APPLY; cursor = 0; lastPercent = -1;
        ServerController.status(player, owner);
    }
    void finishHistory(long deadline, int budget) {
        if (edit != null && !noHistory) {
            ArrayListHistory history = (ArrayListHistory) edit.getChangeSet();
            history.setRecordChanges(true);
            while (historyIterator.hasNext() && budget-- > 0 && System.nanoTime() < deadline) {
                var entry = historyIterator.next();
                Snapshot now = Snapshot.read(level, entry.getKey());
                if (!entry.getValue().same(now)) history.add(new BlockChange(ForgeAdapter.adapt(entry.getKey()), entry.getValue().toWorldEdit(), now.toWorldEdit()));
            }
            if (historyIterator.hasNext()) return;
            ServerController.session(player).remember(edit);
            historyCommitted = true;
        }
        if (edit != null) { edit.close(); edit = null; }
        ServerController.message(player, "执行完成，修改 " + changed + " 个方块。" + (noHistory ? "本次无法撤销。" : "已加入 WorldEdit 共享历史，可使用 /undo。"));
        finish();
    }
    void stop(boolean report) {
        if (finished) return;
        if (edit != null) {
            edit.flushSession();
            if (!noHistory && !historyCommitted) {
                ArrayListHistory history = (ArrayListHistory) edit.getChangeSet();
                history.setRecordChanges(true);
                if (historyIterator == null) historyIterator = before.entrySet().iterator();
                while (historyIterator.hasNext()) {
                    var entry = historyIterator.next();
                    Snapshot now = Snapshot.read(level, entry.getKey());
                    if (!entry.getValue().same(now)) history.add(new BlockChange(ForgeAdapter.adapt(entry.getKey()), entry.getValue().toWorldEdit(), now.toWorldEdit()));
                }
                ServerController.session(player).remember(edit);
            }
            edit.close(); edit = null;
        }
        if (report && changed > 0) ServerController.message(player, "任务中止，已修改 " + changed + " 个方块。" + (noHistory ? "已完成部分无法撤销。" : "已完成部分已加入共享撤销历史。"));
        finish();
    }
    void finish() {
        finished = true; phase = Phase.DONE;
        closePlan();
        before.clear(); preview.clear(); clipboard = null; historyIterator = null; validationIterator = null;
    }
    void closePlan() {
        try { if (planOutput != null) planOutput.close(); } catch (IOException ignored) { }
        try { if (planInput != null) planInput.close(); } catch (IOException ignored) { }
        planOutput = null; planInput = null;
        if (planFiles != null) {
            try { planFiles.close(); } catch (IOException ignored) { }
            planFiles = null;
            planFile = null;
        }
    }
    void describe(CompoundTag data) {
        data.putString("stage", switch (phase) {
            case WARNING -> "大范围警告：按 ~ 查看并确认";
            case PREPARE -> "准备预览 " + cursor * 100 / volume + "%";
            case READY -> "右键确认 / 左键取消";
            case VALIDATE -> "重新检查 " + cursor * 100 / volume + "%";
            case NEIGHBORS -> "核对邻接结构";
            case APPLY -> "执行 " + appliedEntries * 100 / Math.max(1, planned) + "%（左键中止）";
            case HISTORY -> "整理撤销记录";
            case DONE -> "完成";
        });
        data.putString("phase", phase.name());
        data.putLong("min", min.asLong()); data.putLong("max", max.asLong());
        data.putLong("volume", volume); data.putLong("planned", planned);
        data.putBoolean("noHistory", noHistory);
        if (phase == Phase.READY) data.put("preview", preview.copy());
    }
}
