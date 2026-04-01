package net.wifil.mcmultilogin.mixin;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.yggdrasil.ProfileResult;
import com.mojang.authlib.yggdrasil.YggdrasilMinecraftSessionService;
import net.wifil.mcmultilogin.McMultiloginCompatMod;
import net.wifil.mcmultilogin.api.ErrorResponse;
import net.wifil.mcmultilogin.api.LoginApiClient;
import net.wifil.mcmultilogin.config.ModConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.UUID;

/**
 * Intercepts {@link YggdrasilMinecraftSessionService#hasJoinedServer} to:
 * <ol>
 * <li>Call the MC-MultiLogin-service with {@code detail=true} so that
 * authentication failures carry a human-readable reason.</li>
 * <li>On {@code DUPLICATE_NAME} (when {@code autoRename} is enabled),
 * automatically retry with the service-suggested {@code availableId}
 * and record the rename in the persistent tracker.</li>
 * <li>On any other 403, store the error message for the disconnect-packet
 * mixin to forward to the client.</li>
 * </ol>
 *
 * <p>
 * {@code remap = false} because {@code com.mojang.authlib} uses stable
 * library names that are not part of the Mojang/Yarn mapping pipeline.
 * </p>
 */
@Mixin(value = YggdrasilMinecraftSessionService.class, remap = false)
public class YggdrasilSessionServiceMixin {

    private static final Gson GSON = new Gson();
    private static final Method GAME_PROFILE_PROPERTIES_METHOD = findGetPropertiesMethod();
    private static final Field GAME_PROFILE_PROPERTIES_FIELD = findPropertiesField();
    private static final Constructor<?> GAME_PROFILE_CONSTRUCTOR_WITH_PROPERTIES = findGameProfileConstructorWithProperties();

    @Inject(method = "hasJoinedServer", at = @At("HEAD"), cancellable = true)
    private void multilogin$hasJoinedServer(
            String username,
            String serverId,
            InetAddress address,
            CallbackInfoReturnable<ProfileResult> cir) {

        ModConfig config = McMultiloginCompatMod.getConfig();
        LoginApiClient client = McMultiloginCompatMod.getApiClient();

        if (config == null || client == null) {
            // Mod not yet initialised — fall through to vanilla behaviour.
            return;
        }

        try {
            // ---- Primary call with detail=true ----------------------------------
            LoginApiClient.ApiResult result = client.hasJoined(username, serverId, address, true);

            if (result.isSuccess()) {
                // Happy path: parse the GameProfile and short-circuit the
                // original Yggdrasil HTTP call (saves a redundant round-trip).
                ProfileResult profile = parseProfileResult(result.body());
                if (profile != null) {
                    cir.setReturnValue(profile);
                    cir.cancel();
                }
                // If parsing failed, fall through to the original method.
                return;
            }

            if (result.isForbidden()) {
                ErrorResponse error = client.parseError(result.body());
                String cause = error.getCause();
                String errorMsg = error.getErrorMessage() != null
                        ? error.getErrorMessage()
                        : "Login rejected by authentication service.";

                // ---- DUPLICATE_NAME: optionally auto-rename --------------------
                if (error.isDuplicateName()
                        && config.isAutoRename()
                        && error.getAvailableId() != null
                        && !error.getAvailableId().isBlank()) {

                    String availableId = error.getAvailableId();
                    McMultiloginCompatMod.LOGGER.info(
                            "[MultiLogin] Name '{}' is duplicate; retrying as '{}'.",
                            username, availableId);

                    try {
                        LoginApiClient.ApiResult retryResult = client.hasJoined(availableId, serverId, address, false);

                        if (retryResult.isSuccess()) {
                            ProfileResult renamedProfile = parseProfileResult(retryResult.body());
                            if (renamedProfile != null) {
                                McMultiloginCompatMod.getNameTracker().track(username, availableId);
                                McMultiloginCompatMod.LOGGER.info(
                                        "[MultiLogin] Auto-renamed '{}' -> '{}'.", username, availableId);
                                cir.setReturnValue(renamedProfile);
                                cir.cancel();
                                return;
                            }
                        }
                        // Retry failed: fall through to showing the original error.
                        McMultiloginCompatMod.LOGGER.info(
                                "[MultiLogin] Retry as '{}' also failed (status {}); showing original error.",
                                availableId, retryResult.statusCode());
                    } catch (Exception retryEx) {
                        McMultiloginCompatMod.LOGGER.warn(
                                "[MultiLogin] Exception during rename retry for '{}': {}",
                                username, retryEx.getMessage());
                    }
                }

                // ---- Any 403 (including failed rename) --------------------------
                McMultiloginCompatMod.LOGGER.info(
                        "[MultiLogin] Login rejected for '{}' (cause={}): {}", username, cause, errorMsg);
                McMultiloginCompatMod.PENDING_ERRORS.put(username, errorMsg);
                cir.setReturnValue(null);
                cir.cancel();
                return;
            }

            // 204 or other non-200/403: standard "not found" — let null propagate
            // through the normal path (don't cancel, let original method run OR
            // handle the null result as vanilla would).
            if (result.statusCode() == 204) {
                // Standard "player not found" response, no detail available.
                cir.setReturnValue(null);
                cir.cancel();
            }
            // For any other unexpected status fall through to original method.

        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            McMultiloginCompatMod.LOGGER.warn(
                    "[MultiLogin] Interrupted while verifying '{}', falling back to vanilla.", username);
        } catch (Exception e) {
            McMultiloginCompatMod.LOGGER.warn(
                    "[MultiLogin] Error calling login API for '{}', falling back to vanilla: {}",
                    username, e.getMessage());
        }
    }

    // -------------------------------------------------------------------------

    /**
     * Parse the Yggdrasil/MultiLogin {@code hasJoined} 200 JSON response
     * into a {@link ProfileResult}.
     *
     * <pre>
     * {
     *   "id": "4566e69fc90748ee8d71d7ba5aa00d20",
     *   "name": "Thinkofdeath",
     *   "properties": [ { "name": "textures", "value": "...", "signature": "..." } ]
     * }
     * </pre>
     */
    private static ProfileResult parseProfileResult(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonObject obj = GSON.fromJson(json, JsonObject.class);
            if (obj == null)
                return null;

            String rawId = obj.has("id") ? obj.get("id").getAsString() : null;
            String name = obj.has("name") ? obj.get("name").getAsString() : null;
            if (rawId == null || name == null)
                return null;

            UUID uuid = parseUuid(rawId);
            GameProfile profile = createProfile(uuid, name);
            if (profile == null) {
                return null;
            }

            JsonArray properties = obj.has("properties") ? obj.getAsJsonArray("properties") : null;
            if (properties != null) {
                for (JsonElement elem : properties) {
                    JsonObject propObj = elem.getAsJsonObject();
                    String propName = propObj.has("name") ? propObj.get("name").getAsString() : null;
                    String propValue = propObj.has("value") ? propObj.get("value").getAsString() : null;
                    String signature = propObj.has("signature") ? propObj.get("signature").getAsString() : null;
                    if (propName != null && propValue != null) {
                        putProfileProperty(profile, propName, propValue, signature);
                    }
                }
            }

            return new ProfileResult(profile);
        } catch (Exception e) {
            McMultiloginCompatMod.LOGGER.warn("[MultiLogin] Failed to parse profile JSON: {}", e.getMessage());
            return null;
        }
    }

    /** Convert an un-hyphenated UUID hex string to a {@link UUID}. */
    private static UUID parseUuid(String raw) {
        if (raw.contains("-")) {
            return UUID.fromString(raw);
        }
        return UUID.fromString(
                raw.replaceAll("(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5"));
    }

    private static Method findGetPropertiesMethod() {
        for (String methodName : new String[] { "getProperties", "properties" }) {
            try {
                Method method = GameProfile.class.getMethod(methodName);
                method.setAccessible(true);
                return method;
            } catch (ReflectiveOperationException ignored) {
                // Try next candidate.
            }
        }
        return null;
    }

    private static Constructor<?> findGameProfileConstructorWithProperties() {
        for (Constructor<?> constructor : GameProfile.class.getConstructors()) {
            Class<?>[] parameterTypes = constructor.getParameterTypes();
            if (parameterTypes.length == 3
                    && UUID.class.equals(parameterTypes[0])
                    && String.class.equals(parameterTypes[1])) {
                constructor.setAccessible(true);
                return constructor;
            }
        }
        return null;
    }

    private static GameProfile createProfile(UUID uuid, String name) {
        if (GAME_PROFILE_CONSTRUCTOR_WITH_PROPERTIES != null) {
            try {
                Class<?> propertiesType = GAME_PROFILE_CONSTRUCTOR_WITH_PROPERTIES.getParameterTypes()[2];
                Object properties = propertiesType.getDeclaredConstructor().newInstance();
                return (GameProfile) GAME_PROFILE_CONSTRUCTOR_WITH_PROPERTIES.newInstance(uuid, name, properties);
            } catch (ReflectiveOperationException e) {
                McMultiloginCompatMod.LOGGER.debug(
                        "[MultiLogin] authlib-injector compat: cannot use 3-arg GameProfile constructor: {}",
                        e.getMessage());
            }
        }
        try {
            return new GameProfile(uuid, name);
        } catch (RuntimeException e) {
            McMultiloginCompatMod.LOGGER.warn(
                    "[MultiLogin] authlib-injector compat: cannot create GameProfile({}, {}): {}",
                    uuid, name, e.getMessage(), e);
            return null;
        }
    }

    private static Field findPropertiesField() {
        for (String fieldName : new String[] { "properties" }) {
            try {
                Field field = GameProfile.class.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field;
            } catch (ReflectiveOperationException ignored) {
                // Try next candidate.
            }
        }
        if (GAME_PROFILE_PROPERTIES_METHOD == null) {
            McMultiloginCompatMod.LOGGER.warn(
                    "[MultiLogin] authlib-injector compat: no GameProfile properties accessor found. methods={}",
                    Arrays.toString(Arrays.stream(GameProfile.class.getMethods()).map(Method::getName).distinct().toArray()));
        }
        return null;
    }

    private static Object getProfileProperties(GameProfile profile) throws ReflectiveOperationException {
        if (GAME_PROFILE_PROPERTIES_METHOD != null) {
            return GAME_PROFILE_PROPERTIES_METHOD.invoke(profile);
        }
        if (GAME_PROFILE_PROPERTIES_FIELD != null) {
            return GAME_PROFILE_PROPERTIES_FIELD.get(profile);
        }
        return null;
    }

    private static Method findPutMethod(Class<?> propertiesClass) {
        for (Method method : propertiesClass.getMethods()) {
            if ("put".equals(method.getName()) && method.getParameterCount() == 2) {
                method.setAccessible(true);
                return method;
            }
        }
        return null;
    }

    private static void putProfileProperty(GameProfile profile, String name, String value, String signature) {
        if (GAME_PROFILE_PROPERTIES_METHOD == null && GAME_PROFILE_PROPERTIES_FIELD == null) {
            McMultiloginCompatMod.LOGGER.debug(
                    "[MultiLogin] authlib-injector compat: skip property '{}' because GameProfile properties accessor is unavailable.",
                    name);
            return;
        }
        try {
            Object properties = getProfileProperties(profile);
            if (properties == null) {
                McMultiloginCompatMod.LOGGER.debug(
                        "[MultiLogin] authlib-injector compat: skip property '{}' because properties container is null.",
                        name);
                return;
            }
            Method putMethod = findPutMethod(properties.getClass());
            if (putMethod == null) {
                McMultiloginCompatMod.LOGGER.warn(
                        "[MultiLogin] authlib-injector compat: no put(name,value) on properties class '{}'.",
                        properties.getClass().getName());
                return;
            }
            putMethod.invoke(properties, name, new Property(name, value, signature));
        } catch (ReflectiveOperationException | RuntimeException e) {
            McMultiloginCompatMod.LOGGER.warn(
                    "[MultiLogin] authlib-injector compat: cannot set profile property '{}' – {}",
                    name, e.getMessage(), e);
        }
    }
}
