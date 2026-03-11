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
 * Exception thrown by {@link XMLImporter} when reading an element
 * opening or closing tag at a point where that element is invalid
 * or if the element is unknown.
 */
public class XMLUnexpectedElementException extends XMLParseException {

    /** The name of the unexpected element */
    private final String elementName;

    /**
     * Whether this was an opening tag {@code true}
     * or closing tag {@code false}
     */
    private final boolean isOpenTag;

    /**
     * The parent tag where this element was found,
     * or {@code null} if it is the document root.
     */
    private final String parentName;

    /**
     * Use this when we don&rsquo;t know anything about where the
     * unexpected element occurred.
     *
     * @param elementName the name of the unexpected XML element
     * @param isOpenTag Whether this was an opening tag {@code true}
     * or closing tag {@code false}
     * @param parentName The parent tag where this element was found,
     * or {@code null} if it is the document root.
     */
    public XMLUnexpectedElementException(
            String elementName, boolean isOpenTag, String parentName) {
        this(elementName, isOpenTag, parentName,
                null, -1, -1);
    }

    public XMLUnexpectedElementException(
            String elementName, boolean isOpenTag, String parentName,
            int lineNumber) {
        this(elementName, isOpenTag, parentName,
                null, lineNumber, -1);
    }

    public XMLUnexpectedElementException(
            String elementName, boolean isOpenTag, String parentName,
            String filename) {
        this(elementName, isOpenTag, parentName,
                filename, -1, -1);
    }

    public XMLUnexpectedElementException(
            String elementName, boolean isOpenTag, String parentName,
            String filename, int lineNumber) {
        this(elementName, isOpenTag, parentName,
                filename, lineNumber, -1);
    }

     /**
      * Use this when the name of the XML file and the exact location
      * of the unexpected element are known.
      *
      * @param elementName the name of the unexpected XML element
      * @param isOpenTag Whether this was an opening tag {@code true}
      * or closing tag {@code false}
      * @param parentName The parent tag where this element was found,
      * or {@code null} if it is the document root.
      * @param filename the name of the XML file
      * @param lineNumber the line in the XML file containing
      * the element
      * @param column the column in the line where the element is found
      */
    public XMLUnexpectedElementException(
            String elementName, boolean isOpenTag, String parentName,
            String filename, int lineNumber, int column) {
        super(formatMessage(elementName, isOpenTag, parentName),
                filename, lineNumber, column);
        this.elementName = elementName;
        this.isOpenTag = isOpenTag;
        this.parentName = parentName;
    }

    /**
     * @return the name of the unexpected element
     */
    public String getElementName() {
        return elementName;
    }

    /** @return true if this is an opening tag */
    public boolean isOpenTag() {
        return isOpenTag;
    }

    /**
     * @return the name of the parent tag, or {@code null}
     * if the unexpected element was at the document root
     */
    public String getParentName() {
        return parentName;
    }

    private static String formatMessage(
            String elementName, boolean isOpenTag,
            String parentName) {
        StringBuilder sb = new StringBuilder("Unexpected <");
        if (isOpenTag)
            sb.append('/');
        sb.append(elementName).append("> tag found ");
        if (parentName == null)
            sb.append("at document root");
        else
            sb.append("in <").append(parentName).append("> element");
        return sb.toString();
    }

}
