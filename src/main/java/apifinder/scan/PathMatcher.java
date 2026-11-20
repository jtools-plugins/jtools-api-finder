package apifinder.scan;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PathMatcher {

    private static final Pattern METHOD_PREFIX =
            Pattern.compile("^(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS|ANY)\\s+(.+)$", Pattern.CASE_INSENSITIVE);

    private PathMatcher() {
    }

    public static String[] parseMethodPrefix(String input) {
        String s = input == null ? "" : input.trim();
        Matcher m = METHOD_PREFIX.matcher(s);
        if (m.matches()) {
            return new String[]{m.group(1).toUpperCase(Locale.ROOT), m.group(2).trim()};
        }
        return new String[]{null, s};
    }

    public static List<String> tokenize(String path) {
        List<String> tokens = new ArrayList<>();
        if (path == null) {
            return tokens;
        }
        String s = path.trim();
        int cut = s.indexOf('?');
        if (cut >= 0) {
            s = s.substring(0, cut);
        }
        cut = s.indexOf('#');
        if (cut >= 0) {
            s = s.substring(0, cut);
        }
        for (String seg : s.split("/")) {
            if (seg.isEmpty()) {
                continue;
            }
            if (seg.equals("**")) {
                tokens.add("**");
            } else if (isParam(seg)) {
                tokens.add("*");
            } else {
                tokens.add(seg);
            }
        }
        return tokens;
    }

    private static boolean isParam(String seg) {
        if (seg.equals("*")) {
            return true;
        }
        if (seg.startsWith("{") && seg.endsWith("}")) {
            return true;
        }
        if (seg.startsWith(":")) {
            return true;
        }
        return seg.startsWith("<") && seg.endsWith(">");
    }

    public static boolean match(String queryPath, String routePath) {
        return matchTokens(tokenize(queryPath), tokenize(routePath));
    }

    private static boolean matchTokens(List<String> q, List<String> r) {
        if (!r.isEmpty() && r.get(r.size() - 1).equals("**")) {
            return prefixMatch(q, r.subList(0, r.size() - 1));
        }
        if (!q.isEmpty() && q.get(q.size() - 1).equals("**")) {
            return prefixMatch(r, q.subList(0, q.size() - 1));
        }
        if (q.size() != r.size()) {
            return false;
        }
        for (int i = 0; i < q.size(); i++) {
            if (!segMatch(q.get(i), r.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean prefixMatch(List<String> full, List<String> prefix) {
        if (full.size() < prefix.size()) {
            return false;
        }
        for (int i = 0; i < prefix.size(); i++) {
            if (!segMatch(full.get(i), prefix.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean segMatch(String a, String b) {
        return a.equals(b) || a.equals("*") || b.equals("*");
    }

    public static String joinPath(String base, String sub) {
        String b = base == null ? "" : base.trim();
        String s = sub == null ? "" : sub.trim();
        if (b.isEmpty()) {
            return s.isEmpty() ? "/" : (s.startsWith("/") ? s : "/" + s);
        }
        if (s.isEmpty()) {
            return b.startsWith("/") ? b : "/" + b;
        }
        if (b.endsWith("/") && s.startsWith("/")) {
            return b + s.substring(1);
        }
        if (!b.endsWith("/") && !s.startsWith("/")) {
            return b + "/" + s;
        }
        return b + s;
    }
}