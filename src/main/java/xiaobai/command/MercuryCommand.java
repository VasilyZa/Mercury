package xiaobai.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import xiaobai.memory.MemoryOptimizer;

public final class MercuryCommand implements CommandRegistrationCallback {
	@Override
	public void register(CommandDispatcher<ServerCommandSource> dispatcher, CommandRegistryAccess registryAccess, net.minecraft.server.command.CommandManager.RegistrationEnvironment environment) {
		LiteralArgumentBuilder<ServerCommandSource> root = CommandManager.literal("mercury")
			.requires(source -> source.hasPermissionLevel(2))
			.then(CommandManager.literal("status").executes(ctx -> {
				String status = MemoryOptimizer.getStatus();
				ctx.getSource().sendFeedback(() -> Text.literal(status), false);
				return 1;
			}))
			.then(CommandManager.literal("debug")
				.then(CommandManager.literal("on").executes(ctx -> toggleDebug(ctx.getSource(), true)))
				.then(CommandManager.literal("off").executes(ctx -> toggleDebug(ctx.getSource(), false))))
			.then(CommandManager.literal("gc")
				.then(CommandManager.literal("on").executes(ctx -> toggleGc(ctx.getSource(), true)))
				.then(CommandManager.literal("off").executes(ctx -> toggleGc(ctx.getSource(), false))))
			.then(CommandManager.literal("trim")
				.executes(ctx -> doTrim(ctx.getSource(), false))
				.then(CommandManager.literal("withGc").executes(ctx -> doTrim(ctx.getSource(), true))))
			.then(CommandManager.literal("set")
				.then(CommandManager.literal("ratio")
					.then(CommandManager.argument("value", DoubleArgumentType.doubleArg(0.5, 0.99))
						.executes(ctx -> setRatio(ctx.getSource(), DoubleArgumentType.getDouble(ctx, "value")))))
				.then(CommandManager.literal("trimIntervalMs")
					.then(CommandManager.argument("value", LongArgumentType.longArg(100))
						.executes(ctx -> setTrimInterval(ctx.getSource(), LongArgumentType.getLong(ctx, "value")))))
				.then(CommandManager.literal("gcIntervalMs")
					.then(CommandManager.argument("value", LongArgumentType.longArg(100))
						.executes(ctx -> setGcInterval(ctx.getSource(), LongArgumentType.getLong(ctx, "value")))))
				.then(CommandManager.literal("idleSeconds")
					.then(CommandManager.argument("value", LongArgumentType.longArg(0, 86400))
						.executes(ctx -> setIdleSeconds(ctx.getSource(), LongArgumentType.getLong(ctx, "value")))))
				.then(CommandManager.literal("adaptiveTrend")
					.then(CommandManager.literal("on").executes(ctx -> setAdaptiveTrend(ctx.getSource(), true)))
					.then(CommandManager.literal("off").executes(ctx -> setAdaptiveTrend(ctx.getSource(), false))))
				.then(CommandManager.literal("trendMinIncreaseMb")
					.then(CommandManager.argument("value", LongArgumentType.longArg(1, 8192))
						.executes(ctx -> setTrendIncreaseMb(ctx.getSource(), LongArgumentType.getLong(ctx, "value")))))
				.then(CommandManager.literal("nettyTrimAllEventLoops")
					.then(CommandManager.literal("on").executes(ctx -> setTrimAllEventLoops(ctx.getSource(), true)))
					.then(CommandManager.literal("off").executes(ctx -> setTrimAllEventLoops(ctx.getSource(), false)))))
			.then(CommandManager.literal("save").executes(ctx -> {
				xiaobai.config.MercuryConfig.save();
				ctx.getSource().sendFeedback(() -> Text.literal("Mercury settings saved to config"), true);
				return 1;
			}))
			.then(CommandManager.literal("reload").executes(ctx -> {
				xiaobai.config.MercuryConfig.load();
				ctx.getSource().sendFeedback(() -> Text.literal("Mercury settings reloaded from config"), true);
				return 1;
			}));

		dispatcher.register(root);
	}

	private int toggleDebug(ServerCommandSource source, boolean enable) {
		MemoryOptimizer.setDebugLogs(enable);
		source.sendFeedback(() -> Text.literal("Mercury debugLogs=" + enable), true);
		return 1;
	}

	private int toggleGc(ServerCommandSource source, boolean enable) {
		MemoryOptimizer.setEnableExplicitGc(enable);
		source.sendFeedback(() -> Text.literal("Mercury enableExplicitGc=" + enable), true);
		return 1;
	}

	private int doTrim(ServerCommandSource source, boolean withGc) {
		MemoryOptimizer.trimNow(withGc, "command");
		source.sendFeedback(() -> Text.literal("Trimmed caches" + (withGc ? " and GC" : "")), true);
		return 1;
	}

	private int setRatio(ServerCommandSource source, double value) {
		MemoryOptimizer.setLowMemoryRatio(value);
		source.sendFeedback(() -> Text.literal("Mercury lowMemoryRatio=" + String.format("%.2f", value)), true);
		return 1;
	}

	private int setTrimInterval(ServerCommandSource source, long ms) {
		MemoryOptimizer.setMinTrimIntervalMillis(ms);
		source.sendFeedback(() -> Text.literal("Mercury minTrimIntervalMs=" + ms), true);
		return 1;
	}

	private int setGcInterval(ServerCommandSource source, long ms) {
		MemoryOptimizer.setMinGcIntervalMillis(ms);
		source.sendFeedback(() -> Text.literal("Mercury minGcIntervalMs=" + ms), true);
		return 1;
	}

	private int setIdleSeconds(ServerCommandSource source, long seconds) {
		MemoryOptimizer.setTrimOnIdleSeconds(seconds);
		source.sendFeedback(() -> Text.literal("Mercury trimOnIdleSeconds=" + seconds), true);
		return 1;
	}

	private int setAdaptiveTrend(ServerCommandSource source, boolean enable) {
		MemoryOptimizer.setEnableAdaptiveTrendGuard(enable);
		source.sendFeedback(() -> Text.literal("Mercury enableAdaptiveTrendGuard=" + enable), true);
		return 1;
	}

	private int setTrendIncreaseMb(ServerCommandSource source, long mb) {
		MemoryOptimizer.setTrendGuardMinIncreaseMb(mb);
		source.sendFeedback(() -> Text.literal("Mercury trendGuardMinIncreaseMb=" + mb), true);
		return 1;
	}

	private int setTrimAllEventLoops(ServerCommandSource source, boolean enable) {
		MemoryOptimizer.setNettyTrimAllEventLoops(enable);
		source.sendFeedback(() -> Text.literal("Mercury nettyTrimAllEventLoops=" + enable + ", knownEventLoops=" + MemoryOptimizer.getKnownEventLoopsCount()), true);
		return 1;
	}
} 