package xiaobai.client.hud;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

public final class MemoryHudOverlay {
	private static final long UPDATE_INTERVAL_NANOS = 500_000_000L; // 500ms
	private static volatile long lastUpdateNanos = 0L;
	private static volatile String cachedText = "";
	private static volatile int cachedColor = 0xFFFFFF;
	private static volatile boolean enabled = true;
	
	public static void init() {
		HudRenderCallback.EVENT.register(MemoryHudOverlay::render);
	}
	
	public static void register() {
		init();
	}
	
	public static void setEnabled(boolean enable) {
		enabled = enable;
	}
	
	private static void render(DrawContext context, float tickDelta) {
		if (!enabled) return;
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null) return;
		if (client.options.debugEnabled) return;
		
		long currentTime = System.nanoTime();
		if (currentTime - lastUpdateNanos >= UPDATE_INTERVAL_NANOS) {
			updateMemoryInfo();
			lastUpdateNanos = currentTime;
		}
		
		TextRenderer textRenderer = client.textRenderer;
		int margin = 5;
		int x = client.getWindow().getScaledWidth() - textRenderer.getWidth(cachedText) - margin;
		int y = margin;
		
		context.drawTextWithShadow(textRenderer, cachedText, x, y, cachedColor);
	}
	
	private static void updateMemoryInfo() {
		Runtime runtime = Runtime.getRuntime();
		long totalMemory = runtime.totalMemory();
		long freeMemory = runtime.freeMemory();
		long usedMemory = totalMemory - freeMemory;
		long maxMemory = runtime.maxMemory();
		
		double usedMB = usedMemory / (1024.0 * 1024.0);
		double maxMB = maxMemory / (1024.0 * 1024.0);
		double percentage = (usedMB / maxMB) * 100.0;
		
		cachedText = String.format("Memory: %.1f/%.1f MB (%.1f%%)", usedMB, maxMB, percentage);
		
		if (percentage < 70) {
			cachedColor = 0x00FF00;
		} else if (percentage < 90) {
			cachedColor = 0xFFFF00;
		} else {
			cachedColor = 0xFF0000;
		}
	}
}