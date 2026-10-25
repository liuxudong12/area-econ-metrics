# -*- coding: utf-8 -*-
"""
生成「用户改完之后」的两份文件，用来验证导入闭环（Day 11 实验 E）。

【要验证的问题】
用户下载错误报告后，改完重传，到底能不能成功？
而且——如果他下的是"只有错误行"的报告，会发生什么？

【造两份文件】

  E1 = fixed-from-error-scope.xlsx
       把 scope=error 下载到的报告（2989 行）里的错误全部改对，
       保留【Excel行号 / 错误类型 / 错误原因】这三列不动（用户不会去删它们）。
       → 预期：导入"成功"，但库里只有 2989 行。
         **另外 7011 行正确的数据，从来没进过库，用户却以为全好了。**

  E2 = fixed-from-all-scope.xlsx
       把 scope=all 下载到的报告（10000 行）里的错误全部改对，
       前 3 列同样留着。
       → 预期：导入成功，库里正好 10000 行。

这两份文件的对比，就是"为什么必须提供 scope=all"的硬证据。

【怎么用】
  先把两份报告下载到 data/import/ 下：
    curl -o data/import/_err_report.xlsx ".../task/1/errors?scope=error"
    curl -o data/import/_err_all.xlsx    ".../task/1/errors?scope=all"
  再跑本脚本。
"""

import os
import sys
import hashlib

PROJECT_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
IN_DIR = os.path.join(PROJECT_ROOT, "data", "import")
IN_ERROR = os.path.join(IN_DIR, "_err_report.xlsx")
IN_ALL = os.path.join(IN_DIR, "_err_all.xlsx")
IN_FIXED = os.path.join(IN_DIR, "bad-import-fixed.xlsx")
OUT_E1 = os.path.join(IN_DIR, "fixed-from-error-scope.xlsx")
OUT_E2 = os.path.join(IN_DIR, "fixed-from-all-scope.xlsx")


def fix_row(row):
    """
    模拟"用户手工改错"：把一行错误数据改成合法数据。

    真实用户看到的是错误原因那一列，比如「周期类型只能是 Y / Q / M，实际填的是「月」」，
    然后他就在 Excel 里把「月」改成「M」。这里按错误类型做同样的修正。
    """
    row_num, err_type, err_msg, region, indicator, period, ptype, value = row

    if err_type == "周期类型非法":
        ptype = "M"
    elif err_type == "指标值为负":
        try:
            value = abs(float(value))
        except (TypeError, ValueError):
            value = 1000.0
    elif err_type == "必填项为空":
        # 区域编码空了。真实用户会填回正确编码；这里统一填 430000（湖南省）。
        # 注意这可能会和已有的 (430000, 指标, 周期) 组合冲突 ——
        # 但那正好能验证 upsert 的"有则覆盖"，不会报错。
        if not region:
            region = "430000"
        if not ptype:
            ptype = "M"
        if value in (None, ""):
            value = 1000.0
    elif err_type == "数值格式错误":
        value = 1000.0

    return [row_num, err_type, err_msg, region, indicator, period, ptype, value]


def main():
    try:
        from openpyxl import Workbook, load_workbook
    except ImportError:
        print("缺少 openpyxl，请先 pip install openpyxl", file=sys.stderr)
        sys.exit(1)

    # ---------- E1：从 scope=error 的报告改 ----------
    if not os.path.exists(IN_ERROR):
        print("找不到 %s，请先下载 scope=error 的报告" % IN_ERROR, file=sys.stderr)
        sys.exit(1)
    wb = load_workbook(IN_ERROR)
    ws = wb.active
    rows = [[c.value for c in r] for r in ws.iter_rows(min_row=2)]
    fixed1 = [fix_row(r) for r in rows]

    wb1 = Workbook()
    s1 = wb1.active
    s1.title = "错误明细"
    s1.append(["Excel行号", "错误类型", "错误原因", "区域编码", "指标编码", "周期编码", "周期类型", "指标值"])
    for r in fixed1:
        s1.append(r)
    wb1.save(OUT_E1)
    print_row("E1（改自 scope=error）", OUT_E1, len(fixed1))

    # ---------- E2：全量 10000 行 + 3 个额外列 ----------
    if not os.path.exists(IN_FIXED):
        print("找不到 %s" % IN_FIXED, file=sys.stderr)
        sys.exit(1)
    wb2 = load_workbook(IN_FIXED)
    ws2 = wb2.active
    good = [[c.value for c in r] for r in ws2.iter_rows(min_row=2)]

    wb3 = Workbook()
    s3 = wb3.active
    s3.title = "全部数据"
    s3.append(["Excel行号", "错误类型", "错误原因", "区域编码", "指标编码", "周期编码", "周期类型", "指标值"])
    for i, r in enumerate(good, start=2):
        # 前三列留空，模拟"这个文件是 scope=all 导出、用户把错误都改好了"的最终状态。
        # 错误原因列留着旧值也无所谓 —— 导入时它会被忽略（表头对不上 @ExcelProperty）。
        s3.append([i, None, None] + list(r))
    wb3.save(OUT_E2)
    print_row("E2（全量 10000 行 + 额外 3 列）", OUT_E2, len(good))

    print("=" * 62)
    print("两份文件的 MD5 必须不同，否则第二次上传会被幂等逻辑拦下：")
    print("  E1 =", md5(OUT_E1))
    print("  E2 =", md5(OUT_E2))


def print_row(title, path, n):
    print("-" * 62)
    print(title)
    print("  路径：%s" % path)
    print("  行数：%d" % n)
    print("  大小：%.1f KB" % (os.path.getsize(path) / 1024.0))
    print("  MD5 ：%s" % md5(path))


def md5(path):
    return hashlib.md5(open(path, "rb").read()).hexdigest()


if __name__ == "__main__":
    main()
