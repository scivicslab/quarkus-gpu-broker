# Runs declare-capacity.sh against stand-ins for vLLM's /metrics, the Keycloak token endpoint (HTTPS, self-signed
# certificate checked against keycloak-ca.pem) and the broker. Nothing outside this machine is touched, and the "secret" is
# a made-up string. Run: python3 test_declare_capacity.py
import json, os, ssl, stat, subprocess, sys, tempfile, threading, urllib.parse
from http.server import BaseHTTPRequestHandler, HTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, "declare-capacity.sh")
SECRET = "made-up-secret-0123456789abcdef"
TOKEN = "made-up-token-fedcba"
seen = {"token_requests": [], "puts": []}
mode = {"token": "ok", "broker": 204}

class Quiet(BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def send(self, code, body=b"", ctype="text/plain"):
        self.send_response(code); self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body)

class Metrics(Quiet):
    def do_GET(self):
        self.send(200, b"process_start_time_seconds 1790000000.5\nvllm:num_requests_running 0\n")

class Token(Quiet):
    def do_POST(self):
        form = urllib.parse.parse_qs(self.rfile.read(int(self.headers["Content-Length"])).decode())
        seen["token_requests"].append({"form": form, "argv_has_secret": False})
        if mode["token"] == "ok" and form.get("client_secret") == [SECRET] and form.get("grant_type") == ["client_credentials"]:
            self.send(200, json.dumps({"access_token": TOKEN, "expires_in": 300}).encode(), "application/json")
        else:
            self.send(401, b'{"error":"unauthorized_client"}', "application/json")

class Broker(Quiet):
    def do_PUT(self):
        body = self.rfile.read(int(self.headers["Content-Length"])).decode()
        seen["puts"].append({"path": self.path, "auth": self.headers.get("Authorization"), "body": json.loads(body)})
        self.send(mode["broker"])

def serve(handler, tls=None):
    s = HTTPServer(("127.0.0.1", 0), handler)
    if tls: 
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER); ctx.load_cert_chain(*tls); s.socket = ctx.wrap_socket(s.socket, server_side=True)
    threading.Thread(target=s.serve_forever, daemon=True).start()
    return s.server_address[1]

work = tempfile.mkdtemp(prefix="declare-capacity-test-")
cert, key = os.path.join(work, "cert.pem"), os.path.join(work, "key.pem")
subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", key, "-out", cert, "-days", "2",
                "-subj", "/CN=127.0.0.1", "-addext", "subjectAltName=IP:127.0.0.1"], check=True, capture_output=True)
metrics_port, token_port, broker_port = serve(Metrics), serve(Token, (cert, key)), serve(Broker)

def conf(secret_mode=0o600, with_secret=True, with_ca=True):
    d = tempfile.mkdtemp(dir=work)
    if with_secret:
        p = os.path.join(d, "capacity-client.secret")
        fd = os.open(p, os.O_WRONLY | os.O_CREAT, 0o600)
        with os.fdopen(fd, "w") as f: f.write(f"client_id=capacity-writer-test\nclient_secret={SECRET}\n")
        os.chmod(p, secret_mode)
    if with_ca:
        open(os.path.join(d, "keycloak-ca.pem"), "w").write(open(cert).read())
    return d

def run(d):
    seen["token_requests"].clear(); seen["puts"].clear()
    env = dict(os.environ, GPU_BROKER_CONFIG_DIR=d, TOKEN_ATTEMPTS="2", TOKEN_RETRY_SECONDS="0", METRICS_WAIT_SECONDS="0",
               KEYCLOAK_TOKEN_URL=f"https://127.0.0.1:{token_port}/token")
    r = subprocess.run(["bash", SCRIPT, str(metrics_port), "32", f"http://127.0.0.1:{broker_port}"],
                       env=env, capture_output=True, text=True, timeout=60)
    out = r.stdout + r.stderr
    assert SECRET not in out and TOKEN not in out, "a secret or token reached the output: " + out
    return r.returncode, out

# 1. the happy path
mode.update(token="ok", broker=204)
rc, out = run(conf())
assert rc == 0, out
assert len(seen["puts"]) == 1 and seen["puts"][0]["auth"] == f"Bearer {TOKEN}", seen
assert seen["puts"][0]["body"] == {"maxConcurrency": 32, "serverStartTime": 1790000000.5}, seen
assert seen["puts"][0]["path"].endswith(f":{metrics_port}"), seen["puts"][0]["path"]
assert "-> HTTP 204" in out

# 2. a secret file that others can read is refused, and nothing is sent anywhere
rc, out = run(conf(secret_mode=0o644))
assert rc == 1 and "must be mode 600" in out and not seen["token_requests"] and not seen["puts"], (rc, out, seen)
rc, out = run(conf(secret_mode=0o640))
assert rc == 1 and not seen["token_requests"] and not seen["puts"], (rc, out)

# 3. no secret file
rc, out = run(conf(with_secret=False))
assert rc == 1 and "nothing declared" in out and not seen["token_requests"] and not seen["puts"], (rc, out)

# 4. no CA file: the certificate is not left unchecked
rc, out = run(conf(with_ca=False))
assert rc == 1 and "keycloak-ca.pem" in out and not seen["token_requests"] and not seen["puts"], (rc, out)

# 5. a certificate that is not the one in keycloak-ca.pem is not trusted
other = tempfile.mkdtemp(dir=work)
subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", os.path.join(other, "k"),
                "-out", os.path.join(other, "c"), "-days", "2", "-subj", "/CN=127.0.0.1",
                "-addext", "subjectAltName=IP:127.0.0.1"], check=True, capture_output=True)
d = conf(with_ca=False); open(os.path.join(d, "keycloak-ca.pem"), "w").write(open(os.path.join(other, "c")).read())
rc, out = run(d)
assert rc == 1 and not seen["token_requests"] and not seen["puts"], (rc, out)

# 6. Keycloak refuses the client: no PUT, and the attempts are the configured number
mode.update(token="refuse")
rc, out = run(conf())
assert rc == 1 and not seen["puts"] and len(seen["token_requests"]) == 2 and "could not get a token" in out, (rc, out, seen)

# 7. the broker refuses the token: the script says so and fails
mode.update(token="ok", broker=401)
rc, out = run(conf())
assert rc == 1 and "-> HTTP 401" in out and len(seen["puts"]) == 1, (rc, out)

# 8. the secret and the token go to curl on standard input, never as arguments
src = open(SCRIPT).read()
assert "-d @-" in src and "-K -" in src
assert "--data-urlencode" not in src and 'Authorization: Bearer $' not in src
print("declare-capacity.sh: all checks passed")
