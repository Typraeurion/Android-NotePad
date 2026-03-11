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
 * Exception thrown by {@link XMLImporter} when the value of
 * an attribute or element fails conversion from a string to
 * the expected data type or its value is out of range or
 * otherwise invalid.
 */
public class XMLBadValueException extends XMLParseException {

    /** The name of the element containing the invalid value */
    private final String elementName;

    /**
     * The name of the attribute whose value is invalid,
     * or null if it&rsquo;s the element content that is invalid.
     */
    private final String attributeName;

    /**
     * The invalid value string
     */
    private final String badValue;

    /**
     * Use this when we don&rsquo;t know anything about where the
     * bad value occurred.
     *
     * @param elementName the name of the XML element where we
     * expected the attribute
     * @param attributeName the name of the missing attribute
     * @param badValue the invalid value string
     */
    public XMLBadValueException(
            String elementName, String attributeName, String badValue) {
        this(elementName, attributeName, badValue,
                null, -1, -1, null);
    }

    /**
     * Use this when we don&rsquo;t know anything about where the
     * bad value occurred and the bad value was detected by a
     * parse or conversion exception.
     *
     * @param elementName the name of the XML element where we
     * expected the attribute
     * @param attributeName the name of the missing attribute
     * @param badValue the invalid value string
     * @param cause the exception thrown when attempting to parse the value
     */
    public XMLBadValueException(
            String elementName, String attributeName, String badValue,
            Throwable cause) {
        this(elementName, attributeName, badValue,
                null, -1, -1, cause);
    }

    public XMLBadValueException(
            String elementName, String attributeName, String badValue,
            int lineNumber) {
        this(elementName, attributeName, badValue,
                null, lineNumber, -1, null);
    }

    public XMLBadValueException(
            String elementName, String attributeName, String badValue,
            int lineNumber,
            Throwable cause) {
        this(elementName, attributeName, badValue,
                null, lineNumber, -1, cause);
    }

    public XMLBadValueException(
            String elementName, String attributeName, String badValue,
            String filename) {
        this(elementName, attributeName, badValue,
                filename, -1, -1, null);
    }

    public XMLBadValueException(
            String elementName, String attributeName, String badValue,
            String filename, Throwable cause) {
        this(elementName, attributeName, badValue,
                filename, -1, -1, cause);
    }

    public XMLBadValueException(
            String elementName, String attributeName, String badValue,
            String filename, int lineNumber) {
        this(elementName, attributeName, badValue,
                filename, lineNumber, -1, null);
    }

    public XMLBadValueException(
            String elementName, String attributeName, String badValue,
            String filename, int lineNumber,
            Throwable cause) {
        this(elementName, attributeName, badValue,
                filename, lineNumber, -1, cause);
    }

    /**
     * Use this when the name of the XML file and the exact location
     * of the element with the missing attribute are known
     * and the bad value was detected by our own code.
     *
     * @param elementName the name of the XML element where we
     * expected the attribute
     * @param attributeName the name of the missing attribute
     * @param badValue the invalid value string
     * @param filename the name of the XML file
     * @param lineNumber the line in the XML file containing
     * the element
     * @param column the column in the line where the element is found
     */
    public XMLBadValueException(
            String elementName, String attributeName, String badValue,
            String filename, int lineNumber, int column) {
        this(elementName, attributeName, badValue,
                filename, lineNumber, column, null);
    }

    /**
     * Use this when the name of the XML file and the exact location
     * of the element with the missing attribute are known
     * and the bad value was detected by a thrown exception.
     *
     * @param elementName the name of the XML element where we
     * expected the attribute
     * @param attributeName the name of the missing attribute
     * @param badValue the invalid value string
     * @param filename the name of the XML file
     * @param lineNumber the line in the XML file containing
     * the element
     * @param column the column in the line where the element is found
     * @param cause the exception thrown when attempting to parse the value
     */
    public XMLBadValueException(
            String elementName, String attributeName, String badValue,
            String filename, int lineNumber, int column,
            Throwable cause) {
        super(String.format(Locale.US,
                "<%s> attribute \"%s\" has invalid value \"%s\"",
                elementName, attributeName, badValue),
                filename, lineNumber, column, cause);
        this.elementName = elementName;
        this.attributeName = attributeName;
        this.badValue = badValue;
    }

    /**
     * @return the name of the element which has the bad value.
     */
    public String getElementName() {
        return elementName;
    }

    /**
     * @return the name of the attribute containing a bad value,
     * or {@code null} if the bad value was in the element content.
     */
    public String getAttributeName() {
        return attributeName;
    }

    /**
     * @return the invalid value
     */
    public String getBadValue() {
        return badValue;
    }

}
