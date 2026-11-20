package apifinder;

import apifinder.ui.ApiFinderPanel;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.IconLoader;
import com.lhstack.tools.plugins.IPlugin;
import com.lhstack.tools.plugins.IdeInfo;
import com.lhstack.tools.plugins.Support;

import javax.swing.Icon;
import javax.swing.JComponent;

public final class ApiFinderPlugin implements IPlugin {

    @Override
    public boolean supportMultiOpens() {
        return true;
    }

    @Override
    public Support support(Integer buildBaselineVersion, IdeInfo ideInfo) {
        return Support.SUPPORT;
    }

    @Override
    public JComponent createPanel(Project project) {
        return new ApiFinderPanel(project);
    }

    @Override
    public void closePanel(Project project, JComponent panel) {
        if (!(panel instanceof ApiFinderPanel)) {
            throw new IllegalArgumentException("面板类型不匹配: " + panel);
        }
        ((ApiFinderPanel) panel).close();
    }

    @Override
    public java.util.List<com.lhstack.tools.plugins.FunctionCalling> functionCallings(Project project) {
        return java.util.Collections.singletonList(new FindApiFunction(project));
    }

    @Override
    public Icon pluginIcon() {
        return icon("/icons/plugin.png", 48);
    }

    @Override
    public Icon pluginTabIcon() {
        return icon("/icons/tab.png", 14);
    }

    @Override
    public String pluginName() {
        return "API Finder";
    }

    @Override
    public String pluginDesc() {
        return "输入接口地址，查找当前项目中 Java/Go/Rust 对应的路由处理函数";
    }

    @Override
    public String pluginVersion() {
        return "1.2.0";
    }

    private Icon icon(String path, int size) {
        Icon icon = IconLoader.getIcon(path, ApiFinderPlugin.class);
        if (icon.getIconWidth() != size || icon.getIconHeight() != size) {
            throw new IllegalArgumentException(path + " 必须为 " + size + "×" + size);
        }
        return icon;
    }
}