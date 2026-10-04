package com.fluidlabel;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 离线测试（Java 侧）：运行时兜底路径（{@link DistinctMds#labelForName}）必须与 Python 生成器
 * 的期望结果逐条一致 —— 特别是「状态词缀派生名」的牌号保留（PLAN §5.6）。
 *
 * <p>期望文件由 {@code tools/derived_check.py} 写出（{@code ..\_diag\derived_replay.txt}）：
 * <pre>
 * seed     &lt;标签&gt;                  预计算表里已被占用的标签
 * resolve  &lt;显示名&gt;                运行时先查表的显示名（走到这里就说明查表命中，不进兜底）
 * derived  &lt;派生名&gt;&lt;TAB&gt;&lt;标签&gt;     兜底路径的期望标签，按分配顺序排列
 * </pre>
 *
 * <p>只依赖 JDK + {@code DistinctMds}（不碰 Minecraft/EMI），可脱离游戏运行：
 * <pre>
 * javac -encoding UTF-8 -d out src\main\java\com\fluidlabel\DistinctMds.java tools\parity\DerivedLabelCheck.java
 * java '-Dfile.encoding=UTF-8' -cp out com.fluidlabel.DerivedLabelCheck
 * </pre>
 */
public final class DerivedLabelCheck {

    private static int failures = 0;
    private static int compared = 0;

    public static void main(String[] args) throws IOException {
        Path replay = args.length > 0 ? Paths.get(args[0])
                : Paths.get("..", "_diag", "derived_replay.txt");
        if (!Files.isRegularFile(replay)) {
            System.out.println("[error] 找不到期望文件：" + replay.toAbsolutePath());
            System.out.println("        先跑 python tools\\derived_check.py 生成它。");
            System.exit(2);
        }

        Set<String> taken = new HashSet<>();
        Set<String> resolvable = new HashSet<>();
        List<String[]> rows = new ArrayList<>();
        for (String line : Files.readAllLines(replay, StandardCharsets.UTF_8)) {
            if (line.isEmpty() || line.charAt(0) == '#') {
                continue;
            }
            String[] f = line.split("\t", -1);
            if (f.length >= 2 && "seed".equals(f[0])) {
                taken.add(f[1]);
            } else if (f.length >= 2 && "resolve".equals(f[0])) {
                resolvable.add(f[1]);
            } else if (f.length >= 3 && "derived".equals(f[0])) {
                rows.add(new String[] {f[1], f[2]});
            }
        }
        System.out.println("seed labels   : " + taken.size());
        System.out.println("resolve names : " + resolvable.size());
        System.out.println("derived rows  : " + rows.size());

        checkUserCases();

        int skipped = 0;
        for (String[] row : rows) {
            String name = row[0];
            String want = row[1];
            if (resolvable.contains(name)) {
                skipped++;            // 运行时先查表命中 ⇒ 不走兜底，不参与比对
                continue;
            }
            String got = DistinctMds.labelForName(name, taken);
            compared++;
            if (!want.equals(got)) {
                if (failures < 30) {
                    System.out.println("[fail] " + name + " 期望 `" + want + "` 实得 `" + got + "`");
                }
                failures++;
            }
            if (cpLen(got) > 6) {
                System.out.println("[fail] " + name + " 兜底返回超长标签 `" + got + "`（" + cpLen(got) + " 字）");
                failures++;
            }
            taken.add(got);
        }
        System.out.println("compared      : " + compared + "（跳过查表命中的 " + skipped + " 条）");
        System.out.println("mismatches    : " + failures);

        System.out.println(failures == 0 ? "VERDICT: ALL PASS" : "VERDICT: " + failures + " FAILED");
        if (failures != 0) {
            System.exit(1);
        }
    }

    /** 码点数：标签预算的单位（与 {@code LabelRenderer.buildLayout} 的切行口径一致）。 */
    private static int cpLen(String s) {
        return s == null ? 0 : s.codePointCount(0, s.length());
    }

    /** 用户 2026-10 实测报出来的那几条：牌号必须留在标签里，且同族彼此可分。 */
    private static void checkUserCases() {
        Set<String> taken = new HashSet<>();
        String[][] cases = {
            {"熔融高速钢", "熔融高速钢"},
            {"熔融高速钢-G", "熔高速钢-G"},
            {"熔融高速钢-S", "熔高速钢-S"},
            {"熔融高速钢-E", "熔高速钢-E"},
            {"熔融铝合金-2090", "熔铝2090"},
            {"熔融铝合金-7050", "熔铝7050"},
            {"熔融不锈钢-316", "熔不锈316"},
            {"铝合金-2090等离子体", "铝2090浆"},
        };
        Set<String> seen = new HashSet<>();
        for (String[] c : cases) {
            String got = DistinctMds.labelForName(c[0], taken);
            boolean ok = c[1].equals(got) && cpLen(got) <= 6 && seen.add(got);
            if (!ok) {
                System.out.println("[fail] 用户样例 " + c[0] + " 期望 `" + c[1] + "` 实得 `" + got + "`");
                failures++;
            }
            taken.add(got);
        }
        System.out.println("user cases    : " + cases.length + " 条比对完成");
    }
}
