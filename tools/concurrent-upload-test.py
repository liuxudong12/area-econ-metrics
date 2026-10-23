# -*- coding: utf-8 -*-
"""
并发上传同一个文件，验证"异步导入的幂等"。

【要验证什么】
  10 个线程同时上传同一份 Excel，期望结果是：
    - 数据库 async_task 表里只多出 **1 条** 任务记录
    - 业务数据只导入 **1 遍**（不会出现 10 倍的行数）
    - 10 个响应拿到的 taskId **全部相同**

【为什么用 http.client 而不是 requests / urllib】
  Day 8 压测缓存击穿时踩过这个坑：Windows 上 urllib / requests 默认会读系统代理设置，
  每个请求额外多 ~70ms 的代理探测。10 个并发请求，客户端开销比被测逻辑还大。
  http.client.HTTPConnection 直连 localhost，不经过任何代理探测，数字才干净。

【怎么用】
  python tools/concurrent-upload-test.py <文件路径> <并发数> [URL]
"""

import http.client
import sys
import threading
import time
import uuid
from collections import Counter


def build_multipart(file_path, field_name="file"):
    """手工构造 multipart/form-data 请求体（不依赖 requests）。"""
    with open(file_path, "rb") as f:
        content = f.read()

    boundary = "----WorkBuddyBoundary" + uuid.uuid4().hex
    filename = file_path.replace("\\", "/").split("/")[-1]

    body = b""
    body += ("--" + boundary + "\r\n").encode()
    body += ('Content-Disposition: form-data; name="' + field_name + '"; filename="'
             + filename + '"\r\n').encode("utf-8")
    body += b"Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet\r\n\r\n"
    body += content
    body += ("\r\n--" + boundary + "--\r\n").encode()

    return body, "multipart/form-data; boundary=" + boundary


def one_request(url_path, host, port, body, content_type, barrier, results, idx):
    # Barrier 保证所有线程"站在同一条起跑线上"同时发请求。
    # 如果不加，线程是串行创建的，第一个请求可能已经跑完了，
    # 后面的请求就从"并发"变成了"串行查缓存命中"——测试结果完全失真。
    barrier.wait()
    start = time.perf_counter()
    try:
        conn = http.client.HTTPConnection(host, port, timeout=120)
        conn.request("POST", url_path, body=body, headers={"Content-Type": content_type})
        resp = conn.getresponse()
        data = resp.read().decode("utf-8", errors="replace")
        cost = (time.perf_counter() - start) * 1000
        conn.close()
        results[idx] = (resp.status, cost, data)
    except Exception as e:
        results[idx] = (0, (time.perf_counter() - start) * 1000, "EXCEPTION: " + str(e))


def main():
    if len(sys.argv) < 3:
        print("用法: python tools/concurrent-upload-test.py <文件> <并发数> [URL]")
        sys.exit(1)

    file_path = sys.argv[1]
    concurrency = int(sys.argv[2])
    url = sys.argv[3] if len(sys.argv) > 3 else "http://localhost:18080/api/import/async"

    # 从 URL 里拆出 host / port / path
    no_scheme = url.split("://", 1)[1]
    host_port, path = no_scheme.split("/", 1)
    path = "/" + path
    host = host_port.split(":")[0]
    port = int(host_port.split(":")[1]) if ":" in host_port else 80

    body, content_type = build_multipart(file_path)

    print("=" * 66)
    print("并发上传测试：%d 个线程同时上传同一个文件" % concurrency)
    print("文件：%s（%.1f KB）" % (file_path, len(body) / 1024.0))
    print("=" * 66)

    barrier = threading.Barrier(concurrency)
    results = [None] * concurrency
    threads = [
        threading.Thread(target=one_request,
                         args=(path, host, port, body, content_type, barrier, results, i))
        for i in range(concurrency)
    ]

    wall_start = time.perf_counter()
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    wall = (time.perf_counter() - wall_start) * 1000

    costs = [r[1] for r in results if r]
    codes = Counter(r[0] for r in results if r)
    print("\n墙钟总耗时：%.0f ms" % wall)
    print("响应耗时：平均 %.0f ms / 最小 %.0f ms / 最大 %.0f ms"
          % (sum(costs) / len(costs), min(costs), max(costs)))
    print("HTTP 状态码分布：%s" % dict(codes))

    # 把每个响应里的 taskId / status 抠出来
    task_ids = []
    statuses = []
    for r in results:
        if not r:
            continue
        text = r[2]
        tid = _extract(text, '"taskId":')
        st = _extract(text, '"status":"')
        if tid is not None:
            task_ids.append(tid)
        if st is not None:
            statuses.append(st)

    print("\n返回的 taskId 集合：%s" % sorted(set(task_ids)))
    print("返回的 status 分布：%s" % dict(Counter(statuses)))

    if len(set(task_ids)) == 1:
        print("\n✓ 所有请求拿到同一个 taskId —— 幂等生效")
    elif len(set(task_ids)) > 1:
        print("\n✗ 出现了多个 taskId —— 幂等失效！")
    else:
        print("\n! 没有解析到 taskId，看下面的原始响应")

    # 有异常的，打出来
    for r in results:
        if r and (r[0] != 200 or "EXCEPTION" in r[2]):
            print("\n异常响应：HTTP %s  %s" % (r[0], r[2][:300]))


def _extract(text, prefix):
    """从 JSON 字符串里粗暴地抠一个值出来（不引 json 库是为了容忍非 JSON 的错误响应）。"""
    i = text.find(prefix)
    if i < 0:
        return None
    j = i + len(prefix)
    end_chars = '",}' if prefix.endswith('"') else ',}'
    k = j
    while k < len(text) and text[k] not in end_chars:
        k += 1
    val = text[j:k]
    if val == "null" or val == "":
        return None
    try:
        return int(val)
    except ValueError:
        return val


if __name__ == "__main__":
    main()
