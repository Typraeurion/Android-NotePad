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
 * The exception thrown by {@link StringEncryption} when
 * a password does not match the stored password hash.
 * This is also used in the case where there is no
 * stored password hash &mdash; i.e. a password has not
 * been set but one has been provided anyway.
 */
public class PasswordMismatchException extends AuthenticationException {

    /**
     * Construct a PasswordMismatchException
     * with no detail message or cause
     */
    public PasswordMismatchException() {}

    /**
     * Construct a PasswordMismatchException with a detail message
     *
     * @param message the detail message
     */
    public PasswordMismatchException(String message) {
        super(message);
    }

    /**
     * Construct a PasswordMismatchException with a cause
     *
     * @param cause the underlying cause of this exception
     */
    public PasswordMismatchException(Throwable cause) {
        super(cause);
    }

    /**
     * Construct a PasswordMismatchException
     * with a detail message and cause
     *
     * @param message the detail message
     * @param cause the underlying cause of this exception
     */
    public PasswordMismatchException(String message, Throwable cause) {
        super(message, cause);
    }

}
