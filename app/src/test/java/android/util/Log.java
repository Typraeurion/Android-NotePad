/*
 * Code based on a suggestion from
 * https://stackoverflow.com/a/46793567/13442812
 *
 * Copyleft © 2025 Trevin Beattie
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
package android.util;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;

/**
 * Mock logger for use with Android unit tests
 *
 * @author Trevin Beattie
 */
public class Log {

    private enum Level {
        /**
         * Priority constant for the println method; use Log.v.
         */
        VERBOSE,

        /**
         * Priority constant for the println method; use Log.d.
         */
        DEBUG,

        /**
         * Priority constant for the println method; use Log.i.
         */
        INFO,

        /**
         * Priority constant for the println method; use Log.w.
         */
        WARN,

        /**
         * Priority constant for the println method; use Log.e.
         */
        ERROR,

        /**
         * Priority constant for the println method.
         */
        ASSERT
    }

    /**
     * @param level the level of the log message
     * @param tag Used to identify the source of a log message.
     * @param message The message you would like logged.
     *
     * @return the number of bytes written
     */
    static int logAt(Level level, String tag, String message) {
        String line = String.format("[%s] %s: %s\n", level, tag, message);
        System.out.print(line);
        return line.getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * Handy function to get a loggable stack trace from a Throwable
     * @param tr An exception to log
     */
    static String getStackTraceString(Throwable tr) {
        if (tr == null) {
            return "";
        }

        // This is to reduce the amount of log spew that apps do in the non-error
        // condition of the network being unavailable.
        Throwable t = tr;
        while (t != null) {
            if (t instanceof UnknownHostException) {
                return "";
            }
            t = t.getCause();
        }

        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        tr.printStackTrace(pw);
        pw.flush();
        return sw.toString();
    }

    /**
     * @param level the level of the log message
     * @param tag Used to identify the source of a log message.
     * @param message The message you would like logged.
     * @param exception An exception whose stack trace to include in the message
     *
     * @return the number of bytes written
     */
    static int logAt(Level level, String tag,
                     String message, Throwable exception) {
        String line = String.format("[%s] %s: %s\n")
                + getStackTraceString(exception);
        System.out.print(line);
        return line.getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * Log a message at VERBOSE level
     *
     * @param tag Used to identify the source of a log message.
     * @param message The message you would like logged.
     *
     * @return the number of bytes written
     */
    public static int v(String tag, String message) {
        return logAt(Level.VERBOSE, tag, message);
    }

    /**
     * Log a message and stack trace at VERBOSE level
     *
     * @param tag Used to identify the source of a log message.
     * @param message The message you would like logged.
     * @param exception An exception whose stack trace to include in the message
     *
     * @return the number of bytes written
     */
    public static int v(String tag, String message, Throwable exception) {
        return logAt(Level.VERBOSE, tag, message, exception);
    }

    /**
     * Log a message at DEBUG level
     *
     * @param tag Used to identify the source of a log message.
     * @param message The message you would like logged.
     *
     * @return the number of bytes written
     */
    public static int d(String tag, String message) {
        return logAt(Level.DEBUG, tag, message);
    }

    /**
     * Log a message and stack trace at DEBUG level
     *
     * @param tag Used to identify the source of a log message.
     * @param message The message you would like logged.
     * @param exception An exception whose stack trace to include in the message
     *
     * @return the number of bytes written
     */
    public static int d(String tag, String message, Throwable exception) {
        return logAt(Level.DEBUG, tag, message, exception);
    }

    /**
     * Log a message at INFO level
     *
     * @param tag Used to identify the source of a log message.
     * @param message The message you would like logged.
     *
     * @return the number of bytes written
     */
    public static int i(String tag, String message) {
        return logAt(Level.INFO, tag, message);
    }

    /**
     * Log a message and stack trace at INFO level
     *
     * @param tag Used to identify the source of a log message.
     * @param message The message you would like logged.
     * @param exception An exception whose stack trace to include in the message
     *
     * @return the number of bytes written
     */
    public static int i(String tag, String message, Throwable exception) {
        return logAt(Level.INFO, tag, message, exception);
    }

    /**
     * Log a message at WARN level
     *
     * @param tag Used to identify the source of a log message.
     * @param message The message you would like logged.
     *
     * @return the number of bytes written
     */
    public static int w(String tag, String message) {
        return logAt(Level.WARN, tag, message);
    }

    /**
     * Log a message and stack trace at WARN level
     *
     * @param tag Used to identify the source of a log message.
     * @param message The message you would like logged.
     * @param exception An exception whose stack trace to include in the message
     *
     * @return the number of bytes written
     */
    public static int w(String tag, String message, Throwable exception) {
        return logAt(Level.WARN, tag, message, exception);
    }

    /**
     * Log a message at ERROR level
     *
     * @param tag Used to identify the source of a log message.
     * @param message The message you would like logged.
     *
     * @return the number of bytes written
     */
    public static int e(String tag, String message) {
        return logAt(Level.ERROR, tag, message);
    }

    /**
     * Log a message and stack trace at ERROR level
     *
     * @param tag Used to identify the source of a log message.
     * @param message The message you would like logged.
     * @param exception An exception whose stack trace to include in the message
     *
     * @return the number of bytes written
     */
    public static int e(String tag, String message, Throwable exception) {
        return logAt(Level.ERROR, tag, message, exception);
    }

    /**
     * Log a message at ASSERT level
     *
     * @param tag Used to identify the source of a log message.
     * @param message The message you would like logged.
     *
     * @return the number of bytes written
     */
    public static int a(String tag, String message) {
        return logAt(Level.ASSERT, tag, message);
    }

    /**
     * Log a message and stack trace at ASSERT level
     *
     * @param tag Used to identify the source of a log message.
     * @param message The message you would like logged.
     * @param exception An exception whose stack trace to include in the message
     *
     * @return the number of bytes written
     */
    public static int a(String tag, String message, Throwable exception) {
        return logAt(Level.ASSERT, tag, message, exception);
    }

}
