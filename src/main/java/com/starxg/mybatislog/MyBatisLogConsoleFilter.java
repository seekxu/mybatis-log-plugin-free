package com.starxg.mybatislog;

import java.util.*;

import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.ui.JBColor;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.intellij.execution.filters.Filter;
import com.intellij.openapi.project.Project;
import com.starxg.mybatislog.gui.MyBatisLogManager;

/**
 * MyBatisLogConsoleFilter
 *
 * @author huangxingguang
 */
public class MyBatisLogConsoleFilter implements Filter {

    public static final String PREPARING_KEY = MyBatisLogConsoleFilter.class.getName() + ".Preparing";
    public static final String PARAMETERS_KEY = MyBatisLogConsoleFilter.class.getName() + ".Parameters";
    public static final String KEYWORDS_KEY = MyBatisLogConsoleFilter.class.getName() + ".Keywords";

    public static final String INSERT_SQL_COLOR_KEY = MyBatisLogConsoleFilter.class.getName() + ".InsertSQLColor";
    public static final String DELETE_SQL_COLOR_KEY = MyBatisLogConsoleFilter.class.getName() + ".DeleteSQLColor";
    public static final String UPDATE_SQL_COLOR_KEY = MyBatisLogConsoleFilter.class.getName() + ".UpdateSQLColor";
    public static final String SELECT_SQL_COLOR_KEY = MyBatisLogConsoleFilter.class.getName() + ".SelectSQLColor";

    private static final char MARK = '?';
    private static final long PREPARING_TIMEOUT_MS = 10_000; // 10 seconds

    // Regex to extract logger name (e.g., "com.example.UserMapper" or "p.a.d.P.getPersonDetail")
    private static final java.util.regex.Pattern LOGGER_PATTERN = java.util.regex.Pattern.compile(
            "[a-zA-Z_][a-zA-Z0-9_]*\\.[a-zA-Z_][a-zA-Z0-9_.]*"
    );

    private static final Set<String> NEED_BRACKETS;

    private final Project project;

    // Cache multiple Preparing statements by source, with timestamp for timeout
    private final Map<String, PendingSql> pendingSqls = new LinkedHashMap<>();

    static {
        Set<String> types = new HashSet<>(8);
        types.add("String");
        types.add("Date");
        types.add("Time");
        types.add("LocalDate");
        types.add("LocalTime");
        types.add("LocalDateTime");
        types.add("BigDecimal");
        types.add("Timestamp");
        NEED_BRACKETS = Collections.unmodifiableSet(types);
    }

    MyBatisLogConsoleFilter(Project project) {
        this.project = project;
    }

    /**
     * Internal class to hold a pending Preparing statement with its timestamp.
     */
    private static class PendingSql {
        final String sqlLine;
        final long timestamp;

        PendingSql(String sqlLine, long timestamp) {
            this.sqlLine = sqlLine;
            this.timestamp = timestamp;
        }

        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > PREPARING_TIMEOUT_MS;
        }
    }

    @Override
    public @Nullable Result applyFilter(@NotNull String line, int entireLength) {

        final MyBatisLogManager manager = MyBatisLogManager.getInstance(project);
        if (Objects.isNull(manager)) {
            return null;
        }

        if (!manager.isRunning()) {
            return null;
        }

        final String preparing = manager.getPreparing();
        final String parameters = manager.getParameters();
        final List<String> keywords = manager.getKeywords();

        // Clear all pending statements if line matches any keyword
        if (!keywords.isEmpty()) {
            for (String keyword : keywords) {
                if (line.contains(keyword)) {
                    pendingSqls.clear();
                    return null;
                }
            }
        }

        // Clean up expired entries
        cleanupExpired();

        // Extract log source from line
        final String lineSource = extractSource(line, preparing, parameters);

        // Handle Preparing line - cache it by source
        if (line.contains(preparing)) {
            pendingSqls.put(lineSource, new PendingSql(line, System.currentTimeMillis()));
            return null;
        }

        // Handle Parameters line - match with cached Preparing
        if (line.contains(parameters)) {
            final PendingSql pending = pendingSqls.remove(lineSource);
            if (pending == null) {
                return null;
            }

            final String wholeSql = parseSql(
                    StringUtils.substringAfter(pending.sqlLine, preparing),
                    parseParams(StringUtils.substringAfter(line, parameters))
            ).toString();

            final String key;
            if (StringUtils.startsWithIgnoreCase(wholeSql, "insert")) {
                key = INSERT_SQL_COLOR_KEY;
            } else if (StringUtils.startsWithIgnoreCase(wholeSql, "delete")) {
                key = DELETE_SQL_COLOR_KEY;
            } else if (StringUtils.startsWithIgnoreCase(wholeSql, "update")) {
                key = UPDATE_SQL_COLOR_KEY;
            } else if (StringUtils.startsWithIgnoreCase(wholeSql, "select")
                    || StringUtils.startsWithIgnoreCase(wholeSql, "with")
                    || StringUtils.startsWithIgnoreCase(wholeSql, "explain")) {
                key = SELECT_SQL_COLOR_KEY;
            } else {
                key = "unknown";
            }

            final String logPrefix = StringUtils.substringBefore(pending.sqlLine, preparing);
            manager.println(logPrefix, wholeSql,
                    PropertiesComponent.getInstance(project).getInt(key,
                            ConsoleViewContentType.ERROR_OUTPUT.getAttributes().getForegroundColor().getRGB()));
        }

        return null;
    }

    /**
     * Remove expired Preparing entries to prevent memory leaks.
     */
    private void cleanupExpired() {
        final Iterator<Map.Entry<String, PendingSql>> it = pendingSqls.entrySet().iterator();
        while (it.hasNext()) {
            final Map.Entry<String, PendingSql> entry = it.next();
            if (entry.getValue().isExpired()) {
                it.remove();
            }
        }
    }

    /**
     * Extract the log source (logger name) from a log line.
     * Uses regex to find logger name, with fallback strategies.
     * 
     * Priority:
     * 1. Extract logger name via regex (e.g., "com.example.UserMapper")
     * 2. Extract thread name from brackets (e.g., "[http-nio-9020-exec-10]")
     * 3. Use full prefix before arrow markers
     */
    private String extractSource(String line, String preparing, String parameters) {
        int preparingIdx = line.indexOf(preparing);
        int parametersIdx = line.indexOf(parameters);

        int idx;
        if (preparingIdx >= 0 && parametersIdx >= 0) {
            idx = Math.min(preparingIdx, parametersIdx);
        } else if (preparingIdx >= 0) {
            idx = preparingIdx;
        } else if (parametersIdx >= 0) {
            idx = parametersIdx;
        } else {
            return line;
        }

        // Extract the part before the prefix
        String prefix = line.substring(0, idx).trim();
        
        // Step 1: Try to extract logger name via regex
        java.util.regex.Matcher matcher = LOGGER_PATTERN.matcher(prefix);
        if (matcher.find()) {
            return matcher.group();
        }
        
        // Step 2: Try to extract thread name from brackets
        int openBracket = prefix.indexOf('[');
        int closeBracket = prefix.indexOf(']');
        if (openBracket >= 0 && closeBracket > openBracket) {
            return prefix.substring(openBracket, closeBracket + 1);
        }
        
        // Step 3: Remove arrow markers and use remaining prefix
        int markerIdx = prefix.lastIndexOf("===>");
        if (markerIdx >= 0) {
            prefix = prefix.substring(0, markerIdx).trim();
        } else {
            markerIdx = prefix.lastIndexOf("==>");
            if (markerIdx >= 0) {
                prefix = prefix.substring(0, markerIdx).trim();
            }
        }
        
        return prefix;
    }

    static StringBuilder parseSql(String sql, Queue<Map.Entry<String, String>> params) {

        final StringBuilder sb = new StringBuilder(sql);

        for (int i = 0; i < sb.length(); i++) {
            if (sb.charAt(i) != MARK) {
                continue;
            }

            final Map.Entry<String, String> entry = params.poll();
            if (Objects.isNull(entry)) {
                continue;
            }


            sb.deleteCharAt(i);

            if (NEED_BRACKETS.contains(entry.getValue())) {
                sb.insert(i, String.format("'%s'", entry.getKey()));
            } else {
                sb.insert(i, entry.getKey());
            }


        }

        return sb;
    }

    static Queue<Map.Entry<String, String>> parseParams(String line) {
        line = StringUtils.removeEnd(line, "\n");

        final String[] strings = StringUtils.splitByWholeSeparator(line, ", ");
        final Queue<Map.Entry<String, String>> queue = new ArrayDeque<>(strings.length);

        for (String s : strings) {
            String value = StringUtils.substringBeforeLast(s, "(");
            String type = StringUtils.substringBetween(s, "(", ")");
            if (StringUtils.isEmpty(type)) {
                queue.offer(new AbstractMap.SimpleEntry<>(value, null));
            } else {
                queue.offer(new AbstractMap.SimpleEntry<>(value, type));
            }
        }

        return queue;
    }

}
