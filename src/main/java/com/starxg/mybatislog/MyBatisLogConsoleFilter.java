package com.starxg.mybatislog;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.ide.util.PropertiesComponent;
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
    private static final long CLEANUP_INTERVAL_MS = 5_000; // throttle cleanup to every 5s

    // Regex to extract logger name (e.g., "com.example.UserMapper" or "p.a.d.P.getPersonDetail")
    private static final Pattern LOGGER_PATTERN = Pattern.compile(
            "[a-zA-Z_][a-zA-Z0-9_]*\\.[a-zA-Z_][a-zA-Z0-9_.]*"
    );

    // ThreadLocal Matcher to avoid per-line allocation
    private static final ThreadLocal<Matcher> MATCHER_TL = ThreadLocal.withInitial(() -> LOGGER_PATTERN.matcher(""));

    private static final Set<String> NEED_BRACKETS;

    private final Project project;

    // Cache multiple Preparing statements by source, with timestamp for timeout
    private final Map<String, PendingSql> pendingSqls = new ConcurrentHashMap<>();

    private volatile long lastCleanupTime = 0;

    // Buffer for multi-line parameter values (e.g., XML/JSON spanning multiple console lines)
    private String pendingParamSource = null;
    private StringBuilder paramBuffer = null;

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
                    pendingParamSource = null;
                    paramBuffer = null;
                    return null;
                }
            }
        }

        // Clean up expired entries
        cleanupExpired();

        // Handle parameter continuation (multi-line values like XML/JSON)
        if (pendingParamSource != null) {
            if (handleContinuation(line, manager, preparing, parameters)) {
                return null;
            }
        }

        // Extract log source from line
        final String lineSource = extractSource(line, preparing, parameters);

        // Handle Preparing line - cache it by source
        if (line.contains(preparing)) {
            pendingSqls.put(lineSource, new PendingSql(line, System.currentTimeMillis()));
            return null;
        }

        // Handle Parameters line - match with cached Preparing
        if (line.contains(parameters)) {
            final PendingSql pending = pendingSqls.get(lineSource);
            if (pending == null) {
                return null;
            }

            final String paramsContent = StringUtils.substringAfter(line, parameters);
            final Queue<Map.Entry<String, String>> params = parseParams(paramsContent);

            if (params.size() < countPlaceholders(pending, preparing)) {
                // Not enough params yet - may be multi-line value
                pendingParamSource = lineSource;
                paramBuffer = new StringBuilder(paramsContent.trim());
                return null;
            }

            // Have all params, process immediately
            pendingSqls.remove(lineSource);
            processSql(pending, params, manager, preparing);
        }

        return null;
    }

    /**
     * Process matched Preparing and Parameters to restore the complete SQL.
     */
    private void processSql(PendingSql pending, Queue<Map.Entry<String, String>> params,
                            MyBatisLogManager manager, String preparing) {
        final String wholeSql = parseSql(
                StringUtils.substringAfter(pending.sqlLine, preparing),
                params
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

    /**
     * Count the number of '?' placeholders in a pending SQL template.
     */
    private int countPlaceholders(PendingSql pending, String preparing) {
        return StringUtils.countMatches(StringUtils.substringAfter(pending.sqlLine, preparing), "?");
    }

    /**
     * Handle parameter continuation buffer for multi-line values (XML/JSON).
     * Returns true if the line was consumed (handled), false to fall through to normal processing.
     */
    private boolean handleContinuation(String line, MyBatisLogManager manager,
                                       String preparing, String parameters) {
        // Terminator: new SQL starts or MyBatis result marker — flush and process
        if (line.contains(preparing) || line.contains(parameters)
                || line.contains("<==")) {
            final PendingSql pending = pendingSqls.get(pendingParamSource);
            if (pending != null && !pending.isExpired()) {
                final Queue<Map.Entry<String, String>> parsed = parseParams(paramBuffer.toString());
                pendingSqls.remove(pendingParamSource);
                processSql(pending, parsed, manager, preparing);
            } else {
                pendingSqls.remove(pendingParamSource);
            }
            pendingParamSource = null;
            paramBuffer = null;
            return line.contains("<==");
        }

        // Continuation data: accumulate and re-check param count
        final PendingSql pending = pendingSqls.get(pendingParamSource);
        if (pending == null || pending.isExpired()) {
            pendingParamSource = null;
            paramBuffer = null;
        } else {
            if (StringUtils.isNotBlank(line)) {
                paramBuffer.append('\n').append(line);
            }
            final Queue<Map.Entry<String, String>> parsed = parseParams(paramBuffer.toString());
            if (parsed.size() >= countPlaceholders(pending, preparing)) {
                pendingSqls.remove(pendingParamSource);
                pendingParamSource = null;
                paramBuffer = null;
                processSql(pending, parsed, manager, preparing);
            }
            return true;
        }
        return false;
    }

    private void cleanupExpired() {
        final long now = System.currentTimeMillis();
        if (now - lastCleanupTime < CLEANUP_INTERVAL_MS) {
            return;
        }
        lastCleanupTime = now;

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
        
        // Step 1: Try to extract logger name via regex (reuse ThreadLocal Matcher)
        final Matcher matcher = MATCHER_TL.get();
        matcher.reset(prefix);
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
        final StringBuilder sb = new StringBuilder(sql.length() + 32);

        for (int i = 0; i < sql.length(); i++) {
            if (sql.charAt(i) != MARK) {
                sb.append(sql.charAt(i));
                continue;
            }

            final Map.Entry<String, String> entry = params.poll();
            if (entry == null) {
                sb.append(MARK);
                continue;
            }

            if (NEED_BRACKETS.contains(entry.getValue())) {
                sb.append('\'').append(entry.getKey()).append('\'');
            } else {
                sb.append(entry.getKey());
            }
        }

        return sb;
    }

    static Queue<Map.Entry<String, String>> parseParams(String line) {
        // Remove all \r (Windows CRLF normalization) and strip leading/trailing whitespace
        line = StringUtils.remove(line, '\r').trim();

        // Left-to-right tokenization: split by separator at depth 0.
        // The separator is a depth-0 comma followed by whitespace (any sequence of spaces/newlines/tabs)
        // that contains at least one space OR newline. This matches MyBatis's ", " parameter separator
        // even when the console wraps the comma and the space across different lines (",\n   ") or
        // when the comma is at end of a line with no trailing space (",\n").
        // It also safely ignores commas inside values (XML/JSON) where commas are not followed
        // by a space/newline-containing whitespace sequence in this way.
        final List<String> tokens = new ArrayList<>();
        int depth = 0;
        int tokenStart = 0;

        for (int i = 0; i < line.length(); i++) {
            final char c = line.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (depth == 0 && c == ',') {
                // Scan forward after the comma: collect whitespace chars; stop at non-whitespace.
                // If the whitespace run contains at least one ' ' (space) or '\n' (newline),
                // treat as separator. Newline alone is valid because console line-wraps always
                // separate parameters, never values (XML/JSON internal newlines don't follow commas).
                int j = i + 1;
                boolean hasSpace = false;
                while (j < line.length()) {
                    final char wc = line.charAt(j);
                    if (wc == ' ' || wc == '\n' || wc == '\t') {
                        if (wc == ' ' || wc == '\n') hasSpace = true;
                        j++;
                    } else {
                        break;
                    }
                }
                // Also accept a comma directly followed by end-of-input (trailing comma after last param)
                if (hasSpace || j == line.length()) {
                    tokens.add(line.substring(tokenStart, i).trim());
                    i = j - 1; // loop will i++ to j, right after the whitespace run
                    tokenStart = j;
                }
            }
        }
        // Last token
        if (tokenStart < line.length()) {
            tokens.add(line.substring(tokenStart).trim());
        }

        // Parse each token into (value, type)
        final Queue<Map.Entry<String, String>> queue = new ArrayDeque<>(tokens.size());
        for (String token : tokens) {
            if (token.isEmpty()) continue;

            if (token.equals("null")) {
                queue.offer(new AbstractMap.SimpleEntry<>("null", null));
                continue;
            }

            // Extract type from "(TypeName)" at the end
            final int closeParen = token.lastIndexOf(')');
            if (closeParen >= 0 && closeParen == token.length() - 1) {
                final int openParen = token.lastIndexOf('(', closeParen - 1);
                if (openParen >= 0) {
                    final String typeName = token.substring(openParen + 1, closeParen);
                    // Type name should be a simple identifier (no commas or parens)
                    if (!typeName.contains(",") && !typeName.contains("(") && !typeName.contains(")")) {
                        String value = token.substring(0, openParen).trim();
                        if (value.endsWith(",")) {
                            value = value.substring(0, value.length() - 1).trim();
                        }
                        queue.offer(new AbstractMap.SimpleEntry<>(value, typeName.isEmpty() ? null : typeName));
                        continue;
                    }
                }
            }

            // Fallback: no recognizable type marker
            queue.offer(new AbstractMap.SimpleEntry<>(token, null));
        }

        return queue;
    }

}
