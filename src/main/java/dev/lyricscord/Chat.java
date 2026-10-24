package dev.lyricscord;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Sends a message to the player's chat (safe to call from any thread). */
public final class Chat {
    private Chat() {}

    public static void say(String message) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) {
                mc.player.sendSystemMessage(Component.literal("[Lyricscord] " + message));
            }
        });
        System.out.println("[Lyricscord] " + message);
    }
}
