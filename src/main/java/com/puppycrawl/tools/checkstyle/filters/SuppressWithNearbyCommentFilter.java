///////////////////////////////////////////////////////////////////////////////////////////////
// checkstyle: Checks Java source code and other text files for adherence to a set of rules.
// Copyright (C) 2001-2026 the original author or authors.
//
// This library is free software; you can redistribute it and/or
// modify it under the terms of the GNU Lesser General Public
// License as published by the Free Software Foundation; either
// version 2.1 of the License, or (at your option) any later version.
//
// This library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
// Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public
// License along with this library; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
///////////////////////////////////////////////////////////////////////////////////////////////

package com.puppycrawl.tools.checkstyle.filters;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.puppycrawl.tools.checkstyle.AbstractAutomaticBean;
import com.puppycrawl.tools.checkstyle.PropertyType;
import com.puppycrawl.tools.checkstyle.TreeWalkerAuditEvent;
import com.puppycrawl.tools.checkstyle.TreeWalkerFilter;
import com.puppycrawl.tools.checkstyle.XdocsPropertyType;
import com.puppycrawl.tools.checkstyle.api.FileContents;
import com.puppycrawl.tools.checkstyle.api.SeverityLevel;
import com.puppycrawl.tools.checkstyle.api.TextBlock;
import com.puppycrawl.tools.checkstyle.api.Violation;
import com.puppycrawl.tools.checkstyle.utils.CommonUtil;
import com.puppycrawl.tools.checkstyle.utils.WeakReferenceHolder;

/**
 * <div>
 * Filter {@code SuppressWithNearbyCommentFilter} uses nearby comments to suppress audit events.
 * </div>
 *
 * <p>
 * Rationale: Same as {@code SuppressionCommentFilter}.
 * Whereas the SuppressionCommentFilter uses matched pairs of filters to turn
 * on/off comment matching, {@code SuppressWithNearbyCommentFilter} uses single comments.
 * This requires fewer lines to mark a region, and may be aesthetically preferable in some contexts.
 * </p>
 *
 * <p>
 * Attention: This filter may only be specified within the TreeWalker module
 * ({@code <module name="TreeWalker"/>}) and only applies to checks which are also
 * defined within this module. To filter non-TreeWalker checks like {@code RegexpSingleline},
 * a
 * <a href="https://checkstyle.org/filters/suppresswithplaintextcommentfilter.html">
 * SuppressWithPlainTextCommentFilter</a> or similar filter must be used.
 * </p>
 *
 * <p>
 * Notes:
 * SuppressWithNearbyCommentFilter can suppress Checks that have Treewalker as parent module.
 * </p>
 *
 * @since 5.0
 */
public class SuppressWithNearbyCommentFilter
    extends AbstractAutomaticBean
    implements TreeWalkerFilter {

    /** Format to turn checkstyle reporting off. */
    private static final String DEFAULT_COMMENT_FORMAT =
        "SUPPRESS CHECKSTYLE (\\w+)";

    /** Default regex for checks that should be suppressed. */
    private static final String DEFAULT_CHECK_FORMAT = ".*";

    /** Default regex for lines that should be suppressed. */
    private static final String DEFAULT_INFLUENCE_FORMAT = "0";

    /** Tagged comments. */
    private final List<Tag> tags = new ArrayList<>();

    /**
     * References the current FileContents for this filter.
     * Since this is a weak reference to the FileContents, the FileContents
     * can be reclaimed as soon as the strong references in TreeWalker
     * are reassigned to the next FileContents, at which time filtering for
     * the current FileContents is finished.
     */
    private final WeakReferenceHolder<FileContents> fileContentsHolder =
            new WeakReferenceHolder<>();

    /** Control whether to check C style comments (&#47;* ... *&#47;). */
    private boolean checkC = true;

    /** Control whether to check C++ style comments ({@code //}). */
    // -@cs[AbbreviationAsWordInName] We can not change it as,
    // check's property is a part of API (used in configurations).
    private boolean checkCPP = true;

    /** Specify comment pattern to trigger filter to begin suppression. */
    private Pattern commentFormat = Pattern.compile(DEFAULT_COMMENT_FORMAT);

    /** Specify check pattern to suppress. */
    @XdocsPropertyType(PropertyType.PATTERN)
    private String checkFormat = DEFAULT_CHECK_FORMAT;

    /** Define message pattern to suppress. */
    @XdocsPropertyType(PropertyType.PATTERN)
    private String messageFormat;

    /** Specify check ID pattern to suppress. */
    @XdocsPropertyType(PropertyType.PATTERN)
    private String idFormat;

    /**
     * Specify negative/zero/positive value that defines the number of lines
     * preceding/at/following the suppression comment.
     */
    private String influenceFormat = DEFAULT_INFLUENCE_FORMAT;

    /** Opt-in prototype reporting for markers matching no original violation. */
    private boolean reportUnusedSuppressions;

    /** Severity for unused-marker findings. */
    private SeverityLevel severity = SeverityLevel.ERROR;

    /**
     * Creates a new {@code SuppressWithNearbyCommentFilter} instance.
     */
    public SuppressWithNearbyCommentFilter() {
        // no code by default
    }

    /**
     * Setter to specify comment pattern to trigger filter to begin suppression.
     *
     * @param pattern a pattern.
     * @since 5.0
     */
    public final void setCommentFormat(Pattern pattern) {
        commentFormat = pattern;
    }

    /**
     * Setter to specify check pattern to suppress.
     * The pattern is matched against the fully qualified class name of the Check.
     *
     * @param format a {@code String} value
     * @since 5.0
     */
    public final void setCheckFormat(String format) {
        checkFormat = format;
    }

    /**
     * Setter to define message pattern to suppress.
     *
     * @param format a {@code String} value
     * @since 5.0
     */
    public void setMessageFormat(String format) {
        messageFormat = format;
    }

    /**
     * Setter to specify check ID pattern to suppress.
     *
     * @param format a {@code String} value
     * @since 8.24
     */
    public void setIdFormat(String format) {
        idFormat = format;
    }

    /**
     * Setter to specify negative/zero/positive value that defines the number
     * of lines preceding/at/following the suppression comment.
     *
     * @param format a {@code String} value
     * @since 5.0
     */
    public final void setInfluenceFormat(String format) {
        influenceFormat = format;
    }

    /**
     * Setter to control whether to check C++ style comments ({@code //}).
     *
     * @param checkCppComments {@code true} if C++ comments are checked.
     * @since 5.0
     */
    // -@cs[AbbreviationAsWordInName] We can not change it as,
    // check's property is a part of API (used in configurations).
    public void setCheckCPP(boolean checkCppComments) {
        checkCPP = checkCppComments;
    }

    /**
     * Setter to control whether to check C style comments (&#47;* ... *&#47;).
     *
     * @param checkC {@code true} if C comments are checked.
     * @since 5.0
     */
    public void setCheckC(boolean checkC) {
        this.checkC = checkC;
    }

    /**
     * Enables reporting of recognized markers that match no original violation.
     *
     * @param report whether to report unused markers
     */
    public void setReportUnusedSuppressions(boolean report) {
        reportUnusedSuppressions = report;
    }

    /**
     * Sets the severity for unused-marker findings.
     *
     * @param severity severity to use
     */
    public void setSeverity(SeverityLevel severity) {
        this.severity = severity;
    }

    @Override
    public boolean isFileReportRequired() {
        return reportUnusedSuppressions && severity != SeverityLevel.IGNORE;
    }

    @Override
    public SortedSet<Violation> getFileReport(
            FileContents contents, SortedSet<Violation> rawViolations) {
        final SortedSet<Violation> result;
        if (isFileReportRequired()) {
            fileContentsHolder.lazyUpdate(contents, this::tagSuppressions);
            result = new TreeSet<>();
            for (Tag tag : tags) {
                if (rawViolations.stream().noneMatch(tag::isMatch)) {
                    result.add(new Violation(tag.commentLine, tag.commentColumn + 1,
                            "com.puppycrawl.tools.checkstyle.filters.messages",
                            "suppression.unused", new Object[0], severity, null,
                            SuppressWithNearbyCommentFilter.class, null));
                }
            }
        }
        else {
            result = Collections.emptySortedSet();
        }
        return result;
    }

    @Override
    protected void finishLocalSetup() {
        // No code by default
    }

    @Override
    public boolean accept(TreeWalkerAuditEvent event) {
        boolean accepted = true;

        if (event.violation() != null) {
            fileContentsHolder.lazyUpdate(event.fileContents(), this::tagSuppressions);
            if (matchesTag(event)) {
                accepted = false;
            }
        }
        return accepted;
    }

    /**
     * Whether current event matches any tag from {@link #tags}.
     *
     * @param event TreeWalkerAuditEvent to test match on {@code #tags}.
     * @return true if event matches any tag from {@code #tags}, false otherwise.
     */
    private boolean matchesTag(TreeWalkerAuditEvent event) {
        boolean result = false;
        for (final Tag tag : tags) {
            if (tag.isMatch(event)) {
                result = true;
                break;
            }
        }
        return result;
    }

    /**
     * Collects all the suppression tags for all comments into a list and sorts the list.
     */
    private void tagSuppressions() {
        tags.clear();
        final FileContents contents = fileContentsHolder.get();
        if (checkCPP) {
            tagSuppressions(contents.getSingleLineComments().values());
        }
        if (checkC) {
            final Collection<List<TextBlock>> cComments =
                contents.getBlockComments().values();
            cComments.forEach(this::tagSuppressions);
        }
    }

    /**
     * Appends the suppressions in a collection of comments to the full set of suppression tags.
     *
     * @param comments the set of comments.
     */
    private void tagSuppressions(Collection<TextBlock> comments) {
        for (final TextBlock comment : comments) {
            final int startLineNo = comment.getStartLineNo();
            final String[] text = comment.getText();
            tagCommentLine(text[0], startLineNo, comment.getStartColNo());
            for (int index = 1; index < text.length; index++) {
                tagCommentLine(text[index], startLineNo + index, 0);
            }
        }
    }

    /**
     * Tags a string if it matches the format for turning
     * checkstyle reporting on or the format for turning reporting off.
     *
     * @param text the string to tag.
     * @param line the line number of text.
     * @param column the starting source column of text
     */
    private void tagCommentLine(String text, int line, int column) {
        final Matcher matcher = commentFormat.matcher(text);
        if (matcher.find()) {
            addTag(matcher.group(0), line, column + matcher.start());
        }
    }

    /**
     * Adds a comment suppression {@code Tag} to the list of all tags.
     *
     * @param text the text of the tag.
     * @param line the line number of the tag.
     * @param column source column of the tag
     */
    private void addTag(String text, int line, int column) {
        final Tag tag = new Tag(text, line, column, this);
        tags.add(tag);
    }

    /**
     * A Tag holds a suppression comment and its location.
     */
    private static final class Tag {

        /** The text of the tag. */
        private final String text;

        /** Actual marker line, independent of its influence range. */
        private final int commentLine;

        /** Actual marker column, counting from zero. */
        private final int commentColumn;

        /** The first line where warnings may be suppressed. */
        private final int firstLine;

        /** The last line where warnings may be suppressed. */
        private final int lastLine;

        /** The parsed check regexp, expanded for the text of this tag. */
        private final Pattern tagCheckRegexp;

        /** The parsed message regexp, expanded for the text of this tag. */
        private final Pattern tagMessageRegexp;

        /** The parsed check ID regexp, expanded for the text of this tag. */
        private final Pattern tagIdRegexp;

        /**
         * Constructs a tag.
         *
         * @param text the text of the suppression.
         * @param line the line number.
         * @param column source column of the marker
         * @param filter the {@code SuppressWithNearbyCommentFilter} with the context
         * @throws IllegalArgumentException if unable to parse expanded text.
         */
        private Tag(String text, int line, int column, SuppressWithNearbyCommentFilter filter) {
            this.text = text;
            commentLine = line;
            commentColumn = column;

            // Expand regexp for check and message
            // Does not intern Patterns with Utils.getPattern()
            String format = "";
            try {
                format = CommonUtil.fillTemplateWithStringsByRegexp(
                        filter.checkFormat, text, filter.commentFormat);
                tagCheckRegexp = Pattern.compile(format);
                if (filter.messageFormat == null) {
                    tagMessageRegexp = null;
                }
                else {
                    format = CommonUtil.fillTemplateWithStringsByRegexp(
                            filter.messageFormat, text, filter.commentFormat);
                    tagMessageRegexp = Pattern.compile(format);
                }
                if (filter.idFormat == null) {
                    tagIdRegexp = null;
                }
                else {
                    format = CommonUtil.fillTemplateWithStringsByRegexp(
                            filter.idFormat, text, filter.commentFormat);
                    tagIdRegexp = Pattern.compile(format);
                }
                format = CommonUtil.fillTemplateWithStringsByRegexp(
                        filter.influenceFormat, text, filter.commentFormat);

                final int influence = parseInfluence(format, filter.influenceFormat, text);

                if (influence >= 1) {
                    firstLine = line;
                    lastLine = line + influence;
                }
                else {
                    firstLine = line + influence;
                    lastLine = line;
                }
            }
            catch (final PatternSyntaxException exc) {
                throw new IllegalArgumentException(
                    "unable to parse expanded comment " + format, exc);
            }
        }

        /**
         * Gets influence from suppress filter influence format param.
         *
         * @param format          influence format to parse
         * @param influenceFormat raw influence format
         * @param text            text of the suppression
         * @return parsed influence
         * @throws IllegalArgumentException when unable to parse int in format
         */
        private static int parseInfluence(String format, String influenceFormat, String text) {
            try {
                return Integer.parseInt(format);
            }
            catch (final NumberFormatException exc) {
                throw new IllegalArgumentException("unable to parse influence from '" + text
                        + "' using " + influenceFormat, exc);
            }
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (other == null || getClass() != other.getClass()) {
                return false;
            }
            final Tag tag = (Tag) other;
            return firstLine == tag.firstLine
                    && lastLine == tag.lastLine
                    && commentLine == tag.commentLine
                    && commentColumn == tag.commentColumn
                    && Objects.equals(text, tag.text)
                    && Objects.equals(tagCheckRegexp, tag.tagCheckRegexp)
                    && Objects.equals(tagMessageRegexp, tag.tagMessageRegexp)
                    && Objects.equals(tagIdRegexp, tag.tagIdRegexp);
        }

        @Override
        public int hashCode() {
            return Objects.hash(text, firstLine, lastLine, commentLine, commentColumn,
                    tagCheckRegexp, tagMessageRegexp, tagIdRegexp);
        }

        /**
         * Determines whether the source of an audit event matches the text of this tag.
         *
         * @param event the {@code TreeWalkerAuditEvent} to check.
         * @return true if the source of event matches the text of this tag.
         */
        /* package */ boolean isMatch(TreeWalkerAuditEvent event) {
            return isMatch(event.violation());
        }

        /**
         * Matches an original violation using the same predicate as ordinary filtering.
         *
         * @param violation original violation
         * @return whether this marker matches the violation
         */
        private boolean isMatch(Violation violation) {
            return isInScopeOfSuppression(violation)
                    && isCheckMatch(violation)
                    && isIdMatch(violation)
                    && isMessageMatch(violation);
        }

        /**
         * Checks whether the violation is in the scope of the suppression.
         *
         * @param violation original violation
         * @return whether the violation is in the scope of the suppression
         */
        private boolean isInScopeOfSuppression(Violation violation) {
            final int line = violation.getLineNo();
            return line >= firstLine && line <= lastLine;
        }

        /**
         * Checks whether the violation source name matches the check format.
         *
         * @param violation original violation
         * @return true if the {@code TreeWalkerAuditEvent} source name matches the check format.
         */
        private boolean isCheckMatch(Violation violation) {
            final Matcher checkMatcher = tagCheckRegexp.matcher(violation.getSourceName());
            return checkMatcher.find();
        }

        /**
         * Checks whether the violation module ID matches the ID format.
         *
         * @param violation original violation
         * @return true if the {@code TreeWalkerAuditEvent} module ID matches the ID format.
         */
        private boolean isIdMatch(Violation violation) {
            boolean match = true;
            if (tagIdRegexp != null) {
                if (violation.getModuleId() == null) {
                    match = false;
                }
                else {
                    final Matcher idMatcher = tagIdRegexp.matcher(violation.getModuleId());
                    match = idMatcher.find();
                }
            }
            return match;
        }

        /**
         * Checks whether the violation message matches the message format.
         *
         * @param violation original violation
         * @return true if the {@code TreeWalkerAuditEvent} message matches the message format.
         */
        private boolean isMessageMatch(Violation violation) {
            boolean match = true;
            if (tagMessageRegexp != null) {
                final Matcher messageMatcher = tagMessageRegexp.matcher(violation.getViolation());
                match = messageMatcher.find();
            }
            return match;
        }

        @Override
        public String toString() {
            return "Tag[text='" + text + '\''
                    + ", firstLine=" + firstLine
                    + ", lastLine=" + lastLine
                    + ", tagCheckRegexp=" + tagCheckRegexp
                    + ", tagMessageRegexp=" + tagMessageRegexp
                    + ", tagIdRegexp=" + tagIdRegexp
                    + ']';
        }

    }

}
