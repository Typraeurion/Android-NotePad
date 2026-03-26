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

import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.util.zip.ZipOutputStream;

/**
 * Modification of the {@link PrintStream} which redirects its
 * {@link PrintStream#close()} call to {@link PrintStream#flush()},
 * leaving the underlying (ZIP) stream open so we can properly
 * close the entry and potentially start another.
 */
public class ZIPEntryPrintStream extends PrintStream {

    /**
     * Creates a new print stream.
     *
     * @param out The output stream to which values and objects will be printed
     */
    public ZIPEntryPrintStream(ZipOutputStream out) {
        super(out);
    }

    /**
     * Creates a new print stream.
     *
     * @param out The output stream to which values and objects will be printed
     * @param autoFlush A boolean; if {@code true}, the output buffer will be
     * flushed whenever a byte array is written, one of the {@link #println}
     * methods is invoked, or a newline character or byte ({@code '\n'}) is
     * written.
     */
    public ZIPEntryPrintStream(ZipOutputStream out, boolean autoFlush) {
        super(out, autoFlush);
    }

    /**
     * Creates a new print stream.
     *
     * @param out The output stream to which values and objects will be printed
     * @param autoFlush A boolean; if {@code true}, the output buffer will be
     * flushed whenever a byte array is written, one of the {@link #println}
     * methods is invoked, or a newline character or byte ({@code '\n'}) is
     * written.
     * @param encoding The name of a supported character encoding
     */
    public ZIPEntryPrintStream(ZipOutputStream out,
                               boolean autoFlush,
                               String encoding)
            throws UnsupportedEncodingException {
        super(out, autoFlush, encoding);
    }

    /**
     * Flush the stream.  Does not close the underlying stream.
     */
    @Override
    public void close() {
        super.flush();
    }

}
