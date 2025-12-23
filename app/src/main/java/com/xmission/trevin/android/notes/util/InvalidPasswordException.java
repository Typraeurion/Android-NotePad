/*
 * Copyright © 2025 Trevin Beattie
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

import java.security.GeneralSecurityException;

/**
 * Exception thrown (in certain methods) when the user provides a
 * password which does not match the hash stored in the database.
 */
public class InvalidPasswordException extends GeneralSecurityException {

    /**
     * Constructs an InvalidPasswordException with no detail message.
     */
    public InvalidPasswordException() {
        super();
    }

    /**
     * Constructs an InvalidPasswordException
     * with the specified detail message.
     *
     * @param msg the detali message
     */
    public InvalidPasswordException(String msg) {
        super(msg);
    }

    /**
     * Constructs an InvalidPasswordException
     * with the specified detail message and cause.
     *
     * @param message the detail message (which is saved for later retrieval
     *               by the {@link Throwable#getMessage} method).
     * @param cause the cause (which is saved for later retrieval by the
     * {@link Throwable#getCause()} method).  (A {@code null} value is
     * permitted, and indicates that the cause is nonexistent or unknown.)
     */
    public InvalidPasswordException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Constructs an InvalidPasswordException with
     * the specified cause and a detail message of
     * {@code (cause==null ? null : cause.toString())} (which
     * typically contains the class and detail message of {@code cause}).
     *
     * @param cause the cause (which is saved for later retrieval by the
     * {@link Throwable#getCause()} method).  (A {@code null} value is
     * permitted, and indicates that the cause is nonexistent or unknown.)
     */
    public InvalidPasswordException(Throwable cause) {
        super(cause);
    }

}
