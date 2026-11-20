package apifinder.scan;

import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ContextPathResolver {

    private static final Pattern PROPS = Pattern.compile(
            "(?:server\\.servlet\\.context-path|server\\.context-path)\\s*[=:]\\s*[\"']?([^\"'\\s#]+)");
    private static final Pattern YAML = Pattern.compile(
            "context-path\\s*:\\s*[\"']?([^\"'\\s#]+)");

    private ContextPathResolver() {
    }

    public static Set<String> resolve(Project project, ProgressIndicator indicator) {
        Set<String> prefixes = new LinkedHashSet<>();
        for (VirtualFile vf : SourceFileWalker.collect(project, indicator, "yml", "yaml", "properties")) {
            if (indicator != null && indicator.isCanceled()) {
                break;
            }
            String text;
            try {
                text = VfsUtilCore.loadText(vf);
            } catch (Exception e) {
                continue;
            }
            Matcher m = PROPS.matcher(text);
            while (m.find()) {
                add(prefixes, m.group(1));
            }
            m = YAML.matcher(text);
            while (m.find()) {
                add(prefixes, m.group(1));
            }
        }
        return prefixes;
    }

    private static void add(Set<String> prefixes, String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) {
            return;
        }
        if (!s.startsWith("/")) {
            s = "/" + s;
        }
        while (s.endsWith("/") && s.length() > 1) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.length() > 1) {
            prefixes.add(s);
        }
    }
}