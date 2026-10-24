package dev.lyricscord;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

public final class LyricscordClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        Config.get();
        SpotifyService.start();
        registerHud();
        registerCommands();
    }

    // All Minecraft rendering calls are isolated here.
    private static void registerHud() {
        HudElementRegistry.attachElementBefore(
                VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath("lyricscord", "now_playing"),
                (graphics, deltaTracker) -> {
                    Minecraft mc = Minecraft.getInstance();
                    Hud.draw(new Hud.Canvas() {
                        @Override public int screenWidth() { return mc.getWindow().getGuiScaledWidth(); }
                        @Override public int textWidth(String s) { return mc.font.width(s); }
                        @Override public void fill(int x1, int y1, int x2, int y2, int argb) { graphics.fill(x1, y1, x2, y2, argb); }
                        @Override public void text(String s, int x, int y, int argb) { graphics.text(mc.font, s, x, y, argb, true); }
                    });
                });
    }

    private static void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            LiteralArgumentBuilder<FabricClientCommandSource> root = LiteralArgumentBuilder.literal("lyricscord");

            root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("setup")
                    .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("clientId", StringArgumentType.word())
                            .executes(ctx -> {
                                Config.get().clientId = StringArgumentType.getString(ctx, "clientId").trim();
                                Config.save();
                                Chat.say("Client ID saved. Now run /lyricscord login");
                                return 1;
                            })));

            root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("login").executes(ctx -> {
                SpotifyAuth.login();
                return 1;
            }));

            root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("logout").executes(ctx -> {
                SpotifyAuth.logout();
                Chat.say("Logged out of Spotify.");
                return 1;
            }));

            root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("toggle").executes(ctx -> {
                Config c = Config.get();
                c.hudEnabled = !c.hudEnabled;
                Config.save();
                Chat.say("Display " + (c.hudEnabled ? "on" : "off"));
                return 1;
            }));

            root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("lyrics").executes(ctx -> {
                Config c = Config.get();
                c.showLyrics = !c.showLyrics;
                Config.save();
                Chat.say("Lyrics " + (c.showLyrics ? "on" : "off"));
                return 1;
            }));

            root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("offset")
                    .then(RequiredArgumentBuilder.<FabricClientCommandSource, Integer>argument("ms", IntegerArgumentType.integer(-5000, 5000))
                            .executes(ctx -> {
                                Config c = Config.get();
                                c.lyricOffsetMs = IntegerArgumentType.getInteger(ctx, "ms");
                                Config.save();
                                Chat.say("Lyric offset set to " + c.lyricOffsetMs + " ms (positive = lyrics appear earlier).");
                                return 1;
                            })));

            root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("status").executes(ctx -> {
                PlaybackState.Snapshot s = PlaybackState.get();
                Chat.say("Logged in: " + SpotifyAuth.loggedIn()
                        + " | Track: " + (s.track() == null ? s.status() : s.track().title() + " - " + s.track().artist())
                        + " | Lyric lines: " + s.lyrics().size());
                return 1;
            }));

            root.executes(ctx -> {
                Chat.say("Commands: setup <clientId>, login, logout, toggle, lyrics, offset <ms>, status");
                return 1;
            });

            dispatcher.register(root);
        });
    }
}
