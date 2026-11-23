"""开发期调试用的 HTTP CONNECT 代理（仅在本机 127.0.0.1 监听）。

用途：Android 模拟器里 DNS 解析不可用，但 TCP 出网正常。把模拟器的系统代理
指向本机（配合 adb reverse），由宿主机完成 DNS 与 TLS 隧道，从而验证 App 的
真实网络链路。不参与 App 运行时，也不随 APK 分发。

用法：
    python tools/dev_proxy.py [port]
"""
import select
import socket
import sys
import threading
import ipaddress
import re


def parse_connect_target(target: str) -> tuple[str, int]:
    if target.startswith("["):
        end = target.find("]")
        if end < 0:
            raise ValueError("Invalid IPv6 target")
        host = target[1:end]
        ipaddress.IPv6Address(host)
        suffix = target[end + 1:]
        if suffix and not suffix.startswith(":"):
            raise ValueError("Invalid port separator")
        port_text = suffix[1:] if suffix else None
    else:
        if target.count(":") > 1:
            raise ValueError("IPv6 must use brackets")
        host, separator, port_text = target.partition(":")
        if not re.fullmatch(r"[A-Za-z0-9_-]+(?:\.[A-Za-z0-9_-]+)*", host):
            raise ValueError("Invalid host")
        if not separator:
            port_text = None
    if port_text is None:
        return host, 443
    if not re.fullmatch(r"[0-9]{1,5}", port_text):
        raise ValueError("Invalid port")
    port = int(port_text)
    if not 1 <= port <= 65535:
        raise ValueError("Invalid port range")
    return host, port


def _pipe(left: socket.socket, right: socket.socket) -> None:
    sockets = [left, right]
    try:
        while True:
            readable, _, _ = select.select(sockets, [], [], 60)
            if not readable:
                return
            for current in readable:
                data = current.recv(65536)
                if not data:
                    return
                (right if current is left else left).sendall(data)
    except OSError:
        return
    finally:
        for current in sockets:
            try:
                current.close()
            except OSError:
                pass


def _handle(conn: socket.socket) -> None:
    upstream = None
    try:
        conn.settimeout(60)
        buffer = b""
        while b"\r\n\r\n" not in buffer:
            chunk = conn.recv(4096)
            if not chunk:
                return
            buffer += chunk
            if len(buffer) > 65536:
                return

        head, _, remainder = buffer.partition(b"\r\n\r\n")
        request_line = head.split(b"\r\n", 1)[0]
        parts = request_line.split(b" ")
        if len(parts) != 3 or parts[2] not in (b"HTTP/1.0", b"HTTP/1.1"):
            conn.sendall(b"HTTP/1.1 400 Bad Request\r\n\r\n")
            return
        if parts[0] != b"CONNECT":
            conn.sendall(b"HTTP/1.1 405 Method Not Allowed\r\n\r\n")
            return

        try:
            host, port = parse_connect_target(parts[1].decode("ascii"))
        except (ValueError, UnicodeError):
            conn.sendall(b"HTTP/1.1 400 Bad Request\r\n\r\n")
            return
        upstream = socket.create_connection((host, port), timeout=30)
        conn.sendall(b"HTTP/1.1 200 Connection Established\r\n\r\n")
        if remainder:
            upstream.sendall(remainder)
        _pipe(conn, upstream)
    except OSError:
        try:
            conn.sendall(b"HTTP/1.1 502 Bad Gateway\r\n\r\n")
        except OSError:
            pass
    finally:
        if upstream is not None:
            try:
                upstream.close()
            except OSError:
                pass
        try:
            conn.close()
        except OSError:
            pass


def main() -> None:
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8888
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(("127.0.0.1", port))
    server.listen(64)
    print(f"dev proxy listening on 127.0.0.1:{port}", flush=True)
    while True:
        conn, _ = server.accept()
        threading.Thread(target=_handle, args=(conn,), daemon=True).start()


if __name__ == "__main__":
    main()
