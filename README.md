# 创世神：交互建造 / WorldEdit Builder

版本 0.1.0，目标 Minecraft 1.20.1、Forge 47.4.18、WorldEdit 7.2.15。

面向创造模式的 WorldEdit 附属。通过轮盘、真实放置选点、预览和鼠标确认执行编辑。客户端与服务端均需安装；单人游戏由内置服务端执行。

要求 WorldEdit 选区、对应操作以及 `worldedit.setnbt` 权限；避免无数据权限时本体自动剥离 NBT。

## 功能

- `~` 轮盘，独立的无、实心立方体、空心立方体、框架、剪切、复制、粘贴、替换模式。
- 服务端确认真实方块放置，两点选区；粘贴为单点锚定。
- 填充使用第二点完整 BlockState；替换精确匹配第一点 BlockState。
- 空心与框架仅修改外壳或边，内部保持原样。
- 复制使用第一点作为原点，与 WorldEdit 共用剪贴板；粘贴默认忽略空气。
- 直接复用 WorldEdit 的状态转换、方块写入、剪贴板、BlockChange 与 LocalSession 历史。
- 操作前保存方块及邻接位置快照，完成后生成一条共享历史；保留方块实体 NBT。
- 分批准备、检查与执行；大操作显式确认无撤销及清空全部共享历史。
- 床、门、双层植物、大箱子完整性检查。常规模组实体数据通过 WorldEdit Forge 适配层保留。
- `/grass` 或 `/webuilder grass` 生成脚下起始方块。
- 快速建造 3.11 可选兼容，附属激活时暂停其模式，返回“无”时恢复。

## 设计

`ServerController` 负责权限、真实放置和任务生命周期。`EditJob` 负责分阶段编辑与共享历史。`ClientController` 负责点击接管、HUD 与预览，`ModeScreen` 提供轮盘。网络只传交互请求和有界预览，世界修改全部在服务端完成。

方块状态与方块实体数据在准备阶段独立捕获；不会以“反向填充指令”实现撤销。WorldEdit 的原生 `/undo`、`/redo` 使用共用历史，次数遵循本体设置。定位方块已放置后的世界作为基准。

写入使用本体 `EditSession.Stage.BEFORE_REORDER` 跳过自动历史，随后把预先深拷贝的快照加入本体优化历史；本体 7.2.15 的 `BlockOptimizedHistory` 不会对方块变更遵循 `setRecordChanges(false)`，因此不能依赖该开关实现无撤销编辑。附属拒绝全局遮罩和世界覆盖，按冻结计划预查本体数量上限。第三方在被跳过的外层编辑阶段安装的扩展需另行适配。

目标写入计划先流式存入临时文件，执行时顺序读取，避免邻接更新改变后续精确替换匹配。正常完成、取消和失败时删除临时计划；创建下一份计划时回收崩溃遗留，文件锁保护仍在使用的计划。计划文件不承担剪贴板或撤销存储。

并未复制快速建造的源代码；兼容桥只调用其公开方法。主要编辑能力复用已有 WorldEdit 实现。

## 构建

在项目目录运行 `build.bat`。初次构建需要网络下载 Forge/Minecraft 开发依赖。默认使用 `C:\Program Files\Java\jdk-21.0.11`，可通过 JAVA_HOME 指定 JDK。输出 Java 17 字节码。

本地依赖 `libs/worldedit-mod-7.2.15.jar` 来自当前游戏实例，仅作为编译及开发运行依赖，不嵌入附属成品。

构建产物位于 `build/libs/worldedit-builder-1.20.1-0.1.0.jar`。安装名称为 `[创世神：交互建造] worldedit-builder-1.20.1-0.1.0.jar`。

`build` 运行独立回归测试。`gradlew.bat runGameTestServer -x downloadAssets` 在项目的 `run-gametest` 隔离世界中运行真实 Forge/WorldEdit 测试。游戏测试代码和结构位于 `src/gametest`，不打入发布 JAR。

## 使用与状态

详见 [用户手册](用户手册.md)、[TODO](TODO.md)、[验证记录](验证记录.md)。本版本需要由用户在游戏中完成首轮交互测试。

## 许可

本项目源码采用 GPL-3.0-or-later，与 WorldEdit 的许可兼容。WorldEdit 属于 EngineHub 及其贡献者。Forge 开发工具、Gradle 及其他构建依赖分别遵循各自许可。
