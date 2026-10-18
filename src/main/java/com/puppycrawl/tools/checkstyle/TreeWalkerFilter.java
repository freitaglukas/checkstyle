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

package com.puppycrawl.tools.checkstyle;

import java.util.Collections;
import java.util.SortedSet;

import com.puppycrawl.tools.checkstyle.api.FileContents;
import com.puppycrawl.tools.checkstyle.api.Violation;

/**
 * An interface for filtering {@code TreeWalkerAuditEvent}.
 *
 */
@FunctionalInterface
public interface TreeWalkerFilter {

    /**
     * Determines whether or not a filtered {@code TreeWalkerAuditEvent} is accepted.
     * An accepted event is kept in the final report as a violation; a rejected
     * event is filtered out. In other words, returning {@code true} makes the
     * violation appear in the report, while returning {@code false} suppresses it.
     *
     * @param treeWalkerAuditEvent the TreeWalkerAuditEvent to filter.
     * @return true if the event is accepted and its violation should appear in
     *     the final report; false to suppress the event.
     */
    boolean accept(TreeWalkerAuditEvent treeWalkerAuditEvent);

    /**
     * Whether this filter needs a final report for each successfully parsed file.
     *
     * @return whether file reporting is enabled
     */
    default boolean isFileReportRequired() {
        return false;
    }

    /**
     * Reports findings after ordinary filtering, using an immutable snapshot of
     * original violations. Reported findings do not pass through TreeWalker filters.
     *
     * @param contents successfully parsed file contents
     * @param rawViolations complete original violations before filtering
     * @return additional findings for this file
     */
    default SortedSet<Violation> getFileReport(
            FileContents contents, SortedSet<Violation> rawViolations) {
        return Collections.emptySortedSet();
    }

}
