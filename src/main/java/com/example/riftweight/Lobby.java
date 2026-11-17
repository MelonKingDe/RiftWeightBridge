package com.example.riftweight;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/** Looks at the players in your lobby (tab list) and sorts them by database state. */
public final class Lobby {

	private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_]{3,16}$");

	public record Player(UUID id, String name) {}

	/** missing = not in the database, loading = rank lookup still running, errors = API unreachable. */
	public record Result(List<Player> missing, int loading, int errors) {}

	private Lobby() {}

	public static Result scan() {
		List<Player> missing = new ArrayList<>();
		int loading = 0;
		int errors = 0;

		ClientPacketListener connection = Minecraft.getInstance().getConnection();
		if (connection == null) return new Result(missing, 0, 0);

		for (PlayerInfo info : connection.getOnlinePlayers()) {
			var profile = info.getProfile();
			UUID id = profile.id();
			String name = profile.name();
			// real accounts only: skips NPCs and the fake tab entries of Hypixel
			if (id == null || name == null || id.version() != 4 || !NAME.matcher(name).matches()) continue;

			RankCache.rememberName(name, id);
			RankCache.Entry entry = RankCache.get(id); // also queues the lookup
			if (entry == null) loading++;
			else if (entry.rank() == 0) missing.add(new Player(id, name));
			else if (entry.rank() < 0) errors++;
		}
		missing.sort(Comparator.comparing(p -> p.name().toLowerCase()));
		return new Result(missing, loading, errors);
	}
}
