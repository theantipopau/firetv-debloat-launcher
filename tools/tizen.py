#!/usr/bin/env python3
"""Talk to a Samsung Tizen TV over the LAN. No ADB, no Tizen Studio, no dependencies.

    export TIZEN_HOST=192.168.1.45          (or write the IP to tools/.tizen_host)
    tizen.py info                           model, MAC, power state
    tizen.py apps [--json]                  installed apps: id and name
    tizen.py launch <appId>                 open an app
    tizen.py key KEY_HOME                   send one remote key
    tizen.py watch                          log reachability/power changes with timestamps
    tizen.py wol                            magic packet (needs Power On with Mobile)
    tizen.py forget                         drop the pairing token and re-pair next time

The first apps/key/launch run makes the TV ask "Allow this device?" — press Allow with
the remote. The token it hands back is cached in tools/.tizen_token (git-ignored), so it
asks once. Everything here is read-only except launch/key/wol.

Ports, from recon on a 2020 75TU8000: 8001 REST (only /api/v2/), 8002 websocket channels,
8080 Samsung web API, 9197 DLNA.
"""
import base64
import json
import os
import socket
import ssl
import struct
import sys
import time
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
HOST_FILE = os.path.join(HERE, ".tizen_host")
TOKEN_FILE = os.path.join(HERE, ".tizen_token")
REST_PORT = 8001
WS_PORT = 8002
APP_NAME = "tvhome"  # shown on the TV's Allow prompt


def host():
    h = os.environ.get("TIZEN_HOST", "").strip()
    if not h and os.path.exists(HOST_FILE):
        h = open(HOST_FILE).read().strip()
    if not h:
        sys.exit("No TV address. export TIZEN_HOST=192.168.1.45, or write the IP to tools/.tizen_host")
    if os.environ.get("TIZEN_HOST") and not os.path.exists(HOST_FILE):
        with open(HOST_FILE, "w") as f:
            f.write(h)
    return h


def info():
    with urllib.request.urlopen(f"http://{host()}:{REST_PORT}/api/v2/", timeout=6) as r:
        return json.loads(r.read().decode())


def read_token():
    return open(TOKEN_FILE).read().strip() if os.path.exists(TOKEN_FILE) else ""


def write_token(t):
    with open(TOKEN_FILE, "w") as f:
        f.write(t)


class WS:
    """Just enough RFC 6455 for the TV's JSON channels: text frames, ping/pong, optional TLS.

    2020 sets close a plain ws:// handshake on 8002, so wss:// with an unverified cert is
    the working path; the fallback keeps older sets usable.
    """

    def __init__(self, addr, path, timeout=60, tls=True):
        # The handshake gets a short leash; the TV happily holds a TCP socket open forever.
        self.sock = socket.create_connection((addr, WS_PORT), timeout=min(timeout, 10))
        if tls:
            ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
            ctx.check_hostname = False
            ctx.verify_mode = ssl.CERT_NONE
            self.sock = ctx.wrap_socket(self.sock, server_hostname=addr)
        key = base64.b64encode(os.urandom(16)).decode()
        self.sock.sendall(
            (f"GET {path} HTTP/1.1\r\nHost: {addr}:{WS_PORT}\r\nUpgrade: websocket\r\n"
             f"Connection: Upgrade\r\nSec-WebSocket-Key: {key}\r\n"
             "Sec-WebSocket-Version: 13\r\n\r\n").encode())
        self.buf = b""
        try:
            while b"\r\n\r\n" not in self.buf:
                chunk = self.sock.recv(4096)
                if not chunk:
                    raise ConnectionError("TV closed during the websocket handshake")
                self.buf += chunk
        except socket.timeout:
            raise ConnectionError("timed out during the websocket handshake")
        head, self.buf = self.buf.split(b"\r\n\r\n", 1)
        status = head.split(b"\r\n", 1)[0]
        if b" 101 " not in status:
            raise ConnectionError(f"handshake refused: {status.decode(errors='replace')}")
        self.sock.settimeout(2.0)  # small reads; recv() decides when to give up
        self.parts = []

    def _frame(self, opcode, data):
        header = bytearray([0x80 | opcode])
        n = len(data)
        if n < 126:
            header.append(0x80 | n)
        elif n < 65536:
            header.append(0x80 | 126)
            header += struct.pack("!H", n)
        else:
            header.append(0x80 | 127)
            header += struct.pack("!Q", n)
        mask = os.urandom(4)
        header += mask
        self.sock.sendall(bytes(header) + bytes(b ^ mask[i % 4] for i, b in enumerate(data)))

    def send(self, obj):
        self._frame(0x1, json.dumps(obj).encode())

    def _read(self, n, deadline=None):
        while len(self.buf) < n:
            if deadline is not None and time.time() > deadline:
                raise TimeoutError("no reply from the TV")
            try:
                chunk = self.sock.recv(65536)
            except socket.timeout:
                continue
            if not chunk:
                raise ConnectionError("TV closed the connection")
            self.buf += chunk
        out, self.buf = self.buf[:n], self.buf[n:]
        return out

    def recv(self, deadline=None):
        """Next JSON message. The TV pings every few seconds, so silence is not an error
        until `deadline` passes; without one this blocks indefinitely, as the protocol does."""
        while True:
            b1, b2 = self._read(2, deadline)
            fin, op = b1 & 0x80, b1 & 0x0F
            n = b2 & 0x7F
            if n == 126:
                n = struct.unpack("!H", self._read(2, deadline))[0]
            elif n == 127:
                n = struct.unpack("!Q", self._read(8, deadline))[0]
            payload = self._read(n, deadline)
            if op == 0x8:
                raise ConnectionError("TV closed the websocket")
            if op == 0x9:
                self._frame(0xA, payload)
                continue
            if op in (0x1, 0x0):
                self.parts.append(payload)
                if fin:
                    data = b"".join(self.parts)
                    self.parts = []
                    try:
                        return json.loads(data.decode())
                    except ValueError:
                        continue  # non-JSON chatter; not ours
            # pong or binary: ignore


def open_channel(wait=90):
    """Connect to the remote channel, pairing on first use. The TV blocks on its Allow dialog."""
    token = read_token()
    name = base64.b64encode(APP_NAME.encode()).decode()
    path = f"/api/v2/channels/samsung.remote.control?name={name}"
    if token:
        path += f"&token={token}"
    else:
        print(f'Look at the TV: allow "{APP_NAME}" with the remote (waiting up to {wait}s).', flush=True)
    try:
        ws = WS(host(), path, timeout=wait)
    except (ConnectionError, OSError) as e:
        try:
            ws = WS(host(), path, timeout=wait, tls=False)
        except (ConnectionError, OSError):
            sys.exit(f"Could not open the remote channel on {host()}:{WS_PORT} ({e}). "
                     "Is the TV on, with Remote Control / IP Control enabled?")
    deadline = time.time() + wait
    while True:
        try:
            msg = ws.recv(deadline=deadline)
        except TimeoutError:
            sys.exit(f"No answer from the TV in {wait}s. It accepts the socket but never sends\n"
                     "ms.channel.connect — usually one stale client holding the channel.\n"
                     "Turn the TV off and on with the remote, then retry.")
        event = msg.get("event")
        if event == "ms.channel.connect":
            t = (msg.get("data") or {}).get("token")
            if t and not token:
                write_token(t)
                print("Paired; token saved to tools/.tizen_token.", flush=True)
            return ws
        if event == "ms.channel.unauthorized":
            sys.exit("The TV refused access. Press Allow on the prompt, or run: tizen.py forget")


KEY_HELP = """Common keys: KEY_HOME KEY_BACK KEY_ENTER KEY_UP KEY_DOWN KEY_LEFT KEY_RIGHT
KEY_POWER KEY_VOLUMEUP KEY_VOLUMEDOWN KEY_MUTE KEY_CHANNELUP KEY_CHANNELDOWN KEY_GUIDE
KEY_MENU KEY_INFO KEY_SOURCE KEY_PLAY KEY_PAUSE KEY_STOP KEY_REWIND KEY_FASTFORWARD
KEY_0..KEY_9 KEY_HDMI KEY_HDMI1 KEY_HDMI2 KEY_HDMI3 KEY_HDMI4 KEY_TV"""


def main():
    args = sys.argv[1:]
    if not args or args[0] in ("-h", "--help", "help"):
        print(__doc__.split("Ports, from recon")[0].rstrip())
        print("\n" + KEY_HELP)
        return

    cmd = args[0]
    if cmd == "info":
        d = info()["device"]
        for k in ("name", "model", "modelName", "PowerState", "networkType", "resolution",
                  "firmwareVersion", "wifiMac", "TokenAuthSupport", "FrameTVSupport"):
            print(f"{k:18} {d.get(k)}")
    elif cmd == "apps":
        ws = open_channel()
        ws.send({"method": "ms.channel.emit",
                 "params": {"event": "ed.installedApp.get", "to": "host"}})
        deadline = time.time() + 12
        while True:
            try:
                msg = ws.recv(deadline=deadline)
            except TimeoutError:
                sys.exit("No app list came back. This TV only answers ed.installedApp.get for its\n"
                         "host client (the first one after a reboot); keys and launch still work.")
            if msg.get("event") == "ed.installedApp.get":
                data = (msg.get("data") or {}).get("data") or []
                if args[1:] == ["--json"]:
                    print(json.dumps(data, indent=2))
                else:
                    for app in sorted(data, key=lambda a: str(a.get("name"))):
                        print(f'{app.get("appId"):>18}  {app.get("name")}')
                return
    elif cmd == "launch":
        ws = open_channel()
        ws.send({"method": "ms.channel.emit",
                 "params": {"event": "ed.apps.launch", "to": "host",
                            "data": {"appId": args[1], "action_type": "NATIVE_LAUNCH"}}})
        print(f"asked the TV to launch {args[1]}")
        time.sleep(1)
    elif cmd == "key":
        key = args[1] if len(args) > 1 else ""
        if not key:
            sys.exit(KEY_HELP)
        ws = open_channel()
        ws.send({"method": "ms.remote.control",
                 "params": {"Cmd": "Click", "DataOfCmd": key,
                            "Option": "false", "TypeOfRemote": "SendRemoteKey"}})
        print(f"sent {key}")
        time.sleep(0.3)
    elif cmd == "watch":
        # Every change of reachability or PowerState, with both clocks. Ctrl-C to stop.
        t0 = time.time()
        last, last_t = None, t0
        print(f"watching {host()} - Ctrl-C to stop")
        while True:
            try:
                with urllib.request.urlopen(f"http://{host()}:{REST_PORT}/api/v2/", timeout=1.5) as r:
                    state = json.loads(r.read().decode())["device"].get("PowerState", "?")
            except Exception:
                state = "unreachable"
            if state != last:
                now = time.time()
                print(f"{time.strftime('%H:%M:%S')}  {state:12} (t+{now - t0:5.1f}s, "
                      f"+{now - last_t:5.1f}s)", flush=True)
                last, last_t = state, now
            time.sleep(0.4)
    elif cmd == "wol":
        mac = args[1] if len(args) > 1 else info()["device"]["wifiMac"]
        packet = b"\xff" * 6 + bytes.fromhex(mac.replace(":", "")) * 16
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        for dst in ("255.255.255.255", "192.168.1.255"):
            for port in (9, 7):
                s.sendto(packet, (dst, port))
        print(f"woke {mac}")
    elif cmd == "forget":
        if os.path.exists(TOKEN_FILE):
            os.remove(TOKEN_FILE)
        print("token forgotten; the TV will ask again")
    else:
        sys.exit(f"unknown command {cmd!r}\n\n{__doc__.split('Ports, from recon')[0].rstrip()}")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        pass
