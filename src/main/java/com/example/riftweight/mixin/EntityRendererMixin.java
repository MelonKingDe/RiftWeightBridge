package com.example.riftweight.mixin;

import com.example.riftweight.ModConfig;
import com.example.riftweight.RankCache;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Appends the rank tag at the very END of the nametag.
 *
 * Vanilla fills state.nameTag in EntityRenderer#extractRenderState (including team prefix/suffix).
 * We inject at TAIL with a high priority (default 1000, higher = later), so we run after other
 * mods that also append something: "Name [other rank] [ф 12]". Without other mods: "Name [ф 12]".
 */
@Mixin(value = EntityRenderer.class, priority = 1500)
public abstract class EntityRendererMixin {

	@Inject(method = "extractRenderState", at = @At("TAIL"))
	private void riftweight$appendRank(Entity entity, EntityRenderState state, float partialTick, CallbackInfo ci) {
		if (!ModConfig.nametag || state.nameTag == null || !(entity instanceof Player player)) return;

		RankCache.Entry entry = RankCache.get(player.getUUID());
		if (entry == null || !entry.ranked()) return;

		state.nameTag = Component.empty()
				.append(state.nameTag)
				.append(Component.literal(" "))
				.append(ModConfig.tag(entry.rank()));
	}
}
