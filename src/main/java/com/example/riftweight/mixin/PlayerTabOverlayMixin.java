package com.example.riftweight.mixin;

import com.example.riftweight.ComponentUtil;
import com.example.riftweight.Debug;
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

/**
 * Adds a small symbol to the tab list entry of every player who is in the database.
 * Position (config "tabPosition"): "start" = far left "ф [481] Name", "afterLevel" = "[481] ф Name".
 *
 * The player is found by NAME (the first word in the entry that is an online player), because on
 * Hypixel SkyBlock the visible tab entries are fake entries with a different UUID.
 * Ranks come from the batched rank cache, so the whole lobby is resolved within seconds.
 */
@Mixin(value = PlayerTabOverlay.class, priority = 1500)
public abstract class PlayerTabOverlayMixin {

	@Inject(method = "getNameForDisplay", at = @At("RETURN"), cancellable = true)
	private void riftweight$addSymbol(PlayerInfo info, CallbackInfoReturnable<Component> cir) {
		Debug.hookCalls++;
		if (!ModConfig.tablist) return;
		Component name = cir.getReturnValue();
		if (name == null) return;
		ClientPacketListener connection = Minecraft.getInstance().getConnection();
		if (connection == null) return;

		PlayerInfo real = ComponentUtil.findOnlinePlayer(name, connection);
		RankCache.Entry entry = real == null ? null : RankCache.get(real.getProfile().id());
		if (Debug.enabled) Debug.record(name.getString(), real, entry);
		if (entry == null || !entry.ranked()) return;

		cir.setReturnValue("afterLevel".equalsIgnoreCase(ModConfig.tabPosition)
				? ComponentUtil.insertAfterLevel(name, ModConfig.tabSymbol())
				: ComponentUtil.insertAtStart(name, ModConfig.tabSymbol()));
	}
}
