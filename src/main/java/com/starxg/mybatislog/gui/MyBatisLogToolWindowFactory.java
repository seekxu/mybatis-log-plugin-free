package com.starxg.mybatislog.gui;

import org.jetbrains.annotations.NotNull;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;

/**
 * MyBatisLogToolWindowFactory
 * 
 * @author huangxingguang
 */
public class MyBatisLogToolWindowFactory implements ToolWindowFactory {

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        MyBatisLogManager manager = MyBatisLogManager.getInstance(project);
        if (manager != null) {
            // Already initialized, just activate
            return;
        }
        MyBatisLogManager.createInstance(project);
    }
}