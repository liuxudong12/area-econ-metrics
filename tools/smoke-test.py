#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
一键冒烟测试 —— 验证服务是否真的能用（README 里引用的就是它）。

为什么不用 curl：
    Windows 上 curl 每次起进程 + 建连接要 50~70ms，会把接口耗时淹掉。
    这里复用一条 keep-alive 连接，测出来的才是接口本身的耗时。

用法：
    python tools/smoke-test.py                       # 默认 localhost:18080
    python tools/smoke-test.py --host 127.0.0.1 --port 8080

退出码：全部通过 0，有失败 1（方便挂到 CI）
"""
import argparse
import http.client
import json
import sys
import time

# Windows 控制台默认 GBK，中文会炸。这一行必须在任何 print 之前。
try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

PASS, FAIL = "PASS", "FAIL"
results = []


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=18080)
    args = ap.parse_args()

    conn = http.client.HTTPConnection(args.host, args.port, timeout=120)

    def call(path, uid=None, method="GET", expect_binary=False, raw=False):
        headers = {}
        if uid is not None:
            headers["X-User-Id"] = str(uid)
        t0 = time.perf_counter()
        conn.request(method, path, headers=headers)
        resp = conn.getresponse()
        body = resp.read()
        cost = (time.perf_counter() - t0) * 1000
        if expect_binary:
            return resp.status, body, cost
        if raw:
            return resp.status, body.decode("utf-8", "replace"), cost
        try:
            return resp.status, json.loads(body), cost
        except Exception:
            return resp.status, {"_raw": body[:200].decode("utf-8", "replace")}, cost

    def check(name, path, uid=None, fn=None, expect_binary=False, raw=False):
        try:
            status, data, cost = call(path, uid, expect_binary=expect_binary, raw=raw)
        except Exception as e:
            results.append((FAIL, name, "异常：%s" % e, 0))
            print("  [FAIL] %-42s 异常：%s" % (name, e))
            return None
        ok, detail = True, ""
        if status != 200:
            ok, detail = False, "HTTP %s" % status
        elif fn is not None:
            try:
                ok, detail = fn(data)
            except Exception as e:
                ok, detail = False, "断言异常：%s" % e
        results.append((PASS if ok else FAIL, name, detail, cost))
        print("  [%s] %-42s %7.1fms  %s" % ("PASS" if ok else "FAIL", name, cost, detail))
        return data

    print("=" * 78)
    print(" 冒烟测试  %s:%d" % (args.host, args.port))
    print("=" * 78)

    # ---------------------------------------------------------------- 基础
    print("\n[1/6] 服务存活")
    check("GET /ping", "/ping", raw=True,
          fn=lambda d: (d.strip() == "pong", "返回 %r（纯文本，不是 JSON）" % d.strip()))

    # ---------------------------------------------------------------- 身份
    print("\n[2/6] 身份与数据权限上下文")
    check("GET /api/auth/me  (uid=2 changsha)", "/api/auth/me", uid=2,
          fn=lambda d: (d["data"]["username"] == "changsha",
                        "%s / %s / %s" % (d["data"]["username"],
                                          d["data"]["dataScope"],
                                          d["data"]["regionCode"])))
    check("GET /api/auth/me  (无请求头 → 应拒绝)", "/api/auth/me",
          fn=lambda d: (d.get("code") != 0, "code=%s %s" % (d.get("code"), d.get("message"))))
    check("GET /api/auth/users", "/api/auth/users", uid=1,
          fn=lambda d: (len(d["data"]) == 5, "%d 个用户" % len(d["data"])))

    # ---------------------------------------------------------------- 统计
    print("\n[3/6] 统计四接口 + 权限隔离")
    q = "?category=%E5%B7%A5%E4%B8%9A&periodType=Y&periodCodes=2024,2025,2026"

    def cat_fn(d):
        return d["code"] == 0 and len(d["data"]) >= 0, "%d 个区域" % len(d["data"]) if d["code"] == 0 else d["message"]

    admin = check("GET /api/stat/category-summary  (admin)", "/api/stat/category-summary" + q, uid=1, fn=cat_fn)
    cs = check("GET /api/stat/category-summary  (changsha)", "/api/stat/category-summary" + q, uid=2, fn=cat_fn)
    viewer = check("GET /api/stat/category-summary  (viewer 辖区为空)", "/api/stat/category-summary" + q, uid=5, fn=cat_fn)

    if admin and cs and viewer:
        a = len(admin.get("data") or [])
        c = len(cs.get("data") or [])
        v = len(viewer.get("data") or [])
        ok = a > c >= v
        results.append((PASS if ok else FAIL, "权限隔离断言 admin>changsha>=viewer",
                        "admin=%d changsha=%d viewer=%d" % (a, c, v), 0))
        print("  [%s] %-42s %s" % ("PASS" if ok else "FAIL", "权限隔离断言",
                                   "admin=%d > changsha=%d >= viewer=%d" % (a, c, v)))

    check("GET /api/stat/trend", "/api/stat/trend" + q, uid=2,
          fn=lambda d: (d["code"] == 0, "%d 个周期" % len(d["data"])))
    check("GET /api/stat/top-regions", "/api/stat/top-regions" + q + "&topN=3", uid=2,
          fn=lambda d: (d["code"] == 0 and len(d["data"]) <= 3,
                        "%d 条" % len(d["data"])))
    check("GET /api/stat/yoy", "/api/stat/yoy?category=%E5%B7%A5%E4%B8%9A&periodType=Y&basePeriod=2024&currentPeriod=2025", uid=2,
          fn=lambda d: (d["code"] == 0, "%d 条" % len(d["data"])))
    check("GET /api/stat/cache-stats", "/api/stat/cache-stats", uid=1,
          fn=lambda d: (d["code"] == 0, str(d["data"])[:60]))

    # ---------------------------------------------------------------- 明细
    print("\n[4/6] 明细分页（分页 + 权限顺序验证点）")
    admin_page = check("GET /api/fact/page  (admin)", "/api/fact/page?page=1&size=5", uid=1,
                       fn=lambda d: (d["code"] == 0,
                                     "total=%s" % d["data"]["total"]))
    cs_page = check("GET /api/fact/page  (changsha)", "/api/fact/page?page=1&size=5", uid=2,
                    fn=lambda d: (d["code"] == 0,
                                  "total=%s" % d["data"]["total"]))
    if admin_page and cs_page:
        at = admin_page["data"]["total"]
        ct = cs_page["data"]["total"]
        ok = at > ct > 0
        results.append((PASS if ok else FAIL, "分页 total 已按权限收窄",
                        "admin=%d changsha=%d" % (at, ct), 0))
        print("  [%s] %-42s admin=%d changsha=%d" % ("PASS" if ok else "FAIL",
                                                     "分页 total 已按权限收窄", at, ct))

    # ---------------------------------------------------------------- 导出
    print("\n[5/6] Excel 导出（游标 + 流式）")

    def xlsx_ok(d):
        # xlsx 本质是 zip，前两个字节固定是 PK
        return d[:2] == b"PK", "%d 字节, 魔数=%r" % (len(d), d[:2])

    check("GET /api/export/fact  (changsha)", "/api/export/fact", uid=2,
          fn=xlsx_ok, expect_binary=True)
    check("GET /api/export/stat?kind=category", "/api/export/stat?kind=category" + "&" + q[1:], uid=1,
          fn=xlsx_ok, expect_binary=True)
    # ⚠️ 注意这三条都带了 uid：身份拦截器对 /api/** 全量生效，
    #    不带 X-User-Id 一律 401。README 里所有 curl 示例都必须带这个头。
    check("GET /api/import/template", "/api/import/template", uid=1,
          fn=xlsx_ok, expect_binary=True)

    # ---------------------------------------------------------------- 字典
    print("\n[6/6] 字典与区域")
    check("GET /api/indicator/categories", "/api/indicator/categories", uid=1,
          fn=lambda d: (d["code"] == 0, "%d 个分类" % len(d["data"])))
    check("GET /api/region/list", "/api/region/list", uid=1,
          fn=lambda d: (d["code"] == 0, "%d 个区域" % len(d["data"])))

    # ---------------------------------------------------------------- 汇总
    total = len(results)
    failed = [r for r in results if r[0] == FAIL]
    print("\n" + "=" * 78)
    print(" 结果：%d 项，通过 %d，失败 %d" % (total, total - len(failed), len(failed)))
    if failed:
        for r in failed:
            print("   ✗ %s —— %s" % (r[1], r[2]))
        print("=" * 78)
        return 1
    print(" 全部通过 ✓")
    print("=" * 78)
    return 0


if __name__ == "__main__":
    sys.exit(main())
