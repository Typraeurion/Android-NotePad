/*
 * Copyright © 2026 Trevin Beattie
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.xmission.trevin.android.notes;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;
import android.util.Log;

import androidx.appcompat.app.AppCompatDelegate;

import com.xmission.trevin.android.notes.data.NotePreferences;

public class NoteApplication extends Application {

    private static final String TAG = "NoteApplication";

    public static final String SILENT_CHANNEL_ID =
            "silent_notification_channel";

    @Override
    public void onCreate() {
        Log.d(TAG, ".onCreate");
        super.onCreate();

        // Skip initializing night mode if we're running instrumented tests
        // because using real preferences would interfere with mock preferences.
        try {
            Class.forName("androidx.test.InstrumentationRegistry");
            Log.d(TAG, "Instrumentation detected; skipping night mode initialization");
        } catch (ClassNotFoundException cx) {
            // Initialize night mode according to current preferences
            NotePreferences prefs = NotePreferences.getInstance(this);
            NotePreferences.UITheme userTheme = prefs.getUITheme();
            int nightMode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
            switch (userTheme) {
                case LIGHT:
                    nightMode = AppCompatDelegate.MODE_NIGHT_NO;
                    break;
                case DARK:
                    nightMode = AppCompatDelegate.MODE_NIGHT_YES;
                    break;
            }
            AppCompatDelegate.setDefaultNightMode(nightMode);

        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Oreo and up must use channels to send notifications
            NotificationManager notificationManager = (NotificationManager)
                    getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel silentChannel = new NotificationChannel(
                    SILENT_CHANNEL_ID,
                    getString(R.string.NotificationChannelSilentName),
                    NotificationManager.IMPORTANCE_NONE);
            silentChannel.setDescription(getString(
                    R.string.NotificationChannelSilentDescription));
            notificationManager.createNotificationChannel(silentChannel);
        }
    }

    @Override
    public void onLowMemory() {
        Log.d(TAG, ".onLowMemory");
        super.onLowMemory();
    }

    @Override
    public void onTerminate() {
        Log.d(TAG, ".onTerminate");
        super.onTerminate();
    }

}
