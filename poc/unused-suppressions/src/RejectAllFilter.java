import com.puppycrawl.tools.checkstyle.TreeWalkerAuditEvent;
import com.puppycrawl.tools.checkstyle.TreeWalkerFilter;

/** Tests that another filter rejecting first does not imply a marker is unused. */
public final class RejectAllFilter implements TreeWalkerFilter {
    @Override
    public boolean accept(TreeWalkerAuditEvent event) {
        return false;
    }
}
