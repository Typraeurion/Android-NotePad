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
package com.xmission.trevin.android.notes.ui;

import android.app.Activity;
import android.app.Instrumentation.ActivityResult;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import androidx.lifecycle.Lifecycle.State;
import androidx.test.core.app.ActivityScenario;

import java.io.Closeable;
import java.lang.reflect.Field;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Replacement for {@link ActivityScenario} for tests that
 * need to validate the results of an {@link Activity} since
 * {@link ActivityScenario#launchActivityForResult} is broken
 * on API level 34 (Upside-down Cake), or for tests in which
 * the normal {@link ActivityScenario#close()} method fails
 * to detect the activity transition to {@link State#DESTROYED}.
 */
public class ActivityScenarioResultsWrapper<T extends Activity>
        implements AutoCloseable, Closeable {

    private static final String LOG_TAG = "ActivityScenarioRW";

    private static final ExecutorService executor =
            Executors.newCachedThreadPool();

    /** The underlying {@link ActivityScenario} */
    private final ActivityScenario<T> scenario;
    private final boolean launchedForResult;
    private boolean isClosed = false;

    /**
     * Launches an activity of a given class and constructs
     * {@link ActivityScenarioResultsWrapper} with the activity.
     * Waits for the lifecycle state transitions to be complete.
     * Typically the initial state of the activity is
     * {@link androidx.lifecycle.Lifecycle.State#RESUMED RESUMED}
     * but can be in another state.  For instance, if your activity
     * calls {@link Activity#finish} from your {@link Activity#onCreate}, the
     * state is {@link androidx.lifecycle.Lifecycle.State#DESTROYED DESTROYED}
     * when this method returns.
     * <p>
     * If you need to supply parameters to the start activity intent,
     * use {@link #launch(Intent)}.
     * </p>
     *
     * @param activityClass an activity class to launch
     *
     * @return an {@link ActivityScenarioResultsWrapper} which you can use
     * to make further state transitions
     *
     * @throws AssertionError if the lifecycle state transition
     * never completes within the timeout
     */
    public static <T extends Activity> ActivityScenarioResultsWrapper<T> launch(
            Class<T> activityClass) {
        return new ActivityScenarioResultsWrapper<>(activityClass, false);
    }

    public static <T extends Activity> ActivityScenarioResultsWrapper<T> launchForResult(
            Class<T> activityClass) {
        return new ActivityScenarioResultsWrapper<>(activityClass, true);
    }

    private ActivityScenarioResultsWrapper(
            Class<T> activityClass, boolean forResult) {
        if (forResult && (Build.VERSION.SDK_INT
                < Build.VERSION_CODES.UPSIDE_DOWN_CAKE)) {
            scenario = ActivityScenario.launchActivityForResult(activityClass);
            launchedForResult = true;
        } else {
            scenario = ActivityScenario.launch(activityClass);
            launchedForResult = false;
        }
    }

    /**
     * Launches an activity by a given intent and constructs
     * {@link ActivityScenarioResultsWrapper} with the activity.
     * Waits for the lifecycle state transitions to be complete.
     * Typically the initial state of the activity is
     * {@link androidx.lifecycle.Lifecycle.State#RESUMED RESUMED}
     * but can be in another state.  For instance, if your activity
     * calls {@link Activity#finish} from your {@link Activity#onCreate}, the
     * state is {@link androidx.lifecycle.Lifecycle.State#DESTROYED DESTROYED}
     * when this method returns.
     *
     * @param intent an intent to start the activity
     *
     * @return an {@link ActivityScenarioResultsWrapper} which you can use
     * to make further state transitions
     *
     * @throws AssertionError if the lifecycle state transition
     * never completes within the timeout
     */
    public static <T extends Activity> ActivityScenarioResultsWrapper<T> launch(
            Intent intent) {
        return new ActivityScenarioResultsWrapper<>(intent, false);
    }

    public static <T extends Activity> ActivityScenarioResultsWrapper<T> launchForResult(
            Intent intent) {
        return new ActivityScenarioResultsWrapper<>(intent, true);
    }

    private ActivityScenarioResultsWrapper(
            Intent intent, boolean forResult) {
        if (forResult && (Build.VERSION.SDK_INT
                < Build.VERSION_CODES.UPSIDE_DOWN_CAKE)) {
            scenario = ActivityScenario.launchActivityForResult(intent);
            launchedForResult = true;
        } else {
            scenario = ActivityScenario.launch(intent);
            launchedForResult = false;
        }
    }

    /**
     * Finishes the managed activity and cleans up the device state.
     * This method blocks execution until the activity becomes
     * {@link androidx.lifecycle.Lifecycle.State#DESTROYED DESTROYED}.
     * If the underlying scenario fails to close, this will <i>note</i>
     * bubble up the exception but will silently return (after logging).
     * <p>
     * It is highly recommended to call this method after you test is done
     * to keep the device state clean although this is optional.
     * </p>
     * <p>
     * You may call this method more than once.  If the activity has been
     * finished already, this method does nothing.
     * </p>
     * <p>
     * Avoid calling this method directly.  Instead, use it in a
     * try-with-resources block or a {@code @Rule}.
     * </p>
     */
    public void close() {
        if (isClosed) {
            Log.d(LOG_TAG, "close() called after wrapper was already closed");
            return;
        }
        isClosed = true;
        if ((Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) &&
                (Build.VERSION.SDK_INT <= Build.VERSION_CODES.LOLLIPOP_MR1)) {
            // Workaround for broken platforms:
            // close the scenario on a background thread and don't wait up.
            executor.submit(new Runnable() {
                @Override
                public void run() {
                    Log.i(LOG_TAG, "Closing the scenario in the background");
                    try {
                        scenario.close();
                    } catch (Exception e) {
                        Log.e(LOG_TAG, "Failed to close the scenario", e);
                    }
                }
            });
            return;
        }
        Log.d(LOG_TAG, "Closing the scenario on the main thread");
        try {
            scenario.close();
        } catch (Exception e) {
            Log.e(LOG_TAG, "Failed to close the scenario", e);
        }
    }

    /**
     * Runs a given action on the current activity&rsquo;s main thread.
     *
     * @param action the action to run in the activity.
     */
    public void onActivity(ActivityScenario.ActivityAction<T> action) {
        scenario.onActivity(action);
    }

    /**
     * Waits for the activity to be finished and returns the result of
     * its call to {@link Activity#setResult}.
     */
    public ActivityResult getResult() {
        if (launchedForResult)
            return scenario.getResult();

        final ActivityResult[] resultHolder = new ActivityResult[1];
        scenario.onActivity(activity -> {
            /*
             * Use reflection to extract the result code and data
             * from an Activity.  This is a workaround for
             * ActivityScenario.launchActivityForResult being broken
             * on SDK level 34.
             */
            int resultCode;
            Intent resultData;
            try {
                Field resultCodeField = Activity.class
                        .getDeclaredField("mResultCode");
                resultCodeField.setAccessible(true);
                resultCode = (Integer) resultCodeField.get(activity);
            } catch (NoSuchFieldException nfe) {
                throw new RuntimeException(
                        "Activity class has no mResultCode field!", nfe);
            } catch (Exception e) {
                throw new RuntimeException(
                        "Failed to extract activity result code", e);
            }
            try {
                Field resultDataField = Activity.class
                        .getDeclaredField("mResultData");
                resultDataField.setAccessible(true);
                resultData = (Intent) resultDataField.get(activity);
            } catch (NoSuchFieldException nfe) {
                throw new RuntimeException(
                        "Activity class has no mResultData field!", nfe);
            } catch (Exception e) {
                throw new RuntimeException(
                        "Failed to extract activity result data", e);
            }
            resultHolder[0] = new ActivityResult(resultCode, resultData);
        });
        return resultHolder[0];
    }

    /**
     * Get the underlying scenario for this wrapper.  This is meant for
     * use in test utility methods that take an {@link ActivityScenario}
     * as a parameter.
     *
     * @return the underlying {@link ActivityScenario}
     */
    public ActivityScenario<T> getScenario() {
        return scenario;
    }

    /**
     * Recreates the activity.
     * <p>
     * A current {@link Activity} will be destroyed after its data is saved
     * into {@link Bundle} with {@link Activity#onSaveInstanceState(Bundle)},
     * then it creates a new {@link Activity} with the saved {@link Bundle}.
     * After this method call, it is ensured that the {@link Activity} state
     * goes back to the same state as its previous state.
     * </p>
     *
     * @throws IllegalStateException if the {@link Activity} is destroyed,
     * finished or finishing.
     * @throws AssertionError if the {@link Activity} is never re-created.
     */
    public void recreate() {
        scenario.recreate();
    }

}
