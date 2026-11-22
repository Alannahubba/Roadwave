package kr.onradio.player;

import android.app.Activity;
import android.app.LocaleManager;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;
import android.os.LocaleList;

final class AppLanguage {
    private static final String PREFERENCES = "language";
    private static final String TAG = "tag";

    private AppLanguage() {}

    static Context wrap(Context base) {
        if (Build.VERSION.SDK_INT >= 33) return base;
        String tag = base.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(TAG, "");
        if (tag.isEmpty()) return base;
        var configuration = new Configuration(base.getResources().getConfiguration());
        configuration.setLocales(LocaleList.forLanguageTags(tag));
        return base.createConfigurationContext(configuration);
    }

    static String selected(Activity activity) {
        if (Build.VERSION.SDK_INT >= 33) {
            return activity.getSystemService(LocaleManager.class).getApplicationLocales().toLanguageTags();
        }
        return activity.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(TAG, "");
    }

    static void select(Activity activity, String tag) {
        if (selected(activity).equals(tag)) return;
        if (Build.VERSION.SDK_INT >= 33) {
            activity.getSystemService(LocaleManager.class).setApplicationLocales(LocaleList.forLanguageTags(tag));
        } else {
            activity.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putString(TAG, tag).apply();
            activity.recreate();
        }
    }
}
