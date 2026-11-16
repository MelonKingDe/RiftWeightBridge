package com.example.riftweight.mixin;

import com.example.riftweight.ComponentUtil;
import com.example.riftweight.ModConfig;
import com.example.riftweight.RankCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

/**
 * Adds a small symbol between the SkyBlock level and the name for every player who is in the database:
 * "[481] MelonKingDe" -> "[481] ф MelonKingDe".
 *
 * Who is the player? On Hypixel SkyBlock the visible tab entries are fake (different UUID), so for
 * entries that start with "[level] Name" the real player is looked up by that name. Everywhere else
 * the UUID of the tab entry itself is used.
 *
 * Ranks are loaded through the batched rank cache, so the whole lobby is resolved within seconds.
 */
@Mixin(value = PlayerTabOverlay.class, priority = 1500)
public abstract class PlayerTabOverlayMixin {

	@Inject(method = "getNameForDisplay", at = @At("RETURN"), cancellable = true)
	private void riftweight$addSymbol(PlayerInfo info, CallbackInfoReturnable<Component> cir) {
		if (!ModConfig.tablist) return;
		Component name = cir.getReturnValue();
		if (name == null) return;

		UUID id;
		String shown = ComponentUtil.nameAfterLevel(name);
		if (shown != null) {
			ClientPacketListener connection = Minecraft.getInstance().getConnection();
			PlayerInfo real = connection == null ? null : connection.getPlayerInfo(shown);
			if (real == null) return; // not an online player (offline entry, header, ...)
			id = real.getProfile().id();
		} else {
			id = info.getProfile().id();
		}

		RankCache.Entry entry = RankCache.get(id);
		if (entry == null || !entry.ranked()) return;

		cir.setReturnValue(ComponentUtil.insertAfterLevel(name, ModConfig.tabSymbol()));
	}
}
