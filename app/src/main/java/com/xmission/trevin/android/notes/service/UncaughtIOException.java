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
package com.xmission.trevin.android.notes.service;

import androidx.annotation.NonNull;

import java.io.IOException;

/**
 * Wrapper for a {@link java.io.IOException} in order to allow it
 * to pass through a {@link Runnable#run()} method.
 */
public class UncaughtIOException extends RuntimeException {

    public UncaughtIOException(@NonNull IOException cause) {
        super(cause);
    }

    @Override
    @NonNull
    public IOException getCause() {
        return (IOException) super.getCause();
    }

}
