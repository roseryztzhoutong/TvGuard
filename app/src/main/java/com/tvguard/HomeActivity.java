package com.tvguard;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 电视桌面。
 *
 * 这是整件事的关键所在，先说清楚为什么要做这一页。
 *
 * 家长的原话是「不能装完就在电视上授权吗，非得用电脑？」。
 * 答案分两半：
 *
 *   一半是不能。「设备所有者」这个身份只能由 adb 从外面交给应用，
 *   安卓不给任何应用自我提权的口子，否则所有流氓软件都能一步登天。
 *   这一点没法绕过去，谁来做都一样。
 *
 *   另一半是能，而且不用那个身份也能成。换一个思路：
 *   不去抢别人的应用，而是把「家」搬到自己这里。
 *
 * 安卓允许第三方应用注册成 HOME。只要家长在电视设置里把默认桌面改成答题锁
 * （这是一个普通设置项，点几下就行，不需要电脑，不需要开发者模式），
 * 那么孩子按遥控器上那颗「桌面键」回到的就不再是华为自己的主页，而是这一页。
 *
 * 于是整条链路变成：
 *
 *   孩子想看电视 → 只能在这一页点应用 → 到点了先答题 → 答对才放行
 *   在应用里看久了 → 到点自动按一次 HOME（无障碍能做，不需要任何权限）
 *                  → 回到这一页 → 又得答题
 *
 * 到点自动回桌面这一步靠无障碍服务的 performGlobalAction，
 * 在电视的设置里打开就行，同样不需要电脑。
 *
 * 所以这一版把「权限」这条路彻底绕开了：
 * 不要求悬浮窗，不要求设备所有者，孩子也跑不掉。
 */
public class HomeActivity extends Activity implements QuizController.Host {

    /** 桌面是不是正在前台。守护服务根据它决定「到点该不该把人踢回桌面」 */
    public static volatile boolean alive = false;

    private Prefs prefs;
    private Stats stats;
    private QuestionBank bank;
    private QuizController ctrl;

    private View contentLayer;
    private View quizPanel;
    private LinearLayout appGrid;
    private TextView tvHeadline, tvSubline, tvClock, tvHint, tvEmpty;

    private final Handler handler = new Handler(Looper.getMainLooper());

    /** 点开某个应用前被答题拦下，答对之后要接着把它打开 */
    private String pendingPkg = "";
    private String pendingLabel = "";

    private boolean quizShowing = false;
    private boolean backHeld = false;

    /** 装满一屏够用了，再多遥控器也翻得累 */
    private final List<AppEntry> apps = new ArrayList<>();

    static class AppEntry {
        String pkg;
        String label;
        Drawable icon;
        ComponentName comp;
    }

    /**
     * 和其他页面一样，先做逻辑尺寸换算。
     *
     * 全局的逻辑短边是 540dp。少了这一句，在 1080p 和 4K 电视上
     * 这一页的字会小到看不清、格子会挤成一条线，因为布局里写的
     * 26dp 内边距、150dp 宽的格子全是按 540dp 的短边算的。
     */
    @Override
    protected void attachBaseContext(android.content.Context base) {
        Prefs p = new Prefs(base);
        super.attachBaseContext(Ui.wrap(base, p.getUiScale()));
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_home);

        prefs = new Prefs(this);
        stats = new Stats(this);

        contentLayer = findViewById(R.id.contentLayer);
        quizPanel = findViewById(R.id.quizPanel);
        appGrid = findViewById(R.id.appGrid);
        tvHeadline = findViewById(R.id.tvHeadline);
        tvSubline = findViewById(R.id.tvSubline);
        tvClock = findViewById(R.id.tvClock);
        tvHint = findViewById(R.id.tvHint);
        tvEmpty = findViewById(R.id.tvEmpty);

        // 桌面不进最近任务列表：孩子长按最近任务键时不该看到「主页」这种可回去的入口
        try {
            setRecentsSkipped();
        } catch (Throwable ignored) {
        }

        try {
            loadApps();
            refreshHeader();
        } catch (Throwable t) {
            // 桌面一旦崩掉，孩子眼前就直接露出系统主页，整套防护等于归零。
            // 所以这里宁可少显示几个应用、少刷新几个字，也绝不能让 Activity 挂掉。
            Diag.log(this, "桌面", "初始化出错：" + t.getClass().getSimpleName());
        }

        // 题库在后台先备好，弹题时就不用等
        ensureBank();
    }

    private void setRecentsSkipped() {
        // 用 ActivityManager 里没有公开 API，这里改走 manifest 的 excludeFromRecents，
        // 运行时不需要再做什么，留个空实现把意图写明白。
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // 按桌面键回来走这里。刷新一次，时间可能已经跨过间隔了
        refreshHeader();
        maybeGateOnResume();
    }

    @Override
    protected void onResume() {
        super.onResume();
        alive = true;
        ctrl = null;      // 上一轮答完就把控制器丢了，重新绑一次，状态最干净
        quizShowing = false;
        if (quizPanel != null) quizPanel.setVisibility(View.GONE);
        if (contentLayer != null) contentLayer.setVisibility(View.VISIBLE);
        refreshHeader();
        maybeGateOnResume();
        handler.post(ticker);
    }

    @Override
    protected void onPause() {
        alive = false;
        handler.removeCallbacks(ticker);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        alive = false;
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // ---------------------------------------------------------------- 到点就拦

    /**
     * 回到桌面时该不该立刻弹题。
     *
     * 这是「在应用里看久了被踢回来」之后的那一问。孩子从 B 站被 HOME 键弹回来，
     * 第一眼看到的就是这一页，这时候必须先答题，不能让他直接又点回去。
     */
    private void maybeGateOnResume() {
        if (!prefs.isEnabled()) return;
        if (prefs.isLocked()) {
            showLockedState();
            return;
        }
        if (needQuizNow()) {
            // 给焦点一点时间落位，否则刚回来就抢焦点，遥控器会失灵半秒
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    showQuiz();
                }
            }, 260);
        }
    }

    /** 现在该不该先答题 */
    private boolean needQuizNow() {
        if (!prefs.isEnabled()) return false;
        if (exceedDaily()) return true;
        return prefs.getSinceLastSec() >= prefs.getIntervalMin() * 60;
    }

    private boolean exceedDaily() {
        return prefs.getTodayUsedSec() >= prefs.getDailyLimitMin() * 60;
    }

    // ---------------------------------------------------------------- 点应用

    private void onTileClick(AppEntry e) {
        if (prefs.isLocked()) {
            showLockedState();
            return;
        }
        if (!prefs.isEnabled()) {
            launch(e);
            return;
        }
        if (exceedDaily()) {
            // 今天的时间用完了。不弹题，直接不给进，提示家长来开
            tvHeadline.setText("今天的时间用完了");
            tvSubline.setText("要接着看，长按菜单键 2 秒，输家长密码");
            Toast.makeText(this, "今天的时间已经用完", Toast.LENGTH_SHORT).show();
            return;
        }
        if (needQuizNow()) {
            pendingPkg = e.pkg;
            pendingLabel = e.label;
            showQuiz();
            return;
        }
        launch(e);
    }

    private void launch(AppEntry e) {
        try {
            Intent i = null;
            if (e.comp != null) {
                i = new Intent(Intent.ACTION_MAIN);
                i.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
                i.setComponent(e.comp);
            }
            if (i == null) i = getPackageManager().getLaunchIntentForPackage(e.pkg);
            if (i == null) {
                Toast.makeText(this, "这个应用打不开", Toast.LENGTH_SHORT).show();
                return;
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            Toast.makeText(this, "打不开：" + t.getClass().getSimpleName(), Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------------------------------------------------------- 答题面板

    private void showQuiz() {
        if (quizShowing) return;
        if (quizPanel == null || contentLayer == null) return;
        quizShowing = true;

        contentLayer.setVisibility(View.GONE);
        quizPanel.setVisibility(View.VISIBLE);

        QuestionBank b = ensureBank();
        ctrl = new QuizController(prefs, b, stats, this);
        ctrl.bind(quizPanel);
    }

    private void hideQuiz() {
        quizShowing = false;
        if (quizPanel != null) quizPanel.setVisibility(View.GONE);
        if (contentLayer != null) contentLayer.setVisibility(View.VISIBLE);
    }

    @Override
    public void onPassed() {
        hideQuiz();
        prefs.setSinceLastSec(0);
        prefs.setLastPop("桌面上答对了，放行：" + (pendingLabel.isEmpty() ? "回桌面" : pendingLabel));
        String pkg = pendingPkg;
        pendingPkg = "";
        pendingLabel = "";
        refreshHeader();
        if (!pkg.isEmpty()) {
            for (AppEntry e : apps) {
                if (e.pkg.equals(pkg)) {
                    launch(e);
                    return;
                }
            }
        }
        focusFirstTile();
    }

    @Override
    public void onLocked() {
        hideQuiz();
        pendingPkg = "";
        pendingLabel = "";
        showLockedState();
    }

    @Override
    public void post(Runnable r, long delayMs) {
        handler.postDelayed(r, delayMs);
    }

    /** 连错锁定了：把桌面变成一句只读的告示，格子全部点不动 */
    private void showLockedState() {
        long sec = prefs.lockRemainSec();
        long min = Math.max(1, (sec + 59) / 60);
        if (tvHeadline != null) tvHeadline.setText("答错太多了，先歇 " + min + " 分钟");
        if (tvSubline != null) tvSubline.setText("家长想现在就恢复：长按菜单键 2 秒，输密码");
        if (tvHint != null) tvHint.setText("锁定中，遥控器点应用不会打开");
        for (int i = 0; i < appGrid.getChildCount(); i++) {
            setRowEnabled(appGrid.getChildAt(i), false);
        }
    }

    private void setRowEnabled(View row, boolean on) {
        if (row instanceof LinearLayout) {
            LinearLayout l = (LinearLayout) row;
            for (int i = 0; i < l.getChildCount(); i++) {
                View t = l.getChildAt(i);
                t.setEnabled(on);
                t.setFocusable(on);
                t.setAlpha(on ? 1f : 0.4f);
            }
        }
    }

    private void focusFirstTile() {
        if (appGrid.getChildCount() == 0) return;
        View row = appGrid.getChildAt(0);
        if (row instanceof LinearLayout && ((LinearLayout) row).getChildCount() > 0) {
            ((LinearLayout) row).getChildAt(0).requestFocus();
        }
    }

    // ---------------------------------------------------------------- 顶栏

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refreshHeader();
            handler.postDelayed(this, 1000);
        }
    };

    private void refreshHeader() {
        if (tvClock != null) {
            tvClock.setText(new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date()));
        }
        if (prefs == null) return;

        boolean locked = prefs.isLocked();
        if (!prefs.isEnabled()) {
            tvHeadline.setText("守护已关闭");
            tvSubline.setText("长按菜单键 2 秒进设置");
            tvHint.setText("遥控器方向键选择　·　按 OK 打开　·　长按菜单键 2 秒进家长设置");
            for (int i = 0; i < appGrid.getChildCount(); i++) setRowEnabled(appGrid.getChildAt(i), true);
            return;
        }

        if (locked) {
            showLockedState();
            return;
        }

        int used = prefs.getTodayUsedSec() / 60;
        int limit = prefs.getDailyLimitMin();
        tvHeadline.setText("今天已看 " + used + " 分钟，剩 " + Math.max(0, limit - used) + " 分钟");

        int gap = prefs.getIntervalMin() * 60 - prefs.getSinceLastSec();
        if (exceedDaily()) {
            tvSubline.setText("时间用完了，要接着看得家长解锁");
        } else if (gap <= 0) {
            tvSubline.setText("该答题了");
        } else {
            tvSubline.setText("再过 " + Math.max(1, gap / 60) + " 分钟答题一次，连续答对 "
                    + prefs.getStreakRequired() + " 题放行");
        }

        if (!isDefaultHome(this)) {
            tvSubline.setText("还没设成默认桌面，孩子按桌面键会回到电视自己的主页。"
                    + "去 设置 → 应用 → 默认应用 → 主屏幕 里选「答题锁」");
        }
        tvHint.setText("遥控器方向键选择　·　按 OK 打开　·　长按菜单键 2 秒进家长设置");

        for (int i = 0; i < appGrid.getChildCount(); i++) setRowEnabled(appGrid.getChildAt(i), true);
    }

    // ---------------------------------------------------------------- 应用列表

    private void loadApps() {
        final PackageManager pm = getPackageManager();
        List<ResolveInfo> list = new ArrayList<>();
        try {
            Intent lean = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
            list = pm.queryIntentActivities(lean, 0);
            if (list == null || list.isEmpty()) {
                Intent plain = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
                list = pm.queryIntentActivities(plain, 0);
            }
        } catch (Throwable ignored) {
        }

        // 电视自己的桌面不该出现在自己的桌面上。点进去只会弹「选择桌面」，纯添乱
        String homePkg = defaultHomePkg(this);
        Set<String> seen = new HashSet<>();
        apps.clear();
        if (list != null) {
            for (ResolveInfo ri : list) {
                if (ri == null || ri.activityInfo == null) continue;
                String pkg = ri.activityInfo.packageName;
                if (pkg == null) continue;
                if (pkg.equals(getPackageName())) continue;
                if (pkg.equals(homePkg)) continue;
                if (!seen.add(pkg)) continue;      // 一个应用只留一个入口
                AppEntry e = new AppEntry();
                e.pkg = pkg;
                e.comp = new ComponentName(pkg, ri.activityInfo.name);
                try {
                    CharSequence lb = ri.loadLabel(pm);
                    e.label = lb == null ? pkg : lb.toString();
                } catch (Throwable t) {
                    e.label = pkg;
                }
                try {
                    e.icon = ri.loadIcon(pm);
                } catch (Throwable ignored) {
                }
                apps.add(e);
            }
        }
        buildGrid();
    }

    private static final int COLS = 5;

    private void buildGrid() {
        appGrid.removeAllViews();
        if (apps.isEmpty()) {
            if (tvEmpty != null) tvEmpty.setVisibility(View.VISIBLE);
            return;
        }
        if (tvEmpty != null) tvEmpty.setVisibility(View.GONE);

        LayoutInflater inf = LayoutInflater.from(this);
        List<List<Integer>> rowIds = new ArrayList<>();

        for (int i = 0; i < apps.size(); i += COLS) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            if (i > 0) rlp.topMargin = dp(14);
            appGrid.addView(row, rlp);

            List<Integer> ids = new ArrayList<>();
            for (int j = i; j < Math.min(i + COLS, apps.size()); j++) {
                View tile = inf.inflate(R.layout.item_app, row, false);
                bindTile(tile, apps.get(j));
                LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(dp(150), dp(132));
                if (j > i) tlp.leftMargin = dp(14);
                int id = View.generateViewId();
                tile.setId(id);
                ids.add(id);
                row.addView(tile, tlp);
            }
            rowIds.add(ids);
        }

        // 遥控器上下跨行。不加这一段，从第一行按「下」有可能直接跳到底栏提示上
        for (int r = 0; r < rowIds.size(); r++) {
            List<Integer> cur = rowIds.get(r);
            List<Integer> up = r > 0 ? rowIds.get(r - 1) : null;
            List<Integer> down = r + 1 < rowIds.size() ? rowIds.get(r + 1) : null;
            for (int c = 0; c < cur.size(); c++) {
                View v = findViewById(cur.get(c));
                if (v == null) continue;
                if (up != null && !up.isEmpty()) {
                    v.setNextFocusUpId(up.get(Math.min(c, up.size() - 1)));
                }
                if (down != null && !down.isEmpty()) {
                    v.setNextFocusDownId(down.get(Math.min(c, down.size() - 1)));
                }
            }
        }
    }

    private void bindTile(View tile, final AppEntry e) {
        ImageView iv = tile.findViewById(R.id.ivIcon);
        TextView name = tile.findViewById(R.id.tvName);
        TextView tag = tile.findViewById(R.id.tvTag);
        if (iv != null) {
            if (e.icon != null) iv.setImageDrawable(e.icon);
            else iv.setImageResource(R.mipmap.ic_launcher);
        }
        if (name != null) name.setText(e.label);
        if (tag != null) {
            // 小字标一下有没有在看视频类应用，家长一眼知道哪些该管
            tag.setText(videoTag(e.pkg));
        }
        tile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onTileClick(e);
            }
        });
    }

    private String videoTag(String pkg) {
        String p = pkg.toLowerCase(Locale.ROOT);
        if (p.contains("bili") || p.contains("youku") || p.contains("iqiyi")
                || p.contains("qiyi") || p.contains("tencent") || p.contains("mgtv")
                || p.contains("douyin") || p.contains("kuaishou") || p.contains("youtube")) {
            return "视频";
        }
        return "";
    }

    // ---------------------------------------------------------------- 按键

    /**
     * 桌面自己吃掉返回键和菜单键。
     *
     * 返回键必须吞掉：桌面是任务栈的根，按返回会退到系统默认主页，
     * 那等于孩子按一下返回就绕开了这一页。
     *
     * 菜单键长按 2 秒是家长入口。选菜单键是因为电视遥控器上它基本没用，
     * 孩子不会误按，家长一学就会。
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        if (code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_INFO) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                if (e.getRepeatCount() == 0) {
                    handler.postDelayed(parentEntry, 2000);
                }
                return true;
            }
            handler.removeCallbacks(parentEntry);
            return true;
        }
        if (quizShowing && code == KeyEvent.KEYCODE_BACK) {
            // 答题面板上，长按返回 2 秒也是家长出口。孩子按一下没反应，只当是坏了
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                if (e.getRepeatCount() == 0) {
                    handler.postDelayed(parentEntry, 2000);
                }
                return true;
            }
            handler.removeCallbacks(parentEntry);
            return true;
        }
        if (!quizShowing && code == KeyEvent.KEYCODE_BACK) {
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    private final Runnable parentEntry = new Runnable() {
        @Override
        public void run() {
            Diag.log(HomeActivity.this, "桌面", "长按进入家长设置");
            try {
                Intent i = new Intent(HomeActivity.this, MainActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Throwable ignored) {
            }
        }
    };

    // ---------------------------------------------------------------- 工具

    private QuestionBank ensureBank() {
        if (bank != null && bank.size() > 0) return bank;
        bank = GuardService.sharedBank;
        if (bank != null && bank.size() > 0) return bank;
        final QuestionBank tmp = new QuestionBank();
        bank = tmp;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    tmp.load(HomeActivity.this,
                            prefs.hasGrade() ? prefs.getGrade() : 1, prefs.getTerm());
                } catch (Throwable ignored) {
                }
            }
        }, "home-bank").start();
        return tmp;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    // ---------------------------------------------------------------- 静态查询

    /** 当前电视的默认桌面是哪个包。取不到就返回空串 */
    public static String defaultHomePkg(android.content.Context c) {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_HOME);
            ResolveInfo r = c.getPackageManager()
                    .resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY);
            if (r != null && r.activityInfo != null) return r.activityInfo.packageName;
        } catch (Throwable ignored) {
        }
        return "";
    }

    /** 我们是不是已经接管了桌面。这是整条免电脑链路的开关 */
    public static boolean isDefaultHome(android.content.Context c) {
        return c.getPackageName().equals(defaultHomePkg(c));
    }

    /**
     * 电视上到底允许不允许换桌面。
     *
     * 有的固件把 HOME 写死成自家主页，第三方注册了也不给选。
     * 与其让家长在设置里翻半天找不到，不如直接告诉他有没有这个选项。
     */
    public static boolean homeSwitchable(android.content.Context c) {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_HOME);
            List<ResolveInfo> all = c.getPackageManager().queryIntentActivities(i, 0);
            return all != null && all.size() >= 2;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 跳到系统里选默认桌面的那一页 */
    public static void openHomeSettings(android.content.Context c) {
        String[] acts = {
                "android.settings.HOME_SETTINGS",
                "android.settings.MANAGE_DEFAULT_APPS_SETTINGS",
                android.provider.Settings.ACTION_APPLICATION_SETTINGS,
                android.provider.Settings.ACTION_SETTINGS
        };
        for (String a : acts) {
            try {
                Intent i = new Intent(a);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(i);
                return;
            } catch (Throwable ignored) {
            }
        }
    }
}
