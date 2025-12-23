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
