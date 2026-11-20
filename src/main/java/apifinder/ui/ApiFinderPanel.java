package apifinder.ui;

import apifinder.scan.RouteMatch;
import apifinder.scan.RouteScanner;
import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.FoldRegion;
import com.intellij.openapi.editor.FoldingModel;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.fileTypes.PlainTextFileType;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.pom.Navigatable;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaCodeReferenceElement;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public final class ApiFinderPanel extends JPanel {

    private static final String[] METHODS = {"ANY", "GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS"};
    private static final String DEFAULT_HOST = "http://localhost:8080";

    private final Project project;
    private final JBTextField queryField = new JBTextField();
    private final JComboBox<String> methodBox = new JComboBox<>(METHODS);
    private final JButton searchButton = new JButton("搜索");
    private final JButton curlButton = new JButton("复制curl");
    private final RouteTree routeTree;
    private final JPanel previewHolder = new JPanel(new BorderLayout());
    private final JBLabel status = new JBLabel();
    private final List<RouteMatch> matches = new ArrayList<>();
    private final AtomicReference<ProgressIndicator> running = new AtomicReference<>();
    private Editor previewEditor;
    private volatile boolean closed = false;

    public ApiFinderPanel(Project project) {
        super(new BorderLayout());
        this.project = project;
        setBorder(JBUI.Borders.empty(10));
        routeTree = new RouteTree(project.getName());

        add(buildTop(), BorderLayout.NORTH);
        add(buildCenter(), BorderLayout.CENTER);
        add(buildBottom(), BorderLayout.SOUTH);

        queryField.addActionListener(e -> search());
        searchButton.addActionListener(e -> search());
        curlButton.addActionListener(e -> copyCurl());
        resetStatus();
    }

    private JComponent buildTop() {
        JPanel top = new JPanel(new BorderLayout(JBUI.scale(8), 0));
        top.setBorder(JBUI.Borders.emptyBottom(10));
        queryField.getEmptyText().setText("输入接口地址，如 /api/user/{id} 或 GET /api/user/list");
        top.add(queryField, BorderLayout.CENTER);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0));
        methodBox.setPreferredSize(JBUI.size(96, 30));
        searchButton.setPreferredSize(JBUI.size(70, 30));
        curlButton.setPreferredSize(JBUI.size(90, 30));
        right.add(new JBLabel("方法:"));
        right.add(methodBox);
        right.add(searchButton);
        right.add(curlButton);
        top.add(right, BorderLayout.EAST);
        return top;
    }

    private JComponent buildCenter() {
        routeTree.onSelect(this::showPreview, this::openSelected);
        JBScrollPane treeScroll = new JBScrollPane(routeTree.component());

        JPanel previewPanel = new JPanel(new BorderLayout());
        previewPanel.add(new JBLabel(" 预览（语法高亮 · 点击引用跳转）"), BorderLayout.NORTH);
        previewPanel.add(previewHolder, BorderLayout.CENTER);
        clearPreview();

        JBSplitter split = new JBSplitter(true, 0.55f);
        split.setFirstComponent(treeScroll);
        split.setSecondComponent(previewPanel);
        return split;
    }

    private JComponent buildBottom() {
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setBorder(JBUI.Borders.emptyTop(8));
        bottom.add(status, BorderLayout.CENTER);
        return bottom;
    }

    private void resetStatus() {
        status.setText("就绪 · 树形浏览当前项目源码与依赖 JAR（Java/Go/Rust/Python/PHP，含组合注解与 contextPath）");
    }

    private void search() {
        if (closed) {
            return;
        }
        String query = queryField.getText().trim();
        String method = (String) methodBox.getSelectedItem();
        searchButton.setEnabled(false);
        matches.clear();
        clearPreview();
        status.setText("正在扫描…");
        ProgressManager.getInstance().run(
                new Task.Backgroundable(project, "API Finder: 搜索接口", true) {
                    private List<RouteMatch> result;

                    @Override
                    public void run(ProgressIndicator indicator) {
                        running.set(indicator);
                        indicator.setIndeterminate(true);
                        result = RouteScanner.find(project, query, method, indicator);
                    }

                    @Override
                    public void onSuccess() {
                        running.set(null);
                        if (closed) {
                            return;
                        }
                        populate(result);
                        searchButton.setEnabled(true);
                    }

                    @Override
                    public void onCancel() {
                        running.set(null);
                        if (!closed) {
                            status.setText("已取消");
                            searchButton.setEnabled(true);
                        }
                    }

                    @Override
                    public void onThrowable(Throwable error) {
                        running.set(null);
                        if (!closed) {
                            status.setText("出错: " + error.getMessage());
                            searchButton.setEnabled(true);
                        }
                    }
                });
    }

    private void populate(List<RouteMatch> result) {
        matches.clear();
        if (result != null) {
            matches.addAll(result);
        }
        routeTree.populate(project.getName(), matches);
        int java = 0;
        int go = 0;
        int rust = 0;
        int python = 0;
        int php = 0;
        for (RouteMatch rm : matches) {
            switch (rm.language) {
                case "Go":
                    go++;
                    break;
                case "Rust":
                    rust++;
                    break;
                case "Python":
                    python++;
                    break;
                case "PHP":
                    php++;
                    break;
                default:
                    java++;
                    break;
            }
        }
        status.setText("共 " + matches.size() + " 条  ·  Java " + java + " · Go " + go + " · Rust " + rust
                + " · Python " + python + " · PHP " + php
                + "  —  单击预览 / 双击跳转 / 复制curl");
    }

    private void clearPreview() {
        releasePreviewEditor();
        previewHolder.removeAll();
        previewHolder.add(new JBLabel(" 单击接口叶节点预览对应函数"), BorderLayout.CENTER);
        previewHolder.revalidate();
        previewHolder.repaint();
    }

    private void releasePreviewEditor() {
        if (previewEditor != null) {
            EditorFactory.getInstance().releaseEditor(previewEditor);
            previewEditor = null;
        }
    }

    private void showPreview(RouteMatch rm) {
        if (rm == null) {
            return;
        }
        releasePreviewEditor();
        previewHolder.removeAll();
        Editor editor = null;
        com.intellij.openapi.editor.Document doc = null;
        com.intellij.openapi.fileTypes.FileType ft =
                rm.language.equals("Java") ? JavaFileType.INSTANCE : PlainTextFileType.INSTANCE;
        if (rm.file != null && rm.file.isValid()) {
            doc = FileDocumentManager.getInstance().getDocument(rm.file);
        }
        if (doc != null && rm.previewEnd > rm.previewStart) {
            editor = EditorFactory.getInstance().createEditor(doc, project, ft, true);
            foldToRange(editor, rm.previewStart, rm.previewEnd);
            editor.getCaretModel().moveToOffset(rm.previewStart);
            editor.getScrollingModel().scrollToCaret(ScrollType.CENTER);
            attachReferenceNavigation(editor);
        } else if (rm.body != null && !rm.body.isEmpty()) {
            com.intellij.openapi.editor.Document bodyDoc = EditorFactory.getInstance().createDocument(rm.body);
            editor = EditorFactory.getInstance().createEditor(bodyDoc, project, ft, true);
        }
        if (editor != null) {
            previewEditor = editor;
            EditorEx ex = (EditorEx) editor;
            ex.getSettings().setLineNumbersShown(true);
            ex.getSettings().setFoldingOutlineShown(true);
            ex.getSettings().setUseSoftWraps(false);
            previewHolder.add(editor.getComponent(), BorderLayout.CENTER);
        } else {
            previewHolder.add(new JBLabel(" (无预览)"), BorderLayout.CENTER);
        }
        previewHolder.revalidate();
        previewHolder.repaint();
    }

    private void foldToRange(Editor editor, int start, int end) {
        FoldingModel fm = editor.getFoldingModel();
        int len = editor.getDocument().getTextLength();
        fm.runBatchFoldingOperation(() -> {
            if (start > 0) {
                FoldRegion r = fm.addFoldRegion(0, start, " … ");
                if (r != null) {
                    r.setExpanded(false);
                }
            }
            if (end < len) {
                FoldRegion r = fm.addFoldRegion(end, len, " … ");
                if (r != null) {
                    r.setExpanded(false);
                }
            }
        });
    }

    private void attachReferenceNavigation(Editor editor) {
        editor.getContentComponent().addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (closed) {
                    return;
                }
                int offset = editor.logicalPositionToOffset(editor.xyToLogicalPosition(e.getPoint()));
                PsiFile psi = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument());
                if (psi == null) {
                    return;
                }
                PsiElement leaf = psi.findElementAt(offset);
                PsiJavaCodeReferenceElement ref = PsiTreeUtil.getParentOfType(leaf, PsiJavaCodeReferenceElement.class, false);
                if (ref == null) {
                    return;
                }
                PsiElement target = ref.resolve();
                if (target instanceof Navigatable && target != leaf) {
                    ((Navigatable) target).navigate(true);
                }
            }
        });
    }

    private void copyCurl() {
        RouteMatch rm = routeTree.selectedRoute();
        if (rm == null) {
            status.setText("先选择一个接口叶节点再复制 curl");
            return;
        }
        String curl = buildCurl(rm);
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(curl), null);
        status.setText("已复制 curl 到剪贴板");
    }

    private String buildCurl(RouteMatch rm) {
        String method = rm.httpMethod == null || rm.httpMethod.equals("ANY") ? "GET" : rm.httpMethod;
        String url = DEFAULT_HOST + rm.contextPath + rm.path;
        StringBuilder sb = new StringBuilder("curl -X ").append(method).append(" '").append(url).append("'");
        sb.append(" \\\n  -H 'Content-Type: application/json'");
        if (method.equals("POST") || method.equals("PUT") || method.equals("PATCH")) {
            sb.append(" \\\n  -d '{}'");
        }
        return sb.toString();
    }

    private void openSelected(RouteMatch rm) {
        if (rm == null) {
            return;
        }
        if (rm.file == null || !rm.file.isValid()) {
            status.setText("文件已失效: " + rm.relativePath);
            return;
        }
        try {
            new OpenFileDescriptor(project, rm.file, Math.max(0, rm.line - 1), 0).navigate(true);
        } catch (Exception ex) {
            status.setText("跳转失败: " + ex.getMessage());
        }
    }

    public void close() {
        closed = true;
        releasePreviewEditor();
        ProgressIndicator indicator = running.getAndSet(null);
        if (indicator != null) {
            indicator.cancel();
        }
    }

}