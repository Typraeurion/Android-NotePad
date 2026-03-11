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
 * Exception thrown by {@link XMLImporter} when a required attribute
 * is not found.
 */
public class XMLMissingRequiredAttributeException extends XMLParseException {

    /** The name of the element which is missing the attribute */
    private final String elementName;

    /** The name of the expected attribute */
    private final String attributeName;

    /**
     * Use this when the name of the XML file and the location
     * of the element are unknown
     *
     * @param elementName the name of the XML element where we
     * expected the attribute
     * @param attributeName the name of the missing attribute
     */
    public XMLMissingRequiredAttributeException(
            String elementName, String attributeName) {
        this(elementName, attributeName, null, -1, -1);
    }

    /**
     * Use this when the name of the XML file is unknown
     * but we have the line number of the element.
     *
     * @param elementName the name of the XML element where we
     * expected the attribute
     * @param attributeName the name of the missing attribute
     * @param lineNumber the line in the XML file containing
     * the element
     */
    public XMLMissingRequiredAttributeException(
            String elementName, String attributeName,
            int lineNumber) {
        this(elementName, attributeName, null, lineNumber, -1);
    }

    /**
     * Use this when the name of the XML file is known
     * but not the line number of the element with the missing attribute.
     *
     * @param elementName the name of the XML element where we
     * expected the attribute
     * @param attributeName the name of the missing attribute
     * @param filename the name of the XML file
     */
    public XMLMissingRequiredAttributeException(
            String elementName, String attributeName,
            String filename) {
        this(elementName, attributeName, filename, -1, -1);
    }

    /**
     * Use this when the name of the XML file and the line number
     * of the element with the missing attribute are known.
     *
     * @param elementName the name of the XML element where we
     * expected the attribute
     * @param attributeName the name of the missing attribute
     * @param filename the name of the XML file
     * @param lineNumber the line in the XML file containing
     * the element
     */
    public XMLMissingRequiredAttributeException(
            String elementName, String attributeName,
            String filename, int lineNumber) {
        this(elementName, attributeName, filename, lineNumber, -1);
    }

    /**
     * Use this when the name of the XML file and the exact location
     * of the element with the missing attribute are known.
     *
     * @param elementName the name of the XML element where we
     * expected the attribute
     * @param attributeName the name of the missing attribute
     * @param filename the name of the XML file
     * @param lineNumber the line in the XML file containing
     * the element
     * @param column the column in the line where the element is found
     */
    public XMLMissingRequiredAttributeException(
            String elementName, String attributeName,
            String filename, int lineNumber, int column) {
        super(String.format(Locale.US,
                "<%s> is missing required attribute \"%s\"",
                elementName, attributeName), filename, lineNumber, column);
        this.elementName = elementName;
        this.attributeName = attributeName;
    }

    /**
     * @return the name of the element which is missing a required attribute
     */
    public String getElementName() {
        return elementName;
    }

    /**
     * @return the name of the missing attribute
     */
    public String getAttributeName() {
        return attributeName;
    }

}
