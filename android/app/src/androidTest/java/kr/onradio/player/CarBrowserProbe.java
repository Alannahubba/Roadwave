package kr.onradio.player;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.browse.MediaBrowser;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Exercises the legacy media browser path used by car hosts against the release APK. */
public final class CarBrowserProbe extends Instrumentation {
    private boolean featuresOnly;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        featuresOnly = arguments != null && "true".equals(arguments.getString("featuresOnly"));
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            runProbe();
            result.putString("stream", featuresOnly
                ? "\nPASS: car favorites queue and navigation, hidden channels, search and track history\n"
                : "\nPASS: car playback and queues, shared stations, search, track history, BAND, SCAN, DISP and preset\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            result.putString("stream", "\nFAIL: " + android.util.Log.getStackTraceString(failure) + "\n");
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void runProbe() throws Exception {
        var preferences = getTargetContext().getSharedPreferences("onradio", Context.MODE_PRIVATE);
        var originalLast = preferences.getString("last", "cbs-music");
        var originalScope = preferences.getString("queueScope", RadioLibrary.ALL);
        var originalHistory = preferences.getString("track_history", null);
        Activity activity = startActivitySync(new Intent(getTargetContext(), MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        require(activity.getVolumeControlStream() == AudioManager.STREAM_MUSIC, "Wrong volume stream");
        AtomicReference<MediaBrowser> browserRef = new AtomicReference<>();
        CountDownLatch connected = new CountDownLatch(1);
        runOnMainSync(() -> {
            MediaBrowser browser = new MediaBrowser(getTargetContext(),
                new ComponentName(getTargetContext(), RadioService.class), new MediaBrowser.ConnectionCallback() {
                    @Override public void onConnected() { connected.countDown(); }
                }, null);
            browserRef.set(browser);
            browser.connect();
        });
        require(connected.await(8, TimeUnit.SECONDS), "Legacy media browser did not connect");
        MediaBrowser browser = browserRef.get();
        AtomicReference<String> root = new AtomicReference<>();
        AtomicReference<MediaController> transport = new AtomicReference<>();
        runOnMainSync(() -> {
            root.set(browser.getRoot());
            transport.set(new MediaController(getTargetContext(), browser.getSessionToken()));
        });
        try {
            var tabs = children(browser, root.get());
            require(!tabs.isEmpty() && tabs.get(0).getMediaId().equals("all"), "All stations must be the first car tab");
            require(tabs.stream().anyMatch(item ->
                item.isBrowsable() && item.getMediaId().equals("favorites")), "Favorites folder missing");
            require(children(browser, "countries").size() == 5, "Country list incomplete");
            List<MediaBrowser.MediaItem> korea = children(browser, "country:KR");
            var hidden = getTargetContext().getSharedPreferences("onradio", Context.MODE_PRIVATE)
                .getStringSet("hidden", java.util.Set.of());
            long visibleKorea = RadioCatalog.load(getTargetContext()).stream()
                .filter(entry -> entry.country().equals("KR") && !hidden.contains(entry.id())).count();
            require(korea.size() == visibleKorea, "Korea library did not match visible channels");
            require(korea.size() >= 3, "Too few visible Korean stations for the playback test");
            var cbs = korea.get(0);
            String firstId = RadioLibrary.stationId(cbs.getMediaId());
            String firstTitle = cbs.getDescription().getTitle().toString();
            String favoriteId = RadioLibrary.stationId(korea.get(1).getMediaId());
            var artwork = cbs.getDescription().getIconUri();
            require(artwork != null, "Station artwork missing from the car catalog");
            try (var image = getTargetContext().getContentResolver().openInputStream(artwork)) {
                require(BitmapFactory.decodeStream(image) != null, "Station artwork is unreadable");
            }
            MediaController controller = transport.get();
            controller.getTransportControls().playFromMediaId(firstId, null);
            waitForStation(controller, firstTitle);
            require(artwork.equals(controller.getMetadata().getDescription().getIconUri()),
                "Current station artwork missing from the car media session");
            var actions = controller.getPlaybackState().getCustomActions().stream()
                .map(PlaybackState.CustomAction::getAction).collect(java.util.stream.Collectors.toList());
            require(actions.containsAll(List.of(RadioLibrary.RECOGNIZE_ACTION, RadioLibrary.FAVORITE_ACTION,
                RadioLibrary.HIDE_ACTION)), "A car playback action is missing");
            waitForQueue(controller, children(browser, "all").size(), RadioLibrary.ALL);
            verifySharedStations(browser, controller, favoriteId, firstId);
            verifySearchAndHistory();
            if (featuresOnly) {
                controller.getTransportControls().stop();
                return;
            }
            controller.getTransportControls().playFromMediaId(cbs.getMediaId(), null);
            waitForQueue(controller, korea.size(), "country:KR");
            waitForStation(controller, firstTitle);
            require(controller.getQueue().stream().allMatch(item -> item.getDescription().getMediaId().startsWith("country:KR/")),
                "Country queue contains stations from another country");
            controller.getTransportControls().skipToNext();
            waitForStation(controller, korea.get((korea.indexOf(cbs) + 1) % korea.size())
                .getDescription().getTitle().toString());
            var kbs = controller.getQueue().get(0);
            controller.getTransportControls().skipToQueueItem(kbs.getQueueId());
            waitForStation(controller, firstTitle);
            verifyRetroKeys(activity, controller, korea);
            controller.getTransportControls().stop();
        } finally {
            runOnMainSync(() -> {
                browser.disconnect();
                activity.finish();
            });
            preferences.edit().putString("last", originalLast).putString("queueScope", originalScope)
                .putString("track_history", originalHistory).commit();
        }
    }

    private void verifyRetroKeys(Activity activity, MediaController controller, List<MediaBrowser.MediaItem> korea) {
        String presetId = RadioLibrary.stationId(korea.get(0).getMediaId());
        String presetTitle = korea.get(0).getDescription().getTitle().toString();
        var preferences = getTargetContext().getSharedPreferences("onradio", Context.MODE_PRIVATE);
        String originalCountry = preferences.getString("country", "");
        String originalPreset = preferences.getString("preset_0", "");
        boolean originalDisplay = preferences.getBoolean("songDisplay", false);
        View root = activity.findViewById(android.R.id.content);
        try {
            AtomicReference<Rect> originalSeek = new AtomicReference<>();
            runOnMainSync(() -> {
                require(findKey(root, "1").performLongClick(), "Preset could not be saved");
                require(preferences.getString("preset_0", "").equals(presetId), "Preset saved the wrong station");
                Button band = findKey(root, "BAND");
                for (int i = 0; i < RadioCatalog.COUNTRY_CODES.length
                        && !preferences.getString("country", "").isEmpty(); i++) band.performClick();
                band.performClick();
                require(preferences.getString("country", "").equals("KR"), "BAND did not select Korea");
                require(containsText(root, "BAND · 한국"), "LCD did not show the current band");
                originalSeek.set(bounds(findKey(root, "‹ SEEK")));
                Button display = findKey(root, "DISP");
                display.performClick();
                require(preferences.getBoolean("songDisplay", false) != originalDisplay, "DISP did not change the display");
            });
            waitForIdleSync();
            runOnMainSync(() -> {
                require(originalSeek.get().equals(bounds(findKey(root, "‹ SEEK"))),
                    "DISP moved the transport row");
                Button display = findKey(root, "DISP");
                display.performClick();
                Button scan = findKey(root, "SCAN");
                scan.performClick();
                require("SCAN 탐색 중지".contentEquals(scan.getContentDescription()), "SCAN did not start");
            });
            waitForStation(controller, korea.get(1).getDescription().getTitle().toString());
            waitForStation(controller, korea.get(2).getDescription().getTitle().toString());
            runOnMainSync(() -> {
                Button scan = findKey(root, "SCAN");
                scan.performClick();
                require("SCAN 채널 탐색".contentEquals(scan.getContentDescription()), "SCAN did not stop");
                findKey(root, "1").performClick();
            });
            waitForStation(controller, presetTitle);
        } finally {
            runOnMainSync(() -> {
                if (originalDisplay != preferences.getBoolean("songDisplay", false)) findKey(root, "DISP").performClick();
                preferences.edit().putString("country", originalCountry).putString("preset_0", originalPreset).apply();
            });
        }
    }

    private static Rect bounds(View view) {
        int[] position = new int[2];
        view.getLocationInWindow(position);
        return new Rect(position[0], position[1], position[0] + view.getWidth(), position[1] + view.getHeight());
    }

    private static Button findKey(View root, String label) {
        if (root instanceof Button key && label.contentEquals(key.getText())) return key;
        if (root instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                Button found = findKey(group.getChildAt(i), label);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static boolean containsText(View root, String label) {
        if (root instanceof TextView text && label.contentEquals(text.getText())) return true;
        if (root instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                if (containsText(group.getChildAt(i), label)) return true;
            }
        }
        return false;
    }

    private void verifySharedStations(MediaBrowser browser, MediaController controller, String id, String second)
            throws Exception {
        AtomicReference<RadioService> radioRef = new AtomicReference<>();
        CountDownLatch bound = new CountDownLatch(1);
        ServiceConnection phone = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder token) {
                radioRef.set(((RadioService.LocalBinder) token).getService());
                bound.countDown();
            }
            @Override public void onServiceDisconnected(ComponentName name) {}
        };
        Context context = getTargetContext();
        require(context.bindService(new Intent(context, RadioService.class).setAction(RadioService.LOCAL),
            phone, Context.BIND_AUTO_CREATE), "Phone service bind failed");
        try {
            require(bound.await(8, TimeUnit.SECONDS), "Phone service unavailable");
            RadioService radio = radioRef.get();
            boolean originalFavorite = includes(browser, "favorites", id);
            try {
                if (!originalFavorite) runOnMainSync(() -> radio.toggleFavorite(id));
                require(includes(browser, "favorites", id), "Phone favorite missing from the car");
                runOnMainSync(() -> radio.toggleHidden(id));
                require(!includes(browser, "all", id) && !includes(browser, "country:KR", id)
                    && !includes(browser, "favorites", id), "A phone-hidden station is still visible in the car");
                require(controller.getQueue().stream().noneMatch(item -> item.getDescription().getMediaId().equals(id)),
                    "A phone-hidden station remains in the queue");
                runOnMainSync(() -> radio.toggleHidden(id));
                require(includes(browser, "favorites", id), "Unhide did not restore the shared favorite");
                runOnMainSync(() -> {
                    String oldCountry = radio.country;
                    radio.stopRadio("테스트");
                    radio.setCountry("KR");
                    String last = radio.station.id();
                    radio.toggleHidden(last);
                    try {
                        var expected = radio.stationForResume("country:KR");
                        require(expected != null && expected.country().equals("KR") && !expected.id().equals(last),
                            "Car resume did not select a visible Korean station");
                        radio.togglePlayback();
                        require(radio.station.id().equals(expected.id()), "Phone tried playing its hidden last station");
                    } finally {
                        radio.stopRadio("테스트");
                        radio.toggleHidden(last);
                        radio.setCountry(oldCountry);
                    }
                });
                boolean secondWasFavorite = includes(browser, "favorites", second);
                try {
                    if (!secondWasFavorite) runOnMainSync(() -> radio.toggleFavorite(second));
                    var favorites = children(browser, "favorites");
                    var target = favorites.stream().filter(item -> RadioLibrary.stationId(item.getMediaId()).equals(id))
                        .findFirst().orElseThrow();
                    require(target.getMediaId().equals("favorites/" + id), "Favorite station scope was lost");
                    controller.getTransportControls().playFromMediaId(target.getMediaId(), null);
                    waitForQueue(controller, favorites.size(), RadioLibrary.FAVORITES);
                    String targetTitle = target.getDescription().getTitle().toString();
                    waitForStation(controller, targetTitle);
                    int next = (favorites.indexOf(target) + 1) % favorites.size();
                    controller.getTransportControls().skipToNext();
                    waitForStation(controller, favorites.get(next).getDescription().getTitle().toString());
                    controller.getTransportControls().skipToPrevious();
                    waitForStation(controller, targetTitle);
                    runOnMainSync(() -> radio.toggleHidden(id));
                    waitForQueue(controller, favorites.size() - 1, RadioLibrary.FAVORITES);
                    require(!includes(browser, "favorites", id), "Hidden favorite appeared in the car");
                } finally {
                    controller.getTransportControls().stop();
                    if (!secondWasFavorite) runOnMainSync(() -> radio.toggleFavorite(second));
                }
            } finally {
                runOnMainSync(() -> {
                    if (radio.hidden.contains(id)) radio.toggleHidden(id);
                    if (radio.favorites.contains(id) != originalFavorite) radio.toggleFavorite(id);
                });
            }
        } finally {
            context.unbindService(phone);
        }
    }

    private void verifySearchAndHistory() {
        Context context = getTargetContext();
        var station = RadioCatalog.load(context).stream().filter(entry -> entry.id().equals("us-kexp"))
            .findFirst().orElseThrow();
        require(RadioLibrary.matchesWords(station, RadioLibrary.searchWords("미국 KEXP")), "Multi-word search missed KEXP");
        require(RadioLibrary.matchesWords(station, RadioLibrary.searchWords("KEXP 미국")), "Search depended on word order");
        var prefs = context.getSharedPreferences("onradio_probe_history", Context.MODE_PRIVATE);
        prefs.edit().clear().apply();
        try {
            var history = new TrackHistory(prefs);
            var track = new MusicRecognition.Track("테스트 곡", "테스트 가수");
            history.remember(track, station);
            history.remember(track, station);
            var reloaded = new TrackHistory(prefs).entries();
            require(reloaded.size() == 1 && reloaded.get(0).track().equals(track)
                && reloaded.get(0).stationId().equals("us-kexp"), "Track history did not persist or deduplicate");
        } finally {
            context.deleteSharedPreferences("onradio_probe_history");
        }
    }

    private boolean includes(MediaBrowser browser, String parent, String id) throws Exception {
        return children(browser, parent).stream().anyMatch(item -> RadioLibrary.stationId(item.getMediaId()).equals(id));
    }

    private static void waitForQueue(MediaController controller, int expectedSize, String scope) {
        long deadline = SystemClock.elapsedRealtime() + 12_000;
        do {
            var queue = controller.getQueue();
            if (queue != null && queue.size() == expectedSize
                    && RadioLibrary.scopeForId(queue.get(0).getDescription().getMediaId()).equals(scope)) return;
            SystemClock.sleep(100);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("Wrong car queue for " + scope + ": " + controller.getQueue());
    }

    private List<MediaBrowser.MediaItem> children(MediaBrowser browser, String id) throws Exception {
        AtomicReference<List<MediaBrowser.MediaItem>> values = new AtomicReference<>();
        CountDownLatch loaded = new CountDownLatch(1);
        runOnMainSync(() -> {
            browser.unsubscribe(id);
            browser.subscribe(id, new MediaBrowser.SubscriptionCallback() {
                @Override public void onChildrenLoaded(String parentId, List<MediaBrowser.MediaItem> items) {
                    values.set(items);
                    loaded.countDown();
                }
            });
        });
        require(loaded.await(8, TimeUnit.SECONDS), "Did not receive children for " + id);
        return values.get();
    }

    private static void waitForStation(MediaController controller, String title) {
        long deadline = SystemClock.elapsedRealtime() + 25_000;
        while (SystemClock.elapsedRealtime() < deadline) {
            MediaMetadata metadata = controller.getMetadata();
            PlaybackState state = controller.getPlaybackState();
            if (metadata != null && state != null && title.equals(metadata.getString(MediaMetadata.METADATA_KEY_TITLE))
                    && state.getState() == PlaybackState.STATE_PLAYING) return;
            SystemClock.sleep(150);
        }
        throw new AssertionError("Car transport did not play " + title);
    }

    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
