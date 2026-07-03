package net.wifil.mcmultilogin.mixin;

import net.minecraft.network.chat.Component;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import net.wifil.mcmultilogin.McMultiloginCompatMod;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

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

    @ModifyVariable(method = "disconnect(Lnet/minecraft/network/chat/Component;)V", at = @At("HEAD"), argsOnly = true)
    private Component multilogin$replaceDisconnectReason(Component originalReason) {
        if (originalReason == null) {
            return null;
        }

        String username = this.requestedUsername;
        if (username == null) {
            return originalReason;
        }

        String customMessage = McMultiloginCompatMod.PENDING_ERRORS.remove(username);
        if (customMessage == null) {
            return originalReason;
        }

        // 直接返回新消息，原版流程只会执行一次 disconnect，
        // 完全避免了 ci.cancel() + 手动二次调用的风险。
        return Component.literal(customMessage);
    }
}
