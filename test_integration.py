#!/usr/bin/env python3
"""Layer 3: Agent + Session Integration Tests & Layer 4: Persistence"""
import json, urllib.request, urllib.error, sys, time, os, subprocess

BASE = "http://localhost:8080"
PASS = 0
FAIL = 0

def req(method, path, data=None, stream=False):
    url = BASE + path
    body = json.dumps(data).encode('utf-8') if data else None
    r = urllib.request.Request(url, data=body, method=method)
    r.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(r, timeout=60) as resp:
            if stream:
                return resp.status, resp.read().decode('utf-8')
            return resp.status, resp.read().decode('utf-8')
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode('utf-8')

def check(desc, condition, detail=""):
    global PASS, FAIL
    if condition:
        PASS += 1
        print(f"  [PASS] {desc}")
    else:
        FAIL += 1
        print(f"  [FAIL] {desc} {detail}")

print("=" * 60)
print("  Layer 3: Agent + Session Integration Tests")
print("=" * 60)

# 3.1 Agent think auto-creates session (no sessionId)
print("\n-- 3.1 POST /api/agent/think (auto-create session)")
status, body = req("POST", "/api/agent/think",
    {"message": "What is soil health?", "techniqueId": "", "deepThink": False}, stream=True)
check("Status 200", status == 200)
check("SSE response received", "data:" in body)
check("Contains AI response content", len(body) > 100)

# 3.2 Check session was auto-created
print("\n-- 3.2 Verify auto-created session")
status, body = req("GET", "/api/sessions?status=ACTIVE")
sessions = json.loads(body)
check("Has sessions", len(sessions) >= 1)
# Find the session with the test message
test_sid = None
for s in sessions:
    if s.get("messageCount", 0) > 0:
        test_sid = s["id"]
        break
check("Found session with messages", test_sid is not None)
if test_sid:
    print(f"    Auto-created session: {test_sid}")

    # 3.3 Check messages were persisted
    print(f"\n-- 3.3 GET /api/sessions/{test_sid}/messages")
    status, body = req("GET", f"/api/sessions/{test_sid}/messages?page=0&size=10")
    msgs = json.loads(body)
    check("Messages returned", len(msgs) >= 2)  # user + ai
    check("Has USER message", any(m["role"] == "USER" for m in msgs))
    check("Has ASSISTANT message", any(m["role"] == "ASSISTANT" for m in msgs))

    # 3.4 Send continuation message
    print(f"\n-- 3.4 POST /api/agent/think (continuation)")
    status, body = req("POST", "/api/agent/think",
        {"message": "Tell me more about nitrogen cycle", "sessionId": test_sid,
         "techniqueId": "", "deepThink": False, "continuationMode": True}, stream=True)
    check("Continuation status 200", status == 200)
    check("SSE response received", "data:" in body)

    # 3.5 Verify messages increased
    print(f"\n-- 3.5 Verify message count increased")
    status, body = req("GET", f"/api/sessions/{test_sid}")
    session = json.loads(body)
    check("Message count >= 4", session.get("messageCount", 0) >= 4,
          f"got {session.get('messageCount')}")

# 3.6 Test improvement mode (manual instruction)
print("\n-- 3.6 Test improvement mode (manual_instruction)")
# Create a new session for improvement test
status, body = req("POST", "/api/sessions", {"name": "Improvement Test"})
imp_sid = json.loads(body)["id"]
# First, get a normal response
status, _ = req("POST", "/api/agent/think",
    {"message": "Analyze SWOT for a small farm", "sessionId": imp_sid,
     "techniqueId": "040", "deepThink": False}, stream=True)
# Then improve
status, body = req("POST", "/api/agent/think",
    {"message": "Please add more detail to financial analysis",
     "sessionId": imp_sid, "techniqueId": "", "deepThink": False,
     "continuationMode": True, "improvementTarget": "manual_instruction",
     "improvementInstruction": "Add detailed cost breakdown and ROI calculation"},
    stream=True)
check("Improvement status 200", status == 200)
check("Improvement response received", "data:" in body)

# 3.7 Test export with sessionId saves output
print("\n-- 3.7 Test export saves session output")
try:
    # Export returns binary, don't try to decode
    url = BASE + "/api/export"
    data = json.dumps({"content": "# Test Report\n\nThis is a test export.", "format": "WORD",
         "title": "Integration Test Report", "techniqueLabel": "[040] SWOT",
         "sessionId": imp_sid}).encode('utf-8')
    r = urllib.request.Request(url, data=data, method="POST")
    r.add_header("Content-Type", "application/json")
    resp = urllib.request.urlopen(r, timeout=60)
    check("Export status 200", resp.status == 200)
    check("Export returned binary data", resp.getheader("Content-Type", "").find("word") >= 0 or len(resp.read()) > 100)
except Exception as e:
    check("Export works", False, str(e))

# Verify output was saved
status, body = req("GET", f"/api/sessions/{imp_sid}/outputs")
outputs = json.loads(body)
check("Output saved from export", len(outputs) >= 1)
check("Output has correct title", outputs[0]["title"] == "Integration Test Report")

# 3.8 Test output versioning via export
print("\n-- 3.8 Test output versioning via export")
try:
    url3 = BASE + "/api/export"
    data3 = json.dumps({"content": "# Improved v2\n\nBetter analysis.", "format": "WORD",
         "title": "Integration Test Report", "techniqueLabel": "[040] SWOT v2",
         "sessionId": imp_sid}).encode('utf-8')
    r3 = urllib.request.Request(url3, data=data3, method="POST")
    r3.add_header("Content-Type", "application/json")
    urllib.request.urlopen(r3, timeout=60)
except Exception as e:
    check("Export v2", False, str(e))
status, body = req("GET", f"/api/sessions/{imp_sid}/outputs")
outputs_v2 = json.loads(body)
check("Has 2 versions", len(outputs_v2) >= 2, f"got {len(outputs_v2)}")
latest = max(outputs_v2, key=lambda o: o.get("versionNumber", 0))
check("Latest is v2", latest.get("versionNumber") == 2, f"got {latest.get('versionNumber')}")

print(f"\n{'='*60}")
print(f"  Layer 3 Results: {PASS} passed, {FAIL} failed")
print(f"{'='*60}")

# ========================
# Layer 4: Persistence Test
# ========================
print("\n" + "=" * 60)
print("  Layer 4: Database Persistence Test")
print("=" * 60)

print("\n-- 4.1 Check H2 file database exists")
h2_path = "D:/myclaude/projects/xuzhenwei-agent/data/xuzhenwei-agent.mv.db"
check("H2 file exists", os.path.exists(h2_path), h2_path)
if os.path.exists(h2_path):
    size_kb = os.path.getsize(h2_path) // 1024
    print(f"    Database size: {size_kb} KB")

# Record the session ID and message count before restart
print(f"\n-- 4.2 Record state before restart")
status, body = req("GET", f"/api/sessions/{test_sid}")
before = json.loads(body)
before_count = before.get("messageCount", 0)
print(f"    Session: {test_sid}, Messages: {before_count}")

# Stop the server
print("\n-- 4.3 Stopping server...")
os.system("taskkill /F /IM java.exe 2>nul")
time.sleep(5)

# Start server again
print("-- 4.4 Starting server...")
os.chdir("D:/myclaude/projects/xuzhenwei-agent")
subprocess.Popen(["mvn", "spring-boot:run", "-q"],
    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, shell=True)
# Wait for ready
for i in range(30):
    try:
        urllib.request.urlopen(BASE + "/api/sessions", timeout=3)
        print(f"    Server ready after {i+1}s")
        break
    except:
        time.sleep(1)

# 4.5 Verify data survived restart
print("\n-- 4.5 Verify data after restart")
status, body = req("GET", f"/api/sessions/{test_sid}")
after = json.loads(body)
check("Session still exists", status == 200)
check("Message count preserved", after.get("messageCount") == before_count,
      f"before={before_count}, after={after.get('messageCount')}")
check("Name preserved", after.get("name") == before.get("name"))

# 4.6 Verify messages survived
status, body = req("GET", f"/api/sessions/{test_sid}/messages")
msgs_after = json.loads(body)
check("Messages survived restart", len(msgs_after) >= 2)
check("Message content intact", len(msgs_after[0].get("content", "")) > 0)

print(f"\n{'='*60}")
print(f"  Layer 4 Results: Layer3({PASS} passed) + Persistence checks above")
print(f"{'='*60}")
print(f"\n  TOTAL: {PASS} passed, {FAIL} failed")
