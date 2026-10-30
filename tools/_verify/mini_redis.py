"""最小 RESP (Redis 协议) 桩服务，仅用于本地验证 RedisSmsCodeStore。

用途：本机没有 Redis / Docker，但需要验证「切到 redis 存储后验证码链路是否真的可用」。
支持的指令只覆盖本项目的实际用法，不是通用 Redis 实现：

    PING / SET k v [PX ms | EX s] / GET k / DEL k... / INCR k
    EXPIRE k s / PEXPIRE k ms / TTL k / PTTL k / CLIENT ... / QUIT

启动：python mini_redis.py --port 6390
在桩日志里能看到每条指令与 key，便于确认「验证码确实落在 Redis 里，而不是内存」。
"""
import argparse
import socket
import sys
import threading
import time

STORE = {}          # key -> value(str)
EXPIRES = {}        # key -> 到期时间戳(ms)
LOCK = threading.Lock()


def now_ms():
    return int(time.time() * 1000)


def alive(key):
    exp = EXPIRES.get(key)
    if exp is not None and exp <= now_ms():
        STORE.pop(key, None)
        EXPIRES.pop(key, None)
        return False
    return key in STORE


def bulk(value):
    if value is None:
        return b"$-1\r\n"
    raw = value.encode("utf-8")
    return b"$%d\r\n%s\r\n" % (len(raw), raw)


def read_command(f):
    """读取一个 RESP 数组指令；返回 list[str] 或 None(连接关闭)"""
    line = f.readline()
    if not line:
        return None
    line = line.strip()
    if not line:
        return []
    if not line.startswith(b"*"):
        # inline command（telnet 手工测试用）
        return [p.decode("utf-8") for p in line.split()]
    count = int(line[1:])
    args = []
    for _ in range(count):
        header = f.readline().strip()
        if not header.startswith(b"$"):
            return []
        length = int(header[1:])
        data = f.read(length + 2)[:length]
        args.append(data.decode("utf-8"))
    return args


def handle(args):
    if not args:
        return b"+OK\r\n"
    cmd = args[0].upper()
    with LOCK:
        if cmd == "PING":
            return b"+PONG\r\n"
        if cmd == "SET":
            key, value = args[1], args[2]
            STORE[key] = value
            EXPIRES.pop(key, None)
            opts = [a.upper() for a in args[3:]]
            if "PX" in opts:
                EXPIRES[key] = now_ms() + int(args[3 + opts.index("PX") + 1])
            elif "EX" in opts:
                EXPIRES[key] = now_ms() + int(args[3 + opts.index("EX") + 1]) * 1000
            print("[redis-stub] SET %s = %s (ttl=%s)" % (key, value, EXPIRES.get(key)), flush=True)
            return b"+OK\r\n"
        if cmd in ("SETEX", "PSETEX"):
            # Spring Data 在 TTL 能整除为秒时用 SETEX，否则 PSETEX
            key, ttl, value = args[1], int(args[2]), args[3]
            ms = ttl * 1000 if cmd == "SETEX" else ttl
            STORE[key] = value
            EXPIRES[key] = now_ms() + ms
            print("[redis-stub] %s %s = %s (ttl=%dms)" % (cmd, key, value, ms), flush=True)
            return b"+OK\r\n"
        if cmd == "GET":
            key = args[1]
            if not alive(key):
                return b"$-1\r\n"
            return bulk(STORE[key])
        if cmd == "DEL":
            removed = 0
            for key in args[1:]:
                if STORE.pop(key, None) is not None:
                    removed += 1
                EXPIRES.pop(key, None)
                print("[redis-stub] DEL %s" % key, flush=True)
            return b":%d\r\n" % removed
        if cmd == "INCR":
            key = args[1]
            cur = 0
            if alive(key):
                cur = int(STORE[key])
            cur += 1
            STORE[key] = str(cur)
            print("[redis-stub] INCR %s -> %d (ttl=%s)" % (key, cur, EXPIRES.get(key)), flush=True)
            return b":%d\r\n" % cur
        if cmd in ("EXPIRE", "PEXPIRE"):
            key = args[1]
            ms = int(args[2]) * (1000 if cmd == "EXPIRE" else 1)
            if key not in STORE:
                return b":0\r\n"
            EXPIRES[key] = now_ms() + ms
            print("[redis-stub] %s %s -> %dms" % (cmd, key, ms), flush=True)
            return b":1\r\n"
        if cmd in ("TTL", "PTTL"):
            key = args[1]
            if not alive(key):
                return b":-2\r\n"
            exp = EXPIRES.get(key)
            if exp is None:
                return b":-1\r\n"
            remain = exp - now_ms()
            return b":%d\r\n" % (remain // 1000 if cmd == "TTL" else remain)
        if cmd == "CLIENT":
            return b"+OK\r\n"
        if cmd in ("QUIT",):
            return b"+OK\r\n"
        # 未实现的指令返回错误，客户端（Lettuce）对 CLIENT SETINFO 之类的错误是容忍的
        print("[redis-stub] 未实现指令 %s" % cmd, flush=True)
        return b"-ERR unknown command '%s'\r\n" % cmd.encode()


def serve(conn):
    f = conn.makefile("rb")
    try:
        while True:
            args = read_command(f)
            if args is None:
                break
            resp = handle(args)
            conn.sendall(resp)
    except Exception as e:  # noqa: BLE001
        print("[redis-stub] 连接异常 %s" % e, flush=True)
    finally:
        try:
            conn.close()
        except Exception:  # noqa: BLE001
            pass


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=6390)
    args = ap.parse_args()
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", args.port))
    srv.listen(16)
    print("[redis-stub] listening on 127.0.0.1:%d" % args.port, flush=True)
    while True:
        conn, _ = srv.accept()
        threading.Thread(target=serve, args=(conn,), daemon=True).start()


if __name__ == "__main__":
    sys.exit(main())
