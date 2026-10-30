"""腾讯云短信网关模板（把厂商 SDK 包成一个 HTTP 接口，供后端 HttpSmsGatewaySender 调用）。

状态：**模板，未实测**。原因：真实下发需要腾讯云「企业实名 / 他用资质」+ 已报备的签名，
本机没有这些凭据，无法验证到「手机真能收到」这一步。代码只用到官方 SDK，签名由 SDK 处理。

协议（与后端 SmsSender / HttpSmsGatewaySender 约定一致）：
    POST /send
    Authorization: <你配置的 SMS_GATEWAY_TOKEN>
    {"phone":"13900000000","code":"123456","scene":"login"}
    成功 -> HTTP 200 {"code":0,"msg":"ok"}
    失败 -> HTTP 200 {"code":1,"msg":"具体原因"}   （后端会按 success-value=0 判定为失败并回 500）

准备：
    pip install tencentcloud-sdk-python
    export TC_SECRET_ID=...
    export TC_SECRET_KEY=...
    export TC_SDK_APP_ID=1400000000
    export TC_SIGN_NAME=你的签名
    export TC_TEMPLATE_ID=1234567
    export TC_REGION=ap-guangzhou
    export GATEWAY_TOKEN=与后端 meeting.sms.gateway.token 一致

启动：
    python tools/_verify/sms_gateway_tencent.py --port 9100

然后后端这样启动（注意 sms-mock 必须关）：
    --meeting.sms-mock=false --meeting.sms.gateway.url=http://127.0.0.1:9100/send \
    --meeting.sms.gateway.token=$GATEWAY_TOKEN

验证是否真的走了真实通道：看 /api/auth/sms-code 的响应里**没有 debugCode** 字段。
"""
import argparse
import json
import os
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# SDK 用惰性导入：没装的时候给出「请 pip install」的可读提示，
# 而不是甩一段 ImportError traceback 让人以为代码坏了。
try:
    from tencentcloud.common import credential
    from tencentcloud.common.exception.tencent_cloud_sdk_exception import TencentCloudSDKException
    from tencentcloud.sms.v20210111 import models, sms_client
    SDK_IMPORT_ERROR = None
except ImportError as e:  # noqa: BLE001
    credential = None
    models = None
    sms_client = None

    class TencentCloudSDKException(Exception):
        pass

    SDK_IMPORT_ERROR = str(e)

CFG = {
    "secret_id": os.environ.get("TC_SECRET_ID", ""),
    "secret_key": os.environ.get("TC_SECRET_KEY", ""),
    "sdk_app_id": os.environ.get("TC_SDK_APP_ID", ""),
    "sign_name": os.environ.get("TC_SIGN_NAME", ""),
    "template_id": os.environ.get("TC_TEMPLATE_ID", ""),
    "region": os.environ.get("TC_REGION", "ap-guangzhou"),
    "gateway_token": os.environ.get("GATEWAY_TOKEN", ""),
}

MISSING = [k for k, v in CFG.items() if k != "gateway_token" and not v]


class Handler(BaseHTTPRequestHandler):
    def _reply(self, payload):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json;charset=UTF-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):  # noqa: N802
        # 1) 鉴权：与后端配置的 token 比对，避免网关裸奔被当成短信轰炸机
        if CFG["gateway_token"]:
            auth = self.headers.get("Authorization", "")
            if auth != CFG["gateway_token"]:
                print("[gw] 鉴权失败 auth=%r" % auth, flush=True)
                self._reply({"code": 401, "msg": "unauthorized"})
                return

        length = int(self.headers.get("Content-Length") or 0)
        try:
            payload = json.loads(self.rfile.read(length).decode("utf-8"))
        except Exception:  # noqa: BLE001
            self._reply({"code": 400, "msg": "bad json"})
            return

        phone = str(payload.get("phone") or "").strip()
        code = str(payload.get("code") or "").strip()
        if not phone or not code:
            self._reply({"code": 400, "msg": "phone/code required"})
            return

        if SDK_IMPORT_ERROR:
            self._reply({"code": 1, "msg": "SDK 未安装：pip install tencentcloud-sdk-python"})
            return

        try:
            cred = credential.Credential(CFG["secret_id"], CFG["secret_key"])
            client = sms_client.SmsClient(cred, CFG["region"])
            req = models.SendSmsRequest()
            req.SmsSdkAppId = CFG["sdk_app_id"]
            req.SignName = CFG["sign_name"]
            req.TemplateId = CFG["template_id"]
            req.PhoneNumberSet = ["+86" + phone]
            # 模板变量：验证码模板里的 {1} 对应这里第一个元素
            req.TemplateParamSet = [code]
            resp = client.SendSms(req)

            status = resp.SendStatusSet[0] if resp.SendStatusSet else None
            if status is None or status.Code != "Ok":
                # 常见失败：签名未报备、模板未通过、手机号格式、余额不足
                msg = "%s: %s" % (getattr(status, "Code", "unknown"), getattr(status, "Message", ""))
                print("[gw] 下发失败 phone=%s %s" % (phone, msg), flush=True)
                self._reply({"code": 1, "msg": msg})
                return

            print("[gw] 下发成功 phone=%s serial=%s" % (phone, resp.SerialNo), flush=True)
            self._reply({"code": 0, "msg": "ok", "serialNo": resp.SerialNo})
        except TencentCloudSDKException as e:
            print("[gw] SDK 异常 %s" % e, flush=True)
            self._reply({"code": 1, "msg": str(e)})
        except Exception as e:  # noqa: BLE001
            print("[gw] 未预期异常 %s" % e, flush=True)
            self._reply({"code": 1, "msg": str(e)})

    def log_message(self, fmt, *args):
        pass


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=9100)
    args = ap.parse_args()
    if SDK_IMPORT_ERROR:
        print("[gw] 腾讯云 SDK 未安装：pip install tencentcloud-sdk-python", flush=True)
        return 1
    if MISSING:
        print("[gw] 缺少环境变量：%s，无法真实下发" % ", ".join(MISSING), flush=True)
        print("     先按文件头部注释导出这些变量再启动（缺一个都不行）", flush=True)
        return 1
    srv = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    print("[gw] listening on 127.0.0.1:%d  sign=%s template=%s"
          % (args.port, CFG["sign_name"], CFG["template_id"]), flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    sys.exit(main())
