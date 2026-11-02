#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
数据权限一键验证脚本。

用法：
    python tools/datascope-check.py                  # 默认 127.0.0.1:18080
    python tools/datascope-check.py 18080            # 指定端口

它做三件事：
    ① 拿接口返回，和"数据库基线"逐行对账（不看日志，只看结果）
    ② 跑越权场景（改 URL 参数想绕过）
    ③ 打印改写开销和权限标注清单

为什么要有这个脚本 ——
    手工 curl 五个用户、三个接口、十几次，很容易漏掉一个；
    而"漏掉一个"正是数据权限最典型的失败方式。
    把校验固化成一个脚本，以后每次改完权限相关代码跑一遍就行。

⚠️ 它不启动服务，也不改数据。只读。
"""

import http.client
import json
import sys

HOST = "127.0.0.1"
PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 18080

# ---------------------------------------------------------------------------
# 基线：和 sql/09-数据权限.sql 末尾那段对照 SQL 算出来的一致。
# 如果数据变了（比如又导入了几万行），这里也要跟着改 —— 这是硬编码的代价，
# 换来的好处是"期望值明确"，不会出现"脚本自己跟自己比，怎么都通过"。
# ---------------------------------------------------------------------------
EXPECTED = {
    1: {"name": "admin（全省）",     "regions": 150, "total_rows": 489600, "prefix": None},
    2: {"name": "changsha（长沙）",  "regions": 11,  "total_rows": 35904,  "prefix": "4301"},
    3: {"name": "yuelu（第2区）",    "regions": 1,   "total_rows": 3264,   "prefix": "430102"},
    4: {"name": "zhangjiajie（张家界）", "regions": 11, "total_rows": 35904, "prefix": "4308"},
    5: {"name": "viewer（未配辖区）", "regions": 0,   "total_rows": 0,      "prefix": None},
}

ok_count = 0
fail_count = 0


def call(path, uid=None):
    """发一个请求。uid=None 表示不带身份头。"""
    conn = http.client.HTTPConnection(HOST, PORT, timeout=30)
    headers = {} if uid is None else {"X-User-Id": str(uid)}
    conn.request("GET", path, headers=headers)
    body = conn.getresponse().read()
    conn.close()
    try:
        return json.loads(body)
    except json.JSONDecodeError:
        return {"_raw": body[:200]}


def check(label, actual, expected):
    global ok_count, fail_count
    if actual == expected:
        ok_count += 1
        print("  [OK]   %-34s = %s" % (label, actual))
    else:
        fail_count += 1
        print("  [FAIL] %-34s 期望 %s，实际 %s" % (label, expected, actual))


def section(title):
    print("\n" + "=" * 68)
    print(title)
    print("=" * 68)


# ---------------------------------------------------------------------------
section(" E1  没有身份")
r = call("/api/auth/me")
check("code（应为 401）", r.get("code"), 401)
print("       message: %s" % r.get("message", "")[:60])

# ---------------------------------------------------------------------------
section(" E2  五个用户看同一个统计接口")
print("  GET /api/stat/category-summary?category=工业&periodType=Y&periodCodes=2024,2025,2026")
for uid, exp in EXPECTED.items():
    r = call("/api/stat/category-summary?category=%E5%B7%A5%E4%B8%9A"
             "&periodType=Y&periodCodes=2024,2025,2026", uid)
    print("\n  uid=%d  %s" % (uid, exp["name"]))
    if r.get("code") != 0:
        fail_count += 1
        print("  [FAIL] 接口报错：%s" % r.get("message"))
        continue
    rows = r["data"]
    check("区域数", len(rows), exp["regions"])
    if exp["prefix"]:
        bad = [x["regionCode"] for x in rows if not str(x["regionCode"]).startswith(exp["prefix"])]
        check("越权区域数（应为 0）", len(bad), 0)

# ---------------------------------------------------------------------------
section(" E3  分页 total（验证 数据权限 在 分页 之前）")
for uid, exp in EXPECTED.items():
    r = call("/api/fact/page?page=1&size=5", uid)
    if r.get("code") != 0:
        fail_count += 1
        print("  [FAIL] uid=%d 报错：%s" % (uid, r.get("message")))
        continue
    check("uid=%d total" % uid, r["data"]["total"], exp["total_rows"])

# ---------------------------------------------------------------------------
section(" E4  参数越权：长沙用户传 4302（益阳）")
r = call("/api/export/fact?regionCodePrefix=4302", 2)
# 期望：不是 403，而是"没有符合条件的数据" —— 权限条件 AND 上去之后交集为空。
# 为什么故意不返回 403：不告诉对方"4302 到底有没有数据"，防止探测。
is_rejected = r.get("code") != 0 and "没有符合条件的数据" in str(r.get("message", ""))
check("被拒且话术为「没有符合条件的数据」", is_rejected, True)
print("       实际返回：code=%s message=%s" % (r.get("code"), str(r.get("message"))[:50]))

# ---------------------------------------------------------------------------
section(" E5  子查询（TOP N 窗口函数）的主语必须是本辖区")
for uid in (1, 2):
    r = call("/api/stat/top-regions?category=%E5%B7%A5%E4%B8%9A"
             "&periodType=Y&periodCodes=2024,2025,2026&topN=3", uid)
    if r.get("code") != 0:
        fail_count += 1
        print("  [FAIL] uid=%d 报错：%s" % (uid, r.get("message")))
        continue
    codes = [x["regionCode"] for x in r["data"]]
    prefix = EXPECTED[uid]["prefix"]
    bad = [c for c in codes if prefix and not str(c).startswith(prefix)]
    check("uid=%d 前 3 名的编码 %s" % (uid, codes), len(bad), 0)
    check("uid=%d 名次从 1 开始" % uid, [x["rankNo"] for x in r["data"]], list(range(1, len(codes) + 1)))

# ---------------------------------------------------------------------------
section(" E8  改写开销 & E9 权限标注清单")
r = call("/api/auth/data-scope-stats", 1)
print("  %s" % r.get("data"))
print("  ↑ 这次只跑了几十次，数字是"冷启动"的（JIT 还没编译热点代码）。")
print("    想看稳态，连续打 500 次以上再看，会降到 0.02~0.06ms/条（见手册实验 E8）。")

r = call("/api/auth/data-scope")
if r.get("code") == 0:
    items = r["data"]
    protected = [i for i in items if i["verdict"] == "PROTECTED"]
    suspect = [i for i in items if i["verdict"] == "SUSPECT"]
    write = [i for i in items if i["verdict"] == "WRITE"]
    print("  PROTECTED=%d  SUSPECT=%d  WRITE=%d" % (len(protected), len(suspect), len(write)))
    for i in suspect:
        print("    ⚠ SUSPECT: %s.%s" % (i["mapper"], i["method"]))

# ---------------------------------------------------------------------------
section(" 汇总")
print("  通过 %d 项，失败 %d 项" % (ok_count, fail_count))
if fail_count:
    print("\n  ⚠️ 有失败项。先确认：")
    print("     · 三处'故意写错'的实验配置是否都已还原（见手册第十三节收尾检查）")
    print("     · fact_indicator_value 的行数是否还是 489,600（跑过导入的话基线会变）")
    print("     · Redis 里是否有旧格式的缓存 key（redis-cli flushdb 后重试）")
    sys.exit(1)
print("  全部通过 ✓")
