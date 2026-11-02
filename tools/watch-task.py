# -*- coding: utf-8 -*-
"""
观察异步导入任务的完整生命周期。

【为什么需要这个脚本】
重试实验要拿到的不是"最终成功/失败"，而是**中间每一步发生在第几秒**：
    0.000s  上传，拿到 taskId
    0.05s   PENDING
    0.31s   RUNNING
    0.62s   RETRY_WAIT     ← 第 1 次失败，记录 next_retry_time
    1.62s   RUNNING        ← 退避 1 秒后重跑（实测 1.0~1.5 秒，多出来的是扫描间隔）
    ...

没有这些时间戳，就没法证明"指数退避真的生效了"，只能说"它最后成功了"。

【为什么轮询间隔用 200ms 而不是 1 秒】
因为退避时间是 1 秒。如果用 1 秒轮询，就会漏掉短暂经过的 RETRY_WAIT 状态，
测出来的间隔误差最大到 1 秒 —— 跟被测的 1 秒一样大，数据就没意义了。
**测量工具的精度必须明显高于被测量的量级。** （压测时踩过）

【为什么用 http.client 不用 requests】
踩过的坑：Windows 上 urllib/requests 会读系统代理设置，
每次请求多 70ms 左右。测 1 秒级的间隔，70ms 误差已经占 7% 了。
http.client 是标准库里最底层的，不碰代理。

用法：
    # 上传一个文件并观察
    python tools/watch-task.py data/import/indicator-import-10000.xlsx

    # 只观察一个已存在的任务
    python tools/watch-task.py --task-id 3

    # 手动重试并观察
    python tools/watch-task.py --retry 3
"""

import http.client
import json
import os
import sys
import time
import uuid

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

HOST = "127.0.0.1"
PORT = int(os.environ.get("APP_PORT", "18080"))
POLL_INTERVAL = 0.2      # 轮询间隔（秒）
MAX_WAIT = 90            # 最长观察时间（秒）


def request(method, path, body=None, headers=None):
    """发一个 HTTP 请求，返回 (status, parsed_json_or_text)。"""
    conn = http.client.HTTPConnection(HOST, PORT, timeout=30)
    try:
        conn.request(method, path, body=body, headers=headers or {})
        resp = conn.getresponse()
        raw = resp.read()
        text = raw.decode("utf-8", errors="replace")
        try:
            return resp.status, json.loads(text)
        except json.JSONDecodeError:
            return resp.status, text
    finally:
        conn.close()


def upload(file_path):
    """用 multipart/form-data 上传文件，返回 taskId。"""
    boundary = "----FormBoundary" + uuid.uuid4().hex
    filename = os.path.basename(file_path)

    with open(file_path, "rb") as f:
        content = f.read()

    body = b""
    body += ("--" + boundary + "\r\n").encode()
    body += ('Content-Disposition: form-data; name="file"; filename="'
             + filename + '"\r\n').encode()
    body += b"Content-Type: application/octet-stream\r\n\r\n"
    body += content
    body += ("\r\n--" + boundary + "--\r\n").encode()

    headers = {"Content-Type": "multipart/form-data; boundary=" + boundary}
    status, data = request("POST", "/api/import/async", body=body, headers=headers)

    if status != 200 or not isinstance(data, dict):
        print("上传失败 HTTP %s: %s" % (status, data))
        sys.exit(1)
    if data.get("code") != 0:
        print("上传被拒: %s" % data.get("message"))
        sys.exit(1)

    d = data["data"]
    print("上传成功 size=%.1fKB taskId=%s status=%s message=%s"
          % (len(content) / 1024.0, d.get("taskId"), d.get("status"), d.get("message")))
    return d["taskId"]


def retry(task_id):
    status, data = request("POST", "/api/import/task/%s/retry" % task_id)
    print("手动重试 HTTP %s: code=%s message=%s"
          % (status, (data or {}).get("code"), (data or {}).get("message")))
    return status == 200 and isinstance(data, dict) and data.get("code") == 0


def watch(task_id):
    """轮询任务状态，每次变化打印一行带时间戳的记录。"""
    t0 = time.time()
    last_status = None
    last_key = None
    transitions = []

    print("\n--- 开始观察 taskId=%s（轮询间隔 %dms）---" % (task_id, POLL_INTERVAL * 1000))

    while time.time() - t0 < MAX_WAIT:
        status, data = request("GET", "/api/import/task/%s" % task_id)
        if status != 200 or not isinstance(data, dict) or data.get("code") != 0:
            time.sleep(POLL_INTERVAL)
            continue

        d = data["data"]
        st = d.get("status")
        rows = d.get("successRows")
        total = d.get("totalRows")
        msg = d.get("message") or ""

        # 状态变了，或者"成功行数"变了，都算一次有意义的变化
        key = (st, rows)
        if key != last_key:
            elapsed = time.time() - t0
            transitions.append((elapsed, st, rows, total, msg))
            print("%7.3fs  %-11s successRows=%-6s totalRows=%-6s %s"
                  % (elapsed, st, rows if rows is not None else "-",
                     total if total is not None else "-", msg[:90]))
            last_key = key
            last_status = st

        if st in ("SUCCESS", "FAILED"):
            break

        time.sleep(POLL_INTERVAL)

    total_elapsed = time.time() - t0
    print("--- 观察结束 用时 %.3fs 最终状态=%s ---\n" % (total_elapsed, last_status))

    if last_status not in ("SUCCESS", "FAILED"):
        print("⚠️ 超时未到终态（可能还在 RESPONSE_WAIT 或卡住了）")

    return transitions


def main():
    args = sys.argv[1:]
    if not args:
        print(__doc__)
        sys.exit(0)

    if args[0] == "--task-id":
        watch(args[1])
    elif args[0] == "--retry":
        tid = args[1]
        if retry(tid):
            watch(tid)
    else:
        tid = upload(args[0])
        watch(tid)


if __name__ == "__main__":
    main()
