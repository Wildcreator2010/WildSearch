import unittest
from unittest.mock import patch
from tools import dev_proxy


class Connection:
    def __init__(self, target):
        self.request = b"CONNECT " + target + b" HTTP/1.1\r\n\r\n"
        self.reply = b""
        self.closed = False

    def settimeout(self, value):
        pass

    def recv(self, size):
        request, self.request = self.request, b""
        return request

    def sendall(self, data):
        self.reply += data

    def close(self):
        self.closed = True


class ProxyTest(unittest.TestCase):
    def test_invalid_targets_return_400_and_close(self):
        for target in (b"localhost:notaport", b"localhost:", b":443", b"localhost:0",
                       b"localhost:65536", b"localhost:-1", b"localhost:+80", b"[invalid]:443",
                       b"::1:443", b"localhost/path:443", b"\xff:443"):
            with self.subTest(target=target), patch.object(dev_proxy.socket, "create_connection") as connect:
                connection = Connection(target)
                dev_proxy._handle(connection)
                self.assertTrue(connection.reply.startswith(b"HTTP/1.1 400"), connection.reply)
                self.assertTrue(connection.closed)
                connect.assert_not_called()

    def test_default_and_ipv6_ports_reach_upstream(self):
        for target, address in ((b"localhost", ("localhost", 443)),
                                (b"example.com:80", ("example.com", 80)),
                                (b"[::1]:443", ("::1", 443)), (b"[::1]", ("::1", 443))):
            with self.subTest(target=target), patch.object(dev_proxy.socket, "create_connection", side_effect=OSError) as connect:
                connection = Connection(target)
                dev_proxy._handle(connection)
                connect.assert_called_once_with(address, timeout=30)
                self.assertTrue(connection.reply.startswith(b"HTTP/1.1 502"))
                self.assertTrue(connection.closed)


if __name__ == "__main__":
    unittest.main()
