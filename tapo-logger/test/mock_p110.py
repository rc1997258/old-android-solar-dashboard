"""Mock Tapo P110 speaking KLAP v2, using python-kasa's own crypto classes.
Usage: python3 mock_p110.py <port> <email> <password> [mode]
mode: ok (default) | forbid (handshake1 -> 403, like Third-Party Compatibility OFF)
"""
import json, os, re, sys, time
from http.server import BaseHTTPRequestHandler, HTTPServer
from kasa.transports.klaptransport import KlapTransportV2, KlapEncryptionSession
from kasa.credentials import Credentials

PORT, EMAIL, PASSWORD = int(sys.argv[1]), sys.argv[2], sys.argv[3]
MODE = sys.argv[4] if len(sys.argv) > 4 else "ok"
AUTH = KlapTransportV2.generate_auth_hash(Credentials(EMAIL, PASSWORD))
S = {}  # session id -> dict(local, remote, verified)
STATS = {"requests": 0}
LOG = []

class H(BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def _send(self, code, body=b"", cookie=None):
        self.send_response(code)
        if cookie: self.send_header("Set-Cookie", f"TP_SESSIONID={cookie};TIMEOUT=86400")
        self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body)
    def do_POST(self):
        body = self.rfile.read(int(self.headers.get("Content-Length", 0)))
        m = re.search(r"TP_SESSIONID=([^;\s]+)", self.headers.get("Cookie", "") or "")
        sid = m.group(1) if m else None
        if self.path == "/app/handshake1":
            if MODE == "forbid": return self._send(403)
            remote = os.urandom(16); sid = os.urandom(8).hex()
            S[sid] = {"local": body, "remote": remote, "ok": False}
            return self._send(200, remote + KlapTransportV2.handshake1_seed_auth_hash(body, remote, AUTH), sid)
        if self.path == "/app/handshake2":
            s = S.get(sid)
            if not s or body != KlapTransportV2.handshake2_seed_auth_hash(s["local"], s["remote"], AUTH):
                return self._send(403)
            s["ok"] = True; return self._send(200)
        if self.path.startswith("/app/request"):
            s = S.get(sid)
            if not s or not s["ok"]: return self._send(403)
            seq = int(self.path.split("seq=")[1])
            sess = KlapEncryptionSession(s["local"], s["remote"], AUTH)
            sess._seq = seq; sess._generate_cipher()
            # verify signature exactly like the device would
            import hashlib, struct
            sig = hashlib.sha256(sess._sig + struct.pack(">l", seq) + body[32:]).digest()
            if sig != body[:32]: return self._send(400)
            req = json.loads(sess.decrypt(body))
            STATS["requests"] += 1
            meth = req["method"]
            res = {"get_energy_usage": {"today_runtime": 300, "month_runtime": 9000, "today_energy": 1234,
                                        "month_energy": 45678, "local_time": time.strftime("%Y-%m-%d %H:%M:%S"),
                                        "current_power": 87650 + STATS["requests"] * 10},
                   "get_emeter_data": {"voltage_mv": 231400, "current_ma": 379, "power_mw": 87700, "energy_wh": 1234},
                   "get_device_info": {"device_on": True, "rssi": -52, "model": "P110",
                                       "nickname": "U29sYXIgUGx1Zw==", "fw_ver": "1.4.6 Build 251001"}}
            if meth == "get_energy_data":
                import datetime as dt
                pr = req["params"]; iv = pr["interval"]; st = pr["start_timestamp"]
                LOG.append(pr)
                start = dt.datetime.fromtimestamp(st)
                if start.year < 2025:
                    reply = {"error_code": -1}          # plug has no data that far back
                else:
                    if iv == 60:
                        n = (pr["end_timestamp"] - st + 1) // 3600
                        data = [10 * (h % 24) for h in range(n)]          # Wh = 10 x hour
                    elif iv == 1440:
                        q2 = (dt.date(start.year + (start.month + 2) // 12, (start.month + 2) % 12 + 1, 1))
                        n = (q2 - start.date()).days
                        data = [1000 + i for i in range(n)]               # Wh = 1000 + day index in quarter
                    else:
                        data = [50000 + m * 1000 for m in range(12)]      # Wh = 50 kWh + 1 kWh x month
                    res_ed = {"local_time": time.strftime("%Y-%m-%d %H:%M:%S"), "data": data,
                              "start_timestamp": st, "end_timestamp": pr["end_timestamp"], "interval": iv}
                    reply = {"error_code": 0, "result": res_ed}
            else:
                reply = {"error_code": 0, "result": res[meth]} if meth in res else {"error_code": -1010}
            sess._seq = seq; sess._generate_cipher()
            # device replies: 32 bytes signature + AES-CBC(key, iv+seq) ciphertext (same seq)
            sess._seq = seq - 1
            payload, _ = sess.encrypt(json.dumps(reply))
            return self._send(200, payload)
        self._send(404)

HTTPServer(("127.0.0.1", PORT), H).serve_forever()
