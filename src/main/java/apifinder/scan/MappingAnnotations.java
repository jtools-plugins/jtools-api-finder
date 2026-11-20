package apifinder.scan;

import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.search.GlobalSearchScope;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class MappingAnnotations {

    public static final Set<String> SPRING_MAPPINGS = new HashSet<>(Arrays.asList(
            "RequestMapping", "GetMapping", "PostMapping", "PutMapping", "DeleteMapping", "PatchMapping"));
    public static final Set<String> JAXRS_METHODS = new HashSet<>(Arrays.asList(
            "GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS"));
    public static final Set<String> MICRONAUT_METHODS = new HashSet<>(Arrays.asList(
            "Get", "Post", "Put", "Delete", "Patch", "Head", "Options", "Trace"));
    public static final Set<String> STEREOTYPES = new HashSet<>(Arrays.asList(
            "RestController", "Controller"));

    public static final class Mapping {
        public final PsiAnnotation source;
        public final String name;

        Mapping(PsiAnnotation source, String name) {
            this.source = source;
            this.name = name;
        }
    }

    private static final class Meta {
        boolean controller;
        final List<String> mappings = new ArrayList<>();
    }

    private final JavaPsiFacade facade;
    private final GlobalSearchScope scope;
    private final Map<String, PsiClass> localClasses;
    private final Map<String, Meta> cache = new HashMap<>();

    public MappingAnnotations(Project project, Map<String, PsiClass> localClasses) {
        this.facade = JavaPsiFacade.getInstance(project);
        this.scope = GlobalSearchScope.allScope(project);
        this.localClasses = localClasses;
    }

    public static String shortName(PsiAnnotation ann) {
        String qn = ann.getQualifiedName();
        if (qn == null) {
            return "";
        }
        int idx = qn.lastIndexOf('.');
        return idx >= 0 ? qn.substring(idx + 1) : qn;
    }

    public static boolean isMappingName(String name) {
        return SPRING_MAPPINGS.contains(name) || name.equals("Path")
                || JAXRS_METHODS.contains(name) || MICRONAUT_METHODS.contains(name);
    }

    private Meta meta(PsiAnnotation a) {
        String qualifiedName = a.getQualifiedName();
        Meta m = cache.get(qualifiedName);
        if (m != null) {
            return m;
        }
        m = new Meta();
        PsiClass type = resolveType(a);
        if (type != null) {
            for (PsiAnnotation ma : type.getAnnotations()) {
                String ms = shortName(ma);
                if (STEREOTYPES.contains(ms)) {
                    m.controller = true;
                }
                if (isMappingName(ms)) {
                    m.mappings.add(ms);
                }
            }
        }
        cache.put(qualifiedName, m);
        return m;
    }

    private PsiClass resolveType(PsiAnnotation a) {
        com.intellij.psi.PsiJavaCodeReferenceElement ref = a.getNameReferenceElement();
        if (ref != null) {
            com.intellij.psi.PsiElement resolved = ref.resolve();
            if (resolved instanceof PsiClass) {
                return (PsiClass) resolved;
            }
        }
        String qn = a.getQualifiedName();
        PsiClass local = localClasses.get(shortName(a));
        if (local == null && qn != null) {
            local = localClasses.get(qn);
        }
        if (local != null) {
            return local;
        }
        return qn == null ? null : facade.findClass(qn, scope);
    }

    public List<Mapping> effective(PsiAnnotation[] anns) {
        List<Mapping> out = new ArrayList<>();
        for (PsiAnnotation a : anns) {
            String s = shortName(a);
            if (isMappingName(s)) {
                out.add(new Mapping(a, s));
            } else {
                for (String n : meta(a).mappings) {
                    out.add(new Mapping(a, n));
                }
            }
        }
        return out;
    }

    public boolean isController(PsiAnnotation[] anns) {
        for (PsiAnnotation a : anns) {
            if (STEREOTYPES.contains(shortName(a))) {
                return true;
            }
            if (meta(a).controller) {
                return true;
            }
        }
        return false;
    }
}