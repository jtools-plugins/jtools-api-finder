# 开发流程：用 Qoder 和 JTools 开发 API Finder

本文记录 API Finder 的实际开发方式：我提出插件要解决的问题和验收标准，Qoder 负责搭建项目、实现功能，并通过 JTools Debugger MCP 在独立 IDE 沙箱里调试。几轮对话和验证后，产出了当前版本。

## 1. 准备环境

1. 在 IntelliJ IDEA 中安装并启用 [JTools](https://plugins.jetbrains.com/plugin/com.lhstack.tools)，再安装 [Qoder](https://qoder.com/)，用 Qoder 打开项目工作区。
2. 在 Qoder 中选用 `qwen4-max`，将**推理级别**设为**高**。这是本项目开发时的模型配置，不是运行插件的要求。
3. 准备 JDK、Gradle 和 JTools SDK；本项目的版本和构建命令见 [README 的构建说明](../README.md#从源码构建)。

## 2. 创建项目并接入 Debugger MCP

1. **让 Qoder 创建 JTools 扩展项目。** 说明这是 JTools 扩展，不是独立的 IntelliJ 插件；API Finder 需要使用 IDEA 的 Java PSI 和编辑器 API，因此采用带 IntelliJ 平台依赖的 Java/Gradle 工程。
2. **将技能安装到项目。** 让 Qoder 从 [`lhstack/JTools/skills`](https://github.com/lhstack/JTools/tree/master/skills) 导入 `jtools-plugin-development`，作用域选当前项目。保留完整技能目录，因为除了 `SKILL.md`，其中还包含模板、调试手册和 Debugger 安装包。本地工作区的 `.awake-claw/skills/` 可用作 Awake 的技能副本，不纳入 Git。在 Qoder 中按它的项目技能配置导入，不要把两者的配置目录混用。
3. **在 JTools 中安装并启用 Debugger。** 从技能目录的 `assets/debugger/` 取出 `plugin-debugger-0.2.0-mcp.jar`，通过 **JTools 的插件管理**安装。打开 Debugger 面板，启动 MCP 服务；仅安装技能不会启动调试服务。
4. **把 stdio 配置交给 Qoder。** 在 Debugger 面板切到 **stdio** 标签，点击「复制配置」，把生成的配置交给 Qoder，让它注册为**当前项目**的 MCP。保留生成的 `command`、`args`、`env` 结构，不用手工拼接 shell 命令。配置包含本机路径和访问令牌，不要提交到仓库或贴到公开文档中。
5. **验证连通。** 让 Qoder 调用 `sandbox_debug` 的 `help` 和 `discover`，确认能看到调试命令并发现本机的 IntelliJ IDEA 和 JTools 安装，再开始调试。

## 3. 在对话中实现、调试和迭代

我先描述想要的体验：**输入 HTTP 方法和路径，在当前项目中找到处理函数，能从树形结果预览源码、跳转位置，并生成 cURL。** Qoder 根据描述逐步实现，然后用 Debugger MCP 自己启动沙箱、操作面板、观察结果；我根据跑出来的效果补充需求，Qoder 再修改和复测。可以用下面这段提示启动第一轮：

> 帮我开发一个 JTools 扩展：输入 HTTP 方法和路径，找到当前项目里对应的路由处理函数。先实现搜索、树形结果和源码预览，再加入跳转及 cURL。每次改动后都用 `shadowJar` 打包，把 JAR 通过 Debugger MCP 加载到独立 IDE 沙箱，用最小测试样例验证。请告诉我实际观察到的结果，不要只报告编译成功。

每轮迭代用同一套步骤，便于核对前后的行为：

1. 用 `gradle shadowJar` 生成 `build/libs/api-finder-1.2.0-plugin.jar`。
2. 通过 MCP 启动沙箱，用 `ping` 确认就绪，再用 `run` 临时加载 JAR；换包前先 `stop`。不把开发中的 JAR 反复安装到日常 IDE。
3. 在沙箱项目放入虚构的路由样例，确认空查询能浏览所有样例、正确方法能命中、错误方法不会误匹配，以及选中结果后能预览处理函数。
4. 根据实际输出继续调整，保留必要的截图和未覆盖项。当前版本的截图及结果见 [README 的沙箱验收](../README.md#沙箱验收)。

Debugger 的命令参数和排障方式见 [上游调试手册](https://github.com/lhstack/JTools/blob/master/skills/jtools-plugin-development/references/debugger.md)。
