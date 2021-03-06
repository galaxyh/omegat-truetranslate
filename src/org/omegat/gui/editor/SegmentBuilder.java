/**************************************************************************
 OmegaT - Computer Assisted Translation (CAT) tool
          with fuzzy matching, translation memory, keyword search,
          glossaries, and translation leveraging into updated projects.

 Copyright (C) 2009 Alex Buloichik, Martin Fleurke
               2010 Alex Buloichik, Didier Briel
               2012 Martin Fleurke, Hans-Peter Jacobs
               2015 Aaron Madlon-Kay
               Home page: http://www.omegat.org/
               Support center: http://groups.yahoo.com/group/OmegaT/

 This file is part of OmegaT.

 OmegaT is free software: you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation, either version 3 of the License, or
 (at your option) any later version.

 OmegaT is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with this program.  If not, see <http://www.gnu.org/licenses/>.
 **************************************************************************/

package org.omegat.gui.editor;

import java.text.DateFormat;
import java.text.DecimalFormat;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.Element;
import javax.swing.text.MutableAttributeSet;
import javax.swing.text.Position;

import org.omegat.core.Core;
import org.omegat.core.data.ProjectTMX;
import org.omegat.core.data.SourceTextEntry;
import org.omegat.core.data.TMXEntry;
import org.omegat.gui.editor.MarkerController.MarkInfo;
import org.omegat.util.Language;
import org.omegat.util.Log;
import org.omegat.util.OConsts;
import org.omegat.util.OStrings;
import org.omegat.util.Preferences;
import org.omegat.util.StaticUtils;
import org.omegat.util.StringUtil;
import org.omegat.util.gui.UIThreadsUtil;

/**
 * Class for store information about displayed segment, and for show segment in
 * editor.
 *
 * @author Alex Buloichik (alex73mail@gmail.com)
 * @author Didier Briel
 * @author Martin Fleurke
 * @author Hans-Peter Jacobs
 * @author Aaron Madlon-Kay
 */
public class SegmentBuilder {

    /** Attributes for show text. */
    public static final String SEGMENT_MARK_ATTRIBUTE = "SEGMENT_MARK_ATTRIBUTE";
    public static final String SEGMENT_SPELL_CHECK = "SEGMENT_SPELL_CHECK";
    private static final DecimalFormat NUMBER_FORMAT = new DecimalFormat("0000");
    private static final DateFormat dateFormat = DateFormat.getDateInstance();
    private static final DateFormat timeFormat = DateFormat.getTimeInstance();

    static AtomicLong globalVersions = new AtomicLong();

    final SourceTextEntry ste;
    final int segmentNumberInProject;

    /**
     * Version of displayed variant of segment. Required for check in delayed
     * thread, like spell checking. Version changed(in Swing thread only) each
     * time when entry drawn, and when user edits it (for active entry).
     */
    private volatile long displayVersion;
    /** Source text of entry, or null if not displayed. */
    private String sourceText;
    /** Translation text of entry, or null if not displayed. */
    private String translationText;
    /** True if entry is active. */
    private boolean active;
    /** True if translation exist for entry. */
    private boolean transExist;
    /** True if entry has a note attached. */
    private boolean noteExist;
    /** True if translation is default, false - is multiple. */
    private boolean defaultTranslation;

    private final Document3 doc;
    private final EditorController controller;
    private final EditorSettings settings;
    /**
     * Offset of first c.q. last character in active source text
     */
    protected int activeTranslationBeginOffset, activeTranslationEndOffset;

    /** Boundary of full entry display. */
    protected Position beginPosP1, endPosM1;

    /** Source start position - for marks. */
    protected Position posSourceBeg;
    protected int posSourceLength;
    /** Translation start position - for marks. */
    protected Position posTranslationBeg;
    protected int posTranslationLength;

    /** current offset in document to insert new stuff*/
    protected int offset;

    /**
     * Markers for this segment.
     * 
     * Array of displayed marks. 1nd dimension - marker, 2nd dimension - marks
     */
    protected MarkInfo[][] marks;

    /**
     * True if source OR target languages is RTL. In this case, we will insert
     * RTL/LTR embedded direction chars. Otherwise - will not insert, since JDK
     * 1.6 has bug with performance with embedded directions chars.
     */
    protected final boolean hasRTL;

    public SegmentBuilder(final EditorController controller, final Document3 doc, final EditorSettings settings,
            final SourceTextEntry ste, final int segmentNumberInProject, final boolean hasRTL) {
        this.controller = controller;
        this.doc = doc;
        this.settings = settings;
        this.ste = ste;
        this.segmentNumberInProject = segmentNumberInProject;
        this.hasRTL = hasRTL;
    }

    public boolean isDefaultTranslation() {
        return defaultTranslation;
    }

    public void setDefaultTranslation(boolean defaultTranslation) {
        this.defaultTranslation = defaultTranslation;
    }

    /**
     * Create element for one segment.
     *
     * @param doc
     *            document
     * @return OmElementSegment
     */
    public void createSegmentElement(final boolean isActive) {
        UIThreadsUtil.mustBeSwingThread();

        displayVersion = globalVersions.incrementAndGet();
        this.active = isActive;

        doc.trustedChangesInProgress = true;
        try {
            try {
                if (beginPosP1 != null && endPosM1 != null) {
                    // remove old segment
                    int beginOffset = beginPosP1.getOffset() - 1;
                    int endOffset = endPosM1.getOffset() + 1;
                    doc.remove(beginOffset, endOffset - beginOffset);
                    offset = beginOffset;
                } else {
                    // there is no segment in document yet - need to add
                    offset = doc.getLength();
                }

                TMXEntry trans = Core.getProject().getTranslationInfo(ste);
                defaultTranslation = trans.defaultTranslation;
                if (!Core.getProject().getProjectProperties().isSupportDefaultTranslations()) {
                    defaultTranslation = false;
                }
                transExist = trans.isTranslated();
                noteExist  = trans.hasNote();

                int beginOffset = offset;
                if (isActive) {
                    createActiveSegmentElement(trans);
                } else {
                    createInactiveSegmentElement(trans);
                }
                int endOffset = offset;

                beginPosP1 = doc.createPosition(beginOffset + 1);
                endPosM1 = doc.createPosition(endOffset - 1);
            } catch (BadLocationException ex) {
                throw new RuntimeException(ex);
            }
        } finally {
            doc.trustedChangesInProgress = false;
        }
    }

    /**
     * Add separator between segments - one empty line.
     *
     * @param doc
     */
    public static void addSegmentSeparator(final Document3 doc) {
        doc.trustedChangesInProgress = true;
        try {
            try {
                doc.insertString(doc.getLength(), "\n", null);
            } catch (BadLocationException ex) {
                throw new RuntimeException(ex);
            }
        } finally {
            doc.trustedChangesInProgress = false;
        }
    }

    /**
     * Create active segment for given entry
     */
    private void createActiveSegmentElement(TMXEntry trans) throws BadLocationException {
        try {
            if (EditorSettings.DISPLAY_MODIFICATION_INFO_ALL.equals(settings.getDisplayModificationInfo())
                    || EditorSettings.DISPLAY_MODIFICATION_INFO_SELECTED.equals(settings
                            .getDisplayModificationInfo())) {
                addModificationInfoPart(trans);
            }

            int prevOffset = offset;
            sourceText = ste.getSrcText();
            addInactiveSegPart(true, sourceText);

            Map<Language,ProjectTMX> otherLanguageTMs = Core.getProject().getOtherTargetLanguageTMs();
            for (Map.Entry<Language,ProjectTMX> entry : otherLanguageTMs.entrySet()) {
                TMXEntry altTrans = entry.getValue().getDefaultTranslation(sourceText);
                if (altTrans!=null && altTrans.isTranslated()) {
                    Language language = entry.getKey();
                    addOtherLanguagePart(altTrans.translation, language);
                }
            }

            posSourceBeg = doc.createPosition(prevOffset + (hasRTL ? 1 : 0));
            posSourceLength = sourceText.length();

            if (trans.isTranslated()) {
                //translation exist
                translationText = trans.translation;
            } else {
                boolean insertSource = !Preferences.isPreference(Preferences.DONT_INSERT_SOURCE_TEXT);
                if (controller.entriesFilter != null && controller.entriesFilter.isSourceAsEmptyTranslation()) {
                    insertSource = true;
                }
                if (insertSource) {
                    // need to insert source text on empty translation
                    translationText = ste.getSrcText();
                } else {
                    // empty text on non-exist translation
                    translationText = "";
                }
            }

            addActiveSegPart(translationText);
            posTranslationBeg = null;

            doc.activeTranslationBeginM1 = doc.createPosition(activeTranslationBeginOffset - 1);
            doc.activeTranslationEndP1 = doc.createPosition(activeTranslationEndOffset + 1);
        } catch (OutOfMemoryError oome) {
            // Oh shit, we're all out of storage space!
            // Of course we should've cleaned up after ourselves earlier,
            // but since we didn't, do a bit of cleaning up now, otherwise
            // we can't even inform the user about our slacking off.
            doc.remove(0, doc.getLength());

            // Well, that cleared up some, GC to the rescue!
            System.gc();

            // There, that should do it, now inform the user
            Object[] args = { Runtime.getRuntime().maxMemory() / 1024 / 1024 };
            Log.logErrorRB("OUT_OF_MEMORY", args);
            Log.log(oome);
            Core.getMainWindow().showErrorDialogRB("OUT_OF_MEMORY", args, "TF_ERROR");
            // Just quit, we can't help it anyway
            System.exit(0);

        }
    }

    /**
     * Create method for inactive segment.
     * @param trans TMX entry with translation
     * @throws BadLocationException
     */
    private void createInactiveSegmentElement(TMXEntry trans) throws BadLocationException {
        if (EditorSettings.DISPLAY_MODIFICATION_INFO_ALL.equals(settings.getDisplayModificationInfo())) {
            addModificationInfoPart(trans);
        }

        sourceText = null;
        translationText = null;

        if (settings.isDisplaySegmentSources()) {
            sourceText = ste.getSrcText();
        }

        if (trans.isTranslated()) {
            // translation exist
            translationText = trans.translation;
            if (StringUtil.isEmpty(translationText)) {
                translationText = OStrings.getString("EMPTY_TRANSLATION");
            }
        } else {
            if (sourceText == null) {
                // translation not exist, but source display disabled also -
                // need to display source
                sourceText = ste.getSrcText();
            }
        }

        if (sourceText != null) {
            int prevOffset = offset;
            addInactiveSegPart(true, sourceText);
            posSourceBeg = doc.createPosition(prevOffset + (hasRTL ? 1 : 0));
            posSourceLength = sourceText.length();
        } else {
            posSourceBeg = null;
        }

        if (translationText != null) {
            int prevOffset = offset;
            addInactiveSegPart(false, translationText);
            posTranslationBeg = doc.createPosition(prevOffset + (hasRTL ? 1 : 0));
            posTranslationLength = translationText.length();
        } else {
            posTranslationBeg = null;
        }
    }

    public SourceTextEntry getSourceTextEntry() {
        return ste;
    }

    public long getDisplayVersion() {
        return displayVersion;
    }

    public boolean isActive() {
        return active;
    }

    public String getSourceText() {
        return sourceText;
    }

    public String getTranslationText() {
        return translationText;
    }

    public int getStartSourcePosition() {
        if (posSourceBeg != null) {
            return posSourceBeg.getOffset();
        } else {
            return -1;
        }
    }

    public int getStartTranslationPosition() {
        if (posTranslationBeg != null) {
            return posTranslationBeg.getOffset();
        } else {
            return -1;
        }
    }

    /**
     * Get segment's start position.
     *
     * @return start position
     */
    public int getStartPosition() {
        return beginPosP1.getOffset() - 1;
    }

    /**
     * Get segment's end position.
     *
     * @return end position
     */
    public int getEndPosition() {
        return endPosM1.getOffset() + 1;
    }

    /**
     * Set attributes for created paragraphs for better RTL support.
     *
     * @param begin
     *            paragraphs begin
     * @param end
     *            paragraphs end
     * @param isRTL
     *            is text right-to-left?
     */
    private void setAlignment(int begin, int end, boolean isRTL) {
        boolean rtl = false;
        switch (controller.currentOrientation) {
        case ALL_LTR:
            rtl = false;
            break;
        case ALL_RTL:
            rtl = true;
            break;
        case DIFFER:
            rtl = isRTL;
            break;
        }
        doc.setAlignment(begin, end, rtl);
    }

    /**
     * Check if location inside segment.
     */
    public boolean isInsideSegment(int location) {
        return beginPosP1.getOffset() - 1 <= location && location < endPosM1.getOffset() + 1;
    }

    /**
     * Add inactive segment part, without segment begin/end marks.
     *
     * @param isSource is text the source text (true) or translation text (false)
     * @param text
     *            segment part text
     * @throws BadLocationException
     */
    private void addInactiveSegPart(boolean isSource, String text)
            throws BadLocationException {
        int prevOffset = offset;
        boolean rtl = isSource ? controller.sourceLangIsRTL : controller.targetLangIsRTL;
        insertDirectionEmbedding(rtl);
        insertTextWithTags(text, isSource);
        insertDirectionEndEmbedding();
        insert("\n", null);
        setAlignment(prevOffset, offset, rtl);
    }

    /**
     * Add inactive segment part, without segment begin/end marks.
     *
     * @param text other language translation text
     * @throws BadLocationException
     */
    private void addOtherLanguagePart(String text, Language language)
            throws BadLocationException {
        int prevOffset = offset;
        boolean rtl = EditorUtils.isRTL(language.getLanguageCode());
        insertDirectionEmbedding(false);
        AttributeSet normal = attrs(true, false, false, false);
        insert(language.getLanguage()+": ", normal);
        insertDirectionEndEmbedding();

        insertDirectionEmbedding(rtl);
        AttributeSet attrs = settings.getOtherLanguageTranslationAttributeSet();
        insert(text, attrs);
        insertDirectionEndEmbedding();
        insert("\n", null);
        setAlignment(prevOffset, offset, rtl);
    }

    /**
     * Adds a string that displays the modification info (author and date). Does
     * nothing if the translation entry is null.
     *
     * @param trans
     *            The translation entry (can be null)
     * @throws BadLocationException
     */
    private void addModificationInfoPart(TMXEntry trans) throws BadLocationException {
        if (!trans.isTranslated())
            return;

        String text;
        if (Preferences.isPreference(Preferences.VIEW_OPTION_TEMPLATE_ACTIVE)) {
             text = ModificationInfoManager.apply(trans);
        } else {
            String author = (trans.changer == null ? OStrings.getString("TF_CUR_SEGMENT_UNKNOWN_AUTHOR")
                    : trans.changer);
            String template;
            if (trans.changeDate != 0) {
                template = OStrings.getString("TF_CUR_SEGMENT_AUTHOR_DATE");
                Date changeDate = new Date(trans.changeDate);
                String changeDateString = dateFormat.format(changeDate);
                String changeTimeString = timeFormat.format(changeDate);
                Object[] args = { author, changeDateString, changeTimeString };
                text = StaticUtils.format(template, args);
            } else {
                template = OStrings.getString("TF_CUR_SEGMENT_AUTHOR");
                Object[] args = { author };
                text = StaticUtils.format(template, args);
            }
        }

        int prevOffset = offset;
        boolean rtl = EditorUtils.localeIsRTL();
        insertDirectionEmbedding(rtl);
        AttributeSet attrs = settings.getModificationInfoAttributeSet();
        insert(text, attrs);
        insertDirectionEndEmbedding();
        insert("\n", null);
        setAlignment(prevOffset, offset, rtl);
    }

    /**
     * Add active (translation) segment part, with segment begin/end marks.
     *
     * @param text
     *            segment part text
     * @throws BadLocationException
     */
    private void addActiveSegPart(String text) throws BadLocationException {
        int prevOffset = offset;

        //write translation part
        boolean rtl = controller.targetLangIsRTL;

        insertDirectionEmbedding(rtl);

        activeTranslationBeginOffset = offset;
        insertTextWithTags(text, false);
        activeTranslationEndOffset = offset;

        insertDirectionEndEmbedding();

        //write segment marker
        //we want the marker AFTER the translated text, so use same direction as target text.
        insertDirectionMarker(rtl);

        //the marker itself is in user language
        insertDirectionEmbedding(EditorUtils.localeIsRTL()); 
        AttributeSet attrSegmentMark = settings.getSegmentMarkerAttributeSet();
        insert(createSegmentMarkText(), attrSegmentMark);
        insertDirectionEndEmbedding();

        //we want the marker AFTER the translated text, so embed in same direction as target text.
        insertDirectionMarker(rtl);

        insert("\n", null);

        setAlignment(prevOffset, offset, rtl);
    }

    void createInputAttributes(Element element, MutableAttributeSet set) {
        set.addAttributes(attrs(false, false, false, false));
    }

    private void insert(String text, AttributeSet attrs) throws BadLocationException {
        doc.insertString(offset, text, attrs);
        offset += text.length();
    }

    /**
     * Make some changes of segment mark from resource bundle for display
     * correctly in editor.
     *
     * @return changed mark text
     */
    private String createSegmentMarkText() {
        String text = OConsts.segmentMarkerString;

        //replace placeholder with actual segment number
        if (text.contains("0000")) {
            String replacement = NUMBER_FORMAT.format(segmentNumberInProject);
            if (Preferences.isPreference(Preferences.MARK_NON_UNIQUE_SEGMENTS)
                    && ste.getDuplicate() != SourceTextEntry.DUPLICATE.NONE) {
                replacement = StaticUtils.format(OStrings.getString("SW_FILE_AND_NR_OF_MORE"),
                        replacement,
                        ste.getNumberOfDuplicates());
            }
            text = text.replace("0000", replacement);
        }
        
        // trim and replace spaces to non-break spaces
        text = text.trim().replace(' ', '\u00A0');

        return text;
    }

    /**
     * Called on the active entry changed. Required for update translation text.
     */
    void onActiveEntryChanged() {
        translationText = doc.extractTranslation();
        displayVersion = globalVersions.incrementAndGet();
    }

    /**
     * Choose segment part attributes based on rules.
     * @param isSource is it a source segment or a target segment
     * @param isPlaceholder is it for a placeholder (OmegaT tag or sprintf-variable etc.) or regular text inside the segment?
     * @param isRemoveText is it text that should be removed in the translation?
     * @param isNBSP is the text a non-breakable space?
     * @return the attributes to format the text
     */
    public AttributeSet attrs(boolean isSource, boolean isPlaceholder, boolean isRemoveText, boolean isNBSP) {
        return settings.getAttributeSet(isSource, isPlaceholder, isRemoveText, ste.getDuplicate(), active, transExist, noteExist, isNBSP);
    }

    /**
     * Inserts the texts and formats the text
     * @param text source or translation text
     * @param isSource true if it is a source text, false if it is a translation
     * @throws BadLocationException
     */
    private void insertTextWithTags(String text, boolean isSource) throws BadLocationException {
        AttributeSet normal = attrs(isSource, false, false, false);
        insert(text, normal);
    }

    public void resetTextAttributes() {
        doc.trustedChangesInProgress = true;
        try {
            if (posSourceBeg != null) {
                int sBeg = posSourceBeg.getOffset();
                int sLen = posSourceLength;
                AttributeSet attrs = attrs(true, false, false, false);
                doc.setCharacterAttributes(sBeg, sLen, attrs, true);
            }
            if (active) {
                int tBeg = doc.getTranslationStart();
                int tEnd = doc.getTranslationEnd();
                AttributeSet attrs = attrs(false, false, false, false);
                doc.setCharacterAttributes(tBeg, tEnd - tBeg, attrs, true);
            } else {
                if (posTranslationBeg != null) {
                    int tBeg = posTranslationBeg.getOffset();
                    int tLen = posTranslationLength;
                    AttributeSet attrs = attrs(false, false, false, false);
                    doc.setCharacterAttributes(tBeg, tLen, attrs, true);
                }
            }
        } finally {
            doc.trustedChangesInProgress = false;
        }
    }

    /**
     * Writes (if necessary) an RTL or LTR marker. Use it before writing text in some language.
     * @param isRTL is the language that has to be written a right-to-left language?
     * @throws BadLocationException
     */
    private void insertDirectionEmbedding(boolean isRTL) throws BadLocationException {
        if (this.hasRTL) {
            insert(isRTL ? "\u202b" : "\u202a", null); // RTL- or LTR- embedding
        }
    }

    /**
     * Writes (if necessary) an end-of-embedding marker. Use it after writing text in some language.
     * @throws BadLocationException
     */
    private void insertDirectionEndEmbedding() throws BadLocationException {
        if (this.hasRTL) {
            insert("\u202c", null); // end of embedding
        }
    }

    /**
     * Writes (if necessary) an RTL or LTR marker. Use it before writing text in some language.
     * @param isRTL is the language that has to be written a right-to-left language?
     * @throws BadLocationException
     */
    private void insertDirectionMarker(boolean isRTL) throws BadLocationException {
        if (this.hasRTL) {
            insert(isRTL ? "\u200f" : "\u200e", null); // RTL- or LTR- marker
        }
    }
}
