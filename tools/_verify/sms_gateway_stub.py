"""短信网关桩服务：接收后端 POST 的 {phone, code, scene}，打印并返回 {"code":0}。

用途：验证「关闭 sms-mock 后，验证码确实通过 HTTP 网关真实下发」这条链路。
桩日志里能看到 Authorization 头与验证码明文，据此完成登录验证。

启动：python sms_gateway_stub.py --port 8099
"""
import argparse
import json
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):  # noqa: N802
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length).decode("utf-8", "replace")
        auth = self.headers.get("Authorization")
        print("[sms-gateway] %s auth=%s body=%s" % (self.path, auth, raw), flush=True)

        # 便于人工核对：把收到的验证码单独打一行
        try:
            payload = json.loads(raw)
            print("[sms-gateway] 收到验证码 phone=%s code=%s"
                  % (payload.get("phone"), payload.get("code")), flush=True)
        except Exception:  # noqa: BLE001
            pass

        body = json.dumps({"code": 0, "msg": "ok"}, ensure_ascii=False).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json;charset=UTF-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt, *args):  # 关掉默认访问日志，只留上面的业务日志
        pass


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8099)
    args = ap.parse_args()
    srv = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    print("[sms-gateway] listening on 127.0.0.1:%d" % args.port, flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    sys.exit(main())
