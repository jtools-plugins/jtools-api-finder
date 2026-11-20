package apifinder;

import apifinder.scan.RouteMatch;
import apifinder.scan.RouteScanner;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.project.Project;
import com.lhstack.tools.plugins.FunctionCalling;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class FindApiFunction implements FunctionCalling {

    private static final Gson GSON = new Gson();
    private final Project project;

    public FindApiFunction(Project project) {
        this.project = project;
    }

    @Override
    public String name() {
        return "find_api";
    }

    @Override
    public String description() {
        return "按接口地址查找当前项目 Java/Go/Rust 的路由处理函数；支持路径参数({id}/:id)、contextPath 前缀、HTTP 方法过滤；返回语言/方法/完整路径/文件位置/函数名";
    }

    @Override
    public String parameters() {
        return "{\"type\":\"object\",\"properties\":{"
                + "\"path\":{\"type\":\"string\",\"description\":\"接口地址，如 /api/user/{id}；可带 contextPath 前缀或方法前缀如 GET /api/user/list\"},"
                + "\"method\":{\"type\":\"string\",\"description\":\"HTTP 方法过滤：ANY/GET/POST/PUT/DELETE/PATCH/HEAD/OPTIONS，默认 ANY\"}"
                + "},\"required\":[\"path\"]}";
    }

    @Override
    public String call(String input) {
        try {
            JsonObject obj = JsonParser.parseString(input == null || input.isEmpty() ? "{}" : input).getAsJsonObject();
            String path = obj.has("path") && !obj.get("path").isJsonNull() ? obj.get("path").getAsString() : "";
            String method = obj.has("method") && !obj.get("method").isJsonNull() ? obj.get("method").getAsString() : "ANY";
            List<RouteMatch> found = RouteScanner.find(project, path, method, null);
            List<Map<String, Object>> matches = new ArrayList<>();
            for (RouteMatch rm : found) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("language", rm.language);
                m.put("method", rm.httpMethod);
                m.put("path", rm.contextPath + rm.path);
                m.put("location", rm.location());
                m.put("function", rm.function);
                matches.add(m);
            }
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("count", matches.size());
            res.put("matches", matches);
            return GSON.toJson(res);
        } catch (Exception e) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", String.valueOf(e.getMessage()));
            return GSON.toJson(err);
        }
    }
}