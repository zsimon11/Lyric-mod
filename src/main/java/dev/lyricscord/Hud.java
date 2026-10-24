package dev.lyricscord;

/**
 * Draws the now-playing pill and the current lyric line.
 * Uses a tiny Canvas interface so all Minecraft-specific drawing calls live in one place
 * (see LyricscordClient).
 */
public final class Hud {
    private Hud() {}

    public interface Canvas {
        int screenWidth();
        int textWidth(String s);
        void fill(int x1, int y1, int x2, int y2, int argb);
        void text(String s, int x, int y, int argb);
    }

    private static final int PILL_BG = 0xC0101018;
    private static final int PILL_BAR_BG = 0xFF2A2A38;
    private static final int ACCENT = 0xFF8B7CFF;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int DIM = 0xFFA0A0B0;
    private static final int LYRIC_BG = 0x90000000;

    public static void draw(Canvas c) {
        Config cfg = Config.get();
        if (!cfg.hudEnabled) return;

        PlaybackState.Snapshot s = PlaybackState.get();
        int sw = c.screenWidth();
        int top = 6;

        String label;
        if (s.track() == null) {
            label = s.status();
        } else {
            label = s.track().title() + " - " + s.track().artist();
            if (!s.playing()) label = "(paused) " + label;
        }

        int maxW = Math.min(300, sw - 20);
        label = fit(c, label, maxW - 16);
        int w = Math.max(110, Math.min(maxW, c.textWidth(label) + 16));
        int x = (sw - w) / 2;
        int h = 17;

        c.fill(x, top, x + w, top + h, PILL_BG);
        c.text(label, x + (w - c.textWidth(label)) / 2, top + 4, WHITE);

        if (s.track() != null && s.track().durationMs() > 0) {
            c.fill(x, top + h, x + w, top + h + 2, PILL_BAR_BG);
            double frac = Math.max(0, Math.min(1, s.positionMs() / (double) s.track().durationMs()));
            c.fill(x, top + h, x + (int) (w * frac), top + h + 2, ACCENT);
        }

        if (!cfg.showLyrics || s.track() == null) return;

        int y = top + h + 10;
        if (s.lyrics().isEmpty()) {
            drawCentered(c, sw, y, "(no synced lyrics found)", DIM, sw - 40);
            return;
        }
        int idx = s.currentLine(cfg.lyricOffsetMs);
        if (idx >= 0) {
            String cur = s.lyrics().get(idx).text();
            if (cur.isBlank()) cur = "...";
            drawCentered(c, sw, y, cur, WHITE, sw - 40);
        }
        int next = idx + 1;
        if (next < s.lyrics().size()) {
            String nx = s.lyrics().get(next).text();
            if (!nx.isBlank()) drawCentered(c, sw, y + 13, nx, DIM, sw - 40);
        }
    }

    private static void drawCentered(Canvas c, int sw, int y, String text, int color, int maxW) {
        text = fit(c, text, maxW);
        int tw = c.textWidth(text);
        int x = (sw - tw) / 2;
        c.fill(x - 4, y - 2, x + tw + 4, y + 10, LYRIC_BG);
        c.text(text, x, y, color);
    }

    private static String fit(Canvas c, String s, int maxW) {
        if (c.textWidth(s) <= maxW) return s;
        while (s.length() > 1 && c.textWidth(s + "...") > maxW) s = s.substring(0, s.length() - 1);
        return s + "...";
    }
}
