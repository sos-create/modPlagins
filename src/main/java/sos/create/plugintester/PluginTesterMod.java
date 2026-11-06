package sos.create.plugintester;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class PluginTesterMod implements ModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("PluginTester");
    private static final String ROOT = "PluginTester";

    @Override
    public void onInitialize() {
        Path gameDir = FabricLoader.getInstance().getGameDir();
        Path root = gameDir.resolve(ROOT);

        try {
            Files.createDirectories(root.resolve("plugins"));
            Files.createDirectories(root.resolve("ready"));
            Files.createDirectories(root.resolve("reports"));
            Files.createDirectories(root.resolve("server"));

            Path instructions = root.resolve("PUT_PAPER_PLUGINS_HERE.txt");
            if (Files.notExists(instructions)) {
                Files.writeString(instructions,
                    "PluginTester for Minecraft 1.21.4\n\n" +
                    "Put Paper/Bukkit plugin .jar files in the plugins folder.\n" +
                    "IMPORTANT: A Fabric singleplayer world cannot load Paper plugins.\n" +
                    "These folders only organize files; actual testing requires a separate local Paper server.\n");
            }
            LOGGER.info("Plugin Tester folders are ready: {}", root.toAbsolutePath());
        } catch (IOException exception) {
            LOGGER.error("Could not create PluginTester folders in {}", root.toAbsolutePath(), exception);
        }
    }
}
