# -*- coding: utf-8 -*-
"""
从开源教材题库（congcong-school / ChinaTextbookStudyFree，MIT）生成按年级学期拆分的题库。

输入：仓库解压目录（默认 ../repo_raw/congcong-school-main），只读 output/*/quizzes/*.json
输出：app/src/main/assets/bank/g1a.json ... g6b.json
      每个文件 = 一个年级一个学期的全部四选一题，App 只加载家长选中的那几本

只保留四选一：判断题、填空题、连线题、连词成句、计算题在电视遥控器上没法答。
"""
import io
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.abspath(os.path.join(HERE, "..", "..", "repo_raw", "congcong-school-main"))
OUT_DIR = os.path.abspath(os.path.join(HERE, "..", "app", "src", "main", "assets", "bank"))

GRADE_RE = re.compile(r"([一二三四五六])年级(上册|下册)")
GRADE_MAP = {"一": 1, "二": 2, "三": 3, "四": 4, "五": 5, "六": 6}
PREFIX = re.compile(r"^\s*[A-Da-d]\s*[.、．:：)]\s*")

MAX_STEM = 120      # 电视上能显示的最长题干
MAX_OPT = 55        # 单选项最长
TIP_LIMIT = 46


def short_tip(text, limit=TIP_LIMIT):
    t = (text or "").strip().replace("\n", " ")
    if not t:
        return ""
    for sep in ("。", "；", "！", "?"):
        i = t.find(sep)
        if 0 < i <= limit:
            return t[:i + 1]
    return t[:limit] + ("…" if len(t) > limit else "")


def normalize(q):
    opts = q.get("options")
    ans = q.get("answer")
    if not isinstance(opts, list) or not isinstance(ans, str):
        return None
    opts = [str(o).strip() for o in opts]
    ans = ans.strip()

    if opts and all(PREFIX.match(o) for o in opts):
        letters = "ABCD"
        stripped = [PREFIX.sub("", o).strip() for o in opts]
        m = re.match(r"^\s*([A-D])\s*(?:[.、．:：)]\s*(.*))?$", ans)
        if m:
            idx = letters.index(m.group(1))
            if idx >= len(stripped):
                return None
            return stripped, idx
        ans_stripped = PREFIX.sub("", ans).strip()
        if ans_stripped in stripped:
            return stripped, stripped.index(ans_stripped)
        return None

    if ans in opts:
        return opts, opts.index(ans)
    return None


def clean(opts, idx):
    seen, keep = set(), []
    for i, o in enumerate(opts[:4]):
        if not o or o in seen:
            continue
        seen.add(o)
        keep.append((o, i == idx))
    if len(keep) < 2:
        return None
    texts = [o for o, _ in keep]
    right = [i for i, (_, r) in enumerate(keep) if r]
    if len(right) != 1:
        return None
    return texts, right[0]


def subject_of(path):
    p = path.replace("\\", "/")
    if "/chinese/" in p:
        return "chinese"
    if "/math/" in p:
        return "math"
    if "/english/" in p:
        return "english"
    return None


def wanted(path):
    """返回 (subject, grade, term) 或 None"""
    p = path.replace("\\", "/")
    if "/quizzes/" not in p or not p.endswith(".json"):
        return None
    name = p.rsplit("/", 1)[-1]
    m = GRADE_RE.search(name)
    if not m:
        return None
    grade = GRADE_MAP[m.group(1)]
    term = "a" if m.group(2) == "上册" else "b"
    subj = subject_of(p)
    if subj is None:
        return None

    # 只收人教 / 统编这条主线，五四制、地方版不要
    if "五四制" in name or "青岛" in name or "鲁科" in name or "苏教" in name or "北师大" in name:
        return None
    if subj == "chinese" and "语文" not in name:
        return None
    if subj == "math" and "数学" not in name:
        return None
    if subj == "english" and "英语" not in name:
        return None
    return subj, grade, term


def main():
    if not os.path.isdir(SRC):
        print("找不到源目录:", SRC)
        return 1

    buckets = {}
    seen_stem = {}
    stat = {"files": 0, "read": 0, "nonchoice": 0, "badopt": 0, "dup": 0, "toolong": 0, "ok": 0}

    for root, _dirs, files in os.walk(SRC):
        for fn in files:
            if not fn.endswith(".json"):
                continue
            path = os.path.join(root, fn)
            w = wanted(path)
            if not w:
                continue
            subj, grade, term = w
            stat["files"] += 1
            try:
                data = json.load(io.open(path, encoding="utf-8"))
            except Exception:
                continue
            key = (grade, term)
            bucket = buckets.setdefault(key, [])
            seen = seen_stem.setdefault(key, set())

            for sect in ("unit_test", "exam"):
                for q in data.get(sect, {}).get("questions", []):
                    stat["read"] += 1
                    if q.get("type") != "choice":
                        stat["nonchoice"] += 1
                        continue
                    norm = normalize(q)
                    if not norm:
                        stat["badopt"] += 1
                        continue
                    fixed = clean(*norm)
                    if not fixed:
                        stat["badopt"] += 1
                        continue
                    opts, idx = fixed
                    stem = str(q.get("question", "")).strip()
                    if not stem:
                        stat["badopt"] += 1
                        continue
                    if len(stem) > MAX_STEM or max(len(o) for o in opts) > MAX_OPT:
                        stat["toolong"] += 1
                        continue
                    k = stem[:70]
                    if k in seen:
                        stat["dup"] += 1
                        continue
                    seen.add(k)
                    bucket.append({
                        "s": subj,
                        "q": stem,
                        "o": opts,
                        "a": idx,
                        "t": short_tip(q.get("explanation")),
                    })
                    stat["ok"] += 1

    os.makedirs(OUT_DIR, exist_ok=True)
    # 清掉旧文件，避免残留年级混进包里
    for f in os.listdir(OUT_DIR):
        if f.startswith("g") and f.endswith(".json"):
            os.remove(os.path.join(OUT_DIR, f))

    print("%-6s %-5s %-6s %-6s %-6s %-8s" % ("文件", "语文", "数学", "英语", "合计", "大小"))
    total = 0
    for (grade, term), qs in sorted(buckets.items()):
        cnt = {"chinese": 0, "math": 0, "english": 0}
        for q in qs:
            cnt[q["s"]] += 1
        name = "g%d%s.json" % (grade, term)
        payload = {"grade": grade, "term": term, "questions": qs}
        out = os.path.join(OUT_DIR, name)
        io.open(out, "w", encoding="utf-8").write(
            json.dumps(payload, ensure_ascii=False, separators=(",", ":"))
        )
        size = os.path.getsize(out) / 1024
        total += len(qs)
        print("%-6s %-5d %-6d %-6d %-6d %.0f KB" % (name, cnt["chinese"], cnt["math"], cnt["english"], len(qs), size))

    # 索引：年级学期 -> 题数，供 App 界面直接显示
    index = {}
    for (grade, term), qs in buckets.items():
        index["%d%s" % (grade, term)] = len(qs)
    io.open(os.path.join(OUT_DIR, "index.json"), "w", encoding="utf-8").write(
        json.dumps(index, ensure_ascii=False, separators=(",", ":"))
    )

    print("\n源文件: %d  读入: %d  非选择题: %d  选项不合格: %d  重复: %d  太长: %d  入库: %d"
          % (stat["files"], stat["read"], stat["nonchoice"], stat["badopt"], stat["dup"], stat["toolong"], stat["ok"]))
    print("总题数: %d" % total)
    print("输出目录总大小: %.1f MB" % (sum(os.path.getsize(os.path.join(OUT_DIR, f)) for f in os.listdir(OUT_DIR)) / 1048576))
    return 0


if __name__ == "__main__":
    sys.exit(main())
