# -*- coding: utf-8 -*-
"""
生成「含错误行」的 Excel 测试数据。

【为什么需要这个文件】
此前几个测试文件都是"全对"的 —— 那种文件只能验证"导入成功"这条happy path。
但真实世界里，用户上传的表**大概率有错**，而且一错就是几千行。
今天要验证的正是这条路径：错 3000 行的时候，系统能不能让用户自己修好。

【造出来的东西】
  data/import/bad-import-10000.xlsx
    总行数 10000，其中 3000 行有错（30% 错误率）
    错误类型分布（模拟真实占比）：
      BAD_PERIOD_TYPE   周期类型填成中文「月」     ~89%   ← 最常见：用户不知道该填 M
      NEGATIVE_VALUE    指标值是负数              ~7%
      EMPTY_REQUIRED    区域编码空着              ~3%
      BAD_NUMBER        指标值填了 "abc"          ~1%    ← 会让 EasyExcel 转换失败

  data/import/bad-import-fixed.xlsx
    把上面那份的 3000 行错误全部改正后的版本，用于验证「改完重传能成功」。
    注意：它的 MD5 必须和错误版不同，否则会被幂等逻辑判定为同一个文件。

【怎么用】
  python tools/gen-bad-import-excel.py
"""

import os
import random
import subprocess
import sys
import hashlib

# ---------- 配置 ----------
MYSQL_EXE = r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
MYSQL_PWD = "Root@1234"
DB = "area_econ"

PROJECT_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_DIR = os.path.join(PROJECT_ROOT, "data", "import")
OUT_BAD = os.path.join(OUT_DIR, "bad-import-10000.xlsx")
OUT_FIXED = os.path.join(OUT_DIR, "bad-import-fixed.xlsx")

PERIODS = [("2027-%02d" % m, "M") for m in range(1, 7)]

TARGET_ROWS = 10000
ERROR_RATE = 0.30          # 30% 的行故意注错
SEED = 20261101            # 固定种子：每次生成的文件完全一样，MD5 可复现


def query(sql):
    env = dict(os.environ)
    env["MYSQL_PWD"] = MYSQL_PWD
    out = subprocess.run(
        [MYSQL_EXE, "-u", "root", "-D", DB,
         "--default-character-set=utf8mb4", "-N", "-B", "-e", sql],
        capture_output=True, env=env,
    )
    if out.returncode != 0:
        print("查询失败：" + out.stderr.decode("utf-8", errors="replace"), file=sys.stderr)
        sys.exit(1)
    text = out.stdout.decode("utf-8", errors="replace")
    rows = []
    for line in text.splitlines():
        line = line.strip()
        if not line:
            continue
        rows.append([c.strip() for c in line.split("\t")])
    return rows


def build_rows():
    """造出 10000 行的"正确数据"，返回 [(区域, 指标, 周期, 类型, 值), ...]"""
    regions = [r[0] for r in query(
        "SELECT region_code FROM dim_region WHERE deleted = 0 ORDER BY region_code")]
    indicators = [r[0] for r in query(
        "SELECT indicator_code FROM dim_indicator i WHERE i.deleted = 0 "
        "AND NOT EXISTS (SELECT 1 FROM dim_indicator c WHERE c.parent_code = i.indicator_code AND c.deleted = 0) "
        "ORDER BY i.indicator_code")]

    if not regions or not indicators:
        print("区域或指标为空，先跑初始化脚本", file=sys.stderr)
        sys.exit(1)

    random.seed(SEED)
    rows = []
    for period_code, period_type in PERIODS:
        for region in regions:
            for indicator in indicators:
                rows.append([region, indicator, period_code, period_type,
                             round(random.uniform(100, 99999), 4)])
                if len(rows) >= TARGET_ROWS:
                    return rows
    return rows


def pick_error_type(rnd):
    """
    按真实占比抽错误类型。

    为什么「周期类型填错了」占 89%？
      因为这是最反直觉的一列：用户看到「周期类型」四个字，第一反应是填「月」「年」，
      而不是 M / Y。我们要求填字母，但模板里那一格如果没写清楚，用户必然填中文。
      真实的导入功能里，这一类"格式约定类"错误永远占大头。
    """
    r = rnd.random()
    if r < 0.89:
        return "BAD_PERIOD_TYPE"
    if r < 0.96:
        return "NEGATIVE_VALUE"
    if r < 0.99:
        return "EMPTY_REQUIRED"
    return "BAD_NUMBER"


def main():
    print("=" * 62)
    print("生成「含错误行」的导入测试数据")
    print("=" * 62)

    good_rows = build_rows()
    print("基础数据：%d 行" % len(good_rows))

    rnd = random.Random(SEED + 1)

    # ---------- 1) 错误版 ----------
    bad_rows = []
    for row in good_rows:
        region, indicator, period, ptype, value = row
        if rnd.random() < ERROR_RATE:
            kind = pick_error_type(rnd)
            if kind == "BAD_PERIOD_TYPE":
                ptype = "月"                       # 中文 → 校验失败
            elif kind == "NEGATIVE_VALUE":
                value = -abs(value)                # 负数 → 校验失败
            elif kind == "EMPTY_REQUIRED":
                region = ""                        # 空 → 校验失败
            elif kind == "BAD_NUMBER":
                value = "abc"                      # 非数字 → EasyExcel 转换失败
        bad_rows.append([region, indicator, period, ptype, value])

    write_xlsx(OUT_BAD, bad_rows, "指标数据")
    summarize(OUT_BAD, bad_rows)

    # ---------- 2) 修正版 ----------
    # 就是原始的正确数据。它的 MD5 和错误版不同，所以会建一个新任务。
    write_xlsx(OUT_FIXED, good_rows, "指标数据")
    summarize(OUT_FIXED, good_rows)

    print("=" * 62)
    print("提示：两份文件的 MD5 必须不同，否则第二份会被幂等逻辑拦下来。")


def write_xlsx(path, rows, sheet_name):
    try:
        from openpyxl import Workbook
    except ImportError:
        print("缺少 openpyxl，请先 pip install openpyxl", file=sys.stderr)
        sys.exit(1)

    os.makedirs(os.path.dirname(path), exist_ok=True)
    wb = Workbook()
    ws = wb.active
    ws.title = sheet_name
    ws.append(["区域编码", "指标编码", "周期编码", "周期类型", "指标值"])
    for r in rows:
        ws.append(r)
    wb.save(path)


def summarize(path, rows):
    size = os.path.getsize(path)
    md5 = hashlib.md5(open(path, "rb").read()).hexdigest()
    print("-" * 62)
    print("已生成：%s" % path)
    print("行数  ：%d 行（不含表头）" % len(rows))
    print("大小  ：%.1f KB" % (size / 1024.0))
    print("MD5   ：%s" % md5)


if __name__ == "__main__":
    main()
