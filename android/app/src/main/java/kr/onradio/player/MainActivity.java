package kr.onradio.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.SearchManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.IOException;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

public final class MainActivity extends Activity {
    private static final int BG = 0xff111317, CARD = 0xff22252b, LINE = 0xff42474e;
    private static final int TEXT = 0xfff1f1ec, MUTED = 0xffbec2c6;
    private static final int LCD_TOP = 0xffd9ef69, LCD_BOTTOM = 0xffa8c73f, LCD_INK = 0xff182410;
    private static final int OPEN_AUDIO = 90, OPEN_MUSIC_FOLDER = 91;
    private static final Set<String> AUDIO_EXTENSIONS = Set.of(
        ".mp3", ".flac", ".wav", ".m4a", ".aac", ".ogg", ".opus", ".wma", ".aif", ".aiff", ".m4b", ".oga");
    private record MusicEntry(String id, String name, boolean directory) {}
    private static final int ALL = 0, FAVORITES = 1, HIDDEN = 2;
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT, WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private RadioService radio;
    private boolean bound, listVisible, firstConnect = true;
    private String pendingVoiceSearch;
    private List<MediaItem> pendingLocalFiles = List.of();
    private int pendingLocalIndex;
    private Uri musicFolder;
    private int mode = ALL;
    private String group = "";
    private String renderedCountry = "";
    private LinearLayout library;
    private ScrollView playerScroll;
    private boolean horizontal, shortWindow, shortLandscape;
    private boolean songDisplay;
    private TextView bandIndicator;
    private LinearLayout lcd;
    private int lcdLevel;
    private Button bandKey, scanKey, favoriteKey, displayKey, dimKey, recallKey;
    private final Button[] presetKeys = new Button[6];
    private View signal;
    private TextView title, countryName, playbackStatus, trackTitle, trackByline, stationCount;
    private Button countryButton, listToggle, star, previous, play, next, identify, automatic, youtube, tools, history, sleepPlus, sleepClear;
    private ClockDisplay sleepRemaining;
    private static final DateTimeFormatter CLOCK_FORMAT = DateTimeFormatter.ofPattern("hh:mm:ss", Locale.ROOT);
    private final Runnable clockTick = new Runnable() {
        @Override public void run() {
            renderClock();
            sleepRemaining.postDelayed(this, 1000);
        }
    };
    private Button[] tabs;
    private Button groupButton;
    private EditText search;
    private ListView stationList;
    private StationAdapter adapter;
    private final Runnable changed = this::render;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            radio = ((RadioService.LocalBinder) binder).getService();
            radio.subscribe(changed);
            render();
            if (!pendingLocalFiles.isEmpty()) openPendingFiles();
            else if (pendingVoiceSearch != null) playVoiceSearch();
            else if (firstConnect && radio.autoplay && !radio.isRequested() && !radio.localPlayback) radio.togglePlayback();
            firstConnect = false;
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            radio = null;
            render();
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        var options = getSharedPreferences("onradio", MODE_PRIVATE);
        songDisplay = options.getBoolean("songDisplay", false);
        String folder = options.getString("musicFolder", "");
        if (!folder.isEmpty()) musicFolder = Uri.parse(folder);
        lcdLevel = Math.max(0, Math.min(2, options.getInt("lcdLevel", 0)));
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        if (state != null) {
            listVisible = state.getBoolean("listVisible");
            firstConnect = false;
            mode = state.getInt("mode");
            group = state.getString("group", "");
        }
        build();
        handleSearchIntent(getIntent());
        if (state != null) search.setText(state.getString("search", ""));
        render();
    }

    private void showMusicMenu(View button) {
        PopupMenu menu = new PopupMenu(this, button);
        if (musicFolder != null) {
            menu.getMenu().add("음악 폴더 둘러보기").setOnMenuItemClickListener(item -> {
                browseMusicFolder(musicFolder);
                return true;
            });
        }
        menu.getMenu().add("폴더 선택…").setOnMenuItemClickListener(item -> {
            var picker = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            if (musicFolder != null) picker.putExtra(DocumentsContract.EXTRA_INITIAL_URI, musicFolder);
            launchPicker(picker, OPEN_MUSIC_FOLDER);
            return true;
        });
        menu.getMenu().add("음악 파일 열기…").setOnMenuItemClickListener(item -> {
            chooseLocalFiles();
            return true;
        });
        menu.show();
    }

    private void chooseLocalFiles() {
        var picker = new Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("audio/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        launchPicker(picker, OPEN_AUDIO);
    }

    private void launchPicker(Intent picker, int requestCode) {
        try { startActivityForResult(picker, requestCode); }
        catch (android.content.ActivityNotFoundException error) {
            Toast.makeText(this, "파일 선택 앱을 찾지 못했어요.", Toast.LENGTH_SHORT).show();
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null) return;
        if (request == OPEN_MUSIC_FOLDER && data.getData() != null) {
            musicFolder = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(musicFolder, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                getSharedPreferences("onradio", MODE_PRIVATE).edit().putString("musicFolder", musicFolder.toString()).apply();
            } catch (SecurityException ignored) {
                // This provider only grants access for the current session.
            }
            browseMusicFolder(musicFolder);
            return;
        }
        if (request != OPEN_AUDIO) return;
        var files = new ArrayList<MediaItem>();
        var clips = data.getClipData();
        if (clips != null) {
            for (int i = 0; i < clips.getItemCount(); i++) {
                Uri uri = clips.getItemAt(i).getUri();
                if (uri != null) files.add(localItem(uri));
            }
        } else if (data.getData() != null) files.add(localItem(data.getData()));
        queueLocalFiles(files, 0);
    }

    private void browseMusicFolder(Uri tree) {
        try {
            var root = new MusicEntry(DocumentsContract.getTreeDocumentId(tree), "음악 폴더", true);
            browseMusicFolder(tree, List.of(root));
        } catch (IllegalArgumentException error) {
            Toast.makeText(this, "폴더를 다시 선택해 주세요.", Toast.LENGTH_SHORT).show();
        }
    }

    private void browseMusicFolder(Uri tree, List<MusicEntry> path) {
        MusicEntry folder = path.get(path.size() - 1);
        var loading = new AlertDialog.Builder(this).setMessage("폴더를 읽는 중…")
            .setNegativeButton("닫기", null).show();
        CompletableFuture.supplyAsync(() -> readMusicFolder(tree, folder)).whenComplete((entries, error) ->
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || !loading.isShowing()) return;
                loading.dismiss();
                var builder = new AlertDialog.Builder(this).setTitle(folder.name());
                if (path.size() > 1) builder.setNegativeButton("‹ 상위 폴더", (d, index) ->
                    browseMusicFolder(tree, path.subList(0, path.size() - 1)));
                else builder.setNegativeButton("닫기", null);
                if (error != null || entries.isEmpty()) {
                    builder.setMessage(error != null ? "폴더를 읽을 수 없어요. 폴더 선택에서 접근 권한을 다시 허용해 주세요."
                        : "표시할 폴더나 음악 파일이 없어요.").show();
                    return;
                }
                var rows = new ArrayList<String>();
                int folderCount = 0;
                for (MusicEntry entry : entries) {
                    if (entry.directory()) folderCount++;
                    rows.add(entry.directory() ? "폴더  ›  " + entry.name() : entry.name());
                }
                int firstTrack = folderCount;
                List<MediaItem> files = musicItems(tree, entries);
                var dialog = builder.setSingleChoiceItems(rows.toArray(new String[0]), -1, null)
                    .setNeutralButton("폴더 열기", null).setPositiveButton("이 폴더 재생", null).show();
                Button enter = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
                Button playFolder = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                ListView list = dialog.getListView();
                enter.setEnabled(false);
                playFolder.setEnabled(!files.isEmpty());
                list.setOnItemClickListener((adapter, view, index, id) -> {
                    boolean directory = entries.get(index).directory();
                    enter.setEnabled(directory);
                    playFolder.setEnabled(true);
                    playFolder.setText(directory ? "선택 폴더 재생" : "선택 곡 재생");
                });
                enter.setOnClickListener(view -> {
                    int index = list.getCheckedItemPosition();
                    if (index < 0 || index >= firstTrack) return;
                    var childPath = new ArrayList<>(path);
                    childPath.add(entries.get(index));
                    dialog.dismiss();
                    browseMusicFolder(tree, childPath);
                });
                playFolder.setOnClickListener(view -> {
                    int index = list.getCheckedItemPosition();
                    if (index >= 0 && index < firstTrack) {
                        playSelectedFolder(tree, entries.get(index), dialog, playFolder);
                    } else if (!files.isEmpty()) {
                        dialog.dismiss();
                        queueLocalFiles(files, index < 0 ? 0 : index - firstTrack);
                    }
                });
            }));
    }

    private void playSelectedFolder(Uri tree, MusicEntry folder, AlertDialog dialog, Button playFolder) {
        playFolder.setEnabled(false);
        CompletableFuture.supplyAsync(() -> musicItems(tree, readMusicFolder(tree, folder)))
            .whenComplete((files, error) -> runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || !dialog.isShowing()) return;
                playFolder.setEnabled(true);
                if (error != null || files.isEmpty()) {
                    Toast.makeText(this, error != null ? "선택한 폴더를 읽을 수 없어요."
                        : "선택한 폴더에 재생할 곡이 없어요.", Toast.LENGTH_SHORT).show();
                } else {
                    dialog.dismiss();
                    queueLocalFiles(files, 0);
                }
            }));
    }

    private static List<MediaItem> musicItems(Uri tree, List<MusicEntry> entries) {
        var files = new ArrayList<MediaItem>();
        for (MusicEntry entry : entries) {
            if (!entry.directory()) files.add(mediaItem(DocumentsContract.buildDocumentUriUsingTree(tree, entry.id()), entry.name()));
        }
        return files;
    }

    private List<MusicEntry> readMusicFolder(Uri tree, MusicEntry parent) {
        var entries = new ArrayList<MusicEntry>();
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent.id());
        try (var cursor = getContentResolver().query(children, new String[]{
                Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE
            }, null, null, null)) {
            if (cursor == null) throw new IllegalStateException("No document list");
            while (cursor.moveToNext()) {
                String id = cursor.getString(0), name = cursor.getString(1), mime = cursor.getString(2);
                if (id == null || name == null) continue;
                boolean directory = Document.MIME_TYPE_DIR.equals(mime);
                int dot = name.lastIndexOf('.');
                if (directory || (mime != null && mime.startsWith("audio/"))
                        || (dot >= 0 && AUDIO_EXTENSIONS.contains(name.substring(dot).toLowerCase(Locale.ROOT)))) {
                    entries.add(new MusicEntry(id, name, directory));
                }
            }
        }
        entries.sort(Comparator.comparing(MusicEntry::directory).reversed()
            .thenComparing(MusicEntry::name, String.CASE_INSENSITIVE_ORDER).thenComparing(MusicEntry::name));
        return entries;
    }

    private void queueLocalFiles(List<MediaItem> files, int index) {
        pendingLocalFiles = files;
        pendingLocalIndex = index;
        openPendingFiles();
    }

    private MediaItem localItem(Uri uri) {
        String title = uri.getLastPathSegment();
        try (var cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) title = cursor.getString(0);
        } catch (RuntimeException ignored) {
            // Some file providers do not return a display name.
        }
        return mediaItem(uri, title);
    }

    private static MediaItem mediaItem(Uri uri, String title) {
        return new MediaItem.Builder().setUri(uri).setMediaId(uri.toString())
            .setMediaMetadata(new MediaMetadata.Builder().setTitle(title == null ? "음악 파일" : title).build()).build();
    }

    private void openPendingFiles() {
        if (radio == null || pendingLocalFiles.isEmpty()) return;
        radio.openLocalFiles(pendingLocalFiles, pendingLocalIndex);
        pendingLocalFiles = List.of();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleSearchIntent(intent);
    }

    private void handleSearchIntent(Intent intent) {
        if (!MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH.equals(intent.getAction())) return;
        String query = intent.getStringExtra(SearchManager.QUERY);
        pendingVoiceSearch = query == null ? "" : query;
        if (radio != null) playVoiceSearch();
    }

    private void playVoiceSearch() {
        String query = pendingVoiceSearch;
        pendingVoiceSearch = null;
        if (!radio.playSearch(query)) Toast.makeText(this, "일치하는 방송국이 없어요.", Toast.LENGTH_SHORT).show();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("listVisible", listVisible);
        state.putInt("mode", mode);
        state.putString("group", group);
        state.putString("search", search.getText().toString());
        super.onSaveInstanceState(state);
    }

    @Override protected void onStart() {
        super.onStart();
        bound = bindService(new Intent(this, RadioService.class).setAction(RadioService.LOCAL),
            connection, BIND_AUTO_CREATE);
        clockTick.run();
    }

    @Override protected void onStop() {
        sleepRemaining.removeCallbacks(clockTick);
        if (radio != null) radio.unsubscribe(changed);
        if (bound) unbindService(connection);
        bound = false;
        radio = null;
        super.onStop();
    }

    private void build() {
        var configuration = getResources().getConfiguration();
        horizontal = configuration.screenWidthDp >= 480 && configuration.screenWidthDp > configuration.screenHeightDp;
        shortWindow = configuration.screenHeightDp < 740;
        shortLandscape = horizontal && configuration.screenHeightDp < 360;
        LinearLayout root = column();
        root.setBackgroundColor(BG);
        root.setPadding(dp(16), dp(10), dp(16), dp(8));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                var edges = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(dp(16) + edges.left, dp(10) + edges.top, dp(16) + edges.right, dp(8) + edges.bottom);
            }
            return insets;
        });
        setContentView(root);
        root.setFocusableInTouchMode(true);
        root.requestFocus();
        LinearLayout header = row();
        TextView brand = label("온라디오", 22, TEXT, true);
        header.addView(brand, weighted());
        countryButton = button("모든 국가 ▾", view -> chooseCountry());
        countryButton.setContentDescription("국가 선택");
        header.addView(countryButton);
        listToggle = button("채널 목록 OFF", view -> {
            listVisible = !listVisible;
            render();
        });
        side(header, listToggle);
        root.addView(header);

        LinearLayout body = horizontal ? row() : column();
        body.setGravity(Gravity.TOP);
        var bodyParams = new LinearLayout.LayoutParams(MATCH, 0, 1);
        bodyParams.topMargin = dp(10);
        root.addView(body, bodyParams);

        playerScroll = new ScrollView(this);
        body.addView(playerScroll, playerLayout());
        LinearLayout player = column();
        player.setBackground(new RadioFace());
        player.setPadding(dp(19), dp(16), dp(19), dp(17));
        playerScroll.addView(player, new ScrollView.LayoutParams(MATCH, WRAP));

        LinearLayout metal = row();
        View slot = new View(this);
        slot.setBackground(gradient(0xff060709, 0xff13151a, 0xff393d44, 3));
        metal.addView(slot, new LinearLayout.LayoutParams(0, dp(8), 1));
        signal = new View(this);
        signal.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        signal.setBackground(shape(LINE, 0, 3));
        var indicatorParams = new LinearLayout.LayoutParams(dp(13), dp(5));
        indicatorParams.leftMargin = dp(9);
        metal.addView(signal, indicatorParams);
        player.addView(metal);
        if (shortLandscape) metal.setVisibility(View.GONE);

        LinearLayout functionKeys = row();
        bandKey = physicalKey("BAND", view -> {
            if (radio == null) return;
            if (radio.localPlayback) radio.returnToRadio();
            else radio.cycleCountry();
        });
        bandKey.setTooltipText("선택할 국가를 바꿉니다.");
        functionKeys.addView(bandKey, weighted());
        scanKey = physicalKey("SCAN", view -> { if (radio != null) radio.toggleScan(); });
        scanKey.setTooltipText("채널을 6초씩 들어봅니다. 다시 누르면 현재 채널에 멈춥니다.");
        side(functionKeys, scanKey, 1f);
        favoriteKey = physicalKey("FAV", view -> {
            if (radio != null) radio.setFavoriteTuning(!radio.favoriteTuning);
        });
        favoriteKey.setTooltipText("SEEK와 SCAN을 현재 국가의 즐겨찾기 안에서만 사용합니다.");
        side(functionKeys, favoriteKey, 1f);
        displayKey = physicalKey("DISP", view -> {
            songDisplay = !songDisplay;
            getSharedPreferences("onradio", MODE_PRIVATE).edit().putBoolean("songDisplay", songDisplay).apply();
            render();
        });
        displayKey.setTooltipText("LCD에 채널 정보 또는 곡 정보를 크게 표시합니다.");
        side(functionKeys, displayKey, 1f);
        dimKey = physicalKey("DIM", view -> {
            lcdLevel = (lcdLevel + 1) % 3;
            getSharedPreferences("onradio", MODE_PRIVATE).edit().putInt("lcdLevel", lcdLevel).apply();
            updateLcdBrightness();
        });
        side(functionKeys, dimKey, 1f);
        for (Button key : new Button[]{bandKey, scanKey, favoriteKey, displayKey, dimKey}) {
            key.setPadding(dp(2), 0, dp(2), 0);
            key.setSingleLine(true);
        }
        if (!shortLandscape) spaceAbove(player, functionKeys, 5);

        LinearLayout bezel = column();
        bezel.setPadding(dp(4), dp(4), dp(4), dp(4));
        bezel.setBackground(gradient(0xff08090a, 0xff18191a, 0xff656962, 7));
        lcd = column();
        LinearLayout display = lcd;
        display.setPadding(dp(10), dp(5), dp(10), dp(6));
        updateLcdBrightness();
        bezel.addView(display, new LinearLayout.LayoutParams(MATCH, WRAP));
        spaceAbove(player, bezel, shortLandscape ? 0 : 7);

        LinearLayout stationHeader = row();
        LinearLayout identity = column();
        title = label("온라디오", 22, LCD_INK, true);
        title.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        countryName = label("", 12, LCD_INK, true);
        bandIndicator = label("", 11, LCD_INK, true);
        identity.addView(title);
        if (shortLandscape) {
            LinearLayout readout = row();
            readout.addView(countryName, weighted());
            readout.addView(bandIndicator);
            identity.addView(readout);
        } else identity.addView(countryName);
        stationHeader.addView(identity, weighted());
        star = button("☆", view -> { if (radio != null) radio.toggleFavorite(radio.station.id()); });
        star.setContentDescription("즐겨찾기");
        side(stationHeader, star);
        display.addView(stationHeader);
        playbackStatus = label("온라디오를 불러오는 중…", 12, LCD_INK, false);
        display.addView(playbackStatus);
        if (!shortLandscape) display.addView(bandIndicator);
        trackTitle = label("", 14, shortLandscape ? TEXT : LCD_INK, true);
        trackByline = label("", 12, shortLandscape ? MUTED : LCD_INK, false);
        Typeface lcdFont = Typeface.createFromAsset(getAssets(), "neodgm.ttf");
        for (TextView readout : new TextView[] {title, countryName, playbackStatus, bandIndicator}) {
            readout.setTypeface(lcdFont);
        }
        if (!shortLandscape) {
            trackTitle.setTypeface(lcdFont);
            trackByline.setTypeface(lcdFont);
        }
        for (TextView readout : new TextView[] {title, countryName, playbackStatus, bandIndicator, trackTitle, trackByline}) {
            readout.setSingleLine(true);
            readout.setEllipsize(TextUtils.TruncateAt.MARQUEE);
            readout.setMarqueeRepeatLimit(-1);
            readout.setSelected(true);
            readout.setGravity(Gravity.CENTER_VERTICAL);
            readout.setIncludeFontPadding(false);
            readout.setHeight(readout.getLineHeight() + dp(4));
        }

        LinearLayout transport = row();
        previous = seekKey("‹ SEEK", -1);
        previous.setContentDescription("이전 채널");
        transport.addView(previous, weighted());
        play = physicalKey("▶ 재생", view -> { if (radio != null) radio.togglePlayback(); });
        styleButton(play, true);
        side(transport, play, 1f);
        next = seekKey("SEEK ›", 1);
        next.setContentDescription("다음 채널");
        side(transport, next, 1f);
        recallKey = physicalKey("RCL", view -> { if (radio != null) radio.recallPrevious(); });
        recallKey.setContentDescription("직전 채널");
        recallKey.setTooltipText("직전에 선택한 방송으로 돌아갑니다.");
        side(transport, recallKey, 1f);
        for (Button key : new Button[]{previous, play, next, recallKey}) {
            key.setPadding(dp(3), 0, dp(3), 0);
            key.setSingleLine(true);
        }
        spaceAbove(player, transport, 7);
        if (shortLandscape) spaceAbove(player, functionKeys, 5);
        LinearLayout presets = row();
        for (int index = 0; index < presetKeys.length; index++) {
            int slotNumber = index;
            Button key = physicalKey(Integer.toString(index + 1), view -> recallPreset(slotNumber));
            key.setPadding(dp(2), 0, dp(2), 0);
            key.setOnLongClickListener(view -> {
                if (radio != null && radio.storePreset(slotNumber)) {
                    Toast.makeText(this, (slotNumber + 1) + "번에 " + radio.station.name() + " 저장", Toast.LENGTH_SHORT).show();
                }
                return true;
            });
            presetKeys[index] = key;
            if (index == 0) presets.addView(key, weighted());
            else side(presets, key, 1f);
        }
        spaceAbove(player, presets, 5);
        LinearLayout trackPanel = shortLandscape ? player : display;
        spaceAbove(trackPanel, trackTitle, shortLandscape ? 6 : 3);
        trackPanel.addView(trackByline);

        LinearLayout music = row();
        identify = button("곡 인식", view -> { if (radio != null) radio.recognize(); });
        identify.setTooltipText("마이크 없이 방송 소리에서 만든 음악 지문을 Shazam에 보냅니다.");
        music.addView(identify, weighted());
        automatic = button("자동 꺼짐", view -> { if (radio != null) radio.setAutomatic(!radio.automatic); });
        automatic.setTooltipText("방송 소리에서 만든 음악 지문을 Shazam에 보내 자동으로 곡을 찾습니다.");
        side(music, automatic, 1f);
        youtube = button("YouTube Music ↗", view -> {
            if (radio != null && radio.track != null) open(radio.track.youtubeMusic());
        });
        youtube.setTooltipText("짧게 누르면 YouTube Music, 길게 누르면 음악 검색 서비스 선택");
        youtube.setOnLongClickListener(view -> {
            if (radio == null || radio.track == null) return false;
            showTrackSearch(radio.track);
            return true;
        });
        side(music, youtube, 1.55f);
        spaceAbove(player, music, 5);
        LinearLayout sleepRow = row();
        sleepPlus = physicalKey("취침 +30분", view -> { if (radio != null) radio.extendSleep(); });
        sleepPlus.setTooltipText("남은 시간에 30분을 더합니다. 최대 6시간까지 설정할 수 있습니다.");
        sleepRow.addView(sleepPlus);
        sleepRemaining = new ClockDisplay(this);
        var clockPanel = new LinearLayout.LayoutParams(0, dp(36), 1);
        clockPanel.setMarginStart(dp(5));
        clockPanel.setMarginEnd(dp(5));
        sleepRow.addView(sleepRemaining, clockPanel);
        sleepClear = physicalKey("해제", view -> { if (radio != null) radio.clearSleep(); });
        sleepClear.setContentDescription("취침 타이머 해제");
        sleepRow.addView(sleepClear);
        spaceAbove(player, sleepRow, 5);
        tools = button("설정", view -> showTools());
        LinearLayout toolsRow = row();
        toolsRow.addView(tools, weighted());
        history = button("곡 기록", view -> showTrackHistory());
        side(toolsRow, physicalKey("FILE", this::showMusicMenu));
        side(toolsRow, history);
        spaceAbove(player, toolsRow, 5);

        library = column();
        library.setBackground(shape(CARD, LINE, 10));
        library.setPadding(dp(9), dp(7), dp(9), dp(5));
        LinearLayout filters = row();
        tabs = new Button[] {
            button("전체", v -> setMode(ALL)), button("즐겨찾기", v -> setMode(FAVORITES)),
            button("숨긴 채널", v -> setMode(HIDDEN))
        };
        filters.addView(tabs[0], weighted());
        side(filters, tabs[1], 1f);
        side(filters, tabs[2], 1f);
        library.addView(filters);

        LinearLayout searchRow = row();
        search = new EditText(this);
        search.setSingleLine();
        search.setTextSize(14);
        search.setTextColor(TEXT);
        search.setHintTextColor(MUTED);
        search.setHint(shortLandscape ? "채널 검색" : "채널명·지역·장르 검색");
        search.setBackground(shape(BG, LINE, 9));
        search.setPadding(dp(10), 0, dp(10), 0);
        search.setMinimumHeight(dp(44));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { refreshList(); }
            @Override public void afterTextChanged(Editable s) {}
        });
        searchRow.addView(search, new LinearLayout.LayoutParams(0, dp(44), 1));
        groupButton = button("방송사 ▾", view -> chooseGroup());
        groupButton.setContentDescription("방송사 선택");
        side(searchRow, groupButton);
        Button add = button("+ 추가", view -> addStation());
        side(searchRow, add);
        spaceAbove(library, searchRow, 5);
        stationCount = label("", 12, MUTED, false);
        spaceAbove(library, stationCount, 4);
        if (shortLandscape) stationCount.setVisibility(View.GONE);

        stationList = new ListView(this);
        stationList.setDivider(null);
        stationList.setContentDescription("방송국 목록");
        adapter = new StationAdapter();
        stationList.setAdapter(adapter);
        stationList.setOnItemClickListener((parent, view, position, id) -> {
            if (radio != null && mode != HIDDEN) radio.choose(adapter.getItem(position));
            else if (radio != null) stationMenu(view, adapter.getItem(position));
        });
        stationList.setOnItemLongClickListener((parent, view, position, id) -> {
            stationMenu(view, adapter.getItem(position));
            return true;
        });
        library.addView(stationList, new LinearLayout.LayoutParams(MATCH, 0, 1));
        var libraryParams = horizontal ? new LinearLayout.LayoutParams(0, MATCH, 1)
            : new LinearLayout.LayoutParams(MATCH, 0, 1);
        if (horizontal) libraryParams.leftMargin = dp(10);
        else libraryParams.topMargin = dp(8);
        body.addView(library, libraryParams);
    }

    private LinearLayout.LayoutParams playerLayout() {
        if (horizontal) return new LinearLayout.LayoutParams(0, MATCH, 1.12f);
        if (!listVisible || shortWindow) return new LinearLayout.LayoutParams(MATCH, 0, listVisible ? 1.65f : 1);
        return new LinearLayout.LayoutParams(MATCH, WRAP);
    }

    private void render() {
        if ((library.getVisibility() == View.VISIBLE) != listVisible) playerScroll.setLayoutParams(playerLayout());
        library.setVisibility(listVisible ? View.VISIBLE : View.GONE);
        listToggle.setText(listVisible ? "채널 목록 ON" : "채널 목록 OFF");
        styleToggle(listToggle, listVisible);
        if (radio == null) {
            for (Button control : new Button[] {previous, play, next, identify, automatic, youtube, star, tools, history, sleepPlus, sleepClear}) enable(control, false);
            enable(bandKey, false);
            enable(scanKey, false);
            enable(favoriteKey, false);
            enable(recallKey, false);
            for (Button key : presetKeys) enable(key, false);
            return;
        }
        RadioCatalog.Station station = radio.station;
        String filter = radio.country;
        if (!renderedCountry.equals(filter)) {
            renderedCountry = filter;
            group = "";
        }
        countryButton.setText((filter.isEmpty() ? "모든 국가" : RadioCatalog.countryName(filter)) + " ▾");
        boolean local = radio.localPlayback;
        String nation = RadioCatalog.countryName(station.country());
        showReadout(bandIndicator, local ? "FILE · 저장된 음악" : (radio.favoriteTuning ? "FAV · " : "BAND · ")
            + (filter.isEmpty() ? "모든 국가" : RadioCatalog.countryName(filter)));
        showReadout(title, local ? radio.localTitle() : songDisplay ? (radio.track == null ? "곡 정보" : radio.track.title()) : station.name());
        showReadout(countryName, local ? radio.localPosition() : songDisplay && radio.track != null ? radio.track.artist() : nation);
        showReadout(playbackStatus, radio.status);
        star.setText(radio.favorites.contains(station.id()) ? "★" : "☆");
        play.setText(radio.isRequested() ? "■ 정지" : "▶ 재생");
        enable(play, true);
        enable(star, !local);
        enable(tools, true);
        boolean canStep = radio.canStep(1);
        boolean seekable = radio.localSeekTarget() != null;
        enable(previous, radio.canStep(-1) || seekable);
        enable(next, canStep || seekable);
        previous.setContentDescription(local ? "짧게 이전 곡, 길게 되감기" : "이전 채널");
        next.setContentDescription(local ? "짧게 다음 곡, 길게 빨리감기" : "다음 채널");
        var previousStation = radio.previousStation();
        enable(recallKey, !local && previousStation != null && !previousStation.id().equals(station.id()));
        showReadout(trackTitle, local ? radio.localTimeline() : songDisplay ? station.name() + " · " + nation
            : (radio.track == null ? radio.trackStatus : radio.track.title()));
        showReadout(trackByline, local ? "" : songDisplay ? (radio.track == null ? radio.trackStatus : radio.trackSource)
            : (radio.track == null ? "" : radio.track.artist() + "  ·  " + radio.trackSource));
        bandKey.setText(local ? "FM" : "BAND");
        enable(bandKey, true);
        enable(scanKey, !local && (radio.scanning || canStep));
        enable(favoriteKey, !local);
        styleToggle(favoriteKey, radio.favoriteTuning);
        favoriteKey.setContentDescription(radio.favoriteTuning ? "FAV 즐겨찾기 청취 켜짐" : "FAV 즐겨찾기 청취 꺼짐");
        styleToggle(scanKey, radio.scanning);
        scanKey.setContentDescription(radio.scanning ? "SCAN 탐색 중지" : "SCAN 채널 탐색");
        styleToggle(displayKey, songDisplay);
        displayKey.setContentDescription(songDisplay ? "DISP 곡 정보 모드" : "DISP 채널 정보 모드");
        renderPresets();
        automatic.setText(radio.automatic ? "자동 켜짐" : "자동 꺼짐");
        if (Boolean.TRUE.equals(automatic.getTag()) != radio.automatic || automatic.getTag() == null) {
            styleButton(automatic, radio.automatic);
            automatic.setTag(radio.automatic);
        }
        enable(automatic, !local);
        enable(identify, radio.canRecognize());
        enable(youtube, !local && radio.track != null);
        enable(history, true);
        enable(sleepPlus, true);
        enable(sleepClear, radio.sleepUntil > 0);
        renderClock();
        boolean live = radio.hasAudio();
        if (signal.isSelected() != live) {
            signal.setSelected(live);
            signal.setBackground(shape(live ? LCD_TOP : LINE, 0, 3));
        }
        if (listVisible) refreshList();
    }

    private void renderClock() {
        long now = SystemClock.elapsedRealtime();
        if (radio != null && radio.sleepUntil > now && (now - radio.sleepViewSince) / 5000 % 2 == 0) {
            long seconds = (radio.sleepUntil - now + 999) / 1000;
            sleepRemaining.showTime(String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600,
                seconds / 60 % 60, seconds % 60), true, "취침 타이머 남은 시간", "");
            return;
        }
        String id = radio == null || radio.localPlayback ? "" : radio.station.timeZone();
        ZoneId zone = ZoneId.systemDefault();
        boolean stationTime = false;
        if (!id.isEmpty()) {
            try {
                zone = ZoneId.of(id);
                stationTime = true;
            } catch (DateTimeException ignored) {
                // Keep the device clock when this timezone is unavailable.
            }
        }
        ZonedDateTime local = ZonedDateTime.now(zone);
        sleepRemaining.showTime(local.format(CLOCK_FORMAT), false,
            stationTime ? "방송국 현지 시각" : "기기 현지 시각", local.getHour() < 12 ? "AM" : "PM");
    }

    private void updateLcdBrightness() {
        lcd.setBackground(switch (lcdLevel) {
            case 1 -> gradient(0xffb5ca61, 0xff90a742, 0xff728542, 4);
            case 2 -> gradient(0xff91a24b, 0xff809440, 0xff5a6932, 4);
            default -> gradient(LCD_TOP, LCD_BOTTOM, 0xff819a39, 4);
        });
        String level = switch (lcdLevel) {
            case 1 -> "어둡게";
            case 2 -> "아주 어둡게";
            default -> "기본";
        };
        dimKey.setContentDescription("DIM LCD 밝기 · " + level);
        dimKey.setTooltipText("LCD 밝기 · " + level);
        styleToggle(dimKey, lcdLevel != 0);
    }

    private static void showReadout(TextView readout, String text) {
        if (!TextUtils.equals(readout.getText(), text)) readout.setText(text);
    }

    private void styleToggle(Button key, boolean active) {
        if (!Boolean.valueOf(active).equals(key.getTag())) {
            styleButton(key, active);
            key.setTag(active);
        }
    }

    private void renderPresets() {
        for (int index = 0; index < presetKeys.length; index++) {
            Button key = presetKeys[index];
            String id = radio.presetId(index);
            RadioCatalog.Station stored = null;
            for (RadioCatalog.Station entry : radio.stations) {
                if (entry.id().equals(id)) { stored = entry; break; }
            }
            boolean hidden = stored != null && radio.hidden.contains(id);
            String name = stored == null ? "비어 있음" : stored.name() + (hidden ? " (숨김)" : "");
            key.setContentDescription("프리셋 " + (index + 1) + " · " + name);
            key.setTooltipText(name + " · 짧게 눌러 재생, 길게 눌러 저장");
            styleToggle(key, stored != null && !hidden);
            enable(key, true);
        }
    }

    private void recallPreset(int index) {
        if (radio == null || radio.recallPreset(index) != null) return;
        String message = radio.hidden.contains(radio.presetId(index))
            ? "숨긴 채널입니다. 숨긴 채널 탭에서 다시 표시할 수 있어요."
            : (index + 1) + "번을 길게 눌러 지금 채널을 저장하세요.";
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private void refreshList() {
        if (radio == null || adapter == null) return;
        String[] words = RadioLibrary.searchWords(search.getText().toString());
        ArrayList<StationRow> rows = new ArrayList<>();
        for (RadioCatalog.Station station : radio.stations) {
            if (!radio.country.isEmpty() && !radio.country.equals(station.country())) continue;
            if (!group.isEmpty() && !group.equals(station.group())) continue;
            boolean hidden = radio.hidden.contains(station.id());
            boolean favorite = radio.favorites.contains(station.id());
            if (hidden != (mode == HIDDEN) || mode == FAVORITES && !favorite) continue;
            if (!RadioLibrary.matchesWords(station, words)) continue;
            rows.add(new StationRow(station, favorite, station.id().equals(radio.station.id())));
        }
        stationCount.setText(rows.isEmpty() ? "조건에 맞는 채널이 없어요." : rows.size() + "개 채널");
        groupButton.setText(group.isEmpty() ? "방송사 ▾" : group + " ▾");
        for (int i = 0; i < tabs.length; i++) {
            boolean active = i == mode;
            if (!(tabs[i].getTag() instanceof Boolean) || (boolean) tabs[i].getTag() != active) {
                styleButton(tabs[i], active);
                tabs[i].setTag(active);
            }
        }
        if (!adapter.rows.equals(rows)) {
            adapter.rows = rows;
            adapter.notifyDataSetChanged();
        }
    }

    private void setMode(int selection) { mode = selection; refreshList(); }

    private void chooseCountry() {
        if (radio == null) return;
        new AlertDialog.Builder(this).setTitle("국가 선택")
            .setItems(RadioCatalog.COUNTRIES, (dialog, index) -> radio.setCountry(RadioCatalog.COUNTRY_CODES[index]))
            .show();
    }

    private void chooseGroup() {
        if (radio == null) return;
        TreeSet<String> groups = new TreeSet<>();
        for (RadioCatalog.Station entry : radio.stations) {
            if (radio.country.isEmpty() || radio.country.equals(entry.country())) groups.add(entry.group());
        }
        ArrayList<String> values = new ArrayList<>();
        values.add("모든 방송사");
        values.addAll(groups);
        new AlertDialog.Builder(this).setTitle("방송사 선택").setItems(values.toArray(new String[0]), (dialog, i) -> {
            group = i == 0 ? "" : values.get(i);
            refreshList();
        }).show();
    }

    private void stationMenu(View anchor, RadioCatalog.Station station) {
        if (radio == null) return;
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, radio.favorites.contains(station.id()) ? "즐겨찾기 해제" : "즐겨찾기 추가");
        menu.getMenu().add(0, 2, 0, radio.hidden.contains(station.id()) ? "다시 표시" : "채널 숨기기");
        if (!station.homepage().isEmpty()) menu.getMenu().add(0, 3, 0, "방송국 공식 사이트");
        if (station.id().startsWith("custom-")) menu.getMenu().add(0, 4, 0, "직접 추가한 채널 삭제");
        menu.setOnMenuItemClickListener(item -> {
            if (radio == null) return false;
            switch (item.getItemId()) {
                case 1 -> radio.toggleFavorite(station.id());
                case 2 -> radio.toggleHidden(station.id());
                case 3 -> open(Uri.parse(station.homepage()));
                case 4 -> new AlertDialog.Builder(this).setMessage(station.name() + " 채널을 삭제할까요?")
                    .setNegativeButton("취소", null)
                    .setPositiveButton("삭제", (d, i) -> { if (radio != null) radio.removeStation(station); }).show();
            }
            return true;
        });
        menu.show();
    }

    private void addStation() {
        if (radio == null) return;
        LinearLayout fields = column();
        fields.setPadding(dp(20), dp(4), dp(20), dp(4));
        EditText name = new EditText(this);
        name.setHint("방송국 이름");
        name.setSingleLine();
        fields.addView(name);
        EditText address = new EditText(this);
        address.setHint("MP3·AAC·HLS 스트리밍 주소");
        address.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        address.setSingleLine();
        fields.addView(address);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("내 채널 추가").setView(fields)
            .setNegativeButton("취소", null).setPositiveButton("추가", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            if (radio == null) return;
            String title = name.getText().toString().trim();
            if (title.isEmpty()) { name.setError("방송국 이름을 입력하세요."); return; }
            try {
                radio.addStation(title.substring(0, Math.min(80, title.length())), address.getText().toString());
                group = "";
                radio.setCountry("");
                search.setText("");
                setMode(ALL);
                dialog.dismiss();
            } catch (Exception error) {
                address.setError("HTTP 또는 HTTPS 스트리밍 주소를 확인하세요.");
            }
        }));
        dialog.show();
    }

    private void showTrackSearch(MusicRecognition.Track track) {
        new AlertDialog.Builder(this).setTitle(track.title() + " · " + track.artist())
            .setItems(new String[]{"YouTube Music", "Spotify"}, (dialog, index) -> {
                if (index == 0) open(track.youtubeMusic());
                else openSpotify(track);
            }).setNegativeButton("닫기", null).show();
    }

    private void showTrackHistory() {
        if (radio == null) return;
        List<TrackHistory.Entry> entries = radio.trackHistory();
        var dialog = new AlertDialog.Builder(this).setTitle("곡 기록").setPositiveButton("닫기", null);
        if (entries.isEmpty()) {
            dialog.setMessage("아직 기록된 곡이 없어요.");
        } else {
            var date = android.text.format.DateFormat.getDateFormat(this);
            var time = android.text.format.DateFormat.getTimeFormat(this);
            var rows = new ArrayList<String>();
            for (TrackHistory.Entry entry : entries) {
                Date stamp = new Date(entry.time());
                rows.add(entry.track().title() + " · " + entry.track().artist() + "\n"
                    + entry.station() + " · " + date.format(stamp) + " " + time.format(stamp));
            }
            dialog.setItems(rows.toArray(new String[0]), (list, index) -> showTrackSearch(entries.get(index).track()));
        }
        dialog.show();
    }

    private void showTools() {
        if (radio == null) return;
        RadioService active = radio;
        LinearLayout view = column();
        view.setPadding(dp(22), dp(6), dp(22), dp(14));
        CheckBox autoplay = new CheckBox(this);
        autoplay.setText("다음 실행 시 마지막 방송 자동 재생");
        autoplay.setChecked(active.autoplay);
        autoplay.setOnCheckedChangeListener((button, checked) -> active.setAutoplay(checked));
        view.addView(autoplay);
        if (!active.station.homepage().isEmpty()) {
            spaceAbove(view, button("현재 방송국 공식 사이트 ↗", b -> open(Uri.parse(active.station.homepage()))), 6);
        }
        new AlertDialog.Builder(this).setTitle("재생 설정").setView(view).setPositiveButton("닫기", null).show();
    }

    private void open(Uri uri) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); }
        catch (android.content.ActivityNotFoundException error) {
            Toast.makeText(this, "이 링크를 열 앱을 찾지 못했어요.", Toast.LENGTH_SHORT).show();
        }
    }

    private void openSpotify(MusicRecognition.Track track) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, track.spotifyApp()).setPackage("com.spotify.music")); }
        catch (android.content.ActivityNotFoundException error) { open(track.spotifyWeb()); }
    }

    private static void enable(Button button, boolean enabled) {
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : .47f);
    }

    private int dp(int value) { return Math.round(getResources().getDisplayMetrics().density * value); }
    private LinearLayout column() { var layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); return layout; }
    private LinearLayout row() { var layout = new LinearLayout(this); layout.setGravity(Gravity.CENTER_VERTICAL); return layout; }
    private TextView label(String text, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private Button seekKey(String label, int direction) {
        Button key = physicalKey(label, view -> { if (radio != null) radio.step(direction); });
        class SeekRepeat implements Runnable {
            MediaItem target;

            void cancel() {
                key.removeCallbacks(this);
                target = null;
            }

            @Override public void run() {
                if (key.isPressed() && radio != null && radio.seekLocal(target, direction)) {
                    key.postDelayed(this, 160);
                } else cancel();
            }
        }
        var repeat = new SeekRepeat();
        key.setOnTouchListener((view, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_UP
                    || action == MotionEvent.ACTION_CANCEL) repeat.cancel();
            return false;
        });
        key.setOnLongClickListener(view -> {
            if (radio == null) return false;
            repeat.target = radio.localSeekTarget();
            if (repeat.target == null) return false;
            if (radio.seekLocal(repeat.target, direction) && key.isPressed()) key.postDelayed(repeat, 160);
            return true;
        });
        return key;
    }

    private Button physicalKey(String label, View.OnClickListener action) {
        Button key = button(label, action);
        key.setTypeface(Typeface.DEFAULT_BOLD);
        return key;
    }

    private Button button(String label, View.OnClickListener action) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(label);
        button.setTextSize(12);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(dp(44));
        button.setMinimumHeight(dp(44));
        button.setPadding(dp(9), 0, dp(9), 0);
        button.setOnClickListener(action);
        styleButton(button, false);
        return button;
    }

    private void styleButton(Button button, boolean active) {
        var face = gradient(active ? 0xff465337 : 0xff3b3e43, active ? 0xff262f20 : 0xff212328,
            active ? 0xff889e55 : 0xff585b60, 5);
        var moldedKey = new LayerDrawable(new Drawable[] {shape(0xff0f1113, 0xff37393e, 5), face});
        moldedKey.setLayerInset(1, 0, 0, 0, dp(2));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x50889c64),
            moldedKey, null));
        button.setTextColor(active ? 0xffe7f8a2 : TEXT);
    }

    private GradientDrawable gradient(int top, int bottom, int border, int radius) {
        var drawable = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {top, bottom});
        drawable.setCornerRadius(dp(radius));
        drawable.setStroke(dp(1), border);
        return drawable;
    }

    private GradientDrawable shape(int fill, int border, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radius));
        if (border != 0) drawable.setStroke(dp(1), border);
        return drawable;
    }

    private final class RadioFace extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final BitmapShader wood;
        private Shader plastic;

        RadioFace() {
            Bitmap tile = null;
            try (var input = getAssets().open("retro-grain.png")) {
                tile = BitmapFactory.decodeStream(input);
            } catch (IOException ignored) {
                // The solid trim is also usable while running a partial build.
            }
            wood = tile == null ? null : new BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
            if (wood != null) {
                var scale = new Matrix();
                scale.setScale(dp(330) / (float) tile.getWidth(), dp(330) / (float) tile.getHeight());
                wood.setLocalMatrix(scale);
            }
        }

        @Override protected void onBoundsChange(Rect bounds) {
            plastic = new LinearGradient(0, bounds.top, 0, bounds.bottom, 0xff383a3e, 0xff232428,
                Shader.TileMode.CLAMP);
        }

        @Override public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            float trimRadius = dp(14);
            float inset = dp(12);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xff63361f);
            paint.setShader(wood);
            canvas.drawRoundRect(bounds.left, bounds.top, bounds.right, bounds.bottom, trimRadius, trimRadius, paint);
            paint.setShader(null);
            paint.setColor(0x80f2a86a);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1));
            float bevel = inset - dp(2);
            canvas.drawRoundRect(bounds.left + bevel, bounds.top + bevel, bounds.right - bevel,
                bounds.bottom - bevel, dp(8), dp(8), paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xff383a3e);
            paint.setShader(plastic);
            canvas.drawRoundRect(bounds.left + inset, bounds.top + inset, bounds.right - inset,
                bounds.bottom - inset, dp(7), dp(7), paint);
            paint.setShader(null);
            paint.setColor(0xff1b100a);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1));
            canvas.drawRoundRect(bounds.left + inset, bounds.top + inset, bounds.right - inset,
                bounds.bottom - inset, dp(7), dp(7), paint);
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    private LinearLayout.LayoutParams weighted() { return new LinearLayout.LayoutParams(0, WRAP, 1); }
    private void side(LinearLayout parent, View view) {
        var params = new LinearLayout.LayoutParams(WRAP, WRAP);
        params.leftMargin = dp(6);
        parent.addView(view, params);
    }
    private void side(LinearLayout parent, View view, float weight) {
        var params = new LinearLayout.LayoutParams(0, WRAP, weight);
        params.leftMargin = dp(6);
        parent.addView(view, params);
    }
    private void spaceAbove(LinearLayout parent, View view, int space) {
        var params = new LinearLayout.LayoutParams(MATCH, WRAP);
        params.topMargin = dp(space);
        parent.addView(view, params);
    }

    private record StationRow(RadioCatalog.Station station, boolean favorite, boolean current) {}

    private final class StationAdapter extends BaseAdapter {
        private List<StationRow> rows = new ArrayList<>();
        @Override public int getCount() { return rows.size(); }
        @Override public RadioCatalog.Station getItem(int position) { return rows.get(position).station(); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            if (recycled == null) recycled = new StationCell();
            ((StationCell) recycled).bind(rows.get(position));
            return recycled;
        }
    }

    private final class StationCell extends LinearLayout {
        private final TextView name, description, menu;
        StationCell() {
            super(MainActivity.this);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(11), dp(6), dp(5), dp(6));
            setMinimumHeight(dp(54));
            LinearLayout labels = column();
            name = label("", 15, TEXT, true);
            name.setMaxLines(2);
            name.setEllipsize(TextUtils.TruncateAt.END);
            description = label("", 12, MUTED, false);
            labels.addView(name);
            labels.addView(description);
            addView(labels, weighted());
            menu = label("⋮", 21, MUTED, true);
            menu.setGravity(Gravity.CENTER);
            menu.setContentDescription("채널 메뉴");
            menu.setFocusable(false);
            addView(menu, new LinearLayout.LayoutParams(dp(38), dp(42)));
        }
        void bind(StationRow row) {
            RadioCatalog.Station entry = row.station();
            name.setText((row.favorite() ? "★  " : "") + entry.name());
            description.setText(RadioCatalog.countryName(entry.country()) + "  ·  " + entry.description());
            setBackground(new RippleDrawable(ColorStateList.valueOf(0x40889c64),
                shape(row.current() ? 0xff303a25 : CARD, row.current() ? 0xff71834f : 0, 5), null));
            menu.setOnClickListener(view -> stationMenu(view, entry));
        }
    }

}
