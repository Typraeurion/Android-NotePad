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

import java.util.Locale;

/**
 * Generic uncaught exception class covering all types of
 * XML parsing errors.  This is thrown by {@link XMLImporter}
 * as a wrapper for {@link org.xml.sax.SAXException} so it can
 * be thrown from interface methods that don't ordinarily declare
 * caught exceptions.
 */
public class XMLParseException extends RuntimeException {

    /** The name of the XML file, if known; may be {@code null} */
    private final String filename;

    /** The line number in the XML file, if known; otherwise -1 */
    private final int lineNumber;

    /** The column number in the XML file, if known; otherwise -1 */
    private final int column;

    /**
     * Construct an XMLParseException with no detail message or cause
     */
    public XMLParseException() {
        this(null, null);
    }

    /**
     * Construct an XMLParseException with a detail message
     *
     * @param message the detail message
     */
    public XMLParseException(String message) {
        this(message, null);
    }

    /**
     * Construct an XMLParseException with a cause
     *
     * @param cause the underlying cause of this exception
     */
    public XMLParseException(Throwable cause) {
        this((cause == null) ? null : cause.getMessage(), cause);
    }

    /**
     * Construct an XMLParseException with a detail message and cause
     *
     * @param message the detail message
     * @param cause the underlying cause of this exception
     */
    public XMLParseException(String message, Throwable cause) {
        super(message, cause);
        filename = null;
        lineNumber = -1;
        column = -1;
    }

    /**
     * Construct an XMLParseException with a detail message
     * and line number.
     *
     * @param message the detail message
     * @param lineNumber the line in the file where the exception occurred
     */
    public XMLParseException(String message,
                             int lineNumber) {
        this(message, null, lineNumber, -1, null);
    }

    /**
     * Construct an XMLParseException with a detail message,
     * line number, and column.
     *
     * @param message the detail message
     * @param lineNumber the line in the file where the exception occurred
     * @param column the column in the line where the exception occurred
     */
    public XMLParseException(String message,
                             int lineNumber, int column) {
        this(message, null, lineNumber, column, null);
    }

    /**
     * Construct an XMLParseException with a detail message,
     * line number, column, and cause.
     *
     * @param message the detail message
     * @param lineNumber the line in the file where the exception occurred
     * @param cause the underlying cause of this exception
     */
    public XMLParseException(String message,
                             int lineNumber,
                             Throwable cause) {
        this(message, null, lineNumber, -1, cause);
    }

    /**
     * Construct an XMLParseException with a detail message,
     * line number, column, and cause.
     *
     * @param message the detail message
     * @param lineNumber the line in the file where the exception occurred
     * @param column the column in the line where the exception occurred
     * @param cause the underlying cause of this exception
     */
    public XMLParseException(String message,
                             int lineNumber, int column,
                             Throwable cause) {
        this(message, null, lineNumber, column, cause);
    }

    /**
     * Construct an XMLParseException with a detail message,
     * file name, and line number.
     *
     * @param message the detail message
     * @param filename the name of the XML file
     * @param lineNumber the line in the file where the exception occurred
     */
    public XMLParseException(String message, String filename,
                             int lineNumber) {
        this(message, filename, lineNumber, -1, null);
    }

    /**
     * Construct an XMLParseException with a detail message,
     * file name, line number, and column.
     *
     * @param message the detail message
     * @param filename the name of the XML file
     * @param lineNumber the line in the file where the exception occurred
     * @param column the column in the line where the exception occurred
     */
    public XMLParseException(String message, String filename,
                             int lineNumber, int column) {
        this(message, filename, lineNumber, column, null);
    }

    /**
     * Construct an XMLParseException with a detail message,
     * file name, line number, aund cause.
     *
     * @param message the detail message
     * @param filename the name of the XML file
     * @param lineNumber the line in the file where the exception occurred
     * @param cause the underlying cause of this exception
     */
    public XMLParseException(String message, String filename,
                             int lineNumber,
                             Throwable cause) {
        this(message, filename, lineNumber, -1, cause);
    }

    /**
     * Construct an XMLParseException with a detail message,
     * file name, line number, column, and cause.
     *
     * @param message the detail message
     * @param filename the name of the XML file
     * @param lineNumber the line in the file where the exception occurred
     * @param column the column in the line where the exception occurred
     * @param cause the underlying cause of this exception
     */
    public XMLParseException(String message, String filename,
                             int lineNumber, int column,
                             Throwable cause) {
        super(String.format(Locale.US, "%s at %s", message,
                getLocation(filename, lineNumber, column)), cause);
        this.filename = filename;
        this.lineNumber = lineNumber;
        this.column = column;
    }

    /**
     * @return the name of the file where this exception occurred
     * if known; otherwise {@code null}
     */
    public String getFilename() {
        return filename;
    }

    /**
     * @return the line number where this exception occurred if known;
     * otherwise -1
     */
    public int getLineNumber() {
        return lineNumber;
    }

    /**
     * @return the column number where this exception occurred if known;
     * otherwise -1
     */
    public int getColumn() {
        return column;
    }

    private static String getLocation(String filename,
                                      int lineNumber, int column) {
        StringBuilder sb = new StringBuilder();
        if (filename != null) {
            sb.append(filename);
            if (lineNumber > 0)
                sb.append(':');
        }
        if (lineNumber > 0) {
            sb.append(lineNumber);
            if (column >= 0)
                sb.append(" column ").append(column);
        }
        return sb.toString();
    }

}
