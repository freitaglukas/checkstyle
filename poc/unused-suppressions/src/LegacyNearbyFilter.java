import com.puppycrawl.tools.checkstyle.TreeWalkerAuditEvent;
import com.puppycrawl.tools.checkstyle.filters.SuppressWithNearbyCommentFilter;

/** One same-line marker per file is sufficient to reproduce the cache concern. */
public final class LegacyNearbyFilter extends SuppressWithNearbyCommentFilter {
    @Override
    public boolean accept(TreeWalkerAuditEvent event) {
        final boolean accepted = super.accept(event);
        if (!accepted) {
            CacheRepro.USED.add(new CacheRepro.Marker(event.fileName(), event.getLine()));
        }
        return accepted;
    }
}
