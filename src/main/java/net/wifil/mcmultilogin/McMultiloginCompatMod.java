package net.wifil.mcmultilogin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.wifil.mcmultilogin.api.LoginApiClient;
import net.wifil.mcmultilogin.config.ModConfig;
import net.wifil.mcmultilogin.tracking.PlayerNameTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;

public class McMultiloginCompatMod implements ModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("mc-multilogin-compat");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * Stores the detailed error message for a pending disconnected login,
     * keyed by the player's requested username.
     * Populated by {@code YggdrasilSessionServiceMixin} and consumed by
     * {@code ServerLoginPacketListenerMixin}.
     */
    public static final ConcurrentHashMap<String, String> PENDING_ERRORS = new ConcurrentHashMap<>();

    private static ModConfig config;
    private static LoginApiClient apiClient;
    private static PlayerNameTracker nameTracker;

    @Override
    public void onInitialize() {
        Path configPath = FabricLoader.getInstance().getConfigDir()
                .resolve("mc-multilogin-compat.json");

        config = loadOrCreateConfig(configPath);

        String url = config.getApiUrl();
        if (url == null || url.isBlank()) {
            LOGGER.error("[MultiLogin] =========================================================");
            LOGGER.error("[MultiLogin] 'apiUrl' is not configured in mc-multilogin-compat.json!");
            LOGGER.error("[MultiLogin] Please set it to the base URL of your MC-MultiLogin-service");
            LOGGER.error("[MultiLogin] instance (e.g. \"http://127.0.0.1:25600/login/my\").");
            LOGGER.error("[MultiLogin] A default config file has been written to:");
            LOGGER.error("[MultiLogin]   {}", configPath.toAbsolutePath());
            LOGGER.error("[MultiLogin] =========================================================");
            throw new IllegalStateException(
                    "[mc-multilogin-compat] apiUrl is not configured. "
                    + "Please edit config/mc-multilogin-compat.json and restart.");
        }

        apiClient = new LoginApiClient(url);
        nameTracker = new PlayerNameTracker();

        LOGGER.info("[MultiLogin] Initialised. API endpoint: {}", url);
        LOGGER.info("[MultiLogin] Auto-rename on duplicate name: {}", config.isAutoRename());
    }

    // -------------------------------------------------------------------------

    public static ModConfig getConfig() {
        return config;
    }

    public static LoginApiClient getApiClient() {
        return apiClient;
    }

    public static PlayerNameTracker getNameTracker() {
        return nameTracker;
    }

    // -------------------------------------------------------------------------

    private static ModConfig loadOrCreateConfig(Path path) {
        if (Files.exists(path)) {
            try {
                String json = Files.readString(path);
                ModConfig loaded = GSON.fromJson(json, ModConfig.class);
                if (loaded != null) {
                    return loaded;
                }
            } catch (IOException e) {
                LOGGER.warn("[MultiLogin] Failed to read config, using defaults: {}", e.getMessage());
            }
        }

        // Write a default (blank) config so the server operator can fill it in.
        ModConfig defaults = new ModConfig();
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(defaults));
            LOGGER.info("[MultiLogin] Default config written to {}", path.toAbsolutePath());
        } catch (IOException e) {
            LOGGER.warn("[MultiLogin] Could not write default config: {}", e.getMessage());
        }
        return defaults;
    }
}
