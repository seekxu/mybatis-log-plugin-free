package com.starxg.mybatislog.gui;

import com.intellij.execution.filters.TextConsoleBuilder;
import com.intellij.execution.filters.TextConsoleBuilderFactory;
import com.intellij.execution.impl.ConsoleViewImpl;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.actions.ScrollToTheEndToolbarAction;
import com.intellij.openapi.editor.actions.ToggleUseSoftWrapsToolbarAction;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.editor.impl.softwrap.SoftWrapAppliancePlaces;
import com.intellij.openapi.editor.markup.HighlighterTargetArea;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.openapi.wm.ex.ToolWindowManagerListener;
import com.intellij.ui.JBColor;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.util.messages.MessageBusConnection;
import com.starxg.mybatislog.BasicFormatter;
import com.starxg.mybatislog.Icons;
import com.starxg.mybatislog.action.*;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static com.starxg.mybatislog.MyBatisLogConsoleFilter.*;

/**
 * MyBatisLogManager
 * 
 * @author huangxingguang
 */
public class MyBatisLogManager implements Disposable {

    private static final Key<MyBatisLogManager> KEY = Key.create(MyBatisLogManager.class.getName());
    private static final BasicFormatter FORMATTER = new BasicFormatter();
    private static final String TOOL_WINDOW_ID = "MyBatis Log Plugin Free";

    private final Map<Integer, ConsoleViewContentType> consoleViewContentTypes = new ConcurrentHashMap<>();

    private final ConsoleViewImpl consoleView;
    private final Project project;
    private final Content content;

    private final AtomicInteger counter;
    private volatile String preparing;
    private volatile String parameters;
    private volatile boolean running = false;

    private final List<String> keywords = new ArrayList<>(0);

    private MyBatisLogManager(@NotNull Project project) {
        this.project = project;

        this.consoleView = createConsoleView();
        this.counter = new AtomicInteger();

        // Build panel: left toolbar + center console
        final JPanel panel = new JPanel(new BorderLayout());

        final ActionToolbar actionToolbar = ActionManager.getInstance().createActionToolbar("MyBatisLogFree",
                createActionToolbar(), false);
        actionToolbar.setTargetComponent(consoleView.getComponent());
        panel.add(actionToolbar.getComponent(), BorderLayout.WEST);
        panel.add(consoleView.getComponent(), BorderLayout.CENTER);

        // Add content to the tool window
        final ToolWindow toolWindow = getToolWindow();
        this.content = ContentFactory.getInstance().createContent(panel, "SQL", false);
        content.setCloseable(false);
        toolWindow.getContentManager().removeAllContents(true);
        toolWindow.getContentManager().addContent(content);
        toolWindow.getContentManager().setSelectedContent(content, true);

        final MessageBusConnection messageBusConnection = project.getMessageBus().connect();

        Disposer.register(this, consoleView);
        Disposer.register(this, messageBusConnection);
        Disposer.register(project, this);

        final PropertiesComponent propertiesComponent = PropertiesComponent.getInstance(project);
        this.preparing = propertiesComponent.getValue(PREPARING_KEY, "Preparing: ");
        this.parameters = propertiesComponent.getValue(PARAMETERS_KEY, "Parameters: ");
        resetKeywords(propertiesComponent.getValue(KEYWORDS_KEY, StringUtils.EMPTY));

        messageBusConnection.subscribe(ToolWindowManagerListener.TOPIC, new ToolWindowManagerListener() {
            @Override
            public void stateChanged(@NotNull ToolWindowManager toolWindowManager) {
                if (!getToolWindow().isAvailable()) {
                    Disposer.dispose(MyBatisLogManager.this);
                }
            }
        });

        // Auto-start capturing when created (panel shows only when user clicks the icon)
        running = true;
    }

    private ConsoleViewImpl createConsoleView() {
        TextConsoleBuilder consoleBuilder = TextConsoleBuilderFactory.getInstance().createBuilder(project);
        final ConsoleViewImpl console = (ConsoleViewImpl) consoleBuilder.getConsole();
        // init editor
        console.getComponent();

        final Editor editor = console.getEditor();
        editor.getDocument().addDocumentListener(new RangeHighlighterDocumentListener(editor));

        return console;
    }

    private DefaultActionGroup createActionToolbar() {

        final ConsoleViewImpl consoleView = this.consoleView;

        final DefaultActionGroup actionGroup = new DefaultActionGroup();
        actionGroup.add(new RerunAction());
        actionGroup.add(new StopAction(this));
        actionGroup.add(new SettingsAction(this));
        actionGroup.addSeparator();
        actionGroup.add(new PreviousSqlAction(consoleView));
        actionGroup.add(new NextSqlAction(consoleView));
        actionGroup.addSeparator();

        actionGroup.add(new ToggleUseSoftWrapsToolbarAction(SoftWrapAppliancePlaces.CONSOLE) {
            @Nullable
            @Override
            protected Editor getEditor(@NotNull AnActionEvent e) {
                return consoleView.getEditor();
            }
        });

        actionGroup.add(new ScrollToTheEndToolbarAction(consoleView.getEditor()));
        actionGroup.add(new PrettyPrintToggleAction());
        actionGroup.addSeparator();
        actionGroup.add(new ClearAllAction(consoleView));
        actionGroup.addSeparator();
        actionGroup.add(new DonateAction(PropertiesComponent.getInstance(project)));

        return actionGroup;
    }

    public void println(String logPrefix, String sql, int rgb) {

        final ConsoleViewContentType consoleViewContentType = consoleViewContentTypes.computeIfAbsent(rgb,
                k -> new ConsoleViewContentType(String.valueOf(rgb),
                        new TextAttributes(new JBColor(rgb, rgb), null, null, null, Font.PLAIN)));

        consoleView.print(String.format("-- %s -- %s\n", counter.incrementAndGet(), logPrefix),
                ConsoleViewContentType.USER_INPUT);

        consoleView.print(String.format("%s\n",
                isFormat() ? FORMATTER.format(sql) : StringUtils.removeEnd(sql, "\n")), consoleViewContentType);

    }

    private boolean isFormat() {
        return PropertiesComponent.getInstance(project).getBoolean(PrettyPrintToggleAction.class.getName());
    }

    public void run() {

        if (running) {
            return;
        }

        running = true;

    }

    public void stop() {
        if (!running) {
            return;
        }
        running = false;

    }

    @Nullable
    public static MyBatisLogManager getInstance(@NotNull Project project) {

        MyBatisLogManager manager = project.getUserData(KEY);

        if (Objects.nonNull(manager)) {
            if (!manager.getToolWindow().isAvailable()) {
                Disposer.dispose(manager);
                manager = null;
            }
        }

        return manager;

    }

    @NotNull
    public static MyBatisLogManager createInstance(@NotNull Project project) {

        MyBatisLogManager manager = getInstance(project);

        if (manager != null && !Disposer.isDisposed(manager)) {
            return manager;
        }

        manager = new MyBatisLogManager(project);
        project.putUserData(KEY, manager);

        return manager;

    }

    @NotNull
    public static MyBatisLogManager recreateInstance(@NotNull Project project) {

        MyBatisLogManager manager = getInstance(project);

        if (manager != null && !Disposer.isDisposed(manager)) {
            Disposer.dispose(manager);
        }

        manager = new MyBatisLogManager(project);
        project.putUserData(KEY, manager);

        return manager;

    }

    public ToolWindow getToolWindow() {
        return ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID);
    }

    public void resetKeywords(String text) {

        keywords.clear();

        if (StringUtils.isBlank(text)) {
            return;
        }

        final String[] split = text.split("\n");

        final List<String> keywords = new ArrayList<>(split.length);

        for (String keyword : split) {
            if (StringUtils.isBlank(keyword)) {
                continue;
            }

            keywords.add(keyword);

        }

        this.keywords.addAll(keywords);
    }

    public String getPreparing() {
        return preparing;
    }

    public void setPreparing(String preparing) {
        this.preparing = preparing;
    }

    public void setParameters(String parameters) {
        this.parameters = parameters;
    }

    public boolean isRunning() {
        return running;
    }

    public String getParameters() {
        return parameters;
    }

    public List<String> getKeywords() {
        return keywords;
    }

    @Override
    public void dispose() {

        project.putUserData(KEY, null);

        stop();

        // Remove content from tool window to prevent duplicate tabs on recreate
        ToolWindow toolWindow = getToolWindow();
        if (toolWindow != null) {
            toolWindow.getContentManager().removeContent(content, true);
        }

    }

    private static final class RangeHighlighterDocumentListener implements DocumentListener {

        private final Editor editor;

        private RangeHighlighterDocumentListener(Editor editor) {
            this.editor = editor;
        }

        @Override
        public void documentChanged(@NotNull DocumentEvent event) {
            final Document document = event.getDocument();
            final int textLength = document.getTextLength();
            if (textLength < 1) {
                return;
            }

            for (int i = event.getOffset(); i < textLength; ) {
                final int endOffset = document.getLineEndOffset(document.getLineNumber(i));
                final String text = document.getText(TextRange.create(i, endOffset));
                if (text.matches("^-- [\\d]+ -- .*")) {
                    editor.getMarkupModel().addRangeHighlighter(i, i + 1, JumpSqlAction.SQL_LAYER,
                            TextAttributes.ERASE_MARKER, HighlighterTargetArea.EXACT_RANGE);
                }
                i = endOffset + 1;
            }
        }
    }

}