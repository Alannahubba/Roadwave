package kr.onradio.player;

import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.exoplayer.audio.TeeAudioProcessor;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

final class MusicRecognition {
    record Track(String title, String artist) {
        private String searchQuery() { return title + " " + artist; }

        Uri youtubeMusic() {
            return Uri.parse("https://music.youtube.com/search").buildUpon()
                .appendQueryParameter("q", searchQuery()).build();
        }

        Uri spotifyApp() { return Uri.parse("spotify:search:" + Uri.encode(searchQuery())); }

        Uri spotifyWeb() {
            return Uri.parse("https://open.spotify.com/search").buildUpon().appendPath(searchQuery()).build();
        }
    }

    record Clip(short[] samples, int rate) {}

    /** Audio comes from the player, not the microphone. Nothing is written to storage. */
    @androidx.media3.common.util.UnstableApi
    static final class AudioTap implements TeeAudioProcessor.AudioBufferSink {
        private short[] ring = new short[0];
        private int rate, channels, encoding, position, length;

        @Override public synchronized void flush(int sampleRateHz, int channelCount, int encoding) {
            this.rate = sampleRateHz;
            this.channels = channelCount;
            this.encoding = encoding;
            ring = new short[sampleRateHz > 0 && sampleRateHz <= 192_000 ? sampleRateHz * 10 : 0];
            clear();
        }

        synchronized void clear() {
            position = length = 0;
        }

        @Override public synchronized void handleBuffer(ByteBuffer buffer) {
            if (ring.length == 0 || channels < 1) return;
            int width = encoding == C.ENCODING_PCM_16BIT ? 2 : encoding == C.ENCODING_PCM_FLOAT ? 4 : 0;
            if (width == 0) return;
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            int frames = buffer.remaining() / (width * channels);
            for (int remaining = frames; remaining > 0; remaining--) {
                int sum = 0;
                for (int channel = 0; channel < channels; channel++) {
                    if (width == 2) sum += buffer.getShort();
                    else sum += (int) (Math.max(-1, Math.min(1, buffer.getFloat())) * Short.MAX_VALUE);
                }
                ring[position++] = (short) (sum / channels);
                if (position == ring.length) position = 0;
            }
            length = Math.min(ring.length, length + frames);
        }

        synchronized Clip sample() {
            if (ring.length == 0 || length < rate * 9) return null;
            short[] copy = new short[length];
            int start = (position + ring.length - length) % ring.length;
            int first = Math.min(length, ring.length - start);
            System.arraycopy(ring, start, copy, 0, first);
            if (first < length) System.arraycopy(ring, 0, copy, first, length - first);
            return new Clip(copy, rate);
        }
    }

    // Same recognition request format as ShazamIO, whose MIT notice is bundled in the assets.
    static Track identify(Clip clip) throws Exception {
        String signature = AudioFingerprint.create(clip.samples(), clip.rate());
        JSONObject body = new JSONObject()
            .put("timezone", "Etc/UTC")
            .put("timestamp", System.currentTimeMillis())
            .put("context", new JSONObject())
            .put("geolocation", new JSONObject())
            .put("signature", new JSONObject().put("uri", signature)
                .put("samplems", (long) clip.samples().length * 1000 / clip.rate()));
        String requestId = UUID.randomUUID().toString().toUpperCase(Locale.ROOT);
        String attemptId = UUID.randomUUID().toString().toUpperCase(Locale.ROOT);
        HttpURLConnection connection = RadioCatalog.connection(
            "https://amp.shazam.com/discovery/v5/en/US/iphone/-/tag/" + requestId + "/" + attemptId
            + "?sync=true&webv3=true&sampling=true&connected=&shazamapiversion=v3&sharehub=true"
            + "&hubv5minorversion=v5.1&hidelb=true&video=v3");
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("X-Shazam-Platform", "IPHONE");
        connection.setRequestProperty("X-Shazam-AppVersion", "14.1.0");
        connection.setRequestProperty("Accept-Language", "en-US");
        connection.setDoOutput(true);
        try {
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            try (InputStream input = connection.getInputStream()) {
                JSONObject result = new JSONObject(RadioCatalog.readText(input));
                JSONObject track = result.optJSONObject("track");
                JSONArray matches = result.optJSONArray("matches");
                if (track == null || matches == null || matches.length() == 0) return null;
                String title = track.optString("title").trim();
                String artist = track.optString("subtitle").trim();
                return title.isEmpty() || artist.isEmpty() ? null : new Track(title, artist);
            }
        } finally {
            connection.disconnect();
        }
    }
}
