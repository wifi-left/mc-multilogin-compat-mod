package net.wifil.mcmultilogin.mixin;

import net.minecraft.network.chat.Component;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import net.wifil.mcmultilogin.McMultiloginCompatMod;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts the login-phase {@code disconnect(Component)} in
 * {@link ServerLoginPacketListenerImpl} so that, when a player is kicked
 * because {@code hasJoinedServer} returned {@code null}, the generic
 * "Failed to verify username!" message is replaced with the detailed reason
 * stored by {@link YggdrasilSessionServiceMixin} in
 * {@link McMultiloginCompatMod#PENDING_ERRORS}.
 */
@Mixin(ServerLoginPacketListenerImpl.class)
public abstract class ServerLoginPacketListenerMixin {

    /** The username sent by the client during the Hello packet. */
    @Shadow
    @Nullable
    private String requestedUsername;

    /** Shadow the {@code disconnect} method so we can call it recursion-safely. */
    @Shadow
    public abstract void disconnect(Component reason);

    /**
     * {@code true} while we are re-calling {@code disconnect} with a custom
     * message to prevent infinite recursion.
     */
    @Unique
    private boolean multilogin$inCustomDisconnect = false;

    @Inject(method = "disconnect(Lnet/minecraft/network/chat/Component;)V",
            at = @At("HEAD"),
            cancellable = true)
    private void multilogin$onDisconnect(Component reason, CallbackInfo ci) {
        // Prevent re-entry when we call disconnect ourselves below.
        if (this.multilogin$inCustomDisconnect) {
            return;
        }

        String username = this.requestedUsername;
        if (username == null) {
            return;
        }

        String customMessage = McMultiloginCompatMod.PENDING_ERRORS.remove(username);
        if (customMessage == null) {
            return;
        }

        // Cancel the generic "Failed to verify username!" disconnect and
        // send the detailed reason instead.
        ci.cancel();
        this.multilogin$inCustomDisconnect = true;
        try {
            this.disconnect(Component.literal(customMessage));
        } finally {
            this.multilogin$inCustomDisconnect = false;
        }
    }
}
