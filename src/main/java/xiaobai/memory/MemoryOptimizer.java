package xiaobai.memory;

import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.PooledByteBufAllocatorMetric;
import io.netty.buffer.PoolArenaMetric;
import io.netty.channel.EventLoopGroup;
import io.netty.util.concurrent.EventExecutor;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourceType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerNetworkIo;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class MemoryOptimizer {
	private static final Logger LOGGER = LoggerFactory.getLogger("Mercury-Memory");
	private static final AtomicBoolean COMMON_INITIALIZED = new AtomicBoolean(false);
	private static final AtomicBoolean CLIENT_INITIALIZED = new AtomicBoolean(false);

	private static final AtomicLong LAST_TRIM_NANOS = new AtomicLong(0L);
	private static final AtomicLong LAST_GC_NANOS = new AtomicLong(0L);

	// Observability
	private static final AtomicLong TRIM_COUNT = new AtomicLong(0L);
	private static final AtomicLong GC_COUNT = new AtomicLong(0L);
	private static final AtomicLong LAST_TRIM_DURATION_NANOS = new AtomicLong(0L);
	private static volatile String LAST_TRIM_REASON = "";

	// Idle detection
	private static final AtomicLong LAST_SERVER_ACTIVE_NANOS = new AtomicLong(0L);
	private static final AtomicLong LAST_IDLE_TRIM_NANOS = new AtomicLong(0L);

	// Delayed trim
	private static final AtomicLong SCHEDULED_TRIM_AT_NANOS = new AtomicLong(0L);

	// Trend guard
	private static final AtomicLong LAST_TREND_SAMPLE_NANOS = new AtomicLong(0L);
	private static final AtomicLong LAST_TREND_SAMPLE_USED_BYTES = new AtomicLong(0L);

	// Netty event loops discovered
	private static final Set<EventExecutor> KNOWN_EVENT_EXECUTORS = Collections.newSetFromMap(new ConcurrentHashMap<>());

	// Defaults; can be overridden by system properties
	private static volatile double LOW_MEMORY_RATIO = 0.82; // used/max >= 82%
	private static volatile Duration MIN_TRIM_INTERVAL = Duration.ofSeconds(5);
	private static volatile Duration MIN_GC_INTERVAL = Duration.ofSeconds(30);
	private static volatile boolean ENABLE_EXPLICIT_GC = false; // default off to avoid heap expansion side-effects
	private static volatile boolean DEBUG_LOGS = false;
	private static volatile long TRIM_ON_IDLE_SECONDS = 30L; // idle time to trigger a trim
	private static volatile boolean ENABLE_ADAPTIVE_TREND_GUARD = true;
	private static volatile long TREND_GUARD_MIN_INCREASE_BYTES = 16L * 1024L * 1024L; // 16MB
	private static volatile boolean NETTY_TRIM_ALL_EVENT_LOOPS = true;

	private MemoryOptimizer() {}

	public static void initializeCommon() {
		if (!COMMON_INITIALIZED.compareAndSet(false, true)) return;

		configureFromSystemProperties();
		configureNettyAllocatorProperties();

		// Server lifecycle hooks for safe trim points
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			LAST_SERVER_ACTIVE_NANOS.set(System.nanoTime());
			tryCollectServerEventLoops(server);
			safeTrim("server_started");
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			KNOWN_EVENT_EXECUTORS.clear();
			safeTrim("server_stopping");
		});

		// Player disconnect → good moment to trim
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> safeTrim("player_disconnect"));

		// Server data reload → safe to trim after
		ResourceManagerHelper.get(ResourceType.SERVER_DATA).registerReloadListener(new SimpleSynchronousResourceReloadListener() {
			@Override
			public void reload(ResourceManager manager) {
				safeTrim("server_data_reloaded");
				scheduleDelayedTrim(Duration.ofMillis(200));
			}

			@Override
			public Identifier getFabricId() {
				return new Identifier("mercury", "memory_trim_server_data_common");
			}
		});

		// Periodic server tick trimming under pressure + idle detection + delayed trims
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			maybeTrimMemory("server_tick");

			// Discover event loops if not collected yet (lazy)
			if (KNOWN_EVENT_EXECUTORS.isEmpty()) {
				tryCollectServerEventLoops(server);
			}

			// Idle detection
			try {
				boolean hasPlayers = !server.getPlayerManager().getPlayerList().isEmpty();
				long now = System.nanoTime();
				if (hasPlayers) {
					LAST_SERVER_ACTIVE_NANOS.set(now);
				} else {
					long idleNs = TRIM_ON_IDLE_SECONDS > 0 ? Duration.ofSeconds(TRIM_ON_IDLE_SECONDS).toNanos() : Long.MAX_VALUE;
					if (now - LAST_SERVER_ACTIVE_NANOS.get() >= idleNs) {
						long lastIdleTrim = LAST_IDLE_TRIM_NANOS.get();
						if (now - lastIdleTrim >= MIN_TRIM_INTERVAL.toNanos() && LAST_IDLE_TRIM_NANOS.compareAndSet(lastIdleTrim, now)) {
							safeTrim("idle_server");
						}
					}
				}
			} catch (Throwable ignored) {}

			// Delayed trim check
			long scheduledAt = SCHEDULED_TRIM_AT_NANOS.get();
			if (scheduledAt > 0L && System.nanoTime() >= scheduledAt && SCHEDULED_TRIM_AT_NANOS.compareAndSet(scheduledAt, 0L)) {
				safeTrim("delayed_trim");
			}
		});
	}

	public static void initializeClient() {
		if (!CLIENT_INITIALIZED.compareAndSet(false, true)) return;

		// Disable ImageIO disk cache to avoid extra memory mappings and buffers
		try {
			ImageIO.setUseCache(false);
		} catch (Throwable t) {
			if (DEBUG_LOGS) LOGGER.debug("ImageIO.setUseCache(false) failed: {}", t.toString());
		}

		// Client resources reload → trim immediately after to release old assets; also schedule slight delayed trim
		ResourceManagerHelper.get(ResourceType.CLIENT_RESOURCES).registerReloadListener(new SimpleSynchronousResourceReloadListener() {
			@Override
			public void reload(ResourceManager manager) {
				safeTrim("client_resources_reloaded");
				scheduleDelayedTrim(Duration.ofMillis(200));
			}

			@Override
			public Identifier getFabricId() {
				return new Identifier("mercury", "memory_trim_client");
			}
		});
	}

	// Exposed for client tick callback in client source set
	public static void clientTick() {
		maybeTrimMemory("client_tick");

		long scheduledAt = SCHEDULED_TRIM_AT_NANOS.get();
		if (scheduledAt > 0L && System.nanoTime() >= scheduledAt && SCHEDULED_TRIM_AT_NANOS.compareAndSet(scheduledAt, 0L)) {
			safeTrim("delayed_trim_client");
		}
	}

	private static void configureNettyAllocatorProperties() {
		// Apply conservative Netty allocator settings to reduce reserved memory without breaking gameplay
		setPropertyIfAbsent("io.netty.allocator.maxOrder", "8"); // smaller chunk size (~1MB)
		setPropertyIfAbsent("io.netty.allocator.useCacheForAllThreads", "false");
		setPropertyIfAbsent("io.netty.recycler.maxCapacityPerThread", "256");
		setPropertyIfAbsent("io.netty.recycler.linkCapacity", "16");

		// Additional conservative cache bounds and periodic trimming (where supported by Netty version)
		setPropertyIfAbsent("io.netty.allocator.smallCacheSize", "128");
		setPropertyIfAbsent("io.netty.allocator.normalCacheSize", "64");
		setPropertyIfAbsent("io.netty.allocator.maxCachedBufferCapacity", "131072"); // 128 KiB
		// Prefer millis property when available; harmless if ignored
		setPropertyIfAbsent("io.netty.allocator.cacheTrimIntervalMillis", "10000");
	}

	private static void configureFromSystemProperties() {
		LOW_MEMORY_RATIO = getDoubleProperty("mercury.lowMemoryRatio", LOW_MEMORY_RATIO, 0.5, 0.99);
		MIN_TRIM_INTERVAL = getDurationProperty("mercury.minTrimIntervalMillis", MIN_TRIM_INTERVAL);
		MIN_GC_INTERVAL = getDurationProperty("mercury.minGcIntervalMillis", MIN_GC_INTERVAL);
		ENABLE_EXPLICIT_GC = getBooleanProperty("mercury.enableExplicitGc", ENABLE_EXPLICIT_GC);
		DEBUG_LOGS = getBooleanProperty("mercury.debugLogs", DEBUG_LOGS);
		TRIM_ON_IDLE_SECONDS = getLongProperty("mercury.trimOnIdleSeconds", TRIM_ON_IDLE_SECONDS, 0, 86400);
		ENABLE_ADAPTIVE_TREND_GUARD = getBooleanProperty("mercury.enableAdaptiveTrendGuard", ENABLE_ADAPTIVE_TREND_GUARD);
		long trendMb = getLongProperty("mercury.trendGuardMinIncreaseMb", TREND_GUARD_MIN_INCREASE_BYTES / (1024L * 1024L), 1, 8192);
		TREND_GUARD_MIN_INCREASE_BYTES = trendMb * 1024L * 1024L;
		NETTY_TRIM_ALL_EVENT_LOOPS = getBooleanProperty("mercury.nettyTrimAllEventLoops", NETTY_TRIM_ALL_EVENT_LOOPS);
	}

	private static boolean getBooleanProperty(String key, boolean def) {
		try {
			String val = System.getProperty(key);
			if (val == null) return def;
			return Boolean.parseBoolean(val);
		} catch (Throwable ignored) {
			return def;
		}
	}

	private static double getDoubleProperty(String key, double def, double min, double max) {
		try {
			String val = System.getProperty(key);
			if (val == null) return def;
			double parsed = Double.parseDouble(val);
			if (parsed < min || parsed > max) return def;
			return parsed;
		} catch (Throwable ignored) {
			return def;
		}
	}

	private static long getLongProperty(String key, long def, long min, long max) {
		try {
			String val = System.getProperty(key);
			if (val == null) return def;
			long parsed = Long.parseLong(val);
			if (parsed < min || parsed > max) return def;
			return parsed;
		} catch (Throwable ignored) {
			return def;
		}
	}

	private static Duration getDurationProperty(String key, Duration def) {
		try {
			String val = System.getProperty(key);
			if (val == null) return def;
			long millis = Long.parseLong(val);
			if (millis < 100) return def;
			return Duration.ofMillis(millis);
		} catch (Throwable ignored) {
			return def;
		}
	}

	private static void setPropertyIfAbsent(String key, String value) {
		try {
			if (System.getProperty(key) == null) {
				System.setProperty(key, value);
				if (DEBUG_LOGS) LOGGER.debug("Applied system property {}={} (absent)", key, value);
			}
		} catch (SecurityException ignored) {
		}
	}

	private static void maybeTrimMemory(String reason) {
		long now = System.nanoTime();
		long last = LAST_TRIM_NANOS.get();
		if (now - last < MIN_TRIM_INTERVAL.toNanos()) return;

		Runtime rt = Runtime.getRuntime();
		long used = rt.totalMemory() - rt.freeMemory();
		long max = rt.maxMemory();
		if (max <= 0) return;

		double ratio = (double) used / (double) max;

		// Adaptive trend guard: only trigger when memory is growing fast enough
		if (ENABLE_ADAPTIVE_TREND_GUARD) {
			long sampleNow = now;
			long sampleAt = LAST_TREND_SAMPLE_NANOS.get();
			long baseUsed = LAST_TREND_SAMPLE_USED_BYTES.get();
			if (sampleNow - sampleAt >= Math.min(Duration.ofSeconds(5).toNanos(), MIN_TRIM_INTERVAL.toNanos())) {
				LAST_TREND_SAMPLE_USED_BYTES.set(used);
				LAST_TREND_SAMPLE_NANOS.set(sampleNow);
				baseUsed = used; // refresh base instantly
			}
			long increase = used - baseUsed;
			if (ratio >= LOW_MEMORY_RATIO && increase >= TREND_GUARD_MIN_INCREASE_BYTES) {
				if (!LAST_TRIM_NANOS.compareAndSet(last, now)) return;
				if (DEBUG_LOGS) LOGGER.debug("Mercury trim triggered (reason={}, used={}MB, max={}MB, ratio={}, increase={}MB)",
					reason, toMb(used), toMb(max), String.format("%.2f", ratio), toMb(increase));
				safeTrim(reason + "_low_memory:" + String.format("%.2f", ratio));
			}
			return;
		}

		if (ratio >= LOW_MEMORY_RATIO) {
			if (!LAST_TRIM_NANOS.compareAndSet(last, now)) return;
			if (DEBUG_LOGS) LOGGER.debug("Mercury trim triggered (reason={}, used={}MB, max={}MB, ratio={})",
				reason, toMb(used), toMb(max), String.format("%.2f", ratio));
			safeTrim(reason + "_low_memory:" + String.format("%.2f", ratio));
		}
	}

	private static void safeTrim(String reason) {
		long start = System.nanoTime();
		try {
			trimNettyCaches();
		} catch (Throwable t) {
			if (DEBUG_LOGS) LOGGER.debug("Netty cache trim failed ({}): {}", reason, t.toString());
		}

		LAST_TRIM_REASON = reason;
		TRIM_COUNT.incrementAndGet();
		LAST_TRIM_DURATION_NANOS.set(System.nanoTime() - start);

		// Throttle explicit GC; only if opt-in via system property
		if (!ENABLE_EXPLICIT_GC) return;

		long now = System.nanoTime();
		long lastGc = LAST_GC_NANOS.get();
		if (now - lastGc >= MIN_GC_INTERVAL.toNanos() && LAST_GC_NANOS.compareAndSet(lastGc, now)) {
			try {
				System.gc();
				GC_COUNT.incrementAndGet();
				if (DEBUG_LOGS) LOGGER.debug("System.gc() invoked by Mercury (reason: {})", reason);
			} catch (Throwable t) {
				if (DEBUG_LOGS) LOGGER.debug("System.gc() failed ({}): {}", reason, t.toString());
			}
		}
	}

	private static void trimNettyCaches() {
		// Trim allocator caches on the current thread; reduces retained direct/heap buffers
		PooledByteBufAllocator.DEFAULT.trimCurrentThreadCache();

		if (!NETTY_TRIM_ALL_EVENT_LOOPS || KNOWN_EVENT_EXECUTORS.isEmpty()) return;

		for (EventExecutor exec : KNOWN_EVENT_EXECUTORS) {
			try {
				exec.submit(PooledByteBufAllocator.DEFAULT::trimCurrentThreadCache);
			} catch (Throwable ignored) {}
		}
	}

	private static void scheduleDelayedTrim(Duration delay) {
		long when = System.nanoTime() + (delay != null ? delay.toNanos() : 0L);
		SCHEDULED_TRIM_AT_NANOS.set(when);
	}

	private static long toMb(long bytes) {
		return Math.round(bytes / (1024.0 * 1024.0));
	}

	// ===== Runtime controls (for commands / config screen) =====

	public static void setEnableExplicitGc(boolean enable) {
		ENABLE_EXPLICIT_GC = enable;
	}

	public static void setDebugLogs(boolean enable) {
		DEBUG_LOGS = enable;
	}

	public static void setLowMemoryRatio(double ratio) {
		if (ratio >= 0.5 && ratio <= 0.99) {
			LOW_MEMORY_RATIO = ratio;
		}
	}

	public static void setMinTrimIntervalMillis(long millis) {
		if (millis >= 100) {
			MIN_TRIM_INTERVAL = Duration.ofMillis(millis);
		}
	}

	public static void setMinGcIntervalMillis(long millis) {
		if (millis >= 100) {
			MIN_GC_INTERVAL = Duration.ofMillis(millis);
		}
	}

	public static void setTrimOnIdleSeconds(long seconds) {
		if (seconds >= 0 && seconds <= 86400) {
			TRIM_ON_IDLE_SECONDS = seconds;
		}
	}

	public static void setEnableAdaptiveTrendGuard(boolean enable) {
		ENABLE_ADAPTIVE_TREND_GUARD = enable;
	}

	public static void setTrendGuardMinIncreaseMb(long mb) {
		if (mb >= 1 && mb <= 8192) {
			TREND_GUARD_MIN_INCREASE_BYTES = mb * 1024L * 1024L;
		}
	}

	public static void setNettyTrimAllEventLoops(boolean enable) {
		NETTY_TRIM_ALL_EVENT_LOOPS = enable;
	}

	public static String getStatus() {
		Runtime rt = Runtime.getRuntime();
		long used = rt.totalMemory() - rt.freeMemory();
		long total = rt.totalMemory();
		long max = rt.maxMemory();
		double ratio = max > 0 ? (double) used / (double) max : 0.0;

		long activeHeapBytes = -1L;
		long activeDirectBytes = -1L;
		int numHeapArenas = -1;
		int numDirectArenas = -1;
		try {
			PooledByteBufAllocatorMetric m = PooledByteBufAllocator.DEFAULT.metric();
			if (m != null) {
				numHeapArenas = m.numHeapArenas();
				numDirectArenas = m.numDirectArenas();
				long h = 0L;
				for (PoolArenaMetric a : m.heapArenas()) h += a.numActiveBytes();
				long d = 0L;
				for (PoolArenaMetric a : m.directArenas()) d += a.numActiveBytes();
				activeHeapBytes = h;
				activeDirectBytes = d;
			}
		} catch (Throwable ignored) {}

		return String.format(
			"enableExplicitGc=%s, debugLogs=%s, ratioThreshold=%.2f, trimIntervalMs=%d, gcIntervalMs=%d, idleSeconds=%d, adaptiveTrend=%s, trendMinIncreaseMb=%d, nettyTrimAllEventLoops=%s, heapUsed=%dMB, heapTotal=%dMB, heapMax=%dMB, used/Max=%.2f, trims=%d, gcs=%d, lastTrimReason=%s, lastTrimDurationMs=%.2f, netty(heapArenas=%d,directArenas=%d,activeHeapMb=%d,activeDirectMb=%d), eventLoops=%d",
			Boolean.toString(ENABLE_EXPLICIT_GC),
			Boolean.toString(DEBUG_LOGS),
			LOW_MEMORY_RATIO,
			MIN_TRIM_INTERVAL.toMillis(),
			MIN_GC_INTERVAL.toMillis(),
			TRIM_ON_IDLE_SECONDS,
			Boolean.toString(ENABLE_ADAPTIVE_TREND_GUARD),
			TREND_GUARD_MIN_INCREASE_BYTES / (1024L * 1024L),
			Boolean.toString(NETTY_TRIM_ALL_EVENT_LOOPS),
			toMb(used),
			toMb(total),
			toMb(max),
			ratio,
			TRIM_COUNT.get(),
			GC_COUNT.get(),
			LAST_TRIM_REASON,
			LAST_TRIM_DURATION_NANOS.get() / 1_000_000.0,
			numHeapArenas,
			numDirectArenas,
			activeHeapBytes > 0 ? toMb(activeHeapBytes) : -1,
			activeDirectBytes > 0 ? toMb(activeDirectBytes) : -1,
			KNOWN_EVENT_EXECUTORS.size()
		);
	}

	// ===== Exposed getters for config persistence =====
	public static boolean isEnableExplicitGc() {
		return ENABLE_EXPLICIT_GC;
	}

	public static boolean isDebugLogs() {
		return DEBUG_LOGS;
	}

	public static double getLowMemoryRatio() {
		return LOW_MEMORY_RATIO;
	}

	public static long getMinTrimIntervalMillis() {
		return MIN_TRIM_INTERVAL.toMillis();
	}

	public static long getMinGcIntervalMillis() {
		return MIN_GC_INTERVAL.toMillis();
	}

	public static long getTrimOnIdleSeconds() {
		return TRIM_ON_IDLE_SECONDS;
	}

	public static boolean isEnableAdaptiveTrendGuard() {
		return ENABLE_ADAPTIVE_TREND_GUARD;
	}

	public static long getTrendGuardMinIncreaseMb() {
		return TREND_GUARD_MIN_INCREASE_BYTES / (1024L * 1024L);
	}

	public static boolean isNettyTrimAllEventLoops() {
		return NETTY_TRIM_ALL_EVENT_LOOPS;
	}

	public static int getKnownEventLoopsCount() {
		return KNOWN_EVENT_EXECUTORS.size();
	}

	public static void trimNow(boolean withGc, String reason) {
		try {
			trimNettyCaches();
		} catch (Throwable ignored) {}
		if (withGc) {
			try {
				System.gc();
				GC_COUNT.incrementAndGet();
			} catch (Throwable ignored) {}
		}
	}

	// ===== Internal helpers =====
	private static void tryCollectServerEventLoops(MinecraftServer server) {
		try {
			Object networkIo = null;
			// Try direct accessor first
			try {
				Method m = MinecraftServer.class.getDeclaredMethod("getNetworkIo");
				m.setAccessible(true);
				networkIo = m.invoke(server);
			} catch (ReflectiveOperationException ignored) {}

			if (networkIo == null) {
				// Fallback: scan fields of server to find ServerNetworkIo
				for (Field f : MinecraftServer.class.getDeclaredFields()) {
					if (!ServerNetworkIo.class.isAssignableFrom(f.getType())) continue;
					f.setAccessible(true);
					networkIo = f.get(server);
					break;
				}
			}

			if (networkIo == null) return;

			collectEventExecutorsFromNetworkIo(networkIo);
		} catch (Throwable t) {
			if (DEBUG_LOGS) LOGGER.debug("Collect event loops failed: {}", t.toString());
		}
	}

	private static void collectEventExecutorsFromNetworkIo(Object networkIo) {
		try {
			// Find EventLoopGroup fields on ServerNetworkIo (boss/worker groups)
			for (Field f : networkIo.getClass().getDeclaredFields()) {
				if (!EventLoopGroup.class.isAssignableFrom(f.getType())) continue;
				f.setAccessible(true);
				Object groupObj = f.get(networkIo);
				if (groupObj instanceof EventLoopGroup group) {
					for (EventExecutor exec : group) {
						KNOWN_EVENT_EXECUTORS.add(exec);
					}
				}
			}
		} catch (Throwable t) {
			if (DEBUG_LOGS) LOGGER.debug("Reflect EventLoopGroup failed: {}", t.toString());
		}
	}
} 