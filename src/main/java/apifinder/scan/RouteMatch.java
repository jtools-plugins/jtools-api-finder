package apifinder.scan;

import com.intellij.openapi.vfs.VirtualFile;

public final class RouteMatch {
    public final String language;
    public final String httpMethod;
    public final String path;
    public final VirtualFile file;
    public final String relativePath;
    public final int line;
    public final String function;

    public int previewStart;
    public int previewEnd;
    public String body;
    public String contextPath = "";
    public String origin = "";
    public boolean originIsJar;
    public java.util.List<String> group = new java.util.ArrayList<>();
    public java.util.List<Boolean> groupModule = new java.util.ArrayList<>();
    public String className = "";
    public String classSummary = "";
    public boolean classIsInterface;
    public String methodSummary = "";

    public RouteMatch(String language, String httpMethod, String path,
                      VirtualFile file, String relativePath, int line, String function) {
        this.language = language;
        this.httpMethod = httpMethod;
        this.path = path;
        this.file = file;
        this.relativePath = relativePath;
        this.line = line;
        this.function = function;
    }

    public String location() {
        return relativePath + ":" + line;
    }
}