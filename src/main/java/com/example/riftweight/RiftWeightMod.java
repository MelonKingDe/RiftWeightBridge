package com.example.riftweight;

import net.fabricmc.api.ClientModInitializer;

public class RiftWeightMod implements ClientModInitializer {

	/** Public address of the Rift Weight API (https, no trailing slash). */
	public static final String API_BASE = "https://desktop-u63qjvf.tail6a0447.ts.net";

	@Override
	public void onInitializeClient() {
		ModConfig.load();
		RankCache.start();
		RiftWeightCommand.register();
		ChatRanks.register();
	}
}
