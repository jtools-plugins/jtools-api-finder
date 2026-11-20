package apifinder.scan;

import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;

import java.util.ArrayList;
import java.util.List;

public final class RouteScanner {

    private RouteScanner() {
    }

    public static List<RouteMatch> find(Project project, String query, String methodFilter,
                                        ProgressIndicator indicator) {
        String[] parsed = PathMatcher.parseMethodPrefix(query);
        String path = parsed[1];
        String requested = parsed[0] != null ? parsed[0] : methodFilter;
        boolean anyMethod = requested == null || requested.equalsIgnoreCase("ANY");
        boolean matchAllPaths = path.trim().isEmpty();

        List<String> variantPaths = new ArrayList<>();
        List<String> variantCtx = new ArrayList<>();
        variantPaths.add(path);
        variantCtx.add("");
        if (!matchAllPaths) {
            for (String ctx : ContextPathResolver.resolve(project, indicator)) {
                if (path.equals(ctx) || path.startsWith(ctx + "/")) {
                    String stripped = path.substring(ctx.length());
                    variantPaths.add(stripped.isEmpty() ? "/" : stripped);
                    variantCtx.add(ctx);
                }
            }
        }

        List<RouteMatch> all = new ArrayList<>();
        if (indicator != null) {
            indicator.setText("Scanning Java (PSI)...");
        }
        all.addAll(JavaRouteScanner.scan(project, indicator));
        if (indicator != null) {
            indicator.setText("Scanning Go / Rust...");
        }
        all.addAll(TextRouteScanner.scan(project, indicator));

        List<RouteMatch> result = new ArrayList<>();
        for (RouteMatch rm : all) {
            if (indicator != null && indicator.isCanceled()) {
                break;
            }
            String ctx = matchAllPaths ? "" : matchedContext(variantPaths, variantCtx, rm.path);
            if (!matchAllPaths && ctx == null) {
                if (!fuzzyMatch(path, rm.path)) {
                    continue;
                }
                ctx = "";
            }
            if (!anyMethod && !requested.equalsIgnoreCase(rm.httpMethod) && !rm.httpMethod.equalsIgnoreCase("ANY")) {
                continue;
            }
            rm.contextPath = ctx;
            result.add(rm);
        }
        return result;
    }

    private static String matchedContext(List<String> paths, List<String> ctxs, String routePath) {
        for (int i = 0; i < paths.size(); i++) {
            if (PathMatcher.match(paths.get(i), routePath)) {
                return ctxs.get(i);
            }
        }
        return null;
    }

    private static boolean fuzzyMatch(String query, String routePath) {
        String q = query == null ? "" : query.trim().toLowerCase(java.util.Locale.ROOT);
        if (q.isEmpty()) {
            return false;
        }
        String r = routePath == null ? "" : routePath.toLowerCase(java.util.Locale.ROOT);
        return r.contains(q);
    }
}