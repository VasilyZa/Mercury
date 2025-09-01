package xiaobai.client.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.text.Text;
import xiaobai.memory.MemoryOptimizer;
import xiaobai.client.hud.MemoryHudOverlay;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public final class MercuryClientCommand implements ClientCommandRegistrationCallback {
	@Override
	public void register(CommandDispatcher<FabricClientCommandSource> dispatcher, CommandRegistryAccess registryAccess) {
		// Register primary client command as "/mercury"
		dispatcher.register(buildRoot("mercury"));
		// Keep old "/mercuryclient" as a compatibility alias
		dispatcher.register(buildRoot("mercuryclient"));
	}

	private LiteralArgumentBuilder<FabricClientCommandSource> buildRoot(String rootLiteral) {
		return literal(rootLiteral)
			.then(literal("status").executes(ctx -> {
				String status = MemoryOptimizer.getStatus();
				sendClientMessage(ctx.getSource(), status);
				return 1;
			}))
			.then(literal("debug")
				.then(literal("on").executes(ctx -> toggleDebug(ctx.getSource(), true)))
				.then(literal("off").executes(ctx -> toggleDebug(ctx.getSource(), false))))
			.then(literal("gc")
				.then(literal("on").executes(ctx -> toggleGc(ctx.getSource(), true)))
				.then(literal("off").executes(ctx -> toggleGc(ctx.getSource(), false))))
			.then(literal("trim")
				.executes(ctx -> doTrim(ctx.getSource(), false))
				.then(literal("withGc").executes(ctx -> doTrim(ctx.getSource(), true))))
			.then(literal("set")
				.then(literal("ratio")
					.then(argument("value", DoubleArgumentType.doubleArg(0.5, 0.99))
						.executes(ctx -> setRatio(ctx.getSource(), DoubleArgumentType.getDouble(ctx, "value")))))
				.then(literal("trimIntervalMs")
					.then(argument("value", LongArgumentType.longArg(100))
						.executes(ctx -> setTrimInterval(ctx.getSource(), LongArgumentType.getLong(ctx, "value")))))
				.then(literal("gcIntervalMs")
					.then(argument("value", LongArgumentType.longArg(100))
						.executes(ctx -> setGcInterval(ctx.getSource(), LongArgumentType.getLong(ctx, "value")))))
			)
			.then(literal("hud")
				.then(literal("on").executes(ctx -> setHud(ctx.getSource(), true)))
				.then(literal("off").executes(ctx -> setHud(ctx.getSource(), false))))
		;
	}

	private static void sendClientMessage(FabricClientCommandSource source, String message) {
		ClientPlayerEntity player = source.getPlayer();
		if (player != null) {
			player.sendMessage(Text.literal(message), false);
		}
	}

	private static int toggleDebug(FabricClientCommandSource source, boolean enable) {
		MemoryOptimizer.setDebugLogs(enable);
		sendClientMessage(source, "Mercury debugLogs=" + enable);
		return 1;
	}

	private static int toggleGc(FabricClientCommandSource source, boolean enable) {
		MemoryOptimizer.setEnableExplicitGc(enable);
		sendClientMessage(source, "Mercury enableExplicitGc=" + enable);
		return 1;
	}

	private static int doTrim(FabricClientCommandSource source, boolean withGc) {
		MemoryOptimizer.trimNow(withGc, "client_command");
		sendClientMessage(source, "Trimmed caches" + (withGc ? " and GC" : ""));
		return 1;
	}

	private static int setRatio(FabricClientCommandSource source, double value) {
		MemoryOptimizer.setLowMemoryRatio(value);
		sendClientMessage(source, "Mercury lowMemoryRatio=" + String.format("%.2f", value));
		return 1;
	}

	private static int setTrimInterval(FabricClientCommandSource source, long ms) {
		MemoryOptimizer.setMinTrimIntervalMillis(ms);
		sendClientMessage(source, "Mercury minTrimIntervalMs=" + ms);
		return 1;
	}

	private static int setGcInterval(FabricClientCommandSource source, long ms) {
		MemoryOptimizer.setMinGcIntervalMillis(ms);
		sendClientMessage(source, "Mercury minGcIntervalMs=" + ms);
		return 1;
	}

	private static int setHud(FabricClientCommandSource source, boolean enable) {
		MemoryHudOverlay.setEnabled(enable);
		sendClientMessage(source, "Mercury HUD " + (enable ? "enabled" : "disabled"));
		return 1;
	}
} 