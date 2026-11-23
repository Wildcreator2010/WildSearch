"""开发期用的 GitHub Releases API 模拟服务（仅本地联调）。

用途：模拟器里 DNS 不可用、也没有真实的 GitHub 仓库可发版，于是用本地
HTTP 服务返回与 api.github.com 完全同构的 release JSON，并把 APK 附件指向
本机已有的 app-debug.apk，从而验证「检查更新 → 下载 → 校验 → 调起安装器」
整条链路。不参与 App 运行时。

用法：
    python tools/mock_update_api.py <apk路径> [port] [tag]
    # 例：模拟有新版本
    python tools/mock_update_api.py app/build/outputs/apk/debug/app-debug.apk 8899 v1.1.0
    # 例：模拟已是最新（tag 与当前 versionName 相同）
    python tools/mock_update_api.py app/build/outputs/apk/debug/app-debug.apk 8899 1.0.0
"""
import hashlib
import json
import os
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

APK_PATH = ""
APK_TAG = "v1.1.0"
APK_BYTES = b""
APK_SIZE = 0
APK_SHA256 = ""
# 模拟器访问宿主机固定用 10.0.2.2
PUBLIC_HOST = "10.0.2.2"
PORT = 8899


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):  # 保持输出简洁
        sys.stdout.write("[mock-api] " + (fmt % args) + "\n")
        sys.stdout.flush()

    def _send_json(self, payload, status=200):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        path = self.path.split("?")[0]
        if "/releases/latest" in path:
            self._send_json(self._release())
            return
        if path == "/download/app.apk":
            self.send_response(200)
            self.send_header("Content-Type", "application/vnd.android.package-archive")
            self.send_header("Content-Length", str(APK_SIZE))
            self.end_headers()
            self.wfile.write(APK_BYTES)
            return
        self._send_json({"message": "Not Found"}, status=404)

    def _release(self):
        return {
            "tag_name": APK_TAG,
            "name": "",
            "draft": False,
            "prerelease": False,
            "html_url": "https://example.com/releases/" + APK_TAG,
            "published_at": "2026-11-23T10:00:00Z",
            "body": "### 本次更新\n\n- 新增应用内更新检查\n- 修复若干问题\n",
            "assets": [
                {
                    "name": "wildsearch-" + APK_TAG + ".apk",
                    "content_type": "application/vnd.android.package-archive",
                    "state": "uploaded",
                    "size": APK_SIZE,
                    "digest": "sha256:" + APK_SHA256,
                    "browser_download_url": "http://%s:%d/download/app.apk" % (PUBLIC_HOST, PORT),
                },
                {
                    "name": "wildsearch-" + APK_TAG + ".apk.sig",
                    "content_type": "binary/octet-stream",
                    "state": "uploaded",
                    "size": 171,
                    "browser_download_url": "http://%s:%d/download/app.apk.sig" % (PUBLIC_HOST, PORT),
                },
            ],
        }


def main():
    global APK_PATH, APK_TAG, APK_BYTES, APK_SIZE, APK_SHA256, PORT
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    APK_PATH = sys.argv[1]
    if len(sys.argv) > 2:
        PORT = int(sys.argv[2])
    if len(sys.argv) > 3:
        APK_TAG = sys.argv[3]
    with open(APK_PATH, "rb") as handle:
        APK_BYTES = handle.read()
    APK_SIZE = len(APK_BYTES)
    APK_SHA256 = hashlib.sha256(APK_BYTES).hexdigest()
    print(
        "[mock-api] serving %s (%d bytes, sha256=%s) as tag %s on 0.0.0.0:%d"
        % (os.path.basename(APK_PATH), APK_SIZE, APK_SHA256, APK_TAG, PORT),
        flush=True,
    )
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()


if __name__ == "__main__":
    main()
