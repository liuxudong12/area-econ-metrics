#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
并发压测小工具 —— 用来实测「缓存击穿」防护有没有生效。

【为什么不用 xargs -P 50 跑 50 个 curl？】
因为那样每个请求都要 fork 一个新进程（Windows 上还要启动一个 bash），
进程创建本身就要一两百毫秒，50 个请求实际上是**排队发出去的**，
根本形不成"同时打到同一个 key"的效果 —— 第一轮实测就是这么翻车的：
墙钟 8.4 秒、抢锁失败 0 次，说明完全没有并发。

【为什么不用 urllib，而用更底层的 http.client？】
urllib 每次请求都会做一次"代理探测"（读 Windows 注册表里的系统代理设置），
在本机实测每个请求凭空多出 ~70ms，会把缓存的真实耗时完全盖住。
http.client 直连、不查代理，测出来的才是服务端自己的耗时。

【这个脚本做对了两件事】
1. 用一个线程池 + Barrier，让 N 个线程**卡在同一行代码上**，
   等所有人都到齐了再一起放行 —— 这才是真正的"同时并发"。
2. 用同一次计时窗口统计墙钟、平均、P50、最大耗时。

用法：
    python cache-stampede-test.py [并发数]
"""
import http.client
import sys
import threading
import time
import urllib.parse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor

HOST, PORT = "127.0.0.1", 18080
PATH = "/api/stat/trend"
QUERY = urllib.parse.urlencode({"category": "工业", "periodType": "Y",
                               "periodCodes": "2024,2025,2026"})
URL_PATH = PATH + "?" + QUERY

N = int(sys.argv[1]) if len(sys.argv) > 1 else 50

barrier = threading.Barrier(N)
lock = threading.Lock()
latencies = []
codes = []


def one(_):
    # 所有线程在这里集合，等到齐了同时放行 —— 这是"同时打"的关键
    barrier.wait()
    t0 = time.perf_counter()
    conn = http.client.HTTPConnection(HOST, PORT, timeout=30)
    try:
        conn.request("GET", URL_PATH)
        resp = conn.getresponse()
        resp.read()
        code = resp.status
    except Exception as e:  # noqa: BLE001  压测脚本，任何异常都只记录不中断
        code = type(e).__name__
    finally:
        conn.close()
    cost = (time.perf_counter() - t0) * 1000
    with lock:
        latencies.append(cost)
        codes.append(str(code))


t_start = time.perf_counter()
with ThreadPoolExecutor(max_workers=N) as pool:
    list(pool.map(one, range(N)))
wall = (time.perf_counter() - t_start) * 1000

latencies.sort()
avg = sum(latencies) / len(latencies)
p50 = latencies[len(latencies) // 2]
print("并发 {} | 墙钟 {:.0f} ms | 平均 {:.1f} ms | P50 {:.1f} ms | 最大 {:.1f} ms".format(
    N, wall, avg, p50, latencies[-1]))
print("状态分布: {}".format(dict(Counter(codes))))
