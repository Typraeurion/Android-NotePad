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
 * Generic uncaught exception class covering all types of
 * password-related errors.  This is thrown by {@link StringEncryption}
 * methods as a wrapper for {@link java.security.GeneralSecurityException}
 * so it can be thrown from interface methods that don't ordinarily
 * declare caught exceptions.
 */
public class AuthenticationException extends RuntimeException {

    /**
     * Construct an AuthenticationException with no detail message or cause
     */
    public AuthenticationException() {}

    /**
     * Construct an AuthenticationException with a detail message
     *
     * @param message the detail message
     */
    public AuthenticationException(String message) {
        super(message);
    }

    /**
     * Construct an AuthenticationException with a cause
     *
     * @param cause the underlying cause of this exception
     */
    public AuthenticationException(Throwable cause) {
        super(cause);
    }

    /**
     * Construct an AuthenticationException with a detail message and cause
     *
     * @param message the detail message
     * @param cause the underlying cause of this exception
     */
    public AuthenticationException(String message, Throwable cause) {
        super(message, cause);
    }

}
