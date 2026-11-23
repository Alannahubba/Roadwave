package kr.onradio.player;

import android.net.Uri;
import android.content.Context;
import android.os.Bundle;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.LibraryResult;
import androidx.media3.session.CommandButton;
import androidx.media3.session.MediaLibraryService.LibraryParams;
import androidx.media3.session.MediaLibraryService.MediaLibrarySession;
import androidx.media3.session.MediaSession;
import androidx.media3.session.SessionError;
import androidx.media3.session.SessionCommand;
import androidx.media3.session.SessionResult;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@OptIn(markerClass = UnstableApi.class)
final class RadioLibrary implements MediaLibrarySession.Callback {
    static final Uri RADIO_ARTWORK = Uri.parse("android.resource://kr.onradio.player/drawable/roadwave_artwork");
    private static final String ROOT = "root", COUNTRIES = "countries";
    static final String ALL = "all", FAVORITES = "favorites";
    private static final String COUNTRY_PREFIX = "country:", FAVORITE_COUNTRY_PREFIX = "favorites:";
    static final String RECOGNIZE_ACTION = "kr.onradio.player.RECOGNIZE";
    static final String FAVORITE_ACTION = "kr.onradio.player.FAVORITE";
    static final String HIDE_ACTION = "kr.onradio.player.HIDE";
    private static final SessionCommand RECOGNIZE = new SessionCommand(RECOGNIZE_ACTION, Bundle.EMPTY);
    private static final SessionCommand FAVORITE = new SessionCommand(FAVORITE_ACTION, Bundle.EMPTY);
    private static final SessionCommand HIDE = new SessionCommand(HIDE_ACTION, Bundle.EMPTY);
    private final RadioService radio;
    private final Map<String, List<MediaItem>> browsedLists = new HashMap<>();
    private List<CommandButton> buttons = List.of();
    private boolean starred, hidden;

    RadioLibrary(RadioService radio) { this.radio = radio; }

    @Override public MediaSession.ConnectionResult onConnect(MediaSession session, MediaSession.ControllerInfo browser) {
        if (!radio.allowsController(session, browser)) return MediaSession.ConnectionResult.reject();
        var commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon();
        if (radio.isCarController(session, browser)) commands.add(RECOGNIZE).add(FAVORITE).add(HIDE);
        return new MediaSession.ConnectionResult.AcceptedResultBuilder(session)
            .setAvailableSessionCommands(commands.build()).build();
    }

    void updateButtons(MediaSession session) {
        if (radio.localPlayback) {
            if (!buttons.isEmpty()) {
                buttons = List.of();
                session.setMediaButtonPreferences(buttons);
            }
            return;
        }
        boolean favorite = radio.favorites.contains(radio.station.id());
        boolean hiddenNow = radio.hidden.contains(radio.station.id());
        if (!buttons.isEmpty() && favorite == starred && hiddenNow == hidden) return;
        starred = favorite;
        hidden = hiddenNow;
        buttons = List.of(
            new CommandButton.Builder(CommandButton.ICON_UNDEFINED).setCustomIconResId(R.drawable.ic_car_recognize)
                .setDisplayName(radio.localizedString(R.string.recognize)).setSessionCommand(RECOGNIZE).build(),
            new CommandButton.Builder(favorite ? CommandButton.ICON_STAR_FILLED : CommandButton.ICON_STAR_UNFILLED)
                .setDisplayName(favorite ? radio.localizedString(R.string.remove_favorite) : radio.localizedString(R.string.add_favorite)).setSessionCommand(FAVORITE).build(),
            new CommandButton.Builder(CommandButton.ICON_BLOCK)
                .setDisplayName(hiddenNow ? radio.localizedString(R.string.show_station) : radio.localizedString(R.string.hide_station)).setSessionCommand(HIDE).build());
        session.setMediaButtonPreferences(buttons);
    }

    @Override public ListenableFuture<SessionResult> onCustomCommand(MediaSession session,
            MediaSession.ControllerInfo browser, SessionCommand command, Bundle args) {
        if (!radio.isCarController(session, browser)) {
            return Futures.immediateFuture(new SessionResult(SessionError.ERROR_PERMISSION_DENIED));
        }
        if (radio.localPlayback) return Futures.immediateFuture(new SessionResult(SessionError.ERROR_INVALID_STATE));
        switch (command.customAction) {
            case RECOGNIZE_ACTION -> {
                if (!radio.canRecognize()) {
                    return Futures.immediateFuture(new SessionResult(SessionError.ERROR_INVALID_STATE));
                }
                radio.recognizeInCar();
            }
            case FAVORITE_ACTION -> radio.toggleFavorite(radio.station.id());
            case HIDE_ACTION -> radio.toggleHidden(radio.station.id());
            default -> { return MediaLibrarySession.Callback.super.onCustomCommand(session, browser, command, args); }
        }
        return Futures.immediateFuture(new SessionResult(SessionResult.RESULT_SUCCESS));
    }

    @Override public ListenableFuture<LibraryResult<MediaItem>> onGetLibraryRoot(MediaLibrarySession session,
            MediaSession.ControllerInfo browser, LibraryParams params) {
        return Futures.immediateFuture(LibraryResult.ofItem(folder(ROOT, radio.localizedString(R.string.app_name)), params));
    }

    @Override public ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> onGetChildren(MediaLibrarySession session,
            MediaSession.ControllerInfo browser, String parent, int page, int pageSize, LibraryParams params) {
        List<MediaItem> items = children(parent);
        if (ROOT.equals(parent) || COUNTRIES.equals(parent) || ALL.equals(parent)
                || FAVORITES.equals(parent) || parent.startsWith(COUNTRY_PREFIX)
                && List.of(RadioCatalog.COUNTRY_CODES).contains(parent.substring(COUNTRY_PREFIX.length()))) {
            browsedLists.put(parent, items);
        }
        return childPage(items, page, pageSize, params);
    }

    private List<MediaItem> children(String parent) {
        var items = new ArrayList<MediaItem>();
        if (ROOT.equals(parent)) {
            items.add(folder(ALL, radio.localizedString(R.string.all_stations)));
            items.add(folder(COUNTRIES, radio.localizedString(R.string.stations_by_country)));
            items.add(folder(FAVORITES, radio.localizedString(R.string.favorites)));
        } else if (COUNTRIES.equals(parent)) {
            for (int i = 1; i < RadioCatalog.COUNTRY_CODES.length; i++) {
                items.add(folder(COUNTRY_PREFIX + RadioCatalog.COUNTRY_CODES[i], RadioCatalog.countryName(radio.languageContext(), RadioCatalog.COUNTRY_CODES[i])));
            }
        } else {
            for (RadioCatalog.Station entry : queue(parent)) items.add(playable(radio.languageContext(), entry, parent));
        }
        return items;
    }

    @Override public ListenableFuture<LibraryResult<MediaItem>> onGetItem(MediaLibrarySession session,
            MediaSession.ControllerInfo browser, String id) {
        RadioCatalog.Station entry = visibleStation(stationId(id));
        String scope = scopeForId(id);
        if (entry == null || !includes(entry, scope)) {
            return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE));
        }
        return Futures.immediateFuture(LibraryResult.ofItem(playable(radio.languageContext(), entry, scope), null));
    }

    @Override public ListenableFuture<LibraryResult<Void>> onSearch(MediaLibrarySession session,
            MediaSession.ControllerInfo browser, String query, LibraryParams params) {
        session.notifySearchResultChanged(browser, query, matches(query).size(), params);
        return Futures.immediateFuture(LibraryResult.ofVoid(params));
    }

    @Override public ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> onGetSearchResult(MediaLibrarySession session,
            MediaSession.ControllerInfo browser, String query, int page, int pageSize, LibraryParams params) {
        var result = new ArrayList<MediaItem>();
        for (RadioCatalog.Station match : matches(query)) result.add(playable(radio.languageContext(), match, ALL));
        return childPage(result, page, pageSize, params);
    }

    @Override public ListenableFuture<List<MediaItem>> onAddMediaItems(MediaSession session,
            MediaSession.ControllerInfo browser, List<MediaItem> items) {
        if (items.isEmpty()) return Futures.immediateFuture(Collections.emptyList());
        MediaItem request = items.get(0);
        String scope = scopeForId(request.mediaId);
        RadioCatalog.Station entry = visibleStation(stationId(request.mediaId));
        if (entry == null && request.requestMetadata.searchQuery != null) {
            List<RadioCatalog.Station> result = matches(request.requestMetadata.searchQuery);
            if (!result.isEmpty()) entry = result.get(0);
        }
        if (entry == null) return Futures.immediateFuture(Collections.emptyList());
        if (isFavoriteScope(scope) && !includes(entry, scope)) {
            return Futures.immediateFuture(Collections.emptyList());
        }
        String selectionScope = includes(entry, scope) ? scope : ALL;
        return Futures.transform(radio.resolveLibraryItem(entry, selectionScope), Collections::singletonList,
            MoreExecutors.directExecutor());
    }

    @Override public ListenableFuture<MediaSession.MediaItemsWithStartPosition> onPlaybackResumption(MediaSession session,
            MediaSession.ControllerInfo browser) {
        String scope = radio.queueScope;
        RadioCatalog.Station entry = radio.stationForResume(scope);
        if (entry == null) return Futures.immediateFailedFuture(new IllegalStateException("No visible stations"));
        return Futures.transform(radio.resolveLibraryItem(entry, scope), item -> new MediaSession.MediaItemsWithStartPosition(
            Collections.singletonList(item), 0, C.TIME_UNSET), MoreExecutors.directExecutor());
    }

    static String scopeForCountry(String country) { return country.isEmpty() ? ALL : COUNTRY_PREFIX + country; }

    static String favoriteScopeForCountry(String country) {
        return country.isEmpty() ? FAVORITES : FAVORITE_COUNTRY_PREFIX + country;
    }

    static boolean isFavoriteScope(String scope) {
        return FAVORITES.equals(scope) || scope.startsWith(FAVORITE_COUNTRY_PREFIX);
    }

    static String scopedId(String stationId, String scope) {
        return isFavoriteScope(scope) || scope.startsWith(COUNTRY_PREFIX) ? scope + "/" + stationId : stationId;
    }

    static String scopeForId(String id) {
        int slash = id.indexOf('/');
        if (slash < 0) return ALL;
        String scope = id.substring(0, slash);
        return isFavoriteScope(scope) || scope.startsWith(COUNTRY_PREFIX) ? scope : ALL;
    }

    static String stationId(String id) {
        return scopeForId(id).equals(ALL) ? id : id.substring(id.indexOf('/') + 1);
    }

    boolean includes(RadioCatalog.Station entry, String scope) {
        if (radio.hidden.contains(entry.id())) return false;
        if (isFavoriteScope(scope)) {
            return radio.favorites.contains(entry.id())
                && (FAVORITES.equals(scope) || scope.equals(favoriteScopeForCountry(entry.country())));
        }
        return ALL.equals(scope) || scope.equals(scopeForCountry(entry.country()));
    }

    List<RadioCatalog.Station> queue(String scope) {
        var entries = new ArrayList<RadioCatalog.Station>();
        for (RadioCatalog.Station entry : radio.stations) {
            if (includes(entry, scope)) entries.add(entry);
        }
        return entries;
    }

    RadioCatalog.Station visibleStation(String id) {
        if (radio.hidden.contains(id)) return null;
        for (RadioCatalog.Station entry : radio.stations) if (entry.id().equals(id)) return entry;
        return null;
    }

    List<RadioCatalog.Station> matches(String query) {
        String[] words = searchWords(query);
        var results = new ArrayList<RadioCatalog.Station>();
        for (RadioCatalog.Station entry : radio.stations) {
            if (radio.hidden.contains(entry.id())) continue;
            if (matchesWords(entry, words)) results.add(entry);
        }
        return results;
    }

    static String[] searchWords(String query) {
        return query.trim().toLowerCase(Locale.ROOT).split("\\s+");
    }

    static boolean matchesWords(RadioCatalog.Station entry, String[] words) {
        String keywords = (entry.name() + " " + entry.group() + " " + entry.description() + " "
            + RadioCatalog.countryKeywords(entry.country())).toLowerCase(Locale.ROOT);
        for (String word : words) if (!keywords.contains(word)) return false;
        return true;
    }

    void changed(MediaLibrarySession session) {
        for (String parent : List.copyOf(browsedLists.keySet())) {
            List<MediaItem> updated = children(parent);
            if (!updated.equals(browsedLists.put(parent, updated))) {
                session.notifyChildrenChanged(parent, updated.size(), null);
            }
        }
    }

    private static ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> childPage(List<MediaItem> items, int page,
            int size, LibraryParams params) {
        int first = (int) Math.min(items.size(), (long) Math.max(page, 0) * Math.max(size, 1));
        int end = (int) Math.min(items.size(), (long) first + Math.max(size, 1));
        return Futures.immediateFuture(LibraryResult.ofItemList(items.subList(first, end), params));
    }

    private static MediaItem folder(String id, String title) {
        return new MediaItem.Builder().setMediaId(id).setMediaMetadata(new MediaMetadata.Builder()
            .setTitle(title).setIsBrowsable(true).setIsPlayable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS).build()).build();
    }

    static MediaItem playable(Context context, RadioCatalog.Station entry, String scope) {
        return new MediaItem.Builder().setMediaId(scopedId(entry.id(), scope)).setMediaMetadata(new MediaMetadata.Builder()
            .setTitle(entry.name()).setSubtitle(RadioCatalog.countryName(context, entry.country())).setArtworkUri(RADIO_ARTWORK)
            .setIsBrowsable(false).setIsPlayable(true).setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
            .build()).build();
    }
}
