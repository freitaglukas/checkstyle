import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import com.puppycrawl.tools.checkstyle.Checker;
import com.puppycrawl.tools.checkstyle.ConfigurationLoader;
import com.puppycrawl.tools.checkstyle.PropertiesExpander;
import com.puppycrawl.tools.checkstyle.TreeWalker;
import com.puppycrawl.tools.checkstyle.TreeWalkerAuditEvent;
import com.puppycrawl.tools.checkstyle.api.AbstractFileSetCheck;
import com.puppycrawl.tools.checkstyle.api.AuditEvent;
import com.puppycrawl.tools.checkstyle.api.AuditListener;
import com.puppycrawl.tools.checkstyle.api.FileText;
import com.puppycrawl.tools.checkstyle.filters.SuppressWithNearbyCommentFilter;

/** Small executable experiment; the Legacy classes model the rejected global design. */
public final class CacheRepro {
    record Marker(String file, int line) { }
    static final List<Marker> DECLARED = new ArrayList<>();
    static final Set<Marker> USED = new HashSet<>();

    private static final class Listener implements AuditListener {
        @Override public void auditStarted(AuditEvent event) { }
        @Override public void auditFinished(AuditEvent event) { }
        @Override public void fileFinished(AuditEvent event) { }
        @Override public void fileStarted(AuditEvent event) {
            System.out.println("PROCESSED\t" + Path.of(event.getFileName()).getFileName());
        }
        @Override public void addError(AuditEvent event) {
            System.out.println(String.join("\t", "DIAGNOSTIC",
                    Path.of(event.getFileName()).getFileName().toString(),
                    Integer.toString(event.getLine()), Integer.toString(event.getColumn()),
                    event.getSeverityLevel().getName(), event.getSourceName(),
                    event.getViolation().getKey(), event.getMessage()));
        }
        @Override public void addException(AuditEvent event, Throwable error) {
            throw new AssertionError(error);
        }
    }

    public static void main(String[] args) throws Exception {
        // args: configuration.xml cache.properties-or-'-' source.java ...
        final List<File> files = new ArrayList<>();
        for (int index = 2; index < args.length; index++) {
            final Path file = Path.of(args[index]).toAbsolutePath();
            files.add(file.toFile());
            final List<String> lines = Files.readAllLines(file);
            for (int line = 0; line < lines.size(); line++) {
                if (lines.get(line).contains("SUPPRESS CHECKSTYLE")) {
                    DECLARED.add(new Marker(file.toString(), line + 1));
                }
            }
        }
        System.out.println("ENGINE\t" + TreeWalker.class.getProtectionDomain()
                .getCodeSource().getLocation());
        final Checker checker = new Checker();
        checker.setModuleClassLoader(CacheRepro.class.getClassLoader());
        checker.configure(ConfigurationLoader.loadConfiguration(args[0],
                new PropertiesExpander(new Properties())));
        checker.addListener(new Listener());
        final int errors;
        try {
            errors = checker.process(files);
        }
        finally {
            checker.destroy();
        }
        System.out.println("ERROR_COUNT\t" + errors);
        if (!"-".equals(args[1])) {
            final Properties cache = new Properties();
            try (var input = Files.newInputStream(Path.of(args[1]))) {
                cache.load(input);
            }
            for (File file : files) {
                System.out.println("CACHED\t" + file.getName() + "\t"
                        + cache.containsKey(file.getAbsolutePath()));
            }
        }
    }
}
