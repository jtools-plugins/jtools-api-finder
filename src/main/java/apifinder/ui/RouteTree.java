package apifinder.ui;

import apifinder.scan.RouteMatch;
import com.intellij.icons.AllIcons;
import com.intellij.ui.ColoredTreeCellRenderer;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.treeStructure.Tree;

import javax.swing.Icon;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import java.awt.Color;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public final class RouteTree {

    private final Tree tree = new Tree();
    private final DefaultTreeModel model;

    public RouteTree(String projectName) {
        model = new DefaultTreeModel(node(projectName, AllIcons.Nodes.ModuleGroup, null, ""));
        tree.setModel(model);
        tree.setRootVisible(true);
        tree.setShowsRootHandles(true);
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.setCellRenderer(new Renderer());
        tree.getEmptyText().setText("输入接口地址回车搜索；留空回车浏览全部接口");
    }

    public Tree component() {
        return tree;
    }

    public void onSelect(Consumer<RouteMatch> single, Consumer<RouteMatch> dbl) {
        tree.addTreeSelectionListener(e -> {
            RouteMatch rm = selectedRoute();
            if (rm != null) {
                single.accept(rm);
            }
        });
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    RouteMatch rm = selectedRoute();
                    if (rm != null) {
                        dbl.accept(rm);
                    }
                }
            }
        });
    }

    public RouteMatch selectedRoute() {
        TreePath path = tree.getSelectionPath();
        if (path == null) {
            return null;
        }
        Object uo = ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
        return uo instanceof Node ? ((Node) uo).route : null;
    }

    public void populate(String projectName, List<RouteMatch> matches) {
        DefaultMutableTreeNode root = node(projectName, AllIcons.Nodes.ModuleGroup, null, "");
        Map<DefaultMutableTreeNode, Map<String, DefaultMutableTreeNode>> children = new IdentityHashMap<>();
        for (RouteMatch rm : matches) {
            DefaultMutableTreeNode cursor = root;
            if (rm.originIsJar) {
                cursor = child(children, cursor, rm.origin, AllIcons.Nodes.JarDirectory);
            } else {
                for (int i = 0; i < rm.group.size(); i++) {
                    Icon icon = Boolean.TRUE.equals(rm.groupModule.get(i))
                            ? AllIcons.Nodes.Module : AllIcons.Nodes.ModuleGroup;
                    cursor = child(children, cursor, rm.group.get(i), icon);
                }
            }
            DefaultMutableTreeNode cn = child(children, cursor, rm.className,
                    rm.classIsInterface ? AllIcons.Nodes.Interface : AllIcons.Nodes.Class);
            ((Node) cn.getUserObject()).summary = rm.classSummary;
            cn.add(node(rm.httpMethod + "  " + rm.path, null, rm, ""));
        }
        countLeaves(root);
        model.setRoot(root);
        tree.expandPath(new TreePath(root.getPath()));
    }

    private static DefaultMutableTreeNode child(Map<DefaultMutableTreeNode, Map<String, DefaultMutableTreeNode>> children,
                                                DefaultMutableTreeNode parent, String name, Icon icon) {
        Map<String, DefaultMutableTreeNode> map = children.computeIfAbsent(parent, k -> new LinkedHashMap<>());
        DefaultMutableTreeNode n = map.get(name);
        if (n == null) {
            n = node(name, icon, null, "");
            map.put(name, n);
            parent.add(n);
        }
        return n;
    }

    private static int countLeaves(DefaultMutableTreeNode n) {
        Node node = (Node) n.getUserObject();
        if (node.route != null) {
            return 1;
        }
        int total = 0;
        for (int i = 0; i < n.getChildCount(); i++) {
            total += countLeaves((DefaultMutableTreeNode) n.getChildAt(i));
        }
        node.count = total;
        return total;
    }

    private static DefaultMutableTreeNode node(String text, Icon icon, RouteMatch route, String summary) {
        return new DefaultMutableTreeNode(new Node(text, icon, route, summary));
    }

    static Color methodColor(String method) {
        switch (method == null ? "" : method) {
            case "GET":
                return new Color(0x6A8759);
            case "POST":
                return new Color(0xCC7832);
            case "PUT":
                return new Color(0x9876AA);
            case "DELETE":
                return new Color(0xC75450);
            case "PATCH":
                return new Color(0x299999);
            default:
                return new Color(0x808080);
        }
    }

    private static final class Node {
        final String name;
        final Icon icon;
        final RouteMatch route;
        String summary;
        int count;

        Node(String name, Icon icon, RouteMatch route, String summary) {
            this.name = name;
            this.icon = icon;
            this.route = route;
            this.summary = summary;
        }
    }

    private static final class Renderer extends ColoredTreeCellRenderer {
        @Override
        public void customizeCellRenderer(JTree t, Object value, boolean selected, boolean expanded,
                                          boolean leaf, int row, boolean hasFocus) {
            if (!(value instanceof DefaultMutableTreeNode)) {
                return;
            }
            Object uo = ((DefaultMutableTreeNode) value).getUserObject();
            if (!(uo instanceof Node)) {
                return;
            }
            Node n = (Node) uo;
            if (n.route != null) {
                setIcon(null);
                append(n.route.httpMethod,
                        new SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, methodColor(n.route.httpMethod)));
                append("  " + n.route.path, SimpleTextAttributes.REGULAR_ATTRIBUTES);
                if (n.route.methodSummary != null && !n.route.methodSummary.isEmpty()) {
                    append("  " + n.route.methodSummary, SimpleTextAttributes.GRAYED_ATTRIBUTES);
                }
            } else {
                setIcon(n.icon);
                append(n.name, SimpleTextAttributes.REGULAR_ATTRIBUTES);
                if (n.count > 0) {
                    append("  (" + n.count + " api)", SimpleTextAttributes.GRAYED_ATTRIBUTES);
                }
                if (n.summary != null && !n.summary.isEmpty()) {
                    append("  " + n.summary, SimpleTextAttributes.GRAYED_ATTRIBUTES);
                }
            }
        }
    }
}