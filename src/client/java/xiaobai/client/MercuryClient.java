package xiaobai.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import xiaobai.memory.MemoryOptimizer;
import xiaobai.client.hud.MemoryHudOverlay;
import xiaobai.client.command.MercuryClientCommand;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import xiaobai.config.MercuryConfig;

public class MercuryClient implements ClientModInitializer {

	@Override
	public void onInitializeClient() {
		MemoryOptimizer.initializeClient();
		MercuryConfig.load();
		MemoryHudOverlay.register();
		ClientTickEvents.END_CLIENT_TICK.register(client -> MemoryOptimizer.clientTick());
		ClientCommandRegistrationCallback.EVENT.register(new MercuryClientCommand());
	}
}
