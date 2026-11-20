package apifinder.scan;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TextRouteScanner {

    private static final Pattern GIN_ECHO = Pattern.compile(
            "\\.(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS|Any)\\s*\\(\\s*\"([^\"]*)\"\\s*,\\s*([^),\\s]+)");
    private static final Pattern CHI = Pattern.compile(
            "\\.(Get|Post|Put|Delete|Patch|Head|Options|HandleFunc|Handle)\\s*\\(\\s*\"([^\"]*)\"\\s*,\\s*([^),\\s]+)");
    private static final Pattern STDLIB = Pattern.compile(
            "\\bHandleFunc\\s*\\(\\s*\"([^\"]*)\"\\s*,\\s*([^),\\s]+)");

    private static final Pattern RUST_ATTR = Pattern.compile(
            "#\\[\\s*(get|post|put|delete|patch|head|options)\\s*\\(\\s*\"([^\"]*)\"");
    private static final Pattern RUST_AXUM = Pattern.compile(
            "\\.route\\s*\\(\\s*\"([^\"]*)\"\\s*,\\s*(get|post|put|delete|patch|head|options|any)\\s*\\(\\s*([\\w:]+)");
    private static final Pattern RUST_RESOURCE = Pattern.compile(
            "web::resource\\s*\\(\\s*\"([^\"]*)\"\\s*\\)");
    private static final Pattern RUST_ACTIX_ROUTE = Pattern.compile(
            "\\.route\\s*\\(\\s*web::(get|post|put|delete|patch|head|options)\\s*\\(\\s*\\)\\s*\\.to\\s*\\(\\s*([\\w:]+)");
    private static final Pattern RUST_FN = Pattern.compile("\\bfn\\s+(\\w+)");

    private static final Pattern PY_ROUTE = Pattern.compile(
            "@\\s*(?:[\\w.]+\\.)?route\\s*\\(\\s*[\"']([^\"']+)[\"']([^)]*)\\)");
    private static final Pattern PY_SHORT = Pattern.compile(
            "@\\s*(?:[\\w.]+\\.)?(get|post|put|delete|patch|head|options)\\s*\\(\\s*[\"']([^\"']+)[\"']");
    private static final Pattern PY_DJANGO = Pattern.compile(
            "\\b(?:re_)?path\\s*\\(\\s*r?[\"']([^\"']+)[\"']\\s*,\\s*([\\w.]+)");
    private static final Pattern PY_METHODS = Pattern.compile(
            "methods\\s*=\\s*\\[([^\\]]*)\\]");
    private static final Pattern PY_DEF = Pattern.compile("\\bdef\\s+(\\w+)");

    private static final Pattern PHP_LARAVEL = Pattern.compile(
            "Route::(get|post|put|delete|patch|options|any|match)\\s*\\(\\s*[\"']([^\"']+)[\"']\\s*,\\s*([^),]+)");
    private static final Pattern PHP_SLIM = Pattern.compile(
            "\\$\\w+->(get|post|put|delete|patch|options|any)\\s*\\(\\s*[\"']([^\"']+)[\"']\\s*,\\s*([^),]+)");
    private static final Pattern PHP_SYMFONY = Pattern.compile(
            "#\\[Route\\s*\\(\\s*(?:path\\s*:\\s*)?[\"']([^\"']+)[\"']([^)]*)\\]");
    private static final Pattern PHP_METHODS = Pattern.compile(
            "methods\\s*:\\s*\\[([^\\]]*)\\]");
    private static final Pattern PHP_FN = Pattern.compile("\\bfunction\\s+(\\w+)");

    private TextRouteScanner() {
    }

    public static List<RouteMatch> scan(Project project, ProgressIndicator indicator) {
        return ApplicationManager.getApplication().runReadAction((Computable<List<RouteMatch>>) () -> {
            List<RouteMatch> out = new ArrayList<>();
            VirtualFile baseDir = project.getBasePath() == null ? null
                    : LocalFileSystem.getInstance().findFileByPath(project.getBasePath());
            Set<String> seen = new HashSet<>();
            for (VirtualFile vf : SourceFileWalker.collect(project, indicator, "go", "rs", "py", "php")) {
                if (indicator != null && indicator.isCanceled()) {
                    break;
                }
                String text;
                try {
                    text = VfsUtilCore.loadText(vf);
                } catch (Exception e) {
                    continue;
                }
                String rel = relative(baseDir, vf);
                String origin = JavaRouteScanner.sourceOrigin(baseDir, rel);
                String[] lines = text.split("\r?\n", -1);
                String name = vf.getName();
                int before = out.size();
                if (name.endsWith(".go")) {
                    scanGo(lines, vf, rel, origin, out, seen);
                } else if (name.endsWith(".rs")) {
                    scanRust(lines, vf, rel, origin, out, seen);
                } else if (name.endsWith(".py")) {
                    scanPython(lines, vf, rel, origin, out, seen);
                } else {
                    scanPhp(lines, vf, rel, origin, out, seen);
                }
                JavaRouteScanner.stampGroup(out, before, JavaRouteScanner.groupPath(baseDir, rel));
            }
            return out;
        });
    }

    private static void scanGo(String[] lines, VirtualFile vf, String rel, String origin,
                               List<RouteMatch> out, Set<String> seen) {
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            Matcher m = GIN_ECHO.matcher(line);
            while (m.find()) {
                add(out, seen, "Go", method(m.group(1)), m.group(2), vf, rel, origin, i + 1, m.group(3), lines);
            }
            m = CHI.matcher(line);
            while (m.find()) {
                add(out, seen, "Go", chiMethod(m.group(1)), m.group(2), vf, rel, origin, i + 1, m.group(3), lines);
            }
            m = STDLIB.matcher(line);
            while (m.find()) {
                add(out, seen, "Go", "ANY", m.group(1), vf, rel, origin, i + 1, m.group(2), lines);
            }
        }
    }

    private static void scanRust(String[] lines, VirtualFile vf, String rel, String origin,
                                 List<RouteMatch> out, Set<String> seen) {
        String pendingResource = null;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            Matcher m = RUST_ATTR.matcher(line);
            while (m.find()) {
                add(out, seen, "Rust", method(m.group(1)), m.group(2), vf, rel, origin, i + 1, findFn(lines, i), lines);
            }
            m = RUST_AXUM.matcher(line);
            while (m.find()) {
                add(out, seen, "Rust", method(m.group(2)), m.group(1), vf, rel, origin, i + 1, m.group(3), lines);
            }
            m = RUST_RESOURCE.matcher(line);
            if (m.find()) {
                pendingResource = m.group(1);
            }
            m = RUST_ACTIX_ROUTE.matcher(line);
            while (m.find()) {
                String path = pendingResource == null ? "" : pendingResource;
                add(out, seen, "Rust", method(m.group(1)), path, vf, rel, origin, i + 1, m.group(2), lines);
            }
        }
    }

    private static void scanPython(String[] lines, VirtualFile vf, String rel, String origin,
                                   List<RouteMatch> out, Set<String> seen) {
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            Matcher m = PY_SHORT.matcher(line);
            while (m.find()) {
                add(out, seen, "Python", method(m.group(1)), m.group(2), vf, rel, origin, i + 1,
                        findNext(lines, i, PY_DEF), lines);
            }
            m = PY_ROUTE.matcher(line);
            while (m.find()) {
                for (String http : methodsFrom(m.group(2), PY_METHODS, "GET")) {
                    add(out, seen, "Python", http, m.group(1), vf, rel, origin, i + 1,
                            findNext(lines, i, PY_DEF), lines);
                }
            }
            m = PY_DJANGO.matcher(line);
            while (m.find()) {
                add(out, seen, "Python", "ANY", m.group(1), vf, rel, origin, i + 1, m.group(2), lines);
            }
        }
    }

    private static void scanPhp(String[] lines, VirtualFile vf, String rel, String origin,
                                List<RouteMatch> out, Set<String> seen) {
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            Matcher m = PHP_LARAVEL.matcher(line);
            while (m.find()) {
                add(out, seen, "PHP", method(m.group(1)), m.group(2), vf, rel, origin, i + 1, m.group(3).trim(), lines);
            }
            m = PHP_SLIM.matcher(line);
            while (m.find()) {
                add(out, seen, "PHP", method(m.group(1)), m.group(2), vf, rel, origin, i + 1, m.group(3).trim(), lines);
            }
            m = PHP_SYMFONY.matcher(line);
            while (m.find()) {
                for (String http : methodsFrom(m.group(2), PHP_METHODS, "ANY")) {
                    add(out, seen, "PHP", http, m.group(1), vf, rel, origin, i + 1,
                            findNext(lines, i, PHP_FN), lines);
                }
            }
        }
    }

    private static List<String> methodsFrom(String rest, Pattern methodsPattern, String defaultMethod) {
        List<String> out = new ArrayList<>();
        Matcher m = methodsPattern.matcher(rest == null ? "" : rest);
        if (m.find()) {
            Matcher q = Pattern.compile("[\"'](\\w+)[\"']").matcher(m.group(1));
            while (q.find()) {
                out.add(method(q.group(1)));
            }
        }
        if (out.isEmpty()) {
            out.add(defaultMethod);
        }
        return out;
    }

    private static String findFn(String[] lines, int from) {
        return findNext(lines, from, RUST_FN);
    }

    private static String findNext(String[] lines, int from, Pattern decl) {
        int end = Math.min(from + 6, lines.length);
        for (int j = from; j < end; j++) {
            Matcher m = decl.matcher(lines[j]);
            if (m.find()) {
                return m.group(1);
            }
        }
        return "(fn)";
    }

    private static void add(List<RouteMatch> out, Set<String> seen, String language, String httpMethod,
                            String path, VirtualFile vf, String rel, String origin, int line, String function,
                            String[] lines) {
        String fn = function == null ? "" : function.trim();
        String key = rel + ":" + line + ":" + httpMethod + ":" + path;
        if (!seen.add(key)) {
            return;
        }
        RouteMatch rm = new RouteMatch(language, httpMethod, path, vf, rel, line, fn);
        rm.origin = origin;
        rm.originIsJar = false;
        rm.className = rel;
        rm.body = extractBlock(lines, fn, language, line);
        rm.methodSummary = firstComment(rm.body);
        out.add(rm);
    }

    private static String firstComment(String body) {
        if (body == null) {
            return "";
        }
        for (String line : body.split("\r?\n")) {
            String s = line.trim();
            if (s.startsWith("//") || s.startsWith("#") || s.startsWith("/*") || s.startsWith("*")) {
                s = s.replaceAll("^//+", "").replaceAll("^#+", "").replaceAll("^/\\*+", "")
                        .replaceAll("\\*+/$", "").replaceAll("^\\*+", "").trim();
                if (!s.isEmpty() && !s.startsWith("@")) {
                    return s;
                }
            } else if (!s.isEmpty()) {
                return "";
            }
        }
        return "";
    }

    private static String extractBlock(String[] lines, String fn, String language, int routeLine) {
        String name = fn;
        int dot = Math.max(name.lastIndexOf('.'), name.lastIndexOf("::"));
        if (dot >= 0) {
            name = name.substring(dot + (name.endsWith("::") ? 0 : 1));
            if (name.startsWith(":")) {
                name = name.substring(1);
            }
        }
        if (name.isEmpty() || name.equals("(fn)")) {
            return lines[Math.min(routeLine - 1, lines.length - 1)];
        }
        Pattern decl;
        switch (language) {
            case "Go":
                decl = Pattern.compile("\\bfunc\\b[^\\n]*\\b" + Pattern.quote(name) + "\\s*\\(");
                break;
            case "Python":
                decl = Pattern.compile("\\bdef\\s+" + Pattern.quote(name) + "\\s*\\(");
                break;
            case "PHP":
                decl = Pattern.compile("\\bfunction\\s+" + Pattern.quote(name) + "\\s*\\(");
                break;
            default:
                decl = Pattern.compile("\\bfn\\s+" + Pattern.quote(name) + "\\b");
                break;
        }
        int found = -1;
        for (int i = 0; i < lines.length; i++) {
            if (decl.matcher(lines[i]).find()) {
                found = i;
                break;
            }
        }
        if (found < 0) {
            return lines[Math.min(routeLine - 1, lines.length - 1)];
        }
        int start = found;
        while (start > 0) {
            String prev = lines[start - 1].trim();
            if (prev.startsWith("//") || prev.startsWith("/*") || prev.startsWith("*")
                    || prev.startsWith("#[") || prev.startsWith("#") || prev.startsWith("@")) {
                start--;
            } else {
                break;
            }
        }
        if (language.equals("Python")) {
            return pythonBlock(lines, start, found);
        }
        int depth = 0;
        boolean opened = false;
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < lines.length; i++) {
            sb.append(lines[i]).append('\n');
            for (char c : lines[i].toCharArray()) {
                if (c == '{') {
                    depth++;
                    opened = true;
                } else if (c == '}') {
                    depth--;
                }
            }
            if (opened && depth <= 0) {
                break;
            }
        }
        return sb.toString();
    }

    private static String pythonBlock(String[] lines, int start, int defLine) {
        int baseIndent = indentOf(lines[defLine]);
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < lines.length; i++) {
            String line = lines[i];
            if (i > defLine && !line.trim().isEmpty() && indentOf(line) <= baseIndent) {
                break;
            }
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private static int indentOf(String line) {
        int n = 0;
        while (n < line.length() && (line.charAt(n) == ' ' || line.charAt(n) == '\t')) {
            n++;
        }
        return n;
    }

    private static String method(String raw) {
        return raw.equalsIgnoreCase("any") || raw.equalsIgnoreCase("match") ? "ANY" : raw.toUpperCase(Locale.ROOT);
    }

    private static String chiMethod(String raw) {
        if (raw.equals("HandleFunc") || raw.equals("Handle")) {
            return "ANY";
        }
        return raw.toUpperCase(Locale.ROOT);
    }

    private static String relative(VirtualFile baseDir, VirtualFile vf) {
        String rel = baseDir == null ? null : VfsUtilCore.getRelativePath(vf, baseDir, '/');
        return rel == null ? vf.getName() : rel;
    }
}