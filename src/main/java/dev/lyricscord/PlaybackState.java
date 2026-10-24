package dev.lyricscord;

import java.util.List;

/** Thread-safe holder for what Spotify is currently playing. */
public final class PlaybackState {
    private PlaybackState() {}

    public record Track(String id, String title, String artist, String album, long durationMs) {}

    public record LrcLine(long timeMs, String text) {}

    public record Snapshot(Track track, boolean playing, long progressMs, long sampleNanos,
                           List<LrcLine> lyrics, String status) {

        public static Snapshot idle(String status) {
            return new Snapshot(null, false, 0, System.nanoTime(), List.of(), status);
        }

        /** Estimated current position, advanced locally between Spotify polls. */
        public long positionMs() {
            if (!playing) return progressMs;
            long elapsed = (System.nanoTime() - sampleNanos) / 1_000_000L;
            long pos = progressMs + elapsed;
            return track != null ? Math.min(pos, track.durationMs()) : pos;
        }

        /** Index of the lyric line active right now, or -1 before the first line. */
        public int currentLine(int offsetMs) {
            if (lyrics.isEmpty()) return -1;
            long pos = positionMs() + offsetMs;
            int lo = 0, hi = lyrics.size() - 1, ans = -1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if (lyrics.get(mid).timeMs() <= pos) { ans = mid; lo = mid + 1; } else { hi = mid - 1; }
            }
            return ans;
        }
    }

    private static volatile Snapshot current = Snapshot.idle("Starting...");

    public static Snapshot get() { return current; }

    public static void set(Snapshot s) { current = s; }

    /** Attach fetched lyrics, but only if that track is still the one playing. */
    public static synchronized void attachLyrics(String trackId, List<LrcLine> lines) {
        Snapshot s = current;
        if (s.track() != null && s.track().id().equals(trackId)) {
            current = new Snapshot(s.track(), s.playing(), s.progressMs(), s.sampleNanos(), lines, s.status());
        }
    }
}
