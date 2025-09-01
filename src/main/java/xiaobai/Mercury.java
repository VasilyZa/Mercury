package xiaobai;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import xiaobai.command.MercuryCommand;
import xiaobai.memory.MemoryOptimizer;
import xiaobai.config.MercuryConfig;

public class Mercury implements ModInitializer {

    @Override
    public void onInitialize() {
        MemoryOptimizer.initializeCommon();
        MercuryConfig.load();
        CommandRegistrationCallback.EVENT.register(new MercuryCommand());
    }
}
