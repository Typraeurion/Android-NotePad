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

import android.util.Log;

import androidx.multidex.MultiDexApplication;

public class NoteApplication extends MultiDexApplication {

    private static final String TAG = "NoteApplication";

    @Override
    public void onCreate() {
        Log.d(TAG, ".onCreate");
        super.onCreate();

        // Initialize MultiDex so that the "desugaring" library can
        // give us access to the java.time.* classes on older API's.
        //MultiDex.install(this);

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
