package local.webuilder;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.forge.ForgeAdapter;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import java.util.*;

public final class ServerController {
    static final Map<UUID, State> STATES = new HashMap<>();
    static final class State {
        Mode mode = Mode.NONE;
        final List<BlockPos> controls = new ArrayList<>();
        BlockPos first;
        BlockState source;
        boolean includeAir;
        EditJob job;
        boolean continuous;
        boolean geometryAirOnly;
        final ArrayDeque<net.minecraftforge.event.level.BlockEvent.EntityPlaceEvent> placements = new ArrayDeque<>();
        String dimension = "";
    }
    static State state(ServerPlayer p) { return STATES.computeIfAbsent(p.getUUID(), id -> new State()); }
    static LocalSession session(ServerPlayer p) {
        return WorldEdit.getInstance().getSessionManager().get(ForgeAdapter.adaptPlayer(p));
    }
    static boolean allowed(ServerPlayer p) {
        var actor = ForgeAdapter.adaptPlayer(p);
        return p.isCreative() && actor.hasPermission("worldedit.selection.pos") && actor.hasPermission("worldedit.setnbt");
    }
    static boolean allowedMode(ServerPlayer p, Mode mode) {
        String permission = switch (mode) {
            case SOLID, HOLLOW, FRAME, LINE, CIRCLE, CURVE, CLEAR -> "worldedit.region.set";
            case REPLACE -> "worldedit.region.replace";
            case COPY -> "worldedit.clipboard.copy";
            case CUT -> "worldedit.clipboard.cut";
            case PASTE -> "worldedit.clipboard.paste";
            default -> "worldedit.selection.pos";
        };
        return ForgeAdapter.adaptPlayer(p).hasPermission(permission);
    }
    static void message(ServerPlayer p, String text) { p.sendSystemMessage(Component.literal("[交互建造] " + text)); }
    static void status(ServerPlayer p, State s) {
        CompoundTag data = new CompoundTag();
        data.putInt("mode", s.mode.ordinal());
        data.putBoolean("geometryAirOnly", s.geometryAirOnly);
        data.putBoolean("air", s.includeAir);
        data.putBoolean("continuous", s.continuous);
        data.putString("stage", s.first == null ? "放置第一点" : "放置第二点");
        if (s.mode == Mode.CURVE) data.putString("stage", "已选 " + s.controls.size() + " 点 · 空手潜行右键结束");
        if (s.mode == Mode.PASTE) data.putString("stage", "放置粘贴定位方块");
        if (s.job != null) s.job.describe(data);
        Network.status(p, data);
    }
    static void reset(ServerPlayer p, State s, boolean report) {
        if (s.job != null) s.job.stop(report);
        s.job = null;
        s.placements.clear();
        s.controls.clear();
        s.first = null;
        s.source = null;
        status(p, s);
    }
    public static void action(ServerPlayer p, int action, int value) {
        State s = state(p);
        if (!allowed(p)) {
            reset(p, s, true);
            s.mode = Mode.NONE;
            status(p, s);
            message(p, "需要创造模式、WorldEdit 选区和方块数据权限（worldedit.setnbt），以免内容数据被本体过滤。");
            return;
        }
        try {
            switch (action) {
                case 0 -> {
                    if (value < 0 || value >= Mode.values().length) return;
                    if (!allowedMode(p, Mode.values()[value])) { message(p, "没有该 WorldEdit 操作的权限。"); return; }
                    reset(p, s, true);
                    s.mode = Mode.values()[value];
                    s.dimension = p.level().dimension().location().toString();
                    status(p, s);
                }
                case 8 -> {
                    if (s.mode != Mode.CURVE || s.job != null || !p.isShiftKeyDown()
                        || !p.getMainHandItem().isEmpty() || !allowedMode(p, s.mode)) return;
                    if (s.controls.size() < 2) { message(p, "至少需要两个控制点。"); return; }
                    s.job = new EditJob(p, s, s.controls.get(0), s.controls.get(s.controls.size()-1), s.source);
                    s.controls.clear();
                    status(p, s);
                }
                case 1 -> { if (s.job != null) s.job.confirm(); }
                case 2 -> { reset(p, s, true); }
                case 3 -> {
                    if (s.job != null) { message(p, "先取消当前预览，再修改空气选项。"); return; }
                    s.includeAir = value == 1;
                    status(p, s);
                }
                case 7 -> {
                    if (s.job != null) { message(p, "先取消预览，再调整几何体选项。"); return; }
                    s.geometryAirOnly = value == 1;
                    status(p, s);
                }
                case 6 -> { s.continuous = value == 1; status(p, s); }
                case 5 -> { if (s.job != null) s.job.approveWarning(); }
                default -> { }
            }
        } catch (Exception e) {
            message(p, "操作失败：" + e.getMessage());
            reset(p, s, true);
        }
    }
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onPlacement(net.minecraftforge.event.level.BlockEvent.EntityPlaceEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        State state = STATES.get(player.getUUID());
        if (state == null || state.mode == Mode.NONE || state.job != null) return;
        // Forge emits one EntityMultiPlaceEvent (a subclass) for a door or bed.
        // Defer until placement has committed and every listener has had a chance to cancel.
        if (state.placements.size() < 2) state.placements.add(event);
    }
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void guardInteraction(net.minecraftforge.event.entity.player.PlayerInteractEvent.RightClickBlock event) {
        if (event.getEntity() instanceof ServerPlayer player && hasJob(player)) event.setCanceled(true);
    }
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void guardBreak(net.minecraftforge.event.level.BlockEvent.BreakEvent event) {
        if (event.getPlayer() instanceof ServerPlayer player && hasJob(player)) event.setCanceled(true);
    }
    private static boolean hasJob(ServerPlayer player) {
        State state = STATES.get(player.getUUID());
        return state != null && state.job != null;
    }
    static String measurement(BlockPos a, BlockPos b) {
        int dx = b.getX() - a.getX(), dz = b.getZ() - a.getZ();
        long tenths = Math.floorMod(Math.round(Math.toDegrees(Math.atan2(dx, -dz)) * 10), 3600L);
        String angle = dx == 0 && dz == 0 ? "无水平方向" : String.format(Locale.ROOT, "%.1f°", tenths / 10.0);
        return String.format(Locale.ROOT, "(%s) → (%s) · ΔX %+d ΔY %+d ΔZ %+d · %.2f 格 · 方位 %s",
            a.toShortString(), b.toShortString(), dx, b.getY()-a.getY(), dz, Math.sqrt(a.distSqr(b)), angle);
    }

    static void recordPlacement(ServerPlayer p, State s, BlockPos pos) throws Exception {
        if (s.mode == Mode.NONE) return;
        BlockState after = p.level().getBlockState(pos);
        if (after.isAir()) return;
        if (s.mode == Mode.CURVE) {
            if (!s.controls.isEmpty() && s.controls.get(s.controls.size()-1).equals(pos)) return;
            if (s.controls.size() >= 4096) { message(p, "已达 4096 个控制点，请结束选点。"); return; }
            s.controls.add(pos.immutable());
            s.source = after;
        } else if (s.mode == Mode.PASTE) {
            s.job = EditJob.paste(p, s, pos);
        } else if (s.first == null) {
            s.first = pos;
            s.source = after;
        } else if (s.mode == Mode.MEASURE) {
            CompoundTag guide = new CompoundTag();
            guide.putString("guideDimension", p.level().dimension().location().toString());
            guide.putLong("start", s.first.asLong());
            guide.putLong("end", pos.asLong());
            Network.status(p, guide);
            message(p, measurement(s.first, pos));
            s.first = null; s.source = null;
            if (!s.continuous) s.mode = Mode.NONE;
        } else {
            if (!p.level().hasChunkAt(s.first)) throw new IllegalArgumentException("第一点所在区块已卸载，请重新选点。");
            s.source = p.level().getBlockState(s.first);
            if (s.mode == Mode.REPLACE && s.source.isAir())
                throw new IllegalArgumentException("第一点已变为空气，请重新放置第一点。");
            s.job = new EditJob(p, s, s.first, pos, after);
        }
        status(p, s);
    }
    @SubscribeEvent
    public static void commands(RegisterCommandsEvent e) {
        if (e.getDispatcher().getRoot().getChild("grass") == null) {
            e.getDispatcher().register(Commands.literal("grass").requires(source -> source.hasPermission(2))
                .executes(context -> grass(context.getSource().getPlayerOrException())));
        }
        e.getDispatcher().register(Commands.literal("webuilder")
            .then(Commands.literal("grass").executes(c -> grass(c.getSource().getPlayerOrException())))
            .then(Commands.literal("cancel").executes(c -> { action(c.getSource().getPlayerOrException(), 2, 0); return 1; }))
            .then(Commands.literal("air").then(Commands.argument("enabled", BoolArgumentType.bool())
                .executes(c -> { action(c.getSource().getPlayerOrException(), 3, BoolArgumentType.getBool(c, "enabled") ? 1 : 0); return 1; }))));
    }
    static int grass(ServerPlayer p) {
        if (!allowed(p) || !allowedMode(p, Mode.SOLID)) { message(p, "需要创造模式与 WorldEdit 放置权限。"); return 0; }
        if (session(p).hasWorldOverride()) { message(p, "请先清除 WorldEdit 的世界覆盖设置。"); return 0; }
        State s = state(p);
        if (s.job != null || busy()) { message(p, "请先完成或取消当前任务。"); return 0; }
        BlockPos pos = p.blockPosition().below();
        if (!p.level().isInWorldBounds(pos) || !p.serverLevel().getWorldBorder().isWithinBounds(pos) || !p.level().getBlockState(pos).isAir()) {
            message(p, "脚下位置不是空气或超出世界范围。"); return 0;
        }
        try (var edit = session(p).createEditSession(ForgeAdapter.adaptPlayer(p))) {
            boolean placed = edit.setBlock(ForgeAdapter.adapt(pos), ForgeAdapter.adapt(Blocks.GRASS_BLOCK.defaultBlockState()));
            edit.flushSession();
            session(p).remember(edit);
            if (!placed || !p.level().getBlockState(pos).is(Blocks.GRASS_BLOCK)) {
                message(p, "草方块未能放置，请检查 WorldEdit 遮罩或区域限制。"); return 0;
            }
            message(p, "脚下草方块已生成，可使用 /undo 撤销。");
            return 1;
        } catch (Exception ex) { message(p, "生成失败：" + ex.getMessage()); return 0; }
    }
    static boolean busy() { return STATES.values().stream().anyMatch(s -> s.job != null && s.job.isWriting()); }
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void command(CommandEvent e) {
        if (!busy()) return;
        String input = e.getParseResults().getReader().getString().toLowerCase(Locale.ROOT);
        String command = input.split(" ", 2)[0].replaceFirst("^/+", "");
        if (command.equals("webuilder")) return;
        // During the short write phase, prevent commands from interleaving with the shared history.
        if (e.getParseResults().getContext().getSource().getEntity() instanceof ServerPlayer p) {
            e.setCanceled(true);
            message(p, "建造任务执行中，请稍后执行命令；/webuilder cancel 可中止自己的任务。");
        }
    }
    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        for (var entry : new ArrayList<>(STATES.entrySet())) {
            State s = entry.getValue();

            ServerPlayer p = e.getServer().getPlayerList().getPlayer(entry.getKey());
            if (p == null) continue;
            if (s.mode == Mode.NONE && s.job == null) continue;
            if (!allowed(p) || !allowedMode(p, s.mode) || !s.dimension.isEmpty() && !s.dimension.equals(p.level().dimension().location().toString())) {
                reset(p, s, true); s.mode = Mode.NONE; s.dimension = ""; status(p, s); continue;
            }
            tickPlayer(p, s);
        }
    }
    static void tickPlayer(ServerPlayer p, State s) {
        try {
            while (!s.placements.isEmpty()) {
                var placed = s.placements.removeFirst();
                if (s.job == null && !placed.isCanceled() && placed.getLevel() == p.level())
                    recordPlacement(p, s, placed.getPos());
            }
        } catch (Exception ex) { message(p, "选点失败：" + ex.getMessage()); reset(p, s, false); }
        if (s.job != null) {
            try {
                s.job.tick();
                if (s.job.finished) { if (!s.continuous) s.mode = Mode.NONE; s.job = null; s.first = null; s.source = null; status(p, s); }
            } catch (Exception ex) {
                message(p, "任务失败：" + ex.getMessage());
                reset(p, s, true);
            }
        }
    }
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) {
            State s = STATES.remove(p.getUUID());
            if (s != null && s.job != null) s.job.stop(false);
        }
    }
    @SubscribeEvent public static void stopping(ServerStoppingEvent e) {
        for (State s : STATES.values()) if (s.job != null) s.job.stop(false);
        STATES.clear();
    }
}
