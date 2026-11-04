package com.tvguard;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * 数学题实时生成器。
 *
 * 固定题库再多也会被背完，所以数学采用「参数化模板」：每次出题现场造数、现场算答案，
 * 同一道题几乎不会重复出现。这是真正意义上的「永远有新题」。
 *
 * 覆盖人教版五年级上册全部考点：小数乘除法、简易方程、多边形面积、单位换算、植树问题。
 * 所有答案用 BigDecimal 计算，不经过浮点，不会出现 0.30000000000000004 这种脏数。
 */
public class MathGen {

    private final Random rnd = new Random();

    /** 按权重抽一个题型。数学题 100% 由这里产出。 */
    public QuestionBank.Q next() {
        int t = rnd.nextInt(100);
        if (t < 14) return tMulInt();        // 小数乘整数
        if (t < 28) return tMulDec();        // 小数乘小数
        if (t < 40) return tDivInt();        // 小数除以整数
        if (t < 50) return tDivDec();        // 一个数除以小数
        if (t < 58) return tParallelogram(); // 平行四边形面积
        if (t < 66) return tTriangle();      // 三角形面积
        if (t < 73) return tTrapezoid();     // 梯形面积
        if (t < 81) return tEqAdd();         // x + a = b
        if (t < 87) return tEqMul();         // ax = b
        if (t < 92) return tEqMix();         // ax + b = c
        if (t < 95) return tUnit();          // 单位换算
        if (t < 97) return tGarden();        // 植树问题
        return tApprox();                    // 积的近似数
    }

    // ==================== 小数乘法 ====================

    private QuestionBank.Q tMulInt() {
        int a = 1 + rnd.nextInt(9);
        int b = 1 + rnd.nextInt(9);
        int c = 2 + rnd.nextInt(8);
        BigDecimal x = new BigDecimal(a + "." + b);
        BigDecimal ans = x.multiply(new BigDecimal(c));
        String stem = a + "." + b + " × " + c + " = ?";
        return build(stem, ans,
                "先当整数算 " + (a * 10 + b) + "×" + c + "，再点一位小数",
                ans.movePointRight(1), ans.movePointLeft(1),
                ans.add(new BigDecimal("0." + b)));
    }

    private QuestionBank.Q tMulDec() {
        int a = 1 + rnd.nextInt(9), b = 1 + rnd.nextInt(9);
        int c = 1 + rnd.nextInt(9), e = 1 + rnd.nextInt(9);
        BigDecimal x = new BigDecimal(a + "." + b);
        BigDecimal y = new BigDecimal(c + "." + e);
        BigDecimal ans = x.multiply(y);
        String stem = a + "." + b + " × " + c + "." + e + " = ?";
        return build(stem, ans,
                "两个因数一共有两位小数，积就点两位小数",
                ans.movePointRight(1), ans.movePointLeft(1), ans.movePointLeft(2));
    }

    // ==================== 小数除法 ====================

    private QuestionBank.Q tDivInt() {
        int n = 2 + rnd.nextInt(8);
        int q1 = 1 + rnd.nextInt(9);
        int q2 = 1 + rnd.nextInt(9);
        BigDecimal q = new BigDecimal(q1 + "." + q2);
        BigDecimal dividend = q.multiply(new BigDecimal(n));
        String stem = plain(dividend) + " ÷ " + n + " = ?";
        return build(stem, q,
                "按整数除法算，商的小数点要和被除数的小数点对齐",
                q.movePointRight(1), q.movePointLeft(1), q.add(new BigDecimal("1")));
    }

    private QuestionBank.Q tDivDec() {
        int q1 = 1 + rnd.nextInt(9);
        int q2 = 1 + rnd.nextInt(9);
        BigDecimal q = new BigDecimal(q1 + "." + q2);
        BigDecimal divisor = new BigDecimal("0." + (2 + rnd.nextInt(8)));
        BigDecimal dividend = q.multiply(divisor);
        String stem = plain(dividend) + " ÷ " + plain(divisor) + " = ?";
        return build(stem, q,
                "除数是小数，先把除数变成整数，被除数同时扩大相同倍数",
                q.movePointRight(1), q.movePointLeft(1), q.add(new BigDecimal("0.1")));
    }

    // ==================== 多边形面积 ====================

    private QuestionBank.Q tParallelogram() {
        int b1 = 1 + rnd.nextInt(9), b2 = 1 + rnd.nextInt(9);
        int h = 2 + rnd.nextInt(9);
        BigDecimal base = new BigDecimal(b1 + "." + b2);
        BigDecimal height = new BigDecimal(h);
        BigDecimal ans = base.multiply(height);
        String stem = "一个平行四边形的底是 " + plain(base) + " 米，高是 " + h
                + " 米，它的面积是多少平方米？";
        return build(stem, ans,
                "平行四边形面积 = 底 × 高",
                ans.divide(new BigDecimal("2"), 1, RoundingMode.HALF_UP),
                ans.movePointRight(1), base.add(height));
    }

    private QuestionBank.Q tTriangle() {
        int b = 2 + rnd.nextInt(18);
        int h1 = 1 + rnd.nextInt(9), h2 = 1 + rnd.nextInt(9);
        BigDecimal base = new BigDecimal(b);
        BigDecimal height = new BigDecimal(h1 + "." + h2);
        BigDecimal prod = base.multiply(height);
        BigDecimal ans = prod.divide(new BigDecimal("2"), 2, RoundingMode.HALF_UP);
        String stem = "一个三角形的底是 " + b + " 米，高是 " + plain(height)
                + " 米，它的面积是多少平方米？";
        return build(stem, ans,
                "三角形面积 = 底 × 高 ÷ 2，别漏了除以 2",
                prod, prod.movePointRight(1), base.add(height));
    }

    private QuestionBank.Q tTrapezoid() {
        int a = 2 + rnd.nextInt(9);
        int b = a + 2 * (1 + rnd.nextInt(5)); // 保证 a+b 是偶数，面积好除
        int h = 2 + rnd.nextInt(8);
        BigDecimal sum = new BigDecimal(a + b);
        BigDecimal ans = sum.multiply(new BigDecimal(h))
                .divide(new BigDecimal("2"), 1, RoundingMode.HALF_UP);
        String stem = "一个梯形的上底 " + a + " 米，下底 " + b + " 米，高 " + h
                + " 米，面积是多少平方米？";
        return build(stem, ans,
                "梯形面积 = (上底 + 下底) × 高 ÷ 2",
                sum.multiply(new BigDecimal(h)),
                new BigDecimal(a * h), new BigDecimal(b * h));
    }

    // ==================== 简易方程 ====================

    private QuestionBank.Q tEqAdd() {
        int x = 2 + rnd.nextInt(20);
        int a = 2 + rnd.nextInt(20);
        String stem = "解方程：x + " + a + " = " + (x + a) + "，x = ?";
        return build(stem, new BigDecimal(x),
                "两边同时减去 " + a,
                new BigDecimal(x + a), new BigDecimal(Math.abs(x - a)),
                new BigDecimal(a));
    }

    private QuestionBank.Q tEqMul() {
        int a = 2 + rnd.nextInt(8);
        int x = 2 + rnd.nextInt(12);
        String stem = "解方程：" + a + "x = " + (a * x) + "，x = ?";
        return build(stem, new BigDecimal(x),
                "两边同时除以 " + a,
                new BigDecimal(a * x), new BigDecimal(a + x), new BigDecimal(x + a));
    }

    private QuestionBank.Q tEqMix() {
        int a = 2 + rnd.nextInt(7);
        int x = 2 + rnd.nextInt(10);
        int b = 1 + rnd.nextInt(15);
        int c = a * x + b;
        String stem = "解方程：" + a + "x + " + b + " = " + c + "，x = ?";
        return build(stem, new BigDecimal(x),
                "先两边同时减去 " + b + "，再同时除以 " + a,
                new BigDecimal(c - b), new BigDecimal(c / a),
                new BigDecimal((c - b) / a));
    }

    // ==================== 其他考点 ====================

    private QuestionBank.Q tUnit() {
        int whole = 1 + rnd.nextInt(9);
        int frac = 1 + rnd.nextInt(9);
        String v = whole + "." + frac;
        BigDecimal meter = new BigDecimal(v);
        BigDecimal cm = meter.multiply(new BigDecimal(100));
        String stem = v + " 米 = ? 厘米";
        return buildStr(stem, plain(cm) + " 厘米",
                plain(meter.multiply(new BigDecimal(10))) + " 厘米",
                plain(meter.multiply(new BigDecimal(1000))) + " 厘米",
                plain(new BigDecimal(whole * 100 + frac)) + " 厘米",
                "1 米 = 100 厘米，小数点往右移两位");
    }

    private QuestionBank.Q tGarden() {
        int gap = 2 + rnd.nextInt(8);
        int seg = 3 + rnd.nextInt(10);
        int total = gap * seg;
        int ans = seg + 1;
        String stem = "一条 " + total + " 米长的路，每隔 " + gap
                + " 米种一棵树，两端都种，一共要种多少棵？";
        return build(stem, new BigDecimal(ans),
                "两端都种，棵数 = 段数 + 1，" + total + "÷" + gap + "=" + seg,
                new BigDecimal(seg), new BigDecimal(seg + 2),
                new BigDecimal(seg - 1 < 0 ? 0 : seg - 1));
    }

    private QuestionBank.Q tApprox() {
        int a = 1 + rnd.nextInt(9), b = 1 + rnd.nextInt(9);
        int c = 1 + rnd.nextInt(9), e = 1 + rnd.nextInt(9);
        BigDecimal x = new BigDecimal(a + "." + b);
        BigDecimal y = new BigDecimal(c + "." + e);
        BigDecimal raw = x.multiply(y);
        BigDecimal ans = raw.setScale(1, RoundingMode.HALF_UP);
        String stem = a + "." + b + " × " + c + "." + e + " ≈ ?（保留一位小数）";
        return build(stem, ans,
                "先算完整结果 " + plain(raw) + "，再看百分位四舍五入",
                raw.setScale(2, RoundingMode.HALF_UP),
                raw.setScale(0, RoundingMode.HALF_UP),
                ans.add(new BigDecimal("0.1")));
    }

    // ==================== 组装 ====================

    /** 数值型题目：给正确答案 + 几个「像那么回事」的错误答案 */
    private QuestionBank.Q build(String stem, BigDecimal ans, String tip, BigDecimal... wrongs) {
        List<String> opts = new ArrayList<>();
        String right = plain(ans);
        opts.add(right);
        for (BigDecimal w : wrongs) {
            if (w == null) continue;
            String s = plain(w);
            if (s.equals(right) || opts.contains(s)) continue;
            opts.add(s);
            if (opts.size() >= 4) break;
        }
        int guard = 0;
        while (opts.size() < 4 && guard++ < 60) {
            int delta = 1 + rnd.nextInt(9);
            BigDecimal cand = rnd.nextBoolean() ? ans.add(new BigDecimal(delta))
                    : ans.subtract(new BigDecimal(delta));
            if (cand.signum() < 0) continue;
            String s = plain(cand);
            if (s.equals(right) || opts.contains(s)) continue;
            opts.add(s);
        }
        return pack(stem, right, opts, tip);
    }

    /** 文本型题目：选项是带单位的字符串，不能靠加减补足 */
    private QuestionBank.Q buildStr(String stem, String right, String w1, String w2, String w3, String tip) {
        List<String> opts = new ArrayList<>();
        opts.add(right);
        for (String s : new String[]{w1, w2, w3}) {
            if (s == null || s.equals(right) || opts.contains(s)) continue;
            opts.add(s);
        }
        int guard = 0;
        while (opts.size() < 4 && guard++ < 60) {
            String s = right.replaceAll("[0-9]+", String.valueOf(1 + rnd.nextInt(99)));
            if (!opts.contains(s)) opts.add(s);
        }
        return pack(stem, right, opts, tip);
    }

    private QuestionBank.Q pack(String stem, String right, List<String> opts, String tip) {
        // 兜底：万一干扰项撞车没凑够 4 个，补几个明显不对的，别让界面上出现空按钮
        int guard = 1;
        while (opts.size() < 4 && guard < 10) {
            opts.add(guard == 1 ? "以上都不对" : "以上都不对" + guard);
            guard++;
        }
        Collections.shuffle(opts, rnd);
        QuestionBank.Q q = new QuestionBank.Q();
        q.subject = "math";
        q.stem = stem;
        q.options = opts.toArray(new String[4]);
        q.answer = opts.indexOf(right);
        q.tip = tip;
        q.generated = true;
        q.id = "";  // 现场生成的题不进历史记录，因为它本来就不会重复
        return q;
    }

    /** BigDecimal 输出成孩子看得懂的写法：去掉末尾多余的 0 */
    static String plain(BigDecimal b) {
        if (b == null) return "0";
        BigDecimal s = b.stripTrailingZeros();
        if (s.scale() < 0) s = s.setScale(0);
        return s.toPlainString();
    }
}
