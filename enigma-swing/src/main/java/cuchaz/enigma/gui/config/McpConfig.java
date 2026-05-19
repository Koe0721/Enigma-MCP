package cuchaz.enigma.gui.config;

import cuchaz.enigma.config.ConfigContainer;

public final class McpConfig {
	public static final int DEFAULT_PORT = 32412;

	private static final ConfigContainer cfg = ConfigContainer.getOrCreate("enigma/mcp");

	private McpConfig() {
	}

	public static void save() {
		cfg.save();
	}

	public static int getPort() {
		return cfg.data().section("Server").setIfAbsentInt("Port", DEFAULT_PORT);
	}

	public static void setPort(int port) {
		cfg.data().section("Server").setInt("Port", port);
	}

	public static boolean isAutoStart() {
		return cfg.data().section("Server").setIfAbsentBool("AutoStart", false);
	}

	public static void setAutoStart(boolean autoStart) {
		cfg.data().section("Server").setBool("AutoStart", autoStart);
	}

	public static boolean isBindAll() {
		return cfg.data().section("Server").setIfAbsentBool("BindAll", false);
	}

	public static void setBindAll(boolean bindAll) {
		cfg.data().section("Server").setBool("BindAll", bindAll);
	}
}
