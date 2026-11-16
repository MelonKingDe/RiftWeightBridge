package com.example.riftweight.mixin;

import com.example.riftweight.ComponentUtil;
import com.example.riftweight.ModConfig;
import com.example.riftweight.RankCache;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Adds a small symbol between the SkyBlock level and the name for every player who is in the database:
 * "[481] MelonKingDe" -> "[481] ф MelonKingDe".
 *
 * Players in the tab list are looked up automatically through the rank cache (batched), so the whole
 * lobby is resolved within a few seconds. Players who are not in the database get no symbol.
 */
@Mixin(value = PlayerTabOverlay.class, priority = 1500)
public abstract class PlayerTabOverlayMixin {

	@Inject(method = "getNameForDisplay", at = @At("RETURN"), cancellable = true)
	private void riftweight$addSymbol(PlayerInfo info, CallbackInfoReturnable<Component> cir) {
		if (!ModConfig.tablist) return;

		RankCache.Entry entry = RankCache.get(info.getProfile().id());
		if (entry == null || !entry.ranked()) return;

		Component name = cir.getReturnValue();
		if (name == null) return;
		cir.setReturnValue(ComponentUtil.insertAfterLevel(name, ModConfig.tabSymbol()));
	}
}
