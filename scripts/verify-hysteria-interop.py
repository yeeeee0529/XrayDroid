#!/usr/bin/env python3
"""以本機一次性設定驗證 Xray 與官方 Hysteria 2 雙向互通。"""

import argparse
import contextlib
import json
import pathlib
import socket
import subprocess
import tempfile
import threading
import time


def free_port(kind: int = socket.SOCK_STREAM) -> int:
    with socket.socket(type=kind) as connection:
        connection.bind(("127.0.0.1", 0))
        return connection.getsockname()[1]


def receive(connection: socket.socket, size: int) -> bytes:
    result = bytearray()
    while len(result) < size:
        chunk = connection.recv(size - len(result))
        if not chunk:
            raise RuntimeError("Unexpected end of protocol stream")
        result.extend(chunk)
    return bytes(result)


def socks(port: int, command: int, target: int = 0) -> tuple[socket.socket, int]:
    connection = socket.create_connection(("127.0.0.1", port), timeout=15)
    connection.sendall(b"\x05\x01\x00")
    assert receive(connection, 2) == b"\x05\x00", "SOCKS authentication failed"
    connection.sendall(bytes((5, command, 0, 1, 127, 0, 0, 1)) + target.to_bytes(2))
    response = receive(connection, 4)
    assert response[:3] == b"\x05\x00\x00", "SOCKS request failed"
    receive(connection, 4 if response[3] == 1 else 16)
    return connection, int.from_bytes(receive(connection, 2))


def transfer(port: int) -> None:
    payload = bytes(range(256)) * 128
    with socket.socket() as echo:
        echo.bind(("127.0.0.1", 0))
        echo.listen(1)
        echo.settimeout(15)

        def tcp_echo() -> None:
            with echo.accept()[0] as connection:
                connection.settimeout(15)
                connection.sendall(receive(connection, len(payload)))

        worker = threading.Thread(target=tcp_echo, daemon=True)
        worker.start()
        with socks(port, 1, echo.getsockname()[1])[0] as connection:
            connection.sendall(payload)
            assert receive(connection, len(payload)) == payload, "TCP payload mismatch"
        worker.join(16)
        assert not worker.is_alive(), "TCP echo did not finish"

    with socket.socket(type=socket.SOCK_DGRAM) as echo:
        echo.bind(("127.0.0.1", 0))
        echo.settimeout(15)

        def udp_echo() -> None:
            data, address = echo.recvfrom(4096)
            echo.sendto(data, address)

        worker = threading.Thread(target=udp_echo, daemon=True)
        worker.start()
        control, relay = socks(port, 3)
        with control, socket.socket(type=socket.SOCK_DGRAM) as connection:
            connection.settimeout(15)
            expected = payload[:1024]
            header = b"\x00\x00\x00\x01\x7f\x00\x00\x01" + echo.getsockname()[
                1
            ].to_bytes(2)
            connection.sendto(header + expected, ("127.0.0.1", relay))
            response = connection.recv(4096)
            assert response[3] == 1 and response[10:] == expected, (
                "UDP payload mismatch"
            )
        worker.join(16)
        assert not worker.is_alive(), "UDP echo did not finish"


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Verify local TCP/UDP interoperability with official Hysteria 2"
    )
    parser.add_argument("--xray", type=pathlib.Path, required=True)
    parser.add_argument("--hysteria", type=pathlib.Path, required=True)
    args = parser.parse_args()
    xray, hysteria = str(args.xray.resolve()), str(args.hysteria.resolve())
    with tempfile.TemporaryDirectory(prefix="xraydroid-hy2-") as temporary:
        directory = pathlib.Path(temporary)
        certificate = directory / "fixture"
        subprocess.run(
            [
                xray,
                "tls",
                "cert",
                "-domain=localhost",
                "-json=false",
                f"-file={certificate}",
            ],
            check=True,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        cert, key = (
            str(certificate.with_suffix(".crt")),
            str(certificate.with_suffix(".key")),
        )
        for official_server in (False, True):
            server_port, socks_port = free_port(socket.SOCK_DGRAM), free_port()
            authentication = "disposable-hysteria-interop-fixture"
            stream = {
                "network": "hysteria",
                "security": "tls",
                "hysteriaSettings": {"version": 2},
            }
            if official_server:
                server = {
                    "listen": f"127.0.0.1:{server_port}",
                    "tls": {"cert": cert, "key": key},
                    "auth": {"type": "password", "password": authentication},
                }
                stream["hysteriaSettings"]["auth"] = authentication
                stream["tlsSettings"] = {
                    "serverName": "localhost",
                    "alpn": ["h3"],
                    "certificates": [{"certificateFile": cert, "usage": "verify"}],
                }
                client = {
                    "log": {"loglevel": "none"},
                    "inbounds": [
                        {
                            "listen": "127.0.0.1",
                            "port": socks_port,
                            "protocol": "socks",
                            "settings": {"udp": True},
                        }
                    ],
                    "outbounds": [
                        {
                            "protocol": "hysteria",
                            "settings": {
                                "version": 2,
                                "address": "127.0.0.1",
                                "port": server_port,
                            },
                            "streamSettings": stream,
                        }
                    ],
                }
            else:
                stream["tlsSettings"] = {
                    "alpn": ["h3"],
                    "certificates": [{"certificateFile": cert, "keyFile": key}],
                }
                server = {
                    "log": {"loglevel": "none"},
                    "inbounds": [
                        {
                            "listen": "127.0.0.1",
                            "port": server_port,
                            "protocol": "hysteria",
                            "settings": {
                                "version": 2,
                                "clients": [{"auth": authentication}],
                            },
                            "streamSettings": stream,
                        }
                    ],
                    "outbounds": [
                        {
                            "protocol": "freedom",
                            "settings": {"finalRules": [{"action": "allow"}]},
                        }
                    ],
                }
                client = {
                    "server": f"127.0.0.1:{server_port}",
                    "auth": authentication,
                    "tls": {"sni": "localhost", "ca": cert},
                    "socks5": {"listen": f"127.0.0.1:{socks_port}"},
                }
            with contextlib.ExitStack() as stack:
                for name, config, executable, action in (
                    (
                        "server",
                        server,
                        hysteria if official_server else xray,
                        "server" if official_server else "run",
                    ),
                    (
                        "client",
                        client,
                        xray if official_server else hysteria,
                        "run" if official_server else "client",
                    ),
                ):
                    config_file = directory / f"{name}.json"
                    config_file.write_text(json.dumps(config))
                    process = subprocess.Popen(
                        [executable, action, "-c", str(config_file)],
                        stdout=subprocess.DEVNULL,
                        stderr=subprocess.DEVNULL,
                    )

                    def stop(child: subprocess.Popen = process) -> None:
                        child.terminate()
                        try:
                            child.wait(timeout=5)
                        except subprocess.TimeoutExpired:
                            child.kill()
                            child.wait(timeout=5)

                    stack.callback(stop)
                    if name == "server":
                        time.sleep(0.3)
                deadline = time.monotonic() + 15
                while True:
                    try:
                        with socket.create_connection(
                            ("127.0.0.1", socks_port), timeout=0.2
                        ):
                            break
                    except OSError:
                        if time.monotonic() > deadline:
                            raise RuntimeError(
                                "Interop client did not open its SOCKS listener"
                            ) from None
                        time.sleep(0.05)
                transfer(socks_port)
                direction = (
                    "Xray client -> official Hysteria 2 server"
                    if official_server
                    else "official Hysteria 2 client -> Xray server"
                )
                print(f"PASS: {direction}, TCP and UDP", flush=True)


if __name__ == "__main__":
    main()
