package kr.onradio.player;

import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

final class TrackHistory {
    private static final int LIMIT = 100;
    record Entry(MusicRecognition.Track track, String stationId, String station, long time) {}

    private final SharedPreferences preferences;
    private final List<Entry> entries = new ArrayList<>();

    TrackHistory(SharedPreferences preferences) {
        this.preferences = preferences;
        try {
            JSONArray saved = new JSONArray(preferences.getString("track_history", "[]"));
            for (int i = 0; i < Math.min(saved.length(), LIMIT); i++) {
                JSONObject row = saved.optJSONObject(i);
                if (row == null) continue;
                String title = row.optString("title"), artist = row.optString("artist");
                if (title.isEmpty() || artist.isEmpty()) continue;
                long time = row.optLong("time", -1);
                if (time < 0) continue;
                entries.add(new Entry(new MusicRecognition.Track(title, artist),
                    row.optString("station_id"), row.optString("station"), time));
            }
        } catch (JSONException | ClassCastException ignored) {
            // An unreadable history can be replaced by the next recognized track.
        }
    }

    List<Entry> entries() { return List.copyOf(entries); }

    void remember(MusicRecognition.Track track, RadioCatalog.Station station) {
        long now = System.currentTimeMillis();
        if (!entries.isEmpty()) {
            Entry last = entries.get(0);
            long elapsed = now - last.time();
            if (elapsed >= 0 && elapsed < 300_000 && last.stationId().equals(station.id())
                    && last.track().title().equalsIgnoreCase(track.title())
                    && last.track().artist().equalsIgnoreCase(track.artist())) return;
        }
        entries.add(0, new Entry(track, station.id(), station.name(), now));
        if (entries.size() > LIMIT) entries.remove(LIMIT);
        JSONArray saved = new JSONArray();
        try {
            for (Entry entry : entries) {
                saved.put(new JSONObject().put("title", entry.track().title()).put("artist", entry.track().artist())
                    .put("station_id", entry.stationId()).put("station", entry.station()).put("time", entry.time()));
            }
            preferences.edit().putString("track_history", saved.toString()).apply();
        } catch (JSONException error) {
            throw new IllegalStateException("Unable to encode track history", error);
        }
    }
}
