package net.wifil.mcmultilogin.tracking;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Persists a mapping of original player name → renamed player name
 * in {@code config/mc-multilogin-renames.json}.
 * <p>
 * All operations are guarded by a simple object lock so concurrent logins
 * won't corrupt the file.
 * </p>
 */
public class PlayerNameTracker {

    private static final Logger LOGGER = LoggerFactory.getLogger("mc-multilogin-compat");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final java.lang.reflect.Type MAP_TYPE =
            new TypeToken<LinkedHashMap<String, String>>() {}.getType();

    private final Path filePath;
    private final Map<String, String> renames;

    public PlayerNameTracker() {
        this.filePath = FabricLoader.getInstance().getConfigDir()
                .resolve("mc-multilogin-renames.json");
        this.renames = load();
    }

    /**
     * Record that {@code originalName} was auto-renamed to {@code newName}.
     */
    public synchronized void track(String originalName, String newName) {
        renames.put(originalName, newName);
        save();
        LOGGER.info("[MultiLogin] Tracked rename: {} -> {}", originalName, newName);
    }

    /**
     * Return the rename target for {@code originalName}, or {@code null} if not tracked.
     */
    public synchronized String getRenamed(String originalName) {
        return renames.get(originalName);
    }

    public synchronized Map<String, String> getAllRenames() {
        return Map.copyOf(renames);
    }

    // -------------------------------------------------------------------------

    private Map<String, String> load() {
        if (!Files.exists(filePath)) {
            return new LinkedHashMap<>();
        }
        try {
            String json = Files.readString(filePath);
            Map<String, String> loaded = GSON.fromJson(json, MAP_TYPE);
            return loaded != null ? loaded : new LinkedHashMap<>();
        } catch (IOException e) {
            LOGGER.warn("[MultiLogin] Failed to read rename tracking file: {}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    private void save() {
        try {
            Files.writeString(filePath, GSON.toJson(renames));
        } catch (IOException e) {
            LOGGER.warn("[MultiLogin] Failed to save rename tracking file: {}", e.getMessage());
        }
    }
}
