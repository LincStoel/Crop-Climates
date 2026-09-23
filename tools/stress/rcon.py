"""Minimal Source RCON client (stdlib only) for driving the stress server on 127.0.0.1."""
import socket
import struct
import pathlib
import time

ROOT = pathlib.Path(__file__).resolve().parents[2]
SERVER_DIR = ROOT / "run-stress" / "server"


def _password():
    props = (SERVER_DIR / "server.properties").read_text(encoding="utf-8")
    for line in props.splitlines():
        if line.startswith("rcon.password="):
            return line.split("=", 1)[1].strip()
    raise RuntimeError("rcon.password missing from server.properties")


class Rcon:
    def __init__(self, host="127.0.0.1", port=25575, password=None, timeout=600):
        self.sock = socket.create_connection((host, port), timeout=timeout)
        self.next_id = 1
        self._send(3, password or _password())
        rid, _, _ = self._recv()
        if rid == -1:
            raise RuntimeError("RCON login failed")

    def _send(self, kind, body):
        rid = self.next_id
        self.next_id += 1
        payload = body.encode("utf-8") + b"\x00\x00"
        self.sock.sendall(struct.pack("<iii", len(payload) + 8, rid, kind) + payload)
        return rid

    def _recv_exact(self, n):
        buf = b""
        while len(buf) < n:
            chunk = self.sock.recv(n - len(buf))
            if not chunk:
                raise ConnectionError("RCON connection closed")
            buf += chunk
        return buf

    def _recv(self):
        (length,) = struct.unpack("<i", self._recv_exact(4))
        data = self._recv_exact(length)
        rid, kind = struct.unpack("<ii", data[:8])
        return rid, kind, data[8:-2].decode("utf-8", "replace")

    def cmd(self, command, timeout=900):
        """Runs a command and returns its reply.

        Vanilla's RCON reader takes exactly one packet per socket read (two
        packets in one segment drop the connection), so a trailing marker
        packet cannot be used. Replies over 4096 characters arrive split;
        after a full-size chunk, keep reading briefly for the rest.
        """
        if len(command.encode("utf-8")) > 1400:
            raise ValueError("vanilla RCON reads one 1460-byte buffer per packet; command too long")
        rid = self._send(2, command)
        self.sock.settimeout(timeout)
        out = []
        got, _, body = self._recv()
        while got != rid:
            got, _, body = self._recv()
        out.append(body)
        while len(body) >= 4096:
            self.sock.settimeout(1.0)
            try:
                got, _, body = self._recv()
            except socket.timeout:
                break
            if got == rid:
                out.append(body)
        self.sock.settimeout(timeout)
        return "".join(out)

    def close(self):
        self.sock.close()


def wait_for_server(timeout=600):
    """Blocks until RCON answers (the server is done starting)."""
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        try:
            r = Rcon(timeout=30)
            return r
        except (OSError, RuntimeError) as ex:
            last = ex
            time.sleep(3)
    raise TimeoutError(f"server did not come up: {last}")


if __name__ == "__main__":
    import sys
    sys.stdout.reconfigure(encoding="utf-8")
    r = Rcon()
    for c in sys.argv[1:]:
        print(r.cmd(c))
    r.close()
