package kr.onradio.player;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import androidx.annotation.OptIn;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.ForwardingSimpleBasePlayer;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Metadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.exoplayer.audio.TeeAudioProcessor;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.extractor.metadata.icy.IcyInfo;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaLibraryService;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@OptIn(markerClass = UnstableApi.class)
public final class RadioService extends MediaLibraryService implements Player.Listener {
    static final String LOCAL = "kr.onradio.player.LOCAL";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newFixedThreadPool(2);
    private final MusicRecognition.AudioTap tap = new MusicRecognition.AudioTap();
    private final LocalBinder binder = new LocalBinder();
    private final Set<Runnable> observers = new HashSet<>();
    final List<RadioCatalog.Station> stations = new ArrayList<>();
    final Set<String> favorites = new HashSet<>(), hidden = new HashSet<>();
    private final JSONArray custom = new JSONArray();

    SharedPreferences preferences;
    RadioCatalog.Station station;
    String country = "";
    String queueScope = RadioLibrary.ALL;
    String status = "";
    boolean automatic, autoplay, favoriteTuning;
    private String previousStationId = "";
    boolean scanning;
    private boolean scanHeard;
    private int scanFailures;
    private long scanDeadline;
    private static final long SCAN_CONNECT_MS = 25_000, SCAN_LISTEN_MS = 6_000;
    private static final long STABLE_PLAYBACK_MS = 30_000;
    long sleepUntil, sleepViewSince;
    MusicRecognition.Track track;
    String trackSource = "";
    String trackStatus = "";

    private ExoPlayer player;
    private StationPlayer stationPlayer;
    private MediaLibrarySession session;
    private RadioLibrary library;
    private boolean wantsPlay, recognizing, manual, destroyed;
    boolean localPlayback;
    private MusicRecognition.Track broadcastTrack;
    private int epoch, attempts;
    private long nextRecognition, trackExpires, manualDeadline, carRecognitionUntil;
    private String retryMessage = "";
    private final Runnable resetRetries = () -> { if (wantsPlay && hasAudio()) attempts = 0; };
    private TrackHistory history;

    private String languageCode;
    Context languageContext() { return AppLanguage.wrap(this); }
    String localizedString(int id, Object... args) {
        Context context = languageContext();
        return args.length == 0 ? context.getString(id) : context.getString(id, args);
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        applyLanguage();
    }

    void applyLanguage() {
        String next = localizedString(R.string.language_code);
        if (station == null || next.equals(languageCode)) return;
        languageCode = next;
        String selected = station.id();
        stations.clear();
        stations.addAll(RadioCatalog.load(languageContext()));
        try {
            stations.addAll(RadioCatalog.readStations(languageContext(), custom));
        } catch (Exception ignored) {
            // The bundled stations are still available.
        }
        station = stations.stream().filter(entry -> entry.id().equals(selected)).findFirst().orElse(stations.get(0));
        status = localizedString(!wantsPlay ? R.string.press_to_start
            : localPlayback ? (hasAudio() ? R.string.local_playing : R.string.loading_audio)
            : hasAudio() ? R.string.live : R.string.connecting);
        trackStatus = localizedString(automatic ? R.string.recognition_autoplay : R.string.recognition_requires_audio);
        if (track != null) trackSource = localizedString(broadcastTrack == null
            ? R.string.recent_recognition : R.string.source_broadcaster);
        retryMessage = "";
        library.changed(session);
        notifyObservers();
    }

    final class LocalBinder extends Binder {
        RadioService getService() { return RadioService.this; }
    }

    @Override public void onCreate() {
        super.onCreate();
        status = localizedString(R.string.press_to_start);
        trackStatus = localizedString(R.string.recognition_requires_audio);
        preferences = getSharedPreferences("onradio", MODE_PRIVATE);
        history = new TrackHistory(preferences);
        stations.addAll(RadioCatalog.load(languageContext()));
        languageCode = localizedString(R.string.language_code);
        try {
            JSONArray saved = new JSONArray(preferences.getString("custom", "[]"));
            for (int i = 0; i < saved.length(); i++) custom.put(saved.getJSONObject(i));
            stations.addAll(RadioCatalog.readStations(languageContext(), custom));
        } catch (Exception ignored) {
            // Bundled channels remain available if preferences were damaged.
        }
        favorites.addAll(preferences.getStringSet("favorites", new HashSet<>()));
        hidden.addAll(preferences.getStringSet("hidden", new HashSet<>()));
        String last = preferences.getString("last", "cbs-music");
        station = stations.get(0);
        for (RadioCatalog.Station entry : stations) {
            if (entry.id().equals(last)) {
                station = entry;
                break;
            }
        }
        automatic = preferences.getBoolean("automatic", false);
        autoplay = preferences.getBoolean("autoplay", false);
        favoriteTuning = preferences.getBoolean("favoriteTuning", false);
        country = preferences.getString("country", "");
        queueScope = preferences.getString("queueScope", RadioLibrary.scopeForCountry(country));
        if (!RadioLibrary.isFavoriteScope(queueScope)
                && !queueScope.equals(RadioLibrary.scopeForCountry(station.country()))) queueScope = RadioLibrary.ALL;

        DefaultRenderersFactory renderers = new DefaultRenderersFactory(this) {
            @Override protected AudioSink buildAudioSink(android.content.Context context,
                    boolean enableFloatOutput, boolean enableAudioTrackPlaybackParams) {
                return new DefaultAudioSink.Builder(context)
                    .setAudioProcessors(new androidx.media3.common.audio.AudioProcessor[] {new TeeAudioProcessor(tap)})
                    .build();
            }
        };
        var http = new DefaultHttpDataSource.Factory().setUserAgent("OnRadio/1.0")
            .setConnectTimeoutMs(10_000).setReadTimeoutMs(15_000).setAllowCrossProtocolRedirects(true);
        player = new ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(new DefaultMediaSourceFactory(new DefaultDataSource.Factory(this, http)))
            .build();
        player.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true);
        player.setHandleAudioBecomingNoisy(true);
        player.setWakeMode(C.WAKE_MODE_NETWORK);
        player.addListener(this);
        PendingIntent launch = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        library = new RadioLibrary(this);
        stationPlayer = new StationPlayer(player);
        session = new MediaLibrarySession.Builder(this, stationPlayer, library)
            .setSessionActivity(launch).build();
        library.updateButtons(session);
        // The player screen uses a local binder, so register the session here
        // instead of waiting for a controller to connect through onGetSession.
        addSession(session);
        main.post(ticker);
    }

    @Override public IBinder onBind(Intent intent) {
        return LOCAL.equals(intent.getAction()) ? binder : super.onBind(intent);
    }

    @Override public MediaLibrarySession onGetSession(MediaSession.ControllerInfo controllerInfo) {
        // Legacy browsers bind with a placeholder identity. Media3 checks the
        // identified client in RadioLibrary.onConnect once it opens the session.
        return session;
    }

    boolean allowsController(MediaSession active, MediaSession.ControllerInfo controller) {
        return active != null && (controller.isTrusted() || isCarController(active, controller));
    }

    boolean isCarController(MediaSession active, MediaSession.ControllerInfo controller) {
        return controller.getUid() == getApplicationInfo().uid
            || active.isAutoCompanionController(controller) || active.isAutomotiveController(controller);
    }

    ListenableFuture<MediaItem> resolveLibraryItem(RadioCatalog.Station entry, String scope) {
        return MoreExecutors.listeningDecorator(network).submit(() -> createMediaItem(entry, RadioCatalog.resolve(entry))
            .buildUpon().setMediaId(RadioLibrary.scopedId(entry.id(), scope)).build());
    }

    private MediaItem createMediaItem(RadioCatalog.Station target, String url) {
        var item = new MediaItem.Builder().setMediaId(target.id()).setUri(url)
            .setMediaMetadata(new MediaMetadata.Builder().setTitle(target.name())
                .setSubtitle(RadioCatalog.countryName(languageContext(), target.country())).setArtworkUri(RadioLibrary.RADIO_ARTWORK).build());
        String path = Uri.parse(url).getPath();
        if (path != null && path.toLowerCase(Locale.ROOT).contains(".m3u8")) {
            item.setMimeType(MimeTypes.APPLICATION_M3U8);
        }
        return item.build();
    }

    void subscribe(Runnable change) { observers.add(change); }
    void unsubscribe(Runnable change) { observers.remove(change); }
    private void notifyObservers() {
        library.updateButtons(session);
        stationPlayer.refreshQueue();
        for (Runnable observer : new ArrayList<>(observers)) observer.run();
    }
    boolean isRequested() { return wantsPlay; }
    boolean hasAudio() { return player != null && player.isPlaying(); }

    void openLocalFiles(List<MediaItem> files, int index) {
        if (files.isEmpty()) return;
        stopRadio(localizedString(R.string.open_audio));
        localPlayback = true;
        wantsPlay = true;
        player.setMediaItems(files, index, 0);
        player.prepare();
        player.play();
        notifyObservers();
    }

    String localTitle() {
        CharSequence title = player.getMediaMetadata().title;
        return title == null ? localizedString(R.string.audio_file) : title.toString();
    }

    String localPosition() { return (player.getCurrentMediaItemIndex() + 1) + " / " + player.getMediaItemCount(); }

    MediaItem localSeekTarget() {
        return localPlayback && wantsPlay && player.isCurrentMediaItemSeekable() && player.getDuration() > 0
            ? player.getCurrentMediaItem() : null;
    }

    boolean seekLocal(MediaItem item, int direction) {
        if (item == null || localSeekTarget() != item) return false;
        long position = player.getCurrentPosition();
        long end = Math.max(0, player.getDuration() - 250);
        long target = Math.max(0, Math.min(end, position + direction * 5000L));
        if ((target - position) * direction <= 0) return false;
        player.seekTo(target);
        return target > 0 && target < end;
    }

    String localTimeline() {
        long position = Math.max(0, player.getCurrentPosition()) / 1000;
        long duration = Math.max(0, player.getDuration()) / 1000;
        return String.format(Locale.ROOT, "%02d:%02d / %02d:%02d",
            position / 60, position % 60, duration / 60, duration % 60);
    }

    void returnToRadio() { resumeRadio(RadioLibrary.scopeForCountry(country)); }

    private void playLocal() {
        wantsPlay = true;
        if (player.getPlaybackState() == Player.STATE_ENDED) player.seekToDefaultPosition();
        if (player.getPlaybackState() == Player.STATE_IDLE) player.prepare();
        player.play();
        notifyObservers();
    }

    void togglePlayback() {
        if (localPlayback) {
            if (wantsPlay) stopRadio(localizedString(R.string.stopped));
            else playLocal();
            return;
        }
        if (wantsPlay) stopRadio(localizedString(R.string.stopped));
        else resumeRadio(RadioLibrary.scopeForCountry(country));
    }

    RadioCatalog.Station stationForResume(String scope) {
        boolean available = !hidden.contains(station.id())
            && (!RadioLibrary.isFavoriteScope(scope) || library.includes(station, scope));
        return available ? station : adjacent(1, scope);
    }

    private void resumeRadio(String scope) {
        RadioCatalog.Station next = stationForResume(scope);
        if (next == null) stopRadio(localizedString(R.string.no_country_station));
        else tune(next, scope);
    }

    void choose(RadioCatalog.Station selection) {
        choose(selection, RadioLibrary.scopeForCountry(country));
    }

    private void choose(RadioCatalog.Station selection, String scope) {
        cancelScan();
        tune(selection, scope);
    }

    private void tune(RadioCatalog.Station selection, String scope) {
        boolean sameStation = station.id().equals(selection.id());
        boolean alreadyPlaying = sameStation && wantsPlay && !localPlayback;
        localPlayback = false;
        if (!sameStation) previousStationId = station.id();
        station = selection;
        queueScope = library.includes(selection, scope) ? scope : RadioLibrary.ALL;
        preferences.edit().putString("last", station.id()).putString("queueScope", queueScope).apply();
        if (alreadyPlaying) notifyObservers();
        else startRadio();
    }

    boolean playSearch(String query) {
        if (query.trim().isEmpty() && library.visibleStation(station.id()) != null) {
            choose(station, queueScope);
            return true;
        }
        List<RadioCatalog.Station> matches = library.matches(query);
        if (matches.isEmpty()) return false;
        choose(matches.get(0), RadioLibrary.ALL);
        return true;
    }

    private void startRadio() {
        wantsPlay = true;
        attempts = 0;
        connect();
    }

    private void connect() {
        main.removeCallbacks(resetRetries);
        int request = ++epoch;
        RadioCatalog.Station target = station;
        player.stop();
        player.clearMediaItems();
        resetMusic();
        status = localizedString(R.string.connecting);
        notifyObservers();
        network.execute(() -> {
            try {
                String url = RadioCatalog.resolve(target);
                main.post(() -> {
                    if (!current(request)) return;
                    player.setMediaItem(createMediaItem(target, url));
                    player.prepare();
                    player.play();
                });
            } catch (Exception error) {
                main.post(() -> { if (current(request)) retry(); });
            }
        });
        main.postDelayed(() -> {
            if (current(request) && player.getPlaybackState() != Player.STATE_READY) retry();
        }, 30_000);
    }

    private boolean current(int request) { return !destroyed && wantsPlay && request == epoch; }

    void stopRadio(String message) {
        cancelScan();
        if (localPlayback) {
            wantsPlay = false;
            player.pause();
            player.seekToDefaultPosition();
            status = message;
            notifyObservers();
            return;
        }
        main.removeCallbacks(resetRetries);
        wantsPlay = false;
        epoch++;
        player.stop();
        player.clearMediaItems();
        resetMusic();
        status = message;
        notifyObservers();
    }

    private void retry() {
        if (!wantsPlay || localPlayback) return;
        main.removeCallbacks(resetRetries);
        if (scanning) {
            scheduleScan(0);
            return;
        }
        if (++attempts > 2) {
            stopRadio(localizedString(R.string.connection_failed));
            return;
        }
        int request = ++epoch;
        player.stop();
        player.clearMediaItems();
        resetMusic();
        status = localizedString(R.string.reconnecting, attempts);
        notifyObservers();
        main.postDelayed(() -> { if (current(request)) connect(); }, attempts * 2000L);
    }

    @Override public void onPlaybackStateChanged(int state) {
        if (localPlayback) {
            if (state == Player.STATE_ENDED) {
                wantsPlay = false;
                status = localizedString(R.string.playlist_ended);
            } else if (state == Player.STATE_BUFFERING) status = localizedString(R.string.loading_audio);
            notifyObservers();
            return;
        }
        if (state == Player.STATE_ENDED && wantsPlay) retry();
        else if (state == Player.STATE_BUFFERING && wantsPlay) {
            status = localizedString(R.string.loading_station);
            if (scanning && scanHeard) {
                scanHeard = false;
                scheduleScan(Math.max(0, scanDeadline - SystemClock.elapsedRealtime()));
            }
            notifyObservers();
        }
    }

    @Override public void onPlayerError(PlaybackException error) {
        if (localPlayback) {
            wantsPlay = false;
            status = localizedString(R.string.audio_error);
            notifyObservers();
        } else retry();
    }

    @Override public void onPositionDiscontinuity(Player.PositionInfo previous, Player.PositionInfo current, int reason) {
        if (localPlayback) notifyObservers();
    }

    @Override public void onMediaItemTransition(MediaItem item, int reason) {
        if (localPlayback) notifyObservers();
    }

    @Override public void onIsPlayingChanged(boolean playing) {
        main.removeCallbacks(resetRetries);
        if (localPlayback) {
            if (playing) status = localizedString(R.string.local_playing);
            notifyObservers();
            return;
        }
        if (playing) {
            if (attempts > 0) main.postDelayed(resetRetries, STABLE_PLAYBACK_MS);
            status = localizedString(R.string.live);
            mediaTrack(player.getMediaMetadata());
            if (scanning) {
                scanHeard = true;
                scheduleScan(SCAN_LISTEN_MS);
            }
        }
        notifyObservers();
    }

    @Override public void onPlayWhenReadyChanged(boolean ready, int reason) {
        if (!ready && wantsPlay && (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY
                || reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS)) {
            main.post(() -> { if (!destroyed && wantsPlay && !player.getPlayWhenReady()) stopRadio(localizedString(R.string.stopped)); });
        }
    }

    @Override public void onMediaMetadataChanged(MediaMetadata metadata) { mediaTrack(metadata); }

    @Override public void onMetadata(Metadata metadata) {
        if (localPlayback || !hasAudio()) return;
        for (int i = 0; i < metadata.length(); i++) {
            if (metadata.get(i) instanceof IcyInfo info && info.title != null) {
                int separator = info.title.indexOf(" - ");
                if (separator > 0) {
                    String artist = info.title.substring(0, separator).trim();
                    String title = info.title.substring(separator + 3).trim();
                    if (!artist.isEmpty() && !title.isEmpty()) {
                        setBroadcastTrack(new MusicRecognition.Track(title, artist));
                        return;
                    }
                }
                if (broadcastTrack != null) {
                    resetMusic();
                    notifyObservers();
                }
            }
        }
    }

    private void mediaTrack(MediaMetadata metadata) {
        if (localPlayback || !hasAudio() || metadata.title == null || metadata.artist == null) return;
        String title = metadata.title.toString().trim();
        String artist = metadata.artist.toString().trim();
        if (!title.isEmpty() && !artist.isEmpty() && !title.equals(station.name())) {
            setBroadcastTrack(new MusicRecognition.Track(title, artist));
        }
    }

    private void setBroadcastTrack(MusicRecognition.Track metadata) {
        if (metadata.equals(broadcastTrack)) return;
        broadcastTrack = track = metadata;
        trackSource = localizedString(R.string.source_broadcaster);
        history.remember(metadata, station);
        manual = false;
        notifyObservers();
    }

    private String tuningScope() {
        return favoriteTuning ? RadioLibrary.favoriteScopeForCountry(country) : RadioLibrary.scopeForCountry(country);
    }

    void setFavoriteTuning(boolean enabled) {
        cancelScan();
        favoriteTuning = enabled;
        preferences.edit().putBoolean("favoriteTuning", enabled).apply();
        notifyObservers();
    }

    RadioCatalog.Station previousStation() { return library.visibleStation(previousStationId); }

    void recallPrevious() {
        RadioCatalog.Station previous = previousStation();
        if (previous != null && !previous.id().equals(station.id())) choose(previous);
    }

    void step(int direction) {
        if (localPlayback) {
            if (!canStep(direction)) return;
            player.seekToDefaultPosition(player.getCurrentMediaItemIndex() + direction);
            playLocal();
            return;
        }
        cancelScan();
        step(direction, tuningScope());
    }

    private void step(int direction, String scope) {
        RadioCatalog.Station candidate = adjacent(direction, scope);
        if (candidate != null) choose(candidate, scope);
    }

    private RadioCatalog.Station adjacent(int direction, String scope) {
        int current = stations.indexOf(station);
        for (int i = 1; i < stations.size(); i++) {
            RadioCatalog.Station candidate = stations.get(Math.floorMod(current + i * direction, stations.size()));
            if (library.includes(candidate, scope)) return candidate;
        }
        return null;
    }

    boolean canStep(int direction) {
        if (!localPlayback) return adjacent(direction, tuningScope()) != null;
        int target = player.getCurrentMediaItemIndex() + direction;
        return target >= 0 && target < player.getMediaItemCount();
    }

    void setCountry(String value) {
        cancelScan();
        country = value;
        preferences.edit().putString("country", value).apply();
        notifyObservers();
    }

    void cycleCountry() {
        int index = java.util.Arrays.asList(RadioCatalog.COUNTRY_CODES).indexOf(country);
        setCountry(RadioCatalog.COUNTRY_CODES[(index + 1) % RadioCatalog.COUNTRY_CODES.length]);
    }

    String presetId(int index) { return preferences.getString("preset_" + index, ""); }

    boolean storePreset(int index) {
        if (localPlayback || hidden.contains(station.id())) return false;
        preferences.edit().putString("preset_" + index, station.id()).apply();
        notifyObservers();
        return true;
    }

    RadioCatalog.Station recallPreset(int index) {
        RadioCatalog.Station selected = library.visibleStation(presetId(index));
        if (selected == null) return null;
        if (!country.isEmpty() && !country.equals(selected.country())) setCountry("");
        choose(selected);
        return selected;
    }

    void toggleScan() {
        if (localPlayback) return;
        if (scanning) {
            cancelScan();
            notifyObservers();
        } else if (canStep(1)) {
            scanning = true;
            scanHeard = true;
            scanFailures = 0;
            scanNext.run();
        }
    }

    private void cancelScan() {
        scanning = false;
        main.removeCallbacks(scanNext);
    }

    private void scheduleScan(long delay) {
        main.removeCallbacks(scanNext);
        main.postDelayed(scanNext, delay);
    }

    private final Runnable scanNext = () -> {
        if (!scanning) return;
        scanFailures = scanHeard ? 0 : scanFailures + 1;
        String scope = tuningScope();
        RadioCatalog.Station candidate = adjacent(1, scope);
        if (candidate == null || scanFailures >= library.queue(scope).size()) {
            stopRadio(localizedString(R.string.scan_finished));
            return;
        }
        scanHeard = false;
        scanDeadline = SystemClock.elapsedRealtime() + SCAN_CONNECT_MS;
        scheduleScan(SCAN_CONNECT_MS);
        tune(candidate, scope);
    };

    void toggleFavorite(String id) {
        if (!favorites.remove(id)) favorites.add(id);
        preferences.edit().putStringSet("favorites", new HashSet<>(favorites)).apply();
        library.changed(session);
        if (!localPlayback && wantsPlay && RadioLibrary.isFavoriteScope(queueScope) && !library.includes(station, queueScope)) {
            RadioCatalog.Station candidate = adjacent(1, queueScope);
            if (candidate == null) stopRadio(localizedString(R.string.no_favorites));
            else choose(candidate, queueScope);
        } else notifyObservers();
    }

    void toggleHidden(String id) {
        if (!hidden.remove(id)) hidden.add(id);
        preferences.edit().putStringSet("hidden", new HashSet<>(hidden)).apply();
        library.changed(session);
        if (!localPlayback && wantsPlay && hidden.contains(station.id())) {
            var available = library.queue(queueScope);
            if (!available.isEmpty()) step(1, queueScope);
            else if (!RadioLibrary.isFavoriteScope(queueScope)
                    && !library.queue(RadioLibrary.ALL).isEmpty()) step(1, RadioLibrary.ALL);
            else stopRadio(localizedString(R.string.no_visible_stations));
        } else notifyObservers();
    }

    void addStation(String name, String url) throws JSONException {
        JSONObject record = new JSONObject().put("id", "custom-" + UUID.randomUUID()).put("name", name)
            .put("group", localizedString(R.string.custom_station)).put("description", localizedString(R.string.custom_description)).put("url", RadioCatalog.webUrl(url));
        custom.put(record);
        stations.add(RadioCatalog.Station.read(languageContext(), record));
        preferences.edit().putString("custom", custom.toString()).apply();
        library.changed(session);
        notifyObservers();
    }

    void removeStation(RadioCatalog.Station entry) {
        if (!entry.id().startsWith("custom-")) return;
        for (int i = 0; i < custom.length(); i++) {
            JSONObject item = custom.optJSONObject(i);
            if (item != null && entry.id().equals(item.optString("id"))) {
                custom.remove(i);
                break;
            }
        }
        if (entry.equals(station)) {
            stopRadio(localizedString(R.string.custom_deleted));
            station = stations.get(0);
        }
        stations.remove(entry);
        hidden.remove(entry.id());
        favorites.remove(entry.id());
        preferences.edit().putString("custom", custom.toString()).putString("last", station.id())
            .putStringSet("hidden", new HashSet<>(hidden)).putStringSet("favorites", new HashSet<>(favorites)).apply();
        library.changed(session);
        notifyObservers();
    }

    List<TrackHistory.Entry> trackHistory() { return history.entries(); }

    void setAutoplay(boolean enabled) {
        autoplay = enabled;
        preferences.edit().putBoolean("autoplay", enabled).apply();
    }

    void extendSleep() {
        long now = SystemClock.elapsedRealtime();
        sleepViewSince = now;
        sleepUntil = Math.min(Math.max(now, sleepUntil) + 30 * 60_000L, now + 6 * 60 * 60_000L);
        notifyObservers();
    }

    void clearSleep() {
        sleepUntil = 0;
        notifyObservers();
    }

    void setAutomatic(boolean enabled) {
        automatic = enabled;
        if (enabled) nextRecognition = 0;
        retryMessage = "";
        preferences.edit().putBoolean("automatic", enabled).apply();
        updateRecognition(SystemClock.elapsedRealtime());
        notifyObservers();
    }

    void recognize() {
        if (localPlayback || !hasAudio() || recognizing || broadcastTrack != null) return;
        manual = true;
        manualDeadline = SystemClock.elapsedRealtime() + 22_000;
        updateRecognition(SystemClock.elapsedRealtime());
        notifyObservers();
    }

    void recognizeInCar() {
        if (broadcastTrack != null) return;
        carRecognitionUntil = SystemClock.elapsedRealtime() + 35_000;
        recognize();
    }

    boolean canRecognize() { return !localPlayback && hasAudio() && !recognizing && !manual; }

    private void resetMusic() {
        tap.clear();
        broadcastTrack = track = null;
        trackSource = "";
        nextRecognition = trackExpires = carRecognitionUntil = 0;
        manual = false;
        retryMessage = "";
        trackStatus = automatic ? localizedString(R.string.recognition_autoplay) : localizedString(R.string.recognition_requires_audio);
    }

    private void updateRecognition(long now) {
        if (track != null && broadcastTrack == null && now >= trackExpires) track = null;
        if (localPlayback || !hasAudio() || broadcastTrack != null || recognizing) return;
        if (!manual && (!automatic || now < nextRecognition)) {
            if (automatic && !retryMessage.isEmpty() && track == null) {
                trackStatus = localizedString(R.string.recognition_retry, retryMessage, Math.max(1, (nextRecognition - now + 999) / 1000));
            } else if (retryMessage.isEmpty()) {
                trackStatus = localizedString(R.string.recognition_prompt);
            }
            return;
        }
        MusicRecognition.Clip clip = tap.sample();
        if (clip == null) {
            if (manual && now >= manualDeadline) {
                manual = false;
                trackStatus = localizedString(R.string.recognition_wait);
            } else {
                trackStatus = localizedString(R.string.collecting_audio);
            }
            return;
        }
        recognizing = true;
        manual = false;
        retryMessage = "";
        nextRecognition = now + 45_000;
        trackStatus = localizedString(R.string.identifying);
        int request = epoch;
        network.execute(() -> {
            try {
                MusicRecognition.Track result = MusicRecognition.identify(clip);
                main.post(() -> recognitionFinished(request, result, false));
            } catch (Exception error) {
                main.post(() -> recognitionFinished(request, null, true));
            }
        });
    }

    private void recognitionFinished(int request, MusicRecognition.Track result, boolean error) {
        recognizing = false;
        if (!current(request) || broadcastTrack != null) {
            notifyObservers();
            return;
        }
        track = result;
        long now = SystemClock.elapsedRealtime();
        if (result != null) {
            trackSource = localizedString(R.string.recent_recognition);
            history.remember(result, station);
            trackExpires = now + 70_000;
        } else if (automatic) {
            retryMessage = error ? localizedString(R.string.recognition_delayed) : localizedString(R.string.recognition_no_match);
            nextRecognition = now + (error ? 20_000 : 12_000);
            updateRecognition(now);
        } else {
            retryMessage = error ? localizedString(R.string.recognition_unreachable) : localizedString(R.string.recognition_no_match_full);
            trackStatus = retryMessage;
        }
        notifyObservers();
    }

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (destroyed) return;
            long now = SystemClock.elapsedRealtime();
            if (sleepUntil > 0 && now >= sleepUntil) {
                sleepUntil = 0;
                stopRadio(localizedString(R.string.timer_finished));
            }
            MusicRecognition.Track priorTrack = track;
            String priorStatus = trackStatus;
            updateRecognition(now);
            boolean dismissCarRecognition = carRecognitionUntil != 0 && now >= carRecognitionUntil;
            if (dismissCarRecognition) carRecognitionUntil = 0;
            if (sleepUntil > 0 || (localPlayback && wantsPlay) || track != priorTrack || !trackStatus.equals(priorStatus) || dismissCarRecognition) {
                notifyObservers();
            }
            main.postDelayed(this, 1000);
        }
    };

    private final class StationPlayer extends ForwardingSimpleBasePlayer {
        StationPlayer(Player inner) { super(inner); }
        void refreshQueue() { invalidateState(); }

        @Override protected State getState() {
            if (localPlayback) return super.getState();
            var commands = player.getAvailableCommands().buildUpon()
                .remove(COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                .remove(COMMAND_SEEK_BACK).remove(COMMAND_SEEK_FORWARD)
                .remove(COMMAND_SET_VOLUME).remove(COMMAND_CHANGE_MEDIA_ITEMS).remove(COMMAND_SET_REPEAT_MODE)
                .add(COMMAND_GET_TIMELINE).add(COMMAND_SET_MEDIA_ITEM).add(COMMAND_SEEK_TO_MEDIA_ITEM)
                .add(COMMAND_SEEK_TO_NEXT).add(COMMAND_SEEK_TO_PREVIOUS).build();
            var entries = new ArrayList<MediaItemData>();
            int current = C.INDEX_UNSET;
            for (RadioCatalog.Station entry : library.queue(queueScope)) {
                MediaItem item = RadioLibrary.playable(languageContext(), entry, queueScope);
                if (entry.id().equals(station.id())) {
                    current = entries.size();
                    String detail = track == null ? null : track.title() + " · " + track.artist();
                    if (detail == null && carRecognitionUntil > SystemClock.elapsedRealtime()) detail = trackStatus;
                    if (detail != null) {
                        item = item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon().setArtist(detail).build()).build();
                    }
                }
                entries.add(new MediaItemData.Builder(entry.id()).setMediaItem(item).setIsDynamic(true).build());
            }
            return super.getState().buildUpon().setPlaylist(entries).setCurrentMediaItemIndex(current)
                .setRepeatMode(Player.REPEAT_MODE_ALL).setAvailableCommands(commands).build();
        }

        @Override protected ListenableFuture<?> handleSeek(int index, long positionMs, int command) {
            if (localPlayback) return super.handleSeek(index, positionMs, command);
            var entries = library.queue(queueScope);
            if (index >= 0 && index < entries.size()) choose(entries.get(index), queueScope);
            return Futures.immediateVoidFuture();
        }

        @Override protected ListenableFuture<?> handleSetPlayWhenReady(boolean ready) {
            if (localPlayback) {
                if (ready) playLocal();
                else stopRadio(localizedString(R.string.stopped));
                return Futures.immediateVoidFuture();
            }
            if (!ready) stopRadio(localizedString(R.string.stopped));
            else if (player.getCurrentMediaItem() != null) {
                wantsPlay = true;
                if (player.getPlaybackState() == Player.STATE_IDLE) player.prepare();
                player.play();
            } else if (!wantsPlay) resumeRadio(queueScope);
            return Futures.immediateVoidFuture();
        }

        @Override protected ListenableFuture<?> handleStop() {
            stopRadio(localizedString(R.string.stopped));
            return Futures.immediateVoidFuture();
        }

        @Override protected ListenableFuture<?> handleSetMediaItems(List<MediaItem> items, int index, long positionMs) {
            if (!items.isEmpty()) {
                MediaItem requested = items.get(index == C.INDEX_UNSET ? 0 : index);
                RadioCatalog.Station entry = library.visibleStation(RadioLibrary.stationId(requested.mediaId));
                String scope = RadioLibrary.scopeForId(requested.mediaId);
                if (entry != null && library.includes(entry, scope) && requested.localConfiguration != null) {
                    cancelScan();
                    main.removeCallbacks(resetRetries);
                    localPlayback = false;
                    epoch++;
                    if (!station.id().equals(entry.id())) previousStationId = station.id();
                    station = entry;
                    queueScope = scope;
                    attempts = 0;
                    resetMusic();
                    preferences.edit().putString("last", entry.id()).putString("queueScope", queueScope).apply();
                    status = localizedString(R.string.loading_station);
                    player.setMediaItem(requested.buildUpon().setMediaId(entry.id()).build());
                    notifyObservers();
                }
            }
            return Futures.immediateVoidFuture();
        }
    }

    @Override public void onDestroy() {
        destroyed = true;
        main.removeCallbacksAndMessages(null);
        network.shutdownNow();
        session.release();
        player.release();
        super.onDestroy();
    }
}
