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
package com.xmission.trevin.android.notes.util;

/**
 * The exception thrown by service classes when
 * a password is required but has not been provided.
 */
public class PasswordRequiredException extends AuthenticationException {

    /**
     * Construct a PasswordRequiredException
     * with no detail message or cause
     */
    public PasswordRequiredException() {}

    /**
     * Construct a PasswordRequiredException with a detail message
     *
     * @param message the detail message
     */
    public PasswordRequiredException(String message) {
        super(message);
    }

    /**
     * Construct a PasswordRequiredException with a cause
     *
     * @param cause the underlying cause of this exception
     */
    public PasswordRequiredException(Throwable cause) {
        super(cause);
    }

    /**
     * Construct a PasswordRequiredException
     * with a detail message and cause
     *
     * @param message the detail message
     * @param cause the underlying cause of this exception
     */
    public PasswordRequiredException(String message, Throwable cause) {
        super(message, cause);
    }

}
