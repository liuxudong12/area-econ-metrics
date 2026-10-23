# -*- coding: utf-8 -*-
"""
生成 Excel 导入的测试数据（Day 9）。

【为什么要单独写个造数据的脚本？】
因为"1 万行"必须是**真实的、不冲突的**数据，才能让性能测试有意义：
  - 如果造出来的 region_code / indicator_code 是假的，
    导入时业务校验会全部失败，测出来的"慢"就是假的。
  - 如果造出来的组合和表里已有的 489,600 行重复，
    唯一索引 uk_fact 会全部拒绝，测出来的"慢"也是假的。

【所以这里的设计是】
  150 个真实区域  ×  12 个真实叶子指标  ×  6 个"2027 年"月度周期  =  10,800 行

为什么选 2027 年？
  维度表 dim_period 里的周期只到 2026 年（Y 到 2026 / Q 到 2026Q4 / M 到 2026-12），
  所以 2027-01 ~ 2027-06 这个区间在事实表里**一条数据都没有** → 纯 INSERT，不会撞唯一索引。
  同时它也不会污染前面几天测出来的 2024-2026 统计结果（趋势接口只查 2024~2026）。

【怎么用】
  python tools/gen-import-excel.py
输出：
  data/import/indicator-import-10000.xlsx
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
OUT_FILE = os.path.join(OUT_DIR, "indicator-import-10000.xlsx")

# 要生成的周期：2027 年 1~6 月。period_type = M 表示月度。
PERIODS = [("2027-%02d" % m, "M") for m in range(1, 7)]


def query(sql):
    """调 mysql 客户端跑一条查询，返回二维列表（-N 去表头，-B 制表符分隔）。"""
    env = dict(os.environ)
    env["MYSQL_PWD"] = MYSQL_PWD
    out = subprocess.run(
        [MYSQL_EXE, "-u", "root", "-D", DB,
         "--default-character-set=utf8mb4", "-N", "-B", "-e", sql],
        capture_output=True, env=env,
    )
    if out.returncode != 0:
        # mysql 会把错误写到 stderr，但 Windows 下可能是 GBK 编码
        msg = out.stderr.decode("utf-8", errors="replace")
        print("查询失败：" + msg, file=sys.stderr)
        sys.exit(1)
    text = out.stdout.decode("utf-8", errors="replace")
    # ⚠️ Windows 上 mysql.exe 输出的是 CRLF，直接用 split("\n") 会让每行末尾多一个 \r，
    #    然后 region_code / indicator_code 就变成 "1-1-1\r" —— 写进 Excel 就是脏数据。
    #    用 splitlines() + 逐列 strip() 才能干净。
    rows = []
    for line in text.splitlines():
        line = line.strip()
        if not line:
            continue
        rows.append([cell.strip() for cell in line.split("\t")])
    return rows


def main():
    print("=" * 62)
    print("生成 Excel 导入测试数据")
    print("=" * 62)

    # 1) 取真实区域编码（省/市/区三级都算，共约 150 个）
    regions = [r[0] for r in query(
        "SELECT region_code FROM dim_region WHERE deleted = 0 ORDER BY region_code")]
    # 2) 取真实叶子指标（parent_code 不为空，但本身不能还有子节点）
    indicators = [r[0] for r in query(
        "SELECT indicator_code FROM dim_indicator i WHERE i.deleted = 0 "
        "AND NOT EXISTS (SELECT 1 FROM dim_indicator c WHERE c.parent_code = i.indicator_code AND c.deleted = 0) "
        "ORDER BY i.indicator_code")]

    print("区域数（叶子）：%d" % len(regions))
    print("叶子指标数    ：%d  %s" % (len(indicators), indicators))
    print("周期数        ：%d  %s" % (len(PERIODS), [p[0] for p in PERIODS]))

    total = len(regions) * len(indicators) * len(PERIODS)
    print("预计总行数    ：%d" % total)

    if len(regions) == 0 or len(indicators) == 0:
        print("区域或指标为空，先确认数据库里有维度数据。", file=sys.stderr)
        sys.exit(1)

    # 3) 写 xlsx
    try:
        from openpyxl import Workbook
    except ImportError:
        print("缺少 openpyxl，请先 pip install openpyxl", file=sys.stderr)
        sys.exit(1)

    os.makedirs(OUT_DIR, exist_ok=True)
    wb = Workbook()
    ws = wb.active
    ws.title = "指标数据"
    ws.append(["区域编码", "指标编码", "周期编码", "周期类型", "指标值"])

    random.seed(20261023)  # 固定种子，保证每次生成的内容完全一样（MD5 才能复现）
    n = 0
    for period_code, period_type in PERIODS:
        for region in regions:
            for indicator in indicators:
                # 数值范围故意做得像真实经济指标：100 ~ 99999，保留 4 位小数
                value = round(random.uniform(100, 99999), 4)
                ws.append([region, indicator, period_code, period_type, value])
                n += 1

    wb.save(OUT_FILE)

    size = os.path.getsize(OUT_FILE)
    md5 = hashlib.md5(open(OUT_FILE, "rb").read()).hexdigest()

    print("-" * 62)
    print("已生成：%s" % OUT_FILE)
    print("行数  ：%d 行（不含表头）" % n)
    print("大小  ：%.1f KB" % (size / 1024.0))
    print("MD5   ：%s" % md5)
    print("-" * 62)
    print("把上面这个 MD5 记下来 —— 导入接口的幂等键就是它。")


if __name__ == "__main__":
    main()
