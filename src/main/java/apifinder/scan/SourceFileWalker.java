package apifinder.scan;

import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class SourceFileWalker {

    private static final Set<String> EXCLUDED_DIRS = new HashSet<>(Arrays.asList(
            ".git", ".idea", ".gradle", ".mvn", ".settings", ".vscode",
            "node_modules", "target", "build", "out", "dist", "logs",
            "venv", ".venv", "vendor", "__pycache__", ".tox",
            ".awake-claw", ".tmp_plugin_shots"));

    private SourceFileWalker() {
    }

    public static List<VirtualFile> collect(Project project, ProgressIndicator indicator, String... extensions) {
        List<VirtualFile> out = new ArrayList<>();
        String base = project.getBasePath();
        if (base == null) {
            return out;
        }
        VirtualFile root = LocalFileSystem.getInstance().findFileByPath(base);
        if (root == null) {
            return out;
        }
        Set<String> ext = new HashSet<>();
        for (String e : extensions) {
            ext.add(e.toLowerCase(Locale.ROOT));
        }
        walk(root, ext, out, indicator);
        return out;
    }

    private static void walk(VirtualFile dir, Set<String> ext, List<VirtualFile> out, ProgressIndicator indicator) {
        if (indicator != null && indicator.isCanceled()) {
            return;
        }
        VirtualFile[] children = dir.getChildren();
        if (children == null) {
            return;
        }
        for (VirtualFile child : children) {
            if (indicator != null && indicator.isCanceled()) {
                return;
            }
            if (child.isDirectory()) {
                if (!EXCLUDED_DIRS.contains(child.getName())) {
                    walk(child, ext, out, indicator);
                }
            } else {
                String name = child.getName();
                int dot = name.lastIndexOf('.');
                if (dot >= 0 && ext.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT))) {
                    out.add(child);
                }
            }
        }
    }
}