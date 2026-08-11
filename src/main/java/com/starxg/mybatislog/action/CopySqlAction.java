package com.starxg.mybatislog.action;

import com.intellij.execution.impl.ConsoleViewImpl;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.util.TextRange;
import org.jetbrains.annotations.NotNull;

import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.regex.Pattern;

/**
 * CopySqlAction
 */
public class CopySqlAction extends JumpSqlAction {

    private static final Pattern HEADER_PATTERN = Pattern.compile("^-- \\d+ -- .*");

    public CopySqlAction(ConsoleViewImpl consoleView) {
        super("Copy SQL", "Copy SQL", AllIcons.Actions.Copy, consoleView);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        super.actionPerformed(e);

        final Document document = editor.getDocument();
        final int caretLine = editor.getCaretModel().getPrimaryCaret().getLogicalPosition().line;

        // Look backward from caret line to find the entry header
        int headerLine = findHeaderLineBackward(document, caretLine);
        if (headerLine < 0) {
            return;
        }

        // Find the next header or end of document
        int nextHeaderLine = findHeaderLineForward(document, headerLine + 1);

        // Calculate start and end offsets for the SQL content
        final int startOffset = document.getLineEndOffset(headerLine) + 1;
        final int endOffset = nextHeaderLine > 0
                ? document.getLineStartOffset(nextHeaderLine)
                : document.getTextLength();

        if (startOffset >= endOffset) {
            return;
        }

        final String sql = document.getText(TextRange.create(startOffset, endOffset)).trim();
        if (!sql.isEmpty()) {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(sql), null);
        }
    }

    private static int findHeaderLineBackward(Document document, int fromLine) {
        for (int line = fromLine; line >= 0; line--) {
            final int lineStart = document.getLineStartOffset(line);
            final int lineEnd = document.getLineEndOffset(line);
            final String text = document.getText(TextRange.create(lineStart, lineEnd));
            if (HEADER_PATTERN.matcher(text).matches()) {
                return line;
            }
        }
        return -1;
    }

    private static int findHeaderLineForward(Document document, int fromLine) {
        final int lineCount = document.getLineCount();
        for (int line = fromLine; line < lineCount; line++) {
            final int lineStart = document.getLineStartOffset(line);
            final int lineEnd = document.getLineEndOffset(line);
            final String text = document.getText(TextRange.create(lineStart, lineEnd));
            if (HEADER_PATTERN.matcher(text).matches()) {
                return line;
            }
        }
        return -1;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        final Document document = editor.getDocument();
        if (document == null) {
            e.getPresentation().setEnabled(false);
            return;
        }

        final int caretLine = editor.getCaretModel().getPrimaryCaret().getLogicalPosition().line;
        e.getPresentation().setEnabled(findHeaderLineBackward(document, caretLine) >= 0);
    }
}