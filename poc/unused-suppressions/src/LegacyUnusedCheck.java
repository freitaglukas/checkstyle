import java.io.File;

import com.puppycrawl.tools.checkstyle.api.AbstractFileSetCheck;
import com.puppycrawl.tools.checkstyle.api.FileText;

/** Deliberately unsound run-wide accounting, reported via the actual Checker. */
public final class LegacyUnusedCheck extends AbstractFileSetCheck {
    @Override
    public void beginProcessing(String charset) {
        CacheRepro.USED.clear();
    }

    @Override
    protected void processFiltered(File file, FileText contents) { }

    @Override
    public void finishProcessing() {
        for (CacheRepro.Marker marker : CacheRepro.DECLARED) {
            if (!CacheRepro.USED.contains(marker)) {
                log(marker.line(), "suppression.unused");
                fireErrors(marker.file());
            }
        }
    }
}
