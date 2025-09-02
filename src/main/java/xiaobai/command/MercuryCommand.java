package xiaobai.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import xiaobai.memory.MemoryOptimizer;
import xiaobai.memory.ShenandoahTuner;

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
					.then(CommandManager.literal("off").executes(ctx -> setTrimAllEventLoops(ctx.getSource(), false))))
				.then(CommandManager.literal("includeDirectInRatio")
					.then(CommandManager.literal("on").executes(ctx -> setIncludeDirect(ctx.getSource(), true)))
					.then(CommandManager.literal("off").executes(ctx -> setIncludeDirect(ctx.getSource(), false))))
				.then(CommandManager.literal("hysteresisMargin")
					.then(CommandManager.argument("value", DoubleArgumentType.doubleArg(0.0, 0.5))
						.executes(ctx -> setHysteresis(ctx.getSource(), DoubleArgumentType.getDouble(ctx, "value")))))
				.then(CommandManager.literal("autoDegrade")
					.executes(ctx -> autoDegradeDisabled(ctx.getSource()))))
			.then(CommandManager.literal("shenandoah")
				.executes(ctx -> {
					ctx.getSource().sendFeedback(() -> Text.literal("GC: " + ShenandoahTuner.buildSummary()), false);
					return 1;
				})
				.then(CommandManager.literal("explicitGcConcurrent")
					.then(CommandManager.literal("on").executes(ctx -> setShenExplicitConcurrent(ctx.getSource(), true)))
					.then(CommandManager.literal("off").executes(ctx -> setShenExplicitConcurrent(ctx.getSource(), false))))
				.then(CommandManager.literal("enableUncommit")
					.then(CommandManager.literal("on").executes(ctx -> setShenUncommit(ctx.getSource(), true)))
					.then(CommandManager.literal("off").executes(ctx -> setShenUncommit(ctx.getSource(), false))))
				.then(CommandManager.literal("uncommitDelayMs")
					.then(CommandManager.argument("value", LongArgumentType.longArg(0))
						.executes(ctx -> setShenUncommitDelay(ctx.getSource(), LongArgumentType.getLong(ctx, "value")))))
				.then(CommandManager.literal("heuristics")
					.then(CommandManager.literal("adaptive").executes(ctx -> setShenHeuristics(ctx.getSource(), "adaptive")))
					.then(CommandManager.literal("static").executes(ctx -> setShenHeuristics(ctx.getSource(), "static")))
					.then(CommandManager.literal("compact").executes(ctx -> setShenHeuristics(ctx.getSource(), "compact")))
					.then(CommandManager.literal("aggressive").executes(ctx -> setShenHeuristics(ctx.getSource(), "aggressive"))))
				.then(CommandManager.literal("garbageThreshold")
					.then(CommandManager.argument("percent", IntegerArgumentType.integer(0, 100))
						.executes(ctx -> setShenGarbageThreshold(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "percent")))))
				.then(CommandManager.literal("guaranteedGcIntervalMs")
					.then(CommandManager.argument("value", LongArgumentType.longArg(0))
						.executes(ctx -> setShenGuaranteedInterval(ctx.getSource(), LongArgumentType.getLong(ctx, "value"))))))
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

	private int setIncludeDirect(ServerCommandSource source, boolean enable) {
		MemoryOptimizer.setIncludeDirectInRatio(enable);
		source.sendFeedback(() -> Text.literal("Mercury includeDirectInRatio=" + enable), true);
		return 1;
	}

	private int setHysteresis(ServerCommandSource source, double value) {
		MemoryOptimizer.setHysteresisMargin(value);
		source.sendFeedback(() -> Text.literal("Mercury hysteresisMargin=" + String.format("%.2f", value)), true);
		return 1;
	}

	private int autoDegradeDisabled(ServerCommandSource source) {
		source.sendFeedback(() -> Text.literal("Auto-degrade is disabled in this build."), false);
		return 1;
	}

	// ===== Shenandoah commands =====
	private int setShenExplicitConcurrent(ServerCommandSource source, boolean enable) {
		boolean ok = ShenandoahTuner.setExplicitGcInvokesConcurrent(enable);
		source.sendFeedback(() -> Text.literal("Shenandoah ExplicitGCInvokesConcurrent=" + enable + (ok ? " (applied)" : " (unsupported)")), true);
		return 1;
	}

	private int setShenUncommit(ServerCommandSource source, boolean enable) {
		boolean ok = ShenandoahTuner.setUncommitEnabled(enable);
		source.sendFeedback(() -> Text.literal("ShenandoahUncommit=" + enable + (ok ? " (applied)" : " (unsupported)")), true);
		return 1;
	}

	private int setShenUncommitDelay(ServerCommandSource source, long ms) {
		boolean ok = ShenandoahTuner.setUncommitDelayMs(ms);
		source.sendFeedback(() -> Text.literal("ShenandoahUncommitDelay=" + ms + (ok ? " (applied)" : " (unsupported)")), true);
		return 1;
	}

	private int setShenHeuristics(ServerCommandSource source, String mode) {
		boolean ok = ShenandoahTuner.setHeuristics(mode);
		source.sendFeedback(() -> Text.literal("ShenandoahGCHeuristics=" + mode + (ok ? " (applied)" : " (unsupported)")), true);
		return 1;
	}

	private int setShenGarbageThreshold(ServerCommandSource source, int percent) {
		boolean ok = ShenandoahTuner.setGarbageThreshold(percent);
		source.sendFeedback(() -> Text.literal("ShenandoahGarbageThreshold=" + percent + (ok ? " (applied)" : " (unsupported)")), true);
		return 1;
	}

	private int setShenGuaranteedInterval(ServerCommandSource source, long ms) {
		boolean ok = ShenandoahTuner.setGuaranteedGcIntervalMs(ms);
		source.sendFeedback(() -> Text.literal("ShenandoahGuaranteedGCInterval=" + ms + (ok ? " (applied)" : " (unsupported)")), true);
		return 1;
	}
} 