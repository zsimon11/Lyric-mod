package dev.lyricscord;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

/** Settings stored in .minecraft/config/lyricscord.json */
public final class Config {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Config instance;

    public String clientId = "";
    public boolean hudEnabled = true;
    public boolean showLyrics = true;
    public int lyricOffsetMs = 0;

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("lyricscord.json");
    }

    public static synchronized Config get() {
        if (instance == null) {
            try {
                Path p = path();
                if (Files.exists(p)) {
                    instance = GSON.fromJson(Files.readString(p), Config.class);
                }
            } catch (Exception e) {
                System.err.println("[Lyricscord] Could not read config: " + e);
            }
            if (instance == null) instance = new Config();
            if (instance.clientId == null) instance.clientId = "";
        }
        return instance;
    }

    public static synchronized void save() {
        try {
            Files.writeString(path(), GSON.toJson(get()));
        } catch (Exception e) {
            System.err.println("[Lyricscord] Could not save config: " + e);
        }
    }
}
