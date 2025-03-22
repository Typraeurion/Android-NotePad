/*
 * Copyleft © 2025 Trevin Beattie
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
package com.xmission.trevin.android.notes.data;

import android.content.SharedPreferences;

import java.lang.reflect.Method;
import java.util.*;

/**
 * Implementation of {@link SharedPreferences} which just keeps the
 * preferences in memory and provides additional methods for verifying
 * method calls.
 *
 * @author Trevin Beattie
 */
public class MockSharedPreferences implements SharedPreferences {

    /** The stored preferences */
    private final Map<String,Object> storage = new HashMap<>();

    /** Listeners for preference changes */
    private final List<OnSharedPreferenceChangeListener> listeners =
            new LinkedList<>();

    /** Method calls for tracking */
    private final Map<String,List<StackTraceElement[]>> methodCallLog =
            new HashMap<>();

    /** All calls for sequencing */
    private final List<StackTraceElement[]> callLog = new ArrayList<>();

    /** Open editors */
    private final List<MockEditor> openEditors = new LinkedList<>();

    /** Reset the mock (e.g. between tests) */
    public void resetMock() {
        storage.clear();
        methodCallLog.clear();
        callLog.clear();
    }

    /**
     * @return an ordered map of methods that were called.
     * The keys are the method names ordered alphabetically.
     * The values are the number of times each method was called.
     */
    public SortedMap<String,Integer> getMethodsCalled() {
        SortedMap<String,Integer> map = new TreeMap<>();
        for (String key : methodCallLog.keySet()) {
            map.put(key, methodCallLog.get(key).size());
        }
        return map;
    }

    /**
     * Get the number of times a particular method was called
     *
     * @param methodName the name of the method in question.  This is
     *                   matched against the {@link Method#getName()}
     *                   of all methods that were called, so if there is
     *                   no method by that name this check will return 0.
     *
     * @return the number of times the method was called
     */
    public int getMethodCallCount(String methodName) {
        for (String key : methodCallLog.keySet()) {
            if (key.equals(methodName)) {
                return methodCallLog.get(key).size();
            }
        }
        return 0;
    }

    /** @return the methods that were called in the order they were called */
    public List<String> getCallLog() {
        List<String> log = new ArrayList<>(callLog.size());
        for (StackTraceElement[] trace : callLog)
            log.add(trace[0].getMethodName());
        return log;
    }

    /** @return whether an Editor for these preferences is still open */
    public boolean isEditorOpen() {
        return !openEditors.isEmpty();
    }

    /**
     * Record a call to an interface method.  This keeps track of the
     * stack trace starting from before this method itself going back
     * to the last class in the notes package.
     */
    private void recordCall() {
        StackTraceElement[] trace = Thread.currentThread().getStackTrace();
        int keepFrom = 1;
        int keepTo = 2;
        while (keepTo < trace.length) {
            StackTraceElement e = trace[keepTo];
            if (!e.getClassName().startsWith("com.xmission.trevin.android.notes."))
                break;
            keepTo++;
        }
        StackTraceElement[] keepTrace = new StackTraceElement[keepTo - keepFrom];
        String methodName = trace[0].getMethodName();
        System.arraycopy(trace, keepFrom, keepTrace, 0, keepTo - keepFrom);
        List<StackTraceElement[]> callList = methodCallLog.get(methodName);
        if (callList == null) {
            callList = new ArrayList<>();
            methodCallLog.put(methodName, callList);
        }
        callList.add(keepTrace);
    }

    @Override
    public Map<String, ?> getAll() {
        recordCall();
        return storage;
    }

    @Override
    public String getString(String key, String defValue) {
        recordCall();
        return Optional.ofNullable((String) storage.get(key)).orElse(defValue);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Set<String> getStringSet(String key, Set<String> defValues) {
        recordCall();
        return Optional.ofNullable((Set<String>) storage.get(key)).orElse(defValues);
    }

    @Override
    public int getInt(String key, int defValue) {
        recordCall();
        return Optional.ofNullable((Integer) storage.get(key)).orElse(defValue);
    }

    @Override
    public long getLong(String key, long defValue) {
        recordCall();
        return Optional.ofNullable((Long) storage.get(key)).orElse(defValue);
    }

    @Override
    public float getFloat(String key, float defValue) {
        recordCall();
        return Optional.ofNullable((Float) storage.get(key)).orElse(defValue);
    }

    @Override
    public boolean getBoolean(String key, boolean defValue) {
        recordCall();
        return Optional.ofNullable((Boolean) storage.get(key)).orElse(defValue);
    }

    @Override
    public boolean contains(String key) {
        recordCall();
        return storage.containsKey(key);
    }

    /**
     * Set a preference outside of the Editor.  This is intended for use by
     * test code to establish pre-conditions for a test.
     *
     * @param key the name of the preference to set
     * @param value the value of the preference to set
     */
    public void initializePreference(String key, Object value) {
        storage.put(key, value);
    }

    /**
     * Get the current value of a preference.  This is intended for use by
     * test code to verify post-conditions for a test.
     *
     * @param key the name of the preference to return
     *
     * @return the value of the preference, or null if it is not set.
     */
    public Object getPreference(String key) {
        return storage.get(key);
    }

    /**
     * Public (app-facing) editor for mock shared preferences.
     * Like MockSharedPreferences, this keeps track of the methods
     * that were called; it also keeps track of whether the edits
     * were applied / committed.  If {@link #apply()} or {@link #commit()}
     * is called more than once for the editor, or if any write operation
     * is called after applying changes, it throws an
     * {@link IllegalStateException}.  If neither {@link #apply()} nor
     * {@link #commit()} is called, {@link MockSharedPreferences#isEditorOpen()}
     * will return {@code true}.
     */
    private class MockEditor implements SharedPreferences.Editor {

        private boolean finished = false;
        private boolean doClear = false;
        private final Set<String> keysToDelete = new HashSet<>();
        private final Map<String,Object> keysToAdd = new HashMap<>();

        /**
         * Called for any write operation to make sure the caller
         * hasn&rsquo;t previously called {@link #apply()} or
         * {@link #commit()}.
         *
         * @throws IllegalStateException if the editor has already
         * applied its changes.
         */
        private void checkEditorOpen() {
            if (finished)
                throw new IllegalStateException(
                        "Write operation called after changes were already applied");
        }

        @Override
        public Editor putString(String key, String value) {
            recordCall();
            checkEditorOpen();
            keysToAdd.put(key, value);
            keysToDelete.remove(key);
            return this;
        }

        @Override
        public Editor putStringSet(String key, Set<String> values) {
            recordCall();
            checkEditorOpen();
            keysToAdd.put(key, values);
            keysToDelete.remove(key);
            return this;
        }

        @Override
        public Editor putInt(String key, int value) {
            recordCall();
            checkEditorOpen();
            keysToAdd.put(key, value);
            keysToDelete.remove(key);
            return this;
        }

        @Override
        public Editor putLong(String key, long value) {
            recordCall();
            checkEditorOpen();
            keysToAdd.put(key, value);
            keysToDelete.remove(key);
            return this;
        }

        @Override
        public Editor putFloat(String key, float value) {
            recordCall();
            checkEditorOpen();
            keysToAdd.put(key, value);
            keysToDelete.remove(key);
            return this;
        }

        @Override
        public Editor putBoolean(String key, boolean value) {
            recordCall();
            checkEditorOpen();
            keysToAdd.put(key, value);
            keysToDelete.remove(key);
            return this;
        }

        @Override
        public Editor remove(String key) {
            recordCall();
            checkEditorOpen();
            keysToDelete.add(key);
            keysToAdd.remove(key);
            return this;
        }

        @Override
        public Editor clear() {
            recordCall();
            checkEditorOpen();
            doClear = true;
            return this;
        }

        @Override
        public boolean commit() {
            recordCall();
            if (finished)
                throw new IllegalStateException("Double commit!");
            return applyChanges();
        }

        @Override
        public void apply() {
            recordCall();
            if (finished)
                throw new IllegalStateException("Double apply!");
            applyChanges();
        }

        private boolean applyChanges() {
            finished = true;
            Set<String> changedKeys = new HashSet<>();
            // Per the Editor interface, any clear operation is done first
            // before adding or removing other keys.
            if (doClear) {
                changedKeys = storage.keySet();
                storage.clear();
            }
            // Per the Editor interface, removals are done before
            // adding new keys.
            for (String key : keysToDelete) {
                changedKeys.add(key);
                storage.remove(key);
            }
            for (String key : keysToAdd.keySet()) {
                changedKeys.add(key);
                storage.put(key, keysToAdd.get(key));
            }
            for (OnSharedPreferenceChangeListener listener : listeners) {
                for (String key : changedKeys) {
                    listener.onSharedPreferenceChanged(MockSharedPreferences.this, key);
                }
            }
            openEditors.remove(this);
            return !changedKeys.isEmpty();
        }
    }

    @Override
    public Editor edit() {
        recordCall();
        MockEditor editor = new MockEditor();
        openEditors.add(editor);
        return editor;
    }

    @Override
    public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        recordCall();
        listeners.add(listener);
    }

    @Override
    public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        recordCall();
        listeners.remove(listener);
    }

}
