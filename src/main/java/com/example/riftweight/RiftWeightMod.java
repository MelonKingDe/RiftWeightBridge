package com.example.riftweight;

import net.fabricmc.api.ClientModInitializer;

public class RiftWeightMod implements ClientModInitializer {

	/** HIER deine API-Adresse eintragen (https, ohne Slash am Ende). */
	public static final String API_BASE = "https://desktop-u63qjvf.tail6a0447.ts.net";

	@Override
	public void onInitializeClient() {
		RankCache.start();
		RiftWeightCommand.register();
	}
}
