package net.wifil.mcmultilogin.mixin;

import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Multimap;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
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
import java.net.InetAddress;
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
 * <p>This version is designed exclusively for <b>authlib 7.x</b> (Record-style GameProfile).</p>
 */
@Mixin(value = YggdrasilMinecraftSessionService.class, remap = false)
public class YggdrasilSessionServiceMixin {

    private static final Gson GSON = new Gson();

    @Inject(method = "hasJoinedServer", at = @At("HEAD"), cancellable = true)
    private void multilogin$hasJoinedServer(
            String username,
            String serverId,
            InetAddress address,
            CallbackInfoReturnable<ProfileResult> cir) {

        ModConfig config = McMultiloginCompatMod.getConfig();
        LoginApiClient client = McMultiloginCompatMod.getApiClient();

        if (config == null || client == null) {
            return;
        }

        try {
            LoginApiClient.ApiResult result = client.hasJoined(username, serverId, address, true);

            if (result.isSuccess()) {
                ProfileResult profile = parseProfileResult(result.body());
                if (profile != null) {
                    cir.setReturnValue(profile);
                    cir.cancel();
                }
                return;
            }

            if (result.isForbidden()) {
                ErrorResponse error = client.parseError(result.body());
                String cause = error.getCause();
                String errorMsg = error.getErrorMessage() != null
                        ? error.getErrorMessage()
                        : "Login rejected by authentication service.";

                if (error.isDuplicateName()
                        && config.isAutoRename()
                        && error.getAvailableId() != null
                        && !error.getAvailableId().isBlank()) {

                    String availableId = error.getAvailableId();
                    McMultiloginCompatMod.LOGGER.info(
                            "[MultiLogin] Name '{}' is duplicate; retrying as '{}'.",
                            username, availableId);

                    try {
                        LoginApiClient.ApiResult retryResult =
                                client.hasJoined(availableId, serverId, address, false);

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
                        McMultiloginCompatMod.LOGGER.info(
                                "[MultiLogin] Retry as '{}' also failed (status {}); showing original error.",
                                availableId, retryResult.statusCode());
                    } catch (Exception retryEx) {
                        McMultiloginCompatMod.LOGGER.warn(
                                "[MultiLogin] Exception during rename retry for '{}': {}",
                                username, retryEx.getMessage());
                    }
                }

                McMultiloginCompatMod.LOGGER.info(
                        "[MultiLogin] Login rejected for '{}' (cause={})", username, cause);
                McMultiloginCompatMod.PENDING_ERRORS.put(username, errorMsg);
                cir.setReturnValue(null);
                cir.cancel();
                return;
            }

            if (result.statusCode() == 204) {
                cir.setReturnValue(null);
                cir.cancel();
            }

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
     * into a {@link ProfileResult} for authlib 7.x.
     */
    private static ProfileResult parseProfileResult(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonObject obj = GSON.fromJson(json, JsonObject.class);
            if (obj == null) return null;

            String rawId = obj.has("id") ? obj.get("id").getAsString() : null;
            String name = obj.has("name") ? obj.get("name").getAsString() : null;
            if (rawId == null || name == null) return null;

            UUID uuid = parseUuid(rawId);
            JsonArray propertiesArray = obj.has("properties") ? obj.getAsJsonArray("properties") : null;

            GameProfile profile = createProfileV7(uuid, name, propertiesArray);
            return profile != null ? new ProfileResult(profile) : null;

        } catch (Exception e) {
            McMultiloginCompatMod.LOGGER.warn("[MultiLogin] Failed to parse profile JSON: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Authlib 7.x style: GameProfile is a Record requiring a three-argument constructor
     * (UUID, String, PropertyMap). PropertyMap is immutable and constructed from a Multimap.
     */
    private static GameProfile createProfileV7(UUID uuid, String name, JsonArray propertiesArray) {
        try {
            // 1. Build a mutable Multimap containing the properties.
            Multimap<String, Property> multimap = LinkedHashMultimap.create();
            if (propertiesArray != null) {
                for (JsonElement elem : propertiesArray) {
                    JsonObject propObj = elem.getAsJsonObject();
                    String propName = propObj.get("name").getAsString();
                    String propValue = propObj.get("value").getAsString();
                    String signature = propObj.has("signature") ? propObj.get("signature").getAsString() : null;
                    multimap.put(propName, new Property(propName, propValue, signature));
                }
            }

            // 2. Wrap the Multimap in an immutable PropertyMap.
            Constructor<PropertyMap> propertyMapCtor = PropertyMap.class.getConstructor(Multimap.class);
            PropertyMap propertyMap = propertyMapCtor.newInstance(multimap);

            // 3. Create the GameProfile using the three-argument constructor.
            Constructor<GameProfile> profileCtor = GameProfile.class.getConstructor(UUID.class, String.class, PropertyMap.class);
            return profileCtor.newInstance(uuid, name, propertyMap);

        } catch (Exception e) {
            McMultiloginCompatMod.LOGGER.warn("[MultiLogin] Failed to create V7 profile: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Convert an un-hyphenated UUID hex string to a {@link UUID}.
     */
    private static UUID parseUuid(String raw) {
        if (raw.contains("-")) {
            return UUID.fromString(raw);
        }
        return UUID.fromString(
                raw.replaceAll("(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5"));
    }
}