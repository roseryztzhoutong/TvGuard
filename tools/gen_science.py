# -*- coding: utf-8 -*-
"""把 sci_data_*.py 的紧凑文本转成 assets/bank/science_gN.json，并做质量校验。"""
import io
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import sci_data_1_3
import sci_data_4_6

DATA = {}
DATA.update(sci_data_1_3.DATA)
DATA.update(sci_data_4_6.DATA)

OUT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                   "app", "src", "main", "assets", "bank")

errs = []
stats = {}

for grade in sorted(DATA):
    questions = []
    seen = set()
    for lineno, raw in enumerate(DATA[grade].strip().split("\n"), 1):
        line = raw.strip()
        if not line:
            continue
        parts = line.split("|")
        if len(parts) != 4:
            errs.append("G%d L%d 字段数=%d: %s" % (grade, lineno, len(parts), line[:30]))
            continue
        stem, opts_s, ans_s, tip = [p.strip() for p in parts]
        opts = [o.strip() for o in opts_s.split("~")]
        if len(opts) != 4:
            errs.append("G%d L%d 选项数=%d: %s" % (grade, lineno, len(opts), stem[:20]))
        if len(set(opts)) != len(opts):
            errs.append("G%d L%d 选项重复: %s" % (grade, lineno, stem[:20]))
        try:
            ans = int(ans_s)
        except ValueError:
            errs.append("G%d L%d 答案不是数字: %s" % (grade, lineno, ans_s))
            continue
        if not (0 <= ans < len(opts)):
            errs.append("G%d L%d 答案越界=%d: %s" % (grade, lineno, ans, stem[:20]))
        if stem in seen:
            errs.append("G%d L%d 题干重复: %s" % (grade, lineno, stem[:20]))
        seen.add(stem)
        if not tip:
            errs.append("G%d L%d 缺解析: %s" % (grade, lineno, stem[:20]))
        if len(stem) < 6:
            errs.append("G%d L%d 题干过短: %s" % (grade, lineno, stem))
        questions.append({"s": "science", "q": stem, "o": opts, "a": ans, "t": tip})

    obj = {"grade": grade, "term": "ab", "questions": questions}
    path = os.path.join(OUT, "science_g%d.json" % grade)
    with io.open(path, "w", encoding="utf-8", newline="") as f:
        f.write(json.dumps(obj, ensure_ascii=False, separators=(",", ":")))
    stats[grade] = len(questions)

print("生成完成：", stats, "合计", sum(stats.values()))
if errs:
    print("发现问题 %d 处：" % len(errs))
    for e in errs[:40]:
        print("  -", e)
    sys.exit(1)
print("校验通过：题量、选项、答案、解析、重复项均正常")
