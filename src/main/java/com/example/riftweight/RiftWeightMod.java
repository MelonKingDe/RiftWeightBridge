package com.example.riftweight;

import net.fabricmc.api.ClientModInitializer;

public class RiftWeightMod implements ClientModInitializer {

	/** Public address of the Rift Weight API (https, no trailing slash). */
	public static final String API_BASE = "https://desktop-u63qjvf.tail6a0447.ts.net";

	/**
	 * Application ID of your Discord application (discord.com/developers). The NAME of that application
	 * is what Discord shows as the activity ("Playing /riftweight"). Leave as is to disable Rich Presence.
	 */
	public static final String DISCORD_APP_ID = "YOUR_APPLICATION_ID";

	@Override
	public void onInitializeClient() {
		ModConfig.load();
		RankCache.start();
		RiftWeightCommand.register();
		ChatRanks.register();
		MissingHud.register();
		AutoChecker.register();
		DiscordPresence.start();
	}
}
