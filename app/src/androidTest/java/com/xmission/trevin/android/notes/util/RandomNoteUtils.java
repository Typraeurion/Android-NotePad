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

import com.xmission.trevin.android.notes.data.NoteCategory;
import com.xmission.trevin.android.notes.data.NoteItem;

import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class RandomNoteUtils {

    private static final Random RAND = new Random();

    /** Random word generator.  Just a string of lower-case letters. */
    public static String randomWord() {
        int targetLen = RAND.nextInt(7) + 1;
        return RandomStringUtils.randomAlphabetic(targetLen).toLowerCase();
    }

    /**
     * Random sentence generator.  Strings random words with the first
     * word capitalized, joined by spaces, ending in a period.
     */
    public static String randomSentence() {
        int numWords = RAND.nextInt(10) + 3;
        List<String> words = new ArrayList<>();
        words.add(('A' + RAND.nextInt(26)) + randomWord());
        while (words.size() < numWords - 1)
            words.add(randomWord());
        words.add(randomWord() + ".");
        return StringUtils.join(words, ' ');
    }

    /**
     * Random paragraph generator.  Strings random sentences together
     * joined by two spaces each.  Ends the whole thing with a newline.
     */
    public static String randomParagraph() {
        int numSentences = RAND.nextInt(5) + 1;
        List<String> sentences = new ArrayList<>();
        while (sentences.size() < numSentences)
            sentences.add(randomSentence());
        return StringUtils.join(sentences, "  ") + "\n";
    }

    /**
     * Generate a random note.
     */
    public static NoteItem randomNote() {
        NoteItem note = new NoteItem();
        note.setCreateTimeNow();
        note.setModTime(note.getCreateTime());
        note.setCategoryId(NoteCategory.UNFILED);
        note.setNote(randomParagraph());
        return note;
    }

}
