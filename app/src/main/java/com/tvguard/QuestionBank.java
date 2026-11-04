package com.tvguard;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * 题库调度中心。
 *
 * 题库按「年级 + 学期」拆成十二个文件（g1a … g6b），只加载家长选中的那几本，
 * 这样一二年级的机器不会去解析四五年级的题目。
 * 科学单独按年级整册存放（science_g1 … science_g6），因为小学科学的编排
 * 各版本差异大，按年级合并比硬拆上下册更稳。
 *
 * 三道防线，保证题目「换不完」：
 *  1. 数学题有相当比例由 MathGen 现场生成，参数随机；
 *  2. 固定题「答对 2 次即退役」，不再出现，孩子没法靠背答案过关；
 *  3. 固定题全部退役后自动轮转（开启下一轮），并把错误率高的题排在前面。
 */
public class QuestionBank {

    public static class Q {
        public String id;        // 固定题的唯一标识，用于记录答题历史
        public String subject;   // chinese / math / english / science
        public String stem;
        public String[] options;
        public int answer;
        public String tip;
        public boolean generated; // true = 现场生成，不进历史
    }

    /** 一道固定题答对几次后退役 */
    public static final int MASTER_AT = 2;

    /**
     * 科目的固定顺序。
     *
     * <p>界面上要按「语文、数学、英语、科学」这个顺序展示，不能依赖
     * HashSet 的遍历顺序，否则每台机器排出来的顺序都不一样。
     */
    public static final String[] SUBJECT_ORDER = {"chinese", "math", "english", "science"};

    private final List<Q> all = new ArrayList<>();
    private final MathGen mathGen = new MathGen();
    private final Random rnd = new Random();
    private final Set<String> subjects = new HashSet<>();

    private String lastId = "";
    private int round = 1;
    private int masteredCount = 0;
    private int totalFixed = 0;
    private int loadedGrade = 0;
    private String loadedTerm = "";

    /**
     * 按年级加载题库。
     *
     * @param grade 1-6
     * @param term  a=上册 b=下册 ab=全学年
     */
    public synchronized void load(Context c, int grade, String term) {
        all.clear();
        subjects.clear();
        totalFixed = 0;
        loadedGrade = grade;
        loadedTerm = term;

        List<String> files = new ArrayList<>();
        if ("ab".equals(term)) {
            files.add("g" + grade + "a.json");
            files.add("g" + grade + "b.json");
        } else {
            files.add("g" + grade + term + ".json");
        }
        // 科学题库按年级整册，选了上册也把本年级科学一起带上
        files.add("science_g" + grade + ".json");

        Set<String> stems = new HashSet<>();
        for (String f : files) {
            parseFile(c, f, stems);
        }

        totalFixed = all.size();
        if (all.isEmpty()) {
            // 兜底：题库读不出来也必须能出题，否则孩子一按就卡死
            Q q = new Q();
            q.subject = "math";
            q.stem = "12 × 8 = ?";
            q.options = new String[]{"86", "96", "104", "108"};
            q.answer = 1;
            q.tip = "12×8＝96";
            q.generated = true;
            q.id = "";
            all.add(q);
        }
    }

    private void parseFile(Context c, String name, Set<String> stems) {
        InputStream is = null;
        try {
            is = c.getAssets().open("bank/" + name);
            BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            is = null;

            JSONObject root = new JSONObject(sb.toString());
            JSONArray arr = root.getJSONArray("questions");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String stem = o.getString("q");
                String key = stem.length() > 70 ? stem.substring(0, 70) : stem;
                if (!stems.add(key)) continue;   // 上下册有重复题，只留一道

                Q q = new Q();
                q.subject = o.getString("s");
                q.stem = stem;
                JSONArray opt = o.getJSONArray("o");
                q.options = new String[opt.length()];
                for (int j = 0; j < opt.length(); j++) q.options[j] = opt.getString(j);
                q.answer = o.getInt("a");
                q.tip = o.optString("t", "");
                q.generated = false;
                q.id = makeId(q.subject, q.stem);
                shuffleOptions(q);
                all.add(q);
                subjects.add(q.subject);
            }
        } catch (Exception e) {
            // 某个年级/学期文件缺失（比如一二年级没有英语）就跳过，不影响其他科目
        } finally {
            if (is != null) {
                try { is.close(); } catch (Exception ignored) { }
            }
        }
    }

    /** 题干哈希做 id，题目内容不变则 id 不变，改一个字就是新题 */
    private static String makeId(String subject, String stem) {
        return subject.charAt(0) + Integer.toHexString(stem.hashCode());
    }

    public synchronized int size() { return all.size(); }
    public synchronized int getRound() { return round; }
    public synchronized int getLoadedGrade() { return loadedGrade; }
    public synchronized String getLoadedTerm() { return loadedTerm; }

    /** 当前题库里实际有哪些科目（一二年级没有英语） */
    public synchronized boolean hasSubject(String s) { return subjects.contains(s); }

    public synchronized String availableSubjects() {
        StringBuilder sb = new StringBuilder();
        for (String s : SUBJECT_ORDER) {
            if (!subjects.contains(s)) continue;
            if (sb.length() > 0) sb.append(",");
            sb.append(s);
        }
        return sb.toString();
    }

    public synchronized Q findById(String id) {
        if (id == null || id.isEmpty()) return null;
        for (Q q : all) {
            if (id.equals(q.id)) return q;
        }
        return null;
    }

    /** 刷新统计：多少题已经掌握（本轮的），还剩多少可用 */
    public synchronized void refreshStats(Prefs prefs) {
        int mastered = 0;
        for (Q q : all) {
            if (q.generated || q.id.isEmpty()) continue;
            if (prefs.getRight(q.id) >= MASTER_AT) mastered++;
        }
        masteredCount = mastered;
    }

    public synchronized int getMasteredCount() { return masteredCount; }
    public synchronized int getTotalFixed() { return totalFixed; }

    public synchronized int getAvailableCount(Prefs prefs) {
        int n = 0;
        for (Q q : all) {
            if (q.generated || q.id.isEmpty()) continue;
            if (prefs.getRight(q.id) < MASTER_AT) n++;
        }
        return n;
    }

    private void shuffleOptions(Q q) {
        if (q.options == null || q.options.length < 2) return;
        String right = q.options[q.answer];
        List<String> tmp = new ArrayList<>(Arrays.asList(q.options));
        Collections.shuffle(tmp, rnd);
        for (int i = 0; i < tmp.size(); i++) {
            q.options[i] = tmp.get(i);
            if (tmp.get(i).equals(right)) q.answer = i;
        }
    }

    /**
     * 取一道题。
     * 数学：按比例走生成器，永远是新题。
     * 语文/英语：从「还没掌握」的题里选，答错过的题权重更高。
     */
    public synchronized Q next(String subjectsCsv, Prefs prefs) {
        return nextInternal(subjectsCsv, prefs, true);
    }

    private Q nextInternal(String subjectsCsv, Prefs prefs, boolean allowRotate) {
        List<String> allow = Arrays.asList(subjectsCsv.split(","));
        boolean mathOn = allow.contains("math") && subjects.contains("math");

        // 数学占的比重：数学开了就有 45% 概率走生成器，其余走固定题
        if (mathOn && rnd.nextInt(100) < 45) {
            Q q = mathGen.next();
            lastId = "";
            return q;
        }

        List<Q> pool = new ArrayList<>();
        List<Q> weak = new ArrayList<>();
        for (Q q : all) {
            if (!allow.contains(q.subject)) continue;
            if (q.generated || q.id.isEmpty()) continue;
            if (prefs.getRight(q.id) >= MASTER_AT) continue;
            pool.add(q);
            if (prefs.getWrong(q.id) > 0) weak.add(q);
        }

        if (pool.isEmpty()) {
            if (allowRotate) {
                rotateToNextRound(prefs, allow);
                return nextInternal(subjectsCsv, prefs, false);
            }
            lastId = "";
            return mathGen.next();
        }

        List<Q> from = (!weak.isEmpty() && rnd.nextBoolean()) ? weak : pool;
        Q pick = from.get(rnd.nextInt(from.size()));
        int guard = 0;
        while (pick.id.equals(lastId) && from.size() > 1 && guard++ < 20) {
            pick = from.get(rnd.nextInt(from.size()));
        }
        lastId = pick.id;
        return pick;
    }

    /**
     * 按指定科目取一道题。
     *
     * <p>为「本组语文、数学、英语各一道」而加。原来的 {@link #next} 是自由抽，
     * 数学还有 45% 的概率直接走生成器，结果连着三道全是数学，孩子答到第三道
     * 还以为软件坏了。
     *
     * <p>规则：
     * <ul>
     *   <li>数学一律现场生成。固定题会退役，生成器不会，能保证这个科目永远有题；</li>
     *   <li>语文、英语优先取「还没掌握」的题，答错过的加权；</li>
     *   <li>某一科本轮的题全被答对过两遍了，就从这一科全量里重新挑。
     *       宁可题目复现，也不能拿别的科目顶替，否则「各一道」就名不副实。</li>
     * </ul>
     *
     * @return 该科目没题（比如一二年级没有英语）时返回 null，调用方自行兜底
     */
    public synchronized Q pickSubject(String subject, Prefs prefs) {
        if (subject == null || subject.isEmpty()) return null;

        List<Q> pool = new ArrayList<>();
        List<Q> weak = new ArrayList<>();
        List<Q> allOfSubject = new ArrayList<>();
        for (Q q : all) {
            if (!subject.equals(q.subject)) continue;
            if (q.generated || q.id.isEmpty()) continue;
            allOfSubject.add(q);
            if (prefs != null && prefs.getRight(q.id) >= MASTER_AT) continue;
            pool.add(q);
            if (prefs != null && prefs.getWrong(q.id) > 0) weak.add(q);
        }

        // 数学：生成器只覆盖五年级考点，一二三四年级用它等于给孩子出超纲题。
        // 所以只有五年级以上、或者这一科的固定题已经出空了，才走生成器；
        // 其余情况老老实实从本年级固定题库里取。
        if ("math".equals(subject)
                && (loadedGrade >= 5 || allOfSubject.isEmpty())
                && rnd.nextInt(100) < 60) {
            lastId = "";
            return mathGen.next();
        }

        if (pool.isEmpty()) pool = allOfSubject;
        if (pool.isEmpty()) return null;

        List<Q> from = (!weak.isEmpty() && rnd.nextBoolean()) ? weak : pool;
        Q pick = from.get(rnd.nextInt(from.size()));
        int guard = 0;
        while (pick.id.equals(lastId) && from.size() > 1 && guard++ < 20) {
            pick = from.get(rnd.nextInt(from.size()));
        }
        lastId = pick.id;
        return pick;
    }

    /**
     * 本轮题全部答对过 2 次了，进入下一轮。
     * 答错次数保留一半，答对次数清零，题目重新变得可出。
     */
    private void rotateToNextRound(Prefs prefs, List<String> allow) {
        List<String> ids = new ArrayList<>();
        for (Q q : all) {
            if (q.generated || q.id.isEmpty()) continue;
            if (!allow.contains(q.subject)) continue;
            ids.add(q.id);
        }
        prefs.rotateRound(ids);
        round = Math.max(round, prefs.getBankRound()) + 1;
        prefs.setBankRound(round);
    }

    /** 启动时把轮次读回来，避免重启后从 1 开始 */
    public synchronized void restoreRound(Prefs prefs) {
        round = prefs.getBankRound();
    }

    /** 家长手动换一批题 */
    public synchronized void resetRound(Prefs prefs, String subjectsCsv) {
        List<String> allow = Arrays.asList(subjectsCsv.split(","));
        List<String> ids = new ArrayList<>();
        for (Q q : all) {
            if (q.generated || q.id.isEmpty()) continue;
            if (allow.contains(q.subject)) ids.add(q.id);
        }
        prefs.rotateRound(ids);
        round = Math.max(round, prefs.getBankRound()) + 1;
        prefs.setBankRound(round);
    }
}
