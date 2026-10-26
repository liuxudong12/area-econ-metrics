"""
并发导出测试：验证「导出通道限流」是否真的挡住了第 3 个人。

【为什么要用 Barrier，而不是简单地起 3 个线程】
起 3 个线程的话，第 1 个线程可能已经在下载了、第 2 个还没开始，
它们根本不在同一个时间窗口里，也就测不出"并发争抢"。
threading.Barrier 让所有线程在这里对齐，然后同时出发 ——
这才是真正的"同时到达"。

【为什么用 http.client 而不是 requests / urllib】
1. urllib 在 Windows 下会读取系统代理设置（IE 代理），
   本机如果装了某些软件，请求会先绕一圈代理，凭空多 70ms 左右，
   测出来的"耗时"是被污染的。
2. requests 功能全但会让脚本多一个第三方依赖。
http.client 是标准库、直连、无代理，最适合做这种短平快的压测。

⚠️ 注意：并发测试的结论只在"同时到达"这个前提下成立。
   如果线程启动时间差超过请求本身耗时，就等于串行了。
"""
import http.client
import json
import sys
import threading
import time

HOST = "127.0.0.1"
PORT = 18080


def fire(results, idx, barrier, path):
    conn = http.client.HTTPConnection(HOST, PORT, timeout=180)
    try:
        barrier.wait()  # 所有线程在这里对齐，然后同时往下走
        t0 = time.time()
        conn.request("GET", path)
        resp = conn.getresponse()
        body = resp.read()
        cost = (time.time() - t0) * 1000
        ctype = resp.getheader("Content-Type") or ""
        is_file = "spreadsheet" in ctype
        note = ""
        if not is_file:
            # 被拒绝时返回的是 JSON，取出 message 看原因
            try:
                note = json.loads(body.decode("utf-8")).get("message", "")
            except Exception:
                note = body[:120].decode("utf-8", "ignore")
        results[idx] = {
            "idx": idx,
            "status": resp.status,
            "type": "文件" if is_file else "JSON(拒绝)",
            "bytes": len(body),
            "ms": round(cost, 0),
            "note": note,
        }
    except Exception as e:
        results[idx] = {"idx": idx, "status": -1, "type": "异常",
                        "bytes": 0, "ms": 0, "note": str(e)}
    finally:
        conn.close()


def main():
    n = int(sys.argv[1]) if len(sys.argv) > 1 else 3
    path = sys.argv[2] if len(sys.argv) > 2 else "/api/export/fact?regionCodePrefix=4301"

    print(f"同时发起 {n} 个导出请求：{path}")
    print("-" * 78)
    results = [None] * n
    barrier = threading.Barrier(n)
    threads = [threading.Thread(target=fire, args=(results, i, barrier, path)) for i in range(n)]

    t0 = time.time()
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    wall = (time.time() - t0) * 1000

    ok = 0
    rejected = 0
    for r in sorted(results, key=lambda x: x["idx"]):
        mark = "✅" if r["type"] == "文件" else "⛔"
        print(f"{mark} #{r['idx']}  HTTP {r['status']}  {r['type']:<10} "
              f"{r['bytes']:>9} B  {r['ms']:>7.0f} ms  {r['note'][:52]}")
        if r["type"] == "文件":
            ok += 1
        else:
            rejected += 1

    print("-" * 78)
    print(f"成功 {ok} 个 / 被拒 {rejected} 个 / 墙钟总耗时 {wall:.0f} ms")


if __name__ == "__main__":
    main()
