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
 * The exception thrown by {@link StringEncryption} when the stored
 * password hash is invalid or unsupported.
 */
public class InvalidPasswordHashException extends AuthenticationException {

    /**
     * Construct an InvalidPasswordHashException
     * with no detail message or cause
     */
    public InvalidPasswordHashException() {}

    /**
     * Construct an InvalidPasswordHashException with a detail message
     *
     * @param message the detail message
     */
    public InvalidPasswordHashException(String message) {
        super(message);
    }

    /**
     * Construct an InvalidPasswordHashException with a cause
     *
     * @param cause the underlying cause of this exception
     */
    public InvalidPasswordHashException(Throwable cause) {
        super(cause);
    }

    /**
     * Construct an InvalidPasswordHashException
     * with a detail message and cause
     *
     * @param message the detail message
     * @param cause the underlying cause of this exception
     */
    public InvalidPasswordHashException(String message, Throwable cause) {
        super(message, cause);
    }

}
