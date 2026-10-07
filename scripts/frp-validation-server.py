#!/usr/bin/env python3
"""提供隔離 Android frpc 端對端測試所需的本機 frps 與 TCP／UDP 探針。"""

import argparse
import http.server
import pathlib
import socket
import subprocess
import tempfile
import threading


def main() -> None:
    parser = argparse.ArgumentParser(description="Run local frps fixture for Android validation")
    parser.add_argument("--frps", type=pathlib.Path, default=pathlib.Path(".core-cache/frps"))
    parser.add_argument("--multi-instance", action="store_true", help="Also serve a second frps on port 17001")
    args = parser.parse_args()
    executable = args.frps.resolve()
    lock = threading.Lock()
    child: subprocess.Popen | None = None
    second_child: subprocess.Popen | None = None
    payload = b"xraydroid-frp-probe"

    with tempfile.TemporaryDirectory(prefix="xraydroid-frp-") as directory:
        config = pathlib.Path(directory) / "frps.toml"
        config.write_text('bindAddr = "127.0.0.1"\nbindPort = 17000\nlog.to = "/dev/null"\n')

        def stop() -> None:
            nonlocal child
            if child is not None:
                child.terminate()
                try:
                    child.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    child.kill()
                    child.wait(timeout=5)
                child = None

        def start() -> None:
            nonlocal child
            if child is None or child.poll() is not None:
                child = subprocess.Popen(
                    [str(executable), "-c", str(config)],
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.DEVNULL,
                )

        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *unused: object) -> None:
                pass

            def do_GET(self) -> None:
                success = False
                try:
                    if self.path == "/health":
                        success = child is not None and child.poll() is None
                    elif self.path in ("/stop", "/start"):
                        with lock:
                            stop() if self.path == "/stop" else start()
                        success = True
                    elif self.path == "/tcp":
                        with socket.create_connection(("127.0.0.1", 16000), timeout=4) as connection:
                            connection.sendall(payload)
                            received = bytearray()
                            while len(received) < len(payload):
                                chunk = connection.recv(len(payload) - len(received))
                                if not chunk:
                                    break
                                received.extend(chunk)
                            success = received == payload
                    elif self.path == "/udp":
                        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as connection:
                            connection.settimeout(4)
                            connection.sendto(payload, ("127.0.0.1", 16001))
                            success = connection.recv(1024) == payload
                except (OSError, subprocess.SubprocessError):
                    pass
                self.send_response(200 if success else 503)
                self.end_headers()
                self.wfile.write(b"ok" if success else b"unavailable")

        server = http.server.ThreadingHTTPServer(("127.0.0.1", 18080), Handler)
        try:
            start()
            if args.multi_instance:
                second_config = pathlib.Path(directory) / "frps-second.toml"
                second_config.write_text('bindAddr = "127.0.0.1"\nbindPort = 17001\nlog.to = "/dev/null"\n')
                second_child = subprocess.Popen(
                    [str(executable), "-c", str(second_config)],
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.DEVNULL,
                )
            print("FRP validation fixture ready: control=17000 probe=18080 tcp=16000 udp=16001", flush=True)
            if args.multi_instance:
                print("Second FRP validation server: control=17001", flush=True)
            server.serve_forever()
        except KeyboardInterrupt:
            pass
        finally:
            server.server_close()
            stop()
            if second_child is not None:
                second_child.terminate()
                try:
                    second_child.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    second_child.kill()
                    second_child.wait(timeout=5)


if __name__ == "__main__":
    main()
