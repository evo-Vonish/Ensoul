package com.dwinovo.numen.mixin;

import com.dwinovo.numen.client.agent.NumenRoster;
import com.dwinovo.numen.client.skin.NumenSkins;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Swaps in a player-provided skin (see {@link NumenSkins}) when this entity is
 * one of the owner's companions; every other player renders untouched.
 */
@Mixin(AbstractClientPlayer.class)
public abstract class MixinAbstractClientPlayer {

    @Inject(method = "getSkin", at = @At("HEAD"), cancellable = true)
    private void numen_api$companionSkin(CallbackInfoReturnable<PlayerSkin> cir) {
        AbstractClientPlayer self = (AbstractClientPlayer) (Object) this;
        NumenRoster.Entry companion = NumenRoster.instance().byUuid(self.getUUID());
        if (companion == null) return;
        PlayerSkin skin = NumenSkins.lookup(companion.name());
        if (skin != null) cir.setReturnValue(skin);
    }
}
