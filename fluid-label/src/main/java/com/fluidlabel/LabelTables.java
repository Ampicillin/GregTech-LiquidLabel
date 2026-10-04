package com.fluidlabel;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 标签数据表：内置预计算表（jar 内 {@code /fluidlabel_labels.json}）+ 外部人工别名表
 * （{@code config/fluidlabel-aliases.json}）+ 显示名池。
 *
 * <p>三层来源里的第 1、2 层（PLAN §5.3）都在这里；第 3 层（运行时规则兜底）在
 * {@link LabelEngine} / {@link DistinctMds}。
 *
 * <p>线程模型：{@link #reload()} 会整体替换 {@code volatile} 快照，读方永远看到一致的一份，
 * 不做逐条目加锁（渲染热路径要求无锁读）。
 *
 * <p><b>不依赖 Gson</b>：PLAN 未授权新增依赖，故内置一个极简 JSON 解析器（只支持标准
 * JSON 子集：object/array/string/number/true/false/null，含 {@code \\uXXXX} 转义）。
 */
public final class LabelTables {

    /** jar 内置表的资源路径（与 gen_labels.py 的 --out 一致）。 */
    public static final String RESOURCE_PATH = "/fluidlabel_labels.json";

    /** jar 内置的人工锚点（由 gen_labels.py 从 tools/anchors.json 导出，供 DYNAMIC 档使用）。 */
    public static final String ANCHORS_RESOURCE_PATH = "/fluidlabel_anchors.json";

    private static final AtomicBoolean RELOADING = new AtomicBoolean(false);

    /** 显示名 → 标签（内置表）。 */
    private static volatile Map<String, String> byDisplayName = Collections.emptyMap();
    /** 注册名（namespace:path） → 标签（内置表）。 */
    private static volatile Map<String, String> byRegistryName = Collections.emptyMap();
    /** 人工别名表：显示名 → 标签（最高优先级）。 */
    private static volatile Map<String, String> aliases = Collections.emptyMap();
    /** jar 内置的人工锚点：显示名 → 标签（DYNAMIC 档的覆盖层，PLAN §0.4/§5.3）。 */
    private static volatile Map<String, String> builtinAnchors = Collections.emptyMap();
    /** 内置表的全部显示名（STABLE 档兜底 MDS 的池）。 */
    private static volatile Set<String> displayPool = Collections.emptySet();
    /** 内置表是否可用（false 时退回纯规则）。 */
    private static volatile boolean tableAvailable = false;
    /** 别名表上次读取的文件时间，用于廉价地探测变更。 */
    private static volatile long aliasesStamp = -1L;
    /** 是否已经就"内置表缺失"打过日志。 */
    private static volatile boolean warnedMissing = false;

    private LabelTables() {
    }

    /** 内置表是否加载成功且非空。 */
    public static boolean tableAvailable() {
        return tableAvailable;
    }

    /** 内置表条数（供日志/自检）。 */
    public static int size() {
        return byDisplayName.size();
    }

    /** 显示名 → 标签（不加别名层；别名请用 {@link #aliasLabel(String)}）。 */
    public static String labelByDisplayName(String displayName) {
        if (displayName == null) {
            return null;
        }
        return byDisplayName.get(displayName);
    }

    /** 注册名（{@code namespace:path}） → 标签。 */
    public static String labelByRegistryName(String registryName) {
        if (registryName == null) {
            return null;
        }
        return byRegistryName.get(registryName);
    }

    /** 人工别名（显示名为键）。 */
    public static String aliasLabel(String displayName) {
        if (displayName == null) {
            return null;
        }
        return aliases.get(displayName);
    }

    /** STABLE 档运行时兜底用的显示名池（不可变快照）。 */
    public static Set<String> displayPool() {
        return displayPool;
    }

    /**
     * jar 内置的人工锚点（显示名 → 标签），DYNAMIC 档据此覆盖重算结果。
     *
     * <p>STABLE 档把锚点烘进了 {@code byDisplayName}，本表是为"运行时不查表"的那一档准备的，
     * 让两档对同一液体给出一致标签。
     */
    public static Map<String, String> builtinAnchors() {
        return builtinAnchors;
    }

    /**
     * 重新加载内置表 + 别名表。
     *
     * <p>任何 IO/解析异常都被吞掉并退化为"表为空"，绝不让主线程崩（A6）。
     */
    public static void reload() {
        if (!RELOADING.compareAndSet(false, true)) {
            return;
        }
        try {
            loadAliases(true);

            Map<String, String> display = new HashMap<>();
            Map<String, String> registry = new HashMap<>();
            boolean ok = false;
            InputStream in = LabelTables.class.getResourceAsStream(RESOURCE_PATH);
            if (in != null) {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    Object parsed = MiniJson.parse(reader);
                    if (parsed instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> root = (Map<String, Object>) parsed;
                        readStringMap(root.get("byDisplayName"), display);
                        readStringMap(root.get("byRegistryName"), registry);
                        ok = !display.isEmpty();
                    }
                }
            }
            byDisplayName = Collections.unmodifiableMap(display);
            byRegistryName = Collections.unmodifiableMap(registry);
            builtinAnchors = loadBuiltinAnchors();
            displayPool = display.isEmpty()
                    ? Collections.<String>emptySet()
                    : Collections.unmodifiableSet(new HashSet<>(display.keySet()));
            tableAvailable = ok;

            if (!ok && !warnedMissing) {
                warnedMissing = true;
                FluidLabelMod.LOGGER.warn(
                        "[fluidlabel] {} 未找到或为空 —— 退回运行时规则标签（功能仍可用，质量下降）。",
                        RESOURCE_PATH);
            }
            if (FluidLabelConfig.debugLog()) {
                FluidLabelMod.LOGGER.info("[fluidlabel] labels table: {} 条显示名 / {} 条注册名，别名 {} 条，随包锚点 {} 条",
                        byDisplayName.size(), byRegistryName.size(), aliases.size(), builtinAnchors.size());
            }
        } catch (Throwable t) {
            FluidLabelMod.LOGGER.warn("[fluidlabel] 加载标签表失败，退回运行时规则", t);
            byDisplayName = Collections.emptyMap();
            byRegistryName = Collections.emptyMap();
            builtinAnchors = Collections.emptyMap();
            displayPool = Collections.emptySet();
            tableAvailable = false;
        } finally {
            RELOADING.set(false);
        }
    }

    /**
     * 读 jar 内的人工锚点（{@code /fluidlabel_anchors.json}，由 {@code tools/gen_labels.py} 导出）。
     *
     * <p>缺失/损坏一律返回空表：DYNAMIC 档会退化为"没有锚点覆盖"，与加此层之前的行为一致。
     */
    private static Map<String, String> loadBuiltinAnchors() {
        try {
            InputStream in = LabelTables.class.getResourceAsStream(ANCHORS_RESOURCE_PATH);
            if (in == null) {
                return Collections.emptyMap();
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
                Object parsed = MiniJson.parse(reader);
                if (parsed instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> root = (Map<String, Object>) parsed;
                    Map<String, String> out = new HashMap<>();
                    readStringMap(root.get("byDisplayName"), out);
                    return out.isEmpty() ? Collections.<String, String>emptyMap()
                            : Collections.unmodifiableMap(out);
                }
            }
        } catch (Throwable t) {
            FluidLabelMod.LOGGER.warn("[fluidlabel] 读随包锚点失败（忽略，DYNAMIC 档不含人工覆盖）", t);
        }
        return Collections.emptyMap();
    }

    /**
     * 廉价地探测别名表是否变更（比对文件 mtime）；变更则重读。
     * 渲染热路径只在每帧第一次调用，开销 = 一次 stat。
     */
    public static void refreshAliasesIfChanged() {
        try {
            Path p = FluidLabelConfig.aliasesFile();
            if (p == null || !Files.isRegularFile(p)) {
                if (aliasesStamp != -1L) {
                    aliasesStamp = -1L;
                    aliases = Collections.emptyMap();
                }
                return;
            }
            long stamp = Files.getLastModifiedTime(p).toMillis();
            if (stamp != aliasesStamp) {
                loadAliases(false);
            }
        } catch (Throwable ignored) {
            // 静默：别名表是可选增强
        }
    }

    private static void loadAliases(boolean force) {
        try {
            Path p = FluidLabelConfig.aliasesFile();
            if (p == null || !Files.isRegularFile(p)) {
                aliases = Collections.emptyMap();
                aliasesStamp = -1L;
                return;
            }
            long stamp = Files.getLastModifiedTime(p).toMillis();
            if (!force && stamp == aliasesStamp) {
                return;
            }
            Map<String, String> out = new HashMap<>();
            try (BufferedReader reader = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
                Object parsed = MiniJson.parse(reader);
                if (parsed instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> root = (Map<String, Object>) parsed;
                    // 允许两种形态：{displayName: label} 或 {"byDisplayName": {...}}
                    Object inner = root.get("byDisplayName");
                    if (inner instanceof Map) {
                        readStringMap(inner, out);
                    } else {
                        readStringMap(root, out);
                    }
                }
            }
            aliases = Collections.unmodifiableMap(out);
            aliasesStamp = stamp;
        } catch (Throwable t) {
            FluidLabelMod.LOGGER.warn("[fluidlabel] 别名表读取失败，忽略", t);
            aliases = Collections.emptyMap();
            aliasesStamp = -1L;
        }
    }

    @SuppressWarnings("unchecked")
    private static void readStringMap(Object node, Map<String, String> out) {
        if (!(node instanceof Map)) {
            return;
        }
        for (Map.Entry<String, Object> e : ((Map<String, Object>) node).entrySet()) {
            Object v = e.getValue();
            if (v instanceof String && !((String) v).isEmpty()) {
                out.put(e.getKey(), (String) v);
            }
        }
    }

    /**
     * 极简 JSON 解析器（纯标准库，够用即可）。
     *
     * <p>只认标准 JSON：object / array / string（含 {@code \\uXXXX} 转义）/ number / true / false / null。
     * 顶层返回 {@code Map<String,Object>}、{@code List<Object>}、String、Double、Boolean 或 null。
     */
    static final class MiniJson {

        private final String src;
        private int pos;

        private MiniJson(String src) {
            this.src = src;
        }

        static Object parse(java.io.Reader reader) throws IOException {
            StringBuilder sb = new StringBuilder(1 << 16);
            char[] buf = new char[8192];
            int n;
            while ((n = reader.read(buf)) > 0) {
                sb.append(buf, 0, n);
            }
            return new MiniJson(sb.toString()).parseValue();
        }

        static Object parse(String text) {
            return new MiniJson(text).parseValue();
        }

        private Object parseValue() {
            skipWs();
            if (pos >= src.length()) {
                return null;
            }
            char c = src.charAt(pos);
            switch (c) {
                case '{':
                    return parseObject();
                case '[':
                    return parseArray();
                case '"':
                    return parseString();
                case 't':
                    expect("true");
                    return Boolean.TRUE;
                case 'f':
                    expect("false");
                    return Boolean.FALSE;
                case 'n':
                    expect("null");
                    return null;
                default:
                    return parseNumber();
            }
        }

        private Map<String, Object> parseObject() {
            Map<String, Object> map = new HashMap<>();
            pos++; // '{'
            skipWs();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWs();
                if (peek() != '"') {
                    throw err("object key");
                }
                String key = parseString();
                skipWs();
                if (peek() != ':') {
                    throw err("':'");
                }
                pos++;
                map.put(key, parseValue());
                skipWs();
                char c = peek();
                if (c == ',') {
                    pos++;
                    continue;
                }
                if (c == '}') {
                    pos++;
                    return map;
                }
                throw err("',' or '}'");
            }
        }

        private List<Object> parseArray() {
            List<Object> list = new ArrayList<>();
            pos++; // '['
            skipWs();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(parseValue());
                skipWs();
                char c = peek();
                if (c == ',') {
                    pos++;
                    continue;
                }
                if (c == ']') {
                    pos++;
                    return list;
                }
                throw err("',' or ']'");
            }
        }

        private String parseString() {
            pos++; // '"'
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (pos >= src.length()) {
                    throw err("string end");
                }
                char c = src.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (pos >= src.length()) {
                    throw err("escape");
                }
                char e = src.charAt(pos++);
                switch (e) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u':
                        if (pos + 4 > src.length()) {
                            throw err("unicode escape");
                        }
                        sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                        pos += 4;
                        break;
                    default:
                        throw err("escape char");
                }
            }
        }

        private Double parseNumber() {
            int start = pos;
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E' || (c >= '0' && c <= '9')) {
                    pos++;
                } else {
                    break;
                }
            }
            if (start == pos) {
                throw err("number");
            }
            return Double.valueOf(src.substring(start, pos));
        }

        private void expect(String lit) {
            if (!src.startsWith(lit, pos)) {
                throw err(lit);
            }
            pos += lit.length();
        }

        private char peek() {
            if (pos >= src.length()) {
                throw err("unexpected end");
            }
            return src.charAt(pos);
        }

        private void skipWs() {
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        private IllegalArgumentException err(String what) {
            return new IllegalArgumentException("JSON 解析失败：期待 " + what + " @ " + pos);
        }
    }
}
