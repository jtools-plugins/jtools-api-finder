package apifinder.scan;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiArrayInitializerMemberValue;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.AnnotatedElementsSearch;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class JavaRouteScanner {

    private static final String[] CLASS_MARKERS = {
            "org.springframework.web.bind.annotation.RestController",
            "org.springframework.web.bind.annotation.Controller",
            "org.springframework.web.bind.annotation.RequestMapping",
            "io.micronaut.http.annotation.Controller",
            "javax.ws.rs.Path",
            "jakarta.ws.rs.Path"};
    private static final String[] METHOD_MARKERS = {
            "org.springframework.web.bind.annotation.GetMapping",
            "org.springframework.web.bind.annotation.PostMapping",
            "org.springframework.web.bind.annotation.PutMapping",
            "org.springframework.web.bind.annotation.DeleteMapping",
            "org.springframework.web.bind.annotation.PatchMapping",
            "org.springframework.web.bind.annotation.RequestMapping",
            "io.micronaut.http.annotation.Get",
            "io.micronaut.http.annotation.Post",
            "io.micronaut.http.annotation.Put",
            "io.micronaut.http.annotation.Delete",
            "io.micronaut.http.annotation.Patch",
            "io.micronaut.http.annotation.Head",
            "io.micronaut.http.annotation.Options"};

    private JavaRouteScanner() {
    }

    public static List<RouteMatch> scan(Project project, ProgressIndicator indicator) {
        return ApplicationManager.getApplication().runReadAction((Computable<List<RouteMatch>>) () -> {
            List<RouteMatch> out = new ArrayList<>();
            VirtualFile baseDir = project.getBasePath() == null ? null
                    : LocalFileSystem.getInstance().findFileByPath(project.getBasePath());
            PsiManager psiManager = PsiManager.getInstance(project);
            List<VirtualFile> files = SourceFileWalker.collect(project, indicator, "java");
            Map<String, PsiClass> localClasses = new HashMap<>();
            List<PsiJavaFile> javaFiles = new ArrayList<>();
            for (VirtualFile vf : files) {
                if (indicator != null && indicator.isCanceled()) {
                    break;
                }
                PsiFile psiFile = psiManager.findFile(vf);
                if (!(psiFile instanceof PsiJavaFile)) {
                    continue;
                }
                PsiJavaFile javaFile = (PsiJavaFile) psiFile;
                javaFiles.add(javaFile);
                for (PsiClass cls : javaFile.getClasses()) {
                    if (cls.getName() != null) {
                        localClasses.putIfAbsent(cls.getName(), cls);
                    }
                    if (cls.getQualifiedName() != null) {
                        localClasses.putIfAbsent(cls.getQualifiedName(), cls);
                    }
                }
            }
            MappingAnnotations helper = new MappingAnnotations(project, localClasses);
            for (PsiJavaFile javaFile : javaFiles) {
                if (indicator != null && indicator.isCanceled()) {
                    break;
                }
                VirtualFile vf = javaFile.getVirtualFile();
                String rel = relative(baseDir, vf);
                Document doc = PsiDocumentManager.getInstance(project).getDocument(javaFile);
                String origin = sourceOrigin(baseDir, rel);
                int before = out.size();
                for (PsiClass cls : javaFile.getClasses()) {
                    processClass(cls, "", vf, rel, doc, out, indicator, helper, origin, false);
                }
                stampGroup(out, before, groupPath(baseDir, rel));
            }
            scanLibraries(project, baseDir, out, indicator, helper);
            return out;
        });
    }

    private static void processClass(PsiClass cls, String parentPath, VirtualFile vf, String rel,
                                     Document doc, List<RouteMatch> out, ProgressIndicator indicator,
                                     MappingAnnotations helper, String origin, boolean originIsJar) {
        if (indicator != null && indicator.isCanceled()) {
            return;
        }
        String base = PathMatcher.joinPath(parentPath, classBasePath(cls, helper));
        String className = cls.getQualifiedName() == null ? cls.getName() : cls.getQualifiedName();
        String summary = classSummary(cls);
        int before = out.size();
        for (PsiMethod method : cls.getMethods()) {
            if (method.isConstructor()) {
                continue;
            }
            collectMethodRoutes(method, base, vf, rel, doc, out, helper, origin, originIsJar, className, summary);
        }
        for (int i = before; i < out.size(); i++) {
            out.get(i).classIsInterface = cls.isInterface();
        }
        for (PsiClass inner : cls.getInnerClasses()) {
            processClass(inner, base, vf, rel, doc, out, indicator, helper, origin, originIsJar);
        }
    }

    private static String classBasePath(PsiClass cls, MappingAnnotations helper) {
        for (PsiAnnotation a : cls.getAnnotations()) {
            if (isMicronaut(a) && MappingAnnotations.shortName(a).equals("Controller")) {
                List<String> paths = new ArrayList<>();
                collectStrings(a.findDeclaredAttributeValue("value"), paths);
                if (paths.isEmpty()) {
                    collectStrings(a.findDeclaredAttributeValue("path"), paths);
                }
                if (!paths.isEmpty()) {
                    return paths.get(0);
                }
            }
        }
        for (MappingAnnotations.Mapping mp : helper.effective(cls.getAnnotations())) {
            if (mp.name.equals("RequestMapping") || mp.name.equals("Path")
                    || (mp.name.equals("Controller") && isMicronaut(mp.source))) {
                List<String> paths = new ArrayList<>();
                collectStrings(mp.source.findDeclaredAttributeValue("value"), paths);
                if (paths.isEmpty()) {
                    collectStrings(mp.source.findDeclaredAttributeValue("path"), paths);
                }
                if (!paths.isEmpty()) {
                    return paths.get(0);
                }
            }
        }
        return "";
    }

    private static void collectMethodRoutes(PsiMethod method, String base, VirtualFile vf, String rel,
                                            Document doc, List<RouteMatch> out, MappingAnnotations helper,
                                            String origin, boolean originIsJar, String className, String summary) {
        String fn = (method.getContainingClass() == null ? "" : method.getContainingClass().getName() + ".")
                + method.getName();
        String mSummary = swaggerSummary(method, false);
        if (mSummary.isEmpty()) {
            mSummary = docSummary(method.getDocComment());
        }
        int line = lineOf(doc, method.getTextOffset());
        int start = 0;
        int end = 0;
        com.intellij.openapi.util.TextRange range = method.getTextRange();
        if (range != null) {
            start = range.getStartOffset();
            end = range.getEndOffset();
            com.intellij.psi.javadoc.PsiDocComment docComment = method.getDocComment();
            if (docComment != null && docComment.getTextRange() != null) {
                start = docComment.getTextRange().getStartOffset();
            }
        }

        boolean jaxrs = false;
        List<String> jaxrsMethods = new ArrayList<>();
        String jaxrsPath = "";
        for (MappingAnnotations.Mapping mp : helper.effective(method.getAnnotations())) {
            if (MappingAnnotations.SPRING_MAPPINGS.contains(mp.name)
                    || MappingAnnotations.MICRONAUT_METHODS.contains(mp.name)) {
                List<String> paths = new ArrayList<>();
                collectStrings(mp.source.findDeclaredAttributeValue("value"), paths);
                if (paths.isEmpty()) {
                    collectStrings(mp.source.findDeclaredAttributeValue("path"), paths);
                }
                if (paths.isEmpty()) {
                    paths.add("");
                }
                for (String httpMethod : springHttpMethods(mp.name, mp.source)) {
                    for (String p : paths) {
                        emit(out, httpMethod, PathMatcher.joinPath(base, p), vf, rel, line, fn, doc, start, end, method,
                                origin, originIsJar, className, summary, mSummary);
                    }
                }
            } else if (mp.name.equals("Path")) {
                jaxrs = true;
                List<String> paths = new ArrayList<>();
                collectStrings(mp.source.findDeclaredAttributeValue("value"), paths);
                if (!paths.isEmpty()) {
                    jaxrsPath = paths.get(0);
                }
            } else if (MappingAnnotations.JAXRS_METHODS.contains(mp.name)) {
                jaxrs = true;
                jaxrsMethods.add(mp.name);
            }
        }
        if (jaxrs) {
            if (jaxrsMethods.isEmpty()) {
                jaxrsMethods.add("ANY");
            }
            for (String httpMethod : jaxrsMethods) {
                emit(out, httpMethod, PathMatcher.joinPath(base, jaxrsPath), vf, rel, line, fn, doc, start, end, method,
                        origin, originIsJar, className, summary, mSummary);
            }
        }
    }

    private static void emit(List<RouteMatch> out, String httpMethod, String fullPath, VirtualFile vf,
                             String rel, int line, String fn, Document doc, int start, int end, PsiMethod method,
                             String origin, boolean originIsJar, String className, String summary,
                             String methodSummary) {
        RouteMatch rm = new RouteMatch("Java", httpMethod, fullPath, vf, rel, line, fn);
        rm.previewStart = start;
        rm.previewEnd = end;
        rm.origin = origin;
        rm.originIsJar = originIsJar;
        rm.className = className;
        rm.classSummary = summary;
        rm.methodSummary = methodSummary;
        if (doc == null) {
            try {
                rm.body = method.getText();
            } catch (Exception e) {
                rm.body = null;
            }
        }
        out.add(rm);
    }

    private static List<String> springHttpMethods(String name, PsiAnnotation ann) {
        switch (name) {
            case "GetMapping":
            case "Get":
                return Arrays.asList("GET");
            case "PostMapping":
            case "Post":
                return Arrays.asList("POST");
            case "PutMapping":
            case "Put":
                return Arrays.asList("PUT");
            case "DeleteMapping":
            case "Delete":
                return Arrays.asList("DELETE");
            case "PatchMapping":
            case "Patch":
                return Arrays.asList("PATCH");
            case "Head":
                return Arrays.asList("HEAD");
            case "Options":
                return Arrays.asList("OPTIONS");
            case "Trace":
                return Arrays.asList("TRACE");
            default:
                List<String> methods = new ArrayList<>();
                collectMethodRefs(ann.findDeclaredAttributeValue("method"), methods);
                if (methods.isEmpty()) {
                    methods.add("ANY");
                }
                return methods;
        }
    }

    private static void collectMethodRefs(PsiAnnotationMemberValue value, List<String> out) {
        if (value == null) {
            return;
        }
        if (value instanceof PsiArrayInitializerMemberValue) {
            for (PsiAnnotationMemberValue init : ((PsiArrayInitializerMemberValue) value).getInitializers()) {
                collectMethodRefs(init, out);
            }
            return;
        }
        String text = value.getText();
        int idx = text.lastIndexOf('.');
        String name = idx >= 0 ? text.substring(idx + 1) : text;
        out.add(name.toUpperCase(Locale.ROOT));
    }

    private static void collectStrings(PsiAnnotationMemberValue value, List<String> out) {
        if (value == null) {
            return;
        }
        if (value instanceof PsiArrayInitializerMemberValue) {
            for (PsiAnnotationMemberValue init : ((PsiArrayInitializerMemberValue) value).getInitializers()) {
                collectStrings(init, out);
            }
            return;
        }
        if (value instanceof PsiLiteralExpression) {
            Object literal = ((PsiLiteralExpression) value).getValue();
            if (literal instanceof String) {
                out.add((String) literal);
            }
        } else if (value instanceof PsiReferenceExpression) {
            com.intellij.psi.PsiElement resolved = ((PsiReferenceExpression) value).resolve();
            if (resolved instanceof com.intellij.psi.PsiField) {
                com.intellij.psi.PsiExpression init = ((com.intellij.psi.PsiField) resolved).getInitializer();
                if (init instanceof PsiLiteralExpression) {
                    Object literal = ((PsiLiteralExpression) init).getValue();
                    if (literal instanceof String) {
                        out.add((String) literal);
                    }
                }
            }
        }
    }

    private static void scanLibraries(Project project, VirtualFile baseDir, List<RouteMatch> out,
                                      ProgressIndicator indicator, MappingAnnotations helper) {
        JavaPsiFacade facade = JavaPsiFacade.getInstance(project);
        GlobalSearchScope scope = GlobalSearchScope.allScope(project);
        Set<PsiClass> controllers = Collections.newSetFromMap(new IdentityHashMap<>());
        for (String name : CLASS_MARKERS) {
            PsiClass ann = facade.findClass(name, scope);
            if (ann == null) {
                continue;
            }
            for (PsiClass c : AnnotatedElementsSearch.searchPsiClasses(ann, scope)) {
                if (indicator != null && indicator.isCanceled()) {
                    return;
                }
                if (isLibrary(c, baseDir)) {
                    controllers.add(c);
                }
            }
        }
        for (String name : METHOD_MARKERS) {
            PsiClass ann = facade.findClass(name, scope);
            if (ann == null) {
                continue;
            }
            for (PsiMethod m : AnnotatedElementsSearch.searchPsiMethods(ann, scope)) {
                if (indicator != null && indicator.isCanceled()) {
                    return;
                }
                PsiClass c = m.getContainingClass();
                if (c != null && isLibrary(c, baseDir)) {
                    controllers.add(c);
                }
            }
        }
        PsiDocumentManager docManager = PsiDocumentManager.getInstance(project);
        for (PsiClass cls : controllers) {
            if (indicator != null && indicator.isCanceled()) {
                return;
            }
            PsiFile file = cls.getContainingFile();
            VirtualFile vf = file == null ? null : file.getVirtualFile();
            if (vf == null) {
                continue;
            }
            String rel = (cls.getQualifiedName() == null ? cls.getName() : cls.getQualifiedName()) + " [jar]";
            Document doc = docManager.getDocument(file);
            int before = out.size();
            processClass(cls, "", vf, rel, doc, out, indicator, helper, jarOrigin(vf), true);
            List<String> g = Collections.singletonList(jarOrigin(vf));
            List<Boolean> f = Collections.singletonList(Boolean.FALSE);
            for (int i = before; i < out.size(); i++) {
                out.get(i).group = g;
                out.get(i).groupModule = f;
            }
        }
    }

    private static boolean isLibrary(PsiClass cls, VirtualFile baseDir) {
        PsiFile file = cls.getContainingFile();
        VirtualFile vf = file == null ? null : file.getVirtualFile();
        if (vf == null) {
            return false;
        }
        return baseDir == null || !VfsUtilCore.isAncestor(baseDir, vf, false);
    }

    private static int lineOf(Document doc, int offset) {
        if (doc == null || offset < 0 || offset > doc.getTextLength()) {
            return 1;
        }
        return doc.getLineNumber(offset) + 1;
    }

    private static String relative(VirtualFile baseDir, VirtualFile vf) {
        String rel = baseDir == null ? null : VfsUtilCore.getRelativePath(vf, baseDir, '/');
        return rel == null ? vf.getName() : rel;
    }

    static String sourceOrigin(VirtualFile baseDir, String rel) {
        String baseName = baseDir == null ? "project" : baseDir.getName();
        if (rel == null || rel.isEmpty()) {
            return baseName;
        }
        int slash = rel.indexOf('/');
        if (slash < 0) {
            return baseName;
        }
        String first = rel.substring(0, slash);
        if (first.equals("src") || first.equals("main") || first.equals("java")) {
            return baseName;
        }
        return first;
    }

    static java.util.List<VirtualFile> groupPath(VirtualFile baseDir, String rel) {
        java.util.List<VirtualFile> dirs = new ArrayList<>();
        if (baseDir == null || rel == null) {
            return dirs;
        }
        String[] segs = rel.split("/");
        VirtualFile cur = baseDir;
        for (int i = 0; i < segs.length - 1; i++) {
            String seg = segs[i];
            if (seg.isEmpty()) {
                continue;
            }
            if (isSourceDirName(seg)) {
                break;
            }
            VirtualFile child = cur.findChild(seg);
            if (child == null || !child.isDirectory()) {
                break;
            }
            dirs.add(child);
            cur = child;
        }
        return dirs;
    }

    static void stampGroup(List<RouteMatch> out, int from, java.util.List<VirtualFile> dirs) {
        List<String> labels = new ArrayList<>();
        List<Boolean> flags = new ArrayList<>();
        for (VirtualFile d : dirs) {
            labels.add(moduleLabel(d));
            flags.add(isLeafModule(d));
        }
        for (int i = from; i < out.size(); i++) {
            RouteMatch rm = out.get(i);
            rm.group = labels;
            rm.groupModule = flags;
            if (!labels.isEmpty()) {
                rm.origin = labels.get(0);
            }
        }
    }

    private static String moduleLabel(VirtualFile dir) {
        String label = dir.getName();
        String art = artifactId(dir);
        return art == null ? label : label + " [" + art + "]";
    }

    private static boolean isLeafModule(VirtualFile dir) {
        if (!hasBuildMarker(dir)) {
            return false;
        }
        VirtualFile[] children = dir.getChildren();
        if (children != null) {
            for (VirtualFile c : children) {
                if (c.isDirectory() && hasBuildMarker(c)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean isSourceDirName(String name) {
        return name.equals("src") || name.equals("main") || name.equals("java") || name.equals("kotlin");
    }

    private static boolean hasBuildMarker(VirtualFile dir) {
        return dir.findChild("pom.xml") != null
                || dir.findChild("build.gradle") != null
                || dir.findChild("build.gradle.kts") != null
                || dir.findChild("go.mod") != null
                || dir.findChild("Cargo.toml") != null
                || dir.findChild("composer.json") != null
                || dir.findChild("pyproject.toml") != null
                || dir.findChild("setup.py") != null;
    }

    private static String artifactId(VirtualFile moduleDir) {
        VirtualFile pom = moduleDir.findChild("pom.xml");
        if (pom == null) {
            return null;
        }
        try {
            String text = VfsUtilCore.loadText(pom);
            text = text.replaceAll("(?s)<parent>.*?</parent>", "");
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("<artifactId>\\s*([^<]+?)\\s*</artifactId>").matcher(text);
            if (m.find()) {
                return m.group(1);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    static String jarOrigin(VirtualFile vf) {
        String path = vf.getPath();
        int bang = path.indexOf("!/");
        String jarPath = bang >= 0 ? path.substring(0, bang) : path;
        if (jarPath.startsWith("jar://")) {
            jarPath = jarPath.substring(6);
        }
        int slash = Math.max(jarPath.lastIndexOf('/'), jarPath.lastIndexOf('\\'));
        String name = slash >= 0 ? jarPath.substring(slash + 1) : jarPath;
        return name.isEmpty() ? vf.getName() : name;
    }

    private static String classSummary(PsiClass cls) {
        String s = swaggerSummary(cls, true);
        return s.isEmpty() ? docSummary(cls.getDocComment()) : s;
    }

    private static String swaggerSummary(com.intellij.psi.PsiModifierListOwner owner, boolean isClass) {
        for (PsiAnnotation a : owner.getAnnotations()) {
            String qn = a.getQualifiedName();
            if (qn == null || !qn.startsWith("io.swagger")) {
                continue;
            }
            String s = MappingAnnotations.shortName(a);
            List<String> vals = new ArrayList<>();
            if (isClass) {
                if (s.equals("Tag")) {
                    collectStrings(a.findDeclaredAttributeValue("description"), vals);
                    if (vals.isEmpty()) {
                        collectStrings(a.findDeclaredAttributeValue("name"), vals);
                    }
                } else if (s.equals("Api")) {
                    collectStrings(a.findDeclaredAttributeValue("value"), vals);
                    if (vals.isEmpty()) {
                        collectStrings(a.findDeclaredAttributeValue("tags"), vals);
                    }
                }
            } else {
                if (s.equals("Operation")) {
                    collectStrings(a.findDeclaredAttributeValue("summary"), vals);
                    if (vals.isEmpty()) {
                        collectStrings(a.findDeclaredAttributeValue("description"), vals);
                    }
                } else if (s.equals("ApiOperation")) {
                    collectStrings(a.findDeclaredAttributeValue("value"), vals);
                    if (vals.isEmpty()) {
                        collectStrings(a.findDeclaredAttributeValue("notes"), vals);
                    }
                }
            }
            if (!vals.isEmpty() && !vals.get(0).trim().isEmpty()) {
                return vals.get(0).trim();
            }
        }
        return "";
    }

    private static String docSummary(com.intellij.psi.javadoc.PsiDocComment doc) {
        if (doc == null) {
            return "";
        }
        for (String line : doc.getText().split("\r?\n")) {
            String s = line.trim();
            s = s.replaceAll("^/\\*\\*+", "").replaceAll("\\*+/$", "").replaceAll("^\\*+", "").trim();
            if (s.isEmpty() || s.startsWith("@")) {
                continue;
            }
            return s;
        }
        return "";
    }

    private static boolean isMicronaut(PsiAnnotation ann) {
        String qn = ann.getQualifiedName();
        return qn != null && qn.startsWith("io.micronaut");
    }
}