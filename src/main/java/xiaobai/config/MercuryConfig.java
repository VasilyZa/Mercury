package xiaobai.config;

import net.fabricmc.loader.api.FabricLoader;
import xiaobai.memory.MemoryOptimizer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class MercuryConfig {
	private static final String FILE_NAME = "mercury.properties";

	private MercuryConfig() {}

	public static void load() {
		Path configDir = FabricLoader.getInstance().getConfigDir();
		Path file = configDir.resolve(FILE_NAME);
		if (!Files.exists(file)) return;

		Properties props = new Properties();
		try (InputStream in = Files.newInputStream(file)) {
			props.load(in);
			apply(props);
		} catch (IOException ignored) {
		}
	}

	public static void save() {
		Path configDir = FabricLoader.getInstance().getConfigDir();
		Path file = configDir.resolve(FILE_NAME);
		Properties props = capture();
		try {
			if (!Files.exists(configDir)) {
				Files.createDirectories(configDir);
			}
			try (OutputStream out = Files.newOutputStream(file)) {
				props.store(out, "Mercury server optimization settings");
			}
		} catch (IOException ignored) {
		}
	}

	private static void apply(Properties p) {
		String ratio = p.getProperty("mercury.lowMemoryRatio");
		if (ratio != null) {
			try {
				MemoryOptimizer.setLowMemoryRatio(Double.parseDouble(ratio));
			} catch (NumberFormatException ignored) {}
		}

		String trimMs = p.getProperty("mercury.minTrimIntervalMillis");
		if (trimMs != null) {
			try {
				MemoryOptimizer.setMinTrimIntervalMillis(Long.parseLong(trimMs));
			} catch (NumberFormatException ignored) {}
		}

		String gcMs = p.getProperty("mercury.minGcIntervalMillis");
		if (gcMs != null) {
			try {
				MemoryOptimizer.setMinGcIntervalMillis(Long.parseLong(gcMs));
			} catch (NumberFormatException ignored) {}
		}

		String enableGc = p.getProperty("mercury.enableExplicitGc");
		if (enableGc != null) {
			MemoryOptimizer.setEnableExplicitGc(Boolean.parseBoolean(enableGc));
		}

		String debug = p.getProperty("mercury.debugLogs");
		if (debug != null) {
			MemoryOptimizer.setDebugLogs(Boolean.parseBoolean(debug));
		}

		String idleSec = p.getProperty("mercury.trimOnIdleSeconds");
		if (idleSec != null) {
			try {
				MemoryOptimizer.setTrimOnIdleSeconds(Long.parseLong(idleSec));
			} catch (NumberFormatException ignored) {}
		}

		String adaptive = p.getProperty("mercury.enableAdaptiveTrendGuard");
		if (adaptive != null) {
			MemoryOptimizer.setEnableAdaptiveTrendGuard(Boolean.parseBoolean(adaptive));
		}

		String trendMb = p.getProperty("mercury.trendGuardMinIncreaseMb");
		if (trendMb != null) {
			try {
				MemoryOptimizer.setTrendGuardMinIncreaseMb(Long.parseLong(trendMb));
			} catch (NumberFormatException ignored) {}
		}

		String trimAllEventLoops = p.getProperty("mercury.nettyTrimAllEventLoops");
		if (trimAllEventLoops != null) {
			MemoryOptimizer.setNettyTrimAllEventLoops(Boolean.parseBoolean(trimAllEventLoops));
		}
	}

	private static Properties capture() {
		Properties p = new Properties();
		p.setProperty("mercury.lowMemoryRatio", Double.toString(MemoryOptimizer.getLowMemoryRatio()));
		p.setProperty("mercury.minTrimIntervalMillis", Long.toString(MemoryOptimizer.getMinTrimIntervalMillis()));
		p.setProperty("mercury.minGcIntervalMillis", Long.toString(MemoryOptimizer.getMinGcIntervalMillis()));
		p.setProperty("mercury.enableExplicitGc", Boolean.toString(MemoryOptimizer.isEnableExplicitGc()));
		p.setProperty("mercury.debugLogs", Boolean.toString(MemoryOptimizer.isDebugLogs()));
		p.setProperty("mercury.trimOnIdleSeconds", Long.toString(MemoryOptimizer.getTrimOnIdleSeconds()));
		p.setProperty("mercury.enableAdaptiveTrendGuard", Boolean.toString(MemoryOptimizer.isEnableAdaptiveTrendGuard()));
		p.setProperty("mercury.trendGuardMinIncreaseMb", Long.toString(MemoryOptimizer.getTrendGuardMinIncreaseMb()));
		p.setProperty("mercury.nettyTrimAllEventLoops", Boolean.toString(MemoryOptimizer.isNettyTrimAllEventLoops()));
		return p;
	}
} 