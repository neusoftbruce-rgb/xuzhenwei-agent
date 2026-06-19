#!/usr/bin/env python3
"""Comprehensive test script for the Session Memory System"""
import json, urllib.request, urllib.error, sys, time

BASE = "http://localhost:8080"
PASS = 0
FAIL = 0

def req(method, path, data=None):
    """Make HTTP request and return (status, body)"""
    url = BASE + path
    body = json.dumps(data).encode('utf-8') if data else None
    r = urllib.request.Request(url, data=body, method=method)
    r.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(r, timeout=30) as resp:
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
print("  Layer 2: SessionController API Tests")
print("=" * 60)

# 2.1 Create session
print("\n-- 2.1 POST /api/sessions (Create)")
status, body = req("POST", "/api/sessions", {"name": "API Test Session", "topic": "Testing persistence"})
s1 = json.loads(body)
check("Status 200", status == 200, f"got {status}")
check("Has id field", "id" in s1)
check("Has name", s1.get("name") == "API Test Session")
sid = s1.get("id", "")
print(f"    Session ID: {sid}")

# 2.2 List sessions
print("\n-- 2.2 GET /api/sessions (List)")
status, body = req("GET", "/api/sessions")
l1 = json.loads(body)
check("Status 200", status == 200)
check("Returns list", isinstance(l1, list))
check("Contains created session", any(s["id"] == sid for s in l1))

# 2.3 Get session detail
print(f"\n-- 2.3 GET /api/sessions/{sid} (Detail)")
status, body = req("GET", f"/api/sessions/{sid}")
d1 = json.loads(body)
check("Status 200", status == 200)
check("Name matches", d1.get("name") == "API Test Session")
check("Status is ACTIVE", d1.get("status") == "ACTIVE")

# 2.4 Update session
print(f"\n-- 2.4 PUT /api/sessions/{sid} (Update)")
status, body = req("PUT", f"/api/sessions/{sid}", {"name": "Updated Session", "tags": "test,important"})
u1 = json.loads(body)
check("Status 200", status == 200)
check("Name updated", u1.get("name") == "Updated Session")
check("Tags updated", "test,important" in (u1.get("tags") or ""))

# 2.5 Archive session
print(f"\n-- 2.5 PUT /api/sessions/{sid}/archive")
status, body = req("PUT", f"/api/sessions/{sid}/archive")
check("Status 200", status == 200)
a1 = json.loads(body)
check("Archived status", a1.get("status") == "ARCHIVED")

# 2.6 List only ACTIVE
print("\n-- 2.6 GET /api/sessions?status=ACTIVE (should exclude archived)")
status, body = req("GET", "/api/sessions?status=ACTIVE")
actives = json.loads(body)
check("Status 200", status == 200)
check("Archived not in active list", not any(s["id"] == sid for s in actives))

# 2.7 Create second session
print("\n-- 2.7 Create second session")
status, body = req("POST", "/api/sessions", {"name": "Second Session"})
s2 = json.loads(body)
sid2 = s2.get("id", "")
check("Created successfully", "id" in s2)
print(f"    Session ID: {sid2}")

# 2.8 Save output
print(f"\n-- 2.8 POST /api/sessions/{sid2}/outputs")
status, body = req("POST", f"/api/sessions/{sid2}/outputs",
    {"format": "WORD", "title": "Test Report", "content": "This is test content", "techniqueLabel": "[001] Test"})
o1 = json.loads(body)
check("Status 200", status == 200)
check("Version 1", o1.get("versionNumber") == 1)

# 2.9 Save output again (v2)
print(f"\n-- 2.9 POST /api/sessions/{sid2}/outputs (v2)")
status, body = req("POST", f"/api/sessions/{sid2}/outputs",
    {"format": "WORD", "title": "Test Report", "content": "Improved content v2", "techniqueLabel": "[001] Test Improved"})
o2 = json.loads(body)
check("Status 200", status == 200)
check("Version 2", o2.get("versionNumber") == 2)
check("Parent points to v1", o2.get("parentOutputId") == o1.get("id"))

# 2.10 List outputs
print(f"\n-- 2.10 GET /api/sessions/{sid2}/outputs")
status, body = req("GET", f"/api/sessions/{sid2}/outputs")
ol = json.loads(body)
check("Status 200", status == 200)
check("Has 2 outputs", len(ol) >= 2)

# 2.11 Soft delete
print(f"\n-- 2.11 DELETE /api/sessions/{sid2}")
status, body = req("DELETE", f"/api/sessions/{sid2}")
dl = json.loads(body)
check("Status 200", status == 200)
check("Status deleted", dl.get("status") == "deleted")

# 2.12 Search
print(f"\n-- 2.12 GET /api/sessions/search?q=Updated")
status, body = req("GET", f"/api/sessions/search?q=Updated&limit=10")
sr = json.loads(body)
check("Status 200", status == 200)
check("Found updated session", any(s["id"] == sid for s in sr))

print(f"\n{'='*60}")
print(f"  Layer 2 Results: {PASS} passed, {FAIL} failed")
print(f"{'='*60}")
