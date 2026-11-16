package com.example.riftweight.mixin;

import com.example.riftweight.RankCache;
import net.minecraft.ChatFormatting;
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
 * Hängt " #<Rang>" GANZ RECHTS an den Nametag an.
 *
 * Vanilla setzt state.nameTag in EntityRenderer#extractRenderState (aus getNameTag(entity),
 * inkl. Team-Prefix/-Suffix). Wir injizieren am TAIL und hängen hinten an. Mit hoher priority
 * (Standard = 1000, höher = später) laufen wir NACH anderen Mods, die ebenfalls etwas an den
 * Namen hängen -> "Name [anderer Rang] #Rang". Ohne andere Mod: "Name #Rang".
 */
@Mixin(value = EntityRenderer.class, priority = 1500)
public abstract class EntityRendererMixin {

	@Inject(method = "extractRenderState", at = @At("TAIL"))
	private void riftweight$appendRank(Entity entity, EntityRenderState state, float partialTick, CallbackInfo ci) {
		if (state.nameTag == null || !(entity instanceof Player player)) return;

		RankCache.Entry entry = RankCache.get(player.getUUID());
		if (entry == null || !entry.ranked()) return;

		ChatFormatting color = entry.rank() <= 3 ? ChatFormatting.GOLD : ChatFormatting.LIGHT_PURPLE;
		state.nameTag = Component.empty()
				.append(state.nameTag)
				.append(Component.literal(" #" + entry.rank()).withStyle(color));
	}
}
