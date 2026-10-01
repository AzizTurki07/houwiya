"""
End-to-end check of the running stack (backend + AI service + Postgres), through the public
API only, with the synthetic inputs from make_fixtures.py. Standard library only.

    python e2e/run_e2e.py --api http://localhost:8080/api --fixtures e2e/out

Admin credentials come from ADMIN_EMAIL / ADMIN_PASSWORD (the backend creates that account
at startup). Prints each step; with --summary FILE also writes a Markdown table (used for
the GitHub Actions job summary). Exits non-zero on the first failed step.
"""
import argparse
import json
import mimetypes
import os
import sys
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path

PASSPORT_A = "PT4021988"
PASSPORT_B = "PT7730154"


class StepFailed(AssertionError):
    pass


class Api:
    def __init__(self, base: str):
        self.base = base.rstrip("/")

    def call(self, method, path, token=None, body=None, files=None, fields=None):
        """Returns (status, parsed JSON or raw bytes). Never raises on HTTP errors."""
        headers = {}
        data = None
        if token:
            headers["Authorization"] = f"Bearer {token}"
        if files is not None:
            boundary = uuid.uuid4().hex
            data = _multipart(boundary, fields or {}, files)
            headers["Content-Type"] = f"multipart/form-data; boundary={boundary}"
        elif body is not None:
            data = json.dumps(body).encode()
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(self.base + path, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(request, timeout=120) as response:
                return response.status, _decode(response.read(), response.headers.get("Content-Type", ""))
        except urllib.error.HTTPError as err:
            return err.code, _decode(err.read(), err.headers.get("Content-Type", ""))


def _decode(raw: bytes, content_type: str):
    if "json" in content_type and raw:
        return json.loads(raw)
    return raw


def _multipart(boundary: str, fields: dict, files: list) -> bytes:
    parts = []
    for name, value in fields.items():
        parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"\r\n\r\n{value}\r\n'.encode())
    for name, path in files:
        ctype = mimetypes.guess_type(path.name)[0] or "application/octet-stream"
        head = (f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"; filename="{path.name}"\r\n'
                f"Content-Type: {ctype}\r\n\r\n").encode()
        parts.append(head + path.read_bytes() + b"\r\n")
    parts.append(f"--{boundary}--\r\n".encode())
    return b"".join(parts)


def expect(condition: bool, message: str) -> None:
    if not condition:
        raise StepFailed(message)


def expect_status(actual, expected, response) -> None:
    expect(actual == expected, f"HTTP {actual}, expected {expected}: {str(response)[:300]}")


def wait_for_backend(api: Api, timeout_s: int) -> None:
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        try:
            status, _ = api.call("POST", "/auth/login", body={})
            if status in (400, 401):
                return
        except OSError:
            pass
        time.sleep(3)
    raise SystemExit(f"backend did not come up within {timeout_s}s")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--api", default="http://localhost:8080/api")
    parser.add_argument("--fixtures", default="e2e/out", type=Path)
    parser.add_argument("--summary")
    parser.add_argument("--wait", type=int, default=180, help="seconds to wait for the backend")
    args = parser.parse_args()

    api = Api(args.api)
    fx = args.fixtures
    admin_email, admin_password = os.environ["ADMIN_EMAIL"], os.environ["ADMIN_PASSWORD"]
    wait_for_backend(api, args.wait)

    state: dict = {}
    results: list[tuple[str, bool, str]] = []

    def step(name):
        def run(fn):
            started = time.time()
            try:
                note = fn() or ""
                results.append((name, True, f"{note} ({time.time() - started:.1f}s)".strip()))
                print(f"PASS  {name}  {note}")
            except StepFailed as err:
                results.append((name, False, str(err)))
                print(f"FAIL  {name}  {err}")
                raise
        return run

    def register():
        email = f"e2e-{uuid.uuid4().hex[:8]}@example.com"
        status, body = api.call("POST", "/auth/register", body={"email": email, "password": "e2e-password-123"})
        expect_status(status, 200, body)
        return body["token"]

    def new_session(token):
        status, body = api.call("POST", "/onboarding/sessions", token)
        expect_status(status, 200, body)
        sid = body["id"]
        status, body = api.call("POST", f"/onboarding/sessions/{sid}/consent", token)
        expect_status(status, 200, body)
        return sid

    def upload(token, sid, passport):
        return api.call("POST", f"/onboarding/sessions/{sid}/document", token,
                        files=[("file", fx / passport)], fields={"documentType": "PASSPORT", "side": "FRONT"})

    def selfie(token, sid, frame):
        return api.call("POST", f"/onboarding/sessions/{sid}/selfie", token, files=[("frames", fx / frame)] * 3)

    try:
        @step("API refuses requests without a token")
        def _():
            status, _ = api.call("GET", "/onboarding/sessions")
            expect_status(status, 401, "")

        @step("Applicant registers, starts a session and consents")
        def _():
            state["a"] = register()
            state["sid_a"] = new_session(state["a"])

        @step("Passport upload: MRZ read by the AI service")
        def _():
            status, doc = upload(state["a"], state["sid_a"], "passport_a.png")
            expect_status(status, 200, doc)
            expect(doc["fields"].get("document_number") == PASSPORT_A, f"number read as {doc['fields'].get('document_number')!r}")
            expect(doc["checksumValid"] is True, "MRZ check digits failed")
            expect(doc["selfieRequired"] is True and doc["selfieCaptured"] is False, "selfie should be required, not taken")
            state["fields_a"] = doc["fields"]
            return f"number {PASSPORT_A}, OCR confidence {doc['ocrConfidence']}"

        @step("Confirm is refused before the selfie")
        def _():
            status, body = api.call("POST", f"/onboarding/sessions/{state['sid_a']}/confirm", state["a"],
                                    body={"fields": state["fields_a"]})
            expect_status(status, 409, body)

        @step("Selfie (same face, still photo): match, liveness fails, no score shown")
        def _():
            status, doc = selfie(state["a"], state["sid_a"], "selfie.jpg")
            expect_status(status, 200, doc)
            expect(doc["faceMatched"] is True, f"faceMatched={doc['faceMatched']}")
            expect(doc["livenessPassed"] is False, "a still photo must not pass liveness")
            expect("LIVENESS_FAILED" in doc["warnings"], f"warnings={doc['warnings']}")
            expect("faceSimilarity" not in doc, "the applicant must never see the similarity score")
            return f"warnings {doc['warnings']}"

        @step("Confirm: goes to the review queue")
        def _():
            status, doc = api.call("POST", f"/onboarding/sessions/{state['sid_a']}/confirm", state["a"],
                                   body={"fields": state["fields_a"]})
            expect_status(status, 200, doc)
            expect(doc["reviewStatus"] == "NEEDS_REVIEW", f"reviewStatus={doc['reviewStatus']}")
            state["doc_a"] = doc["id"]

        @step("Another applicant can't reuse the same passport")
        def _():
            state["b"] = register()
            state["sid_b"] = new_session(state["b"])
            status, body = upload(state["b"], state["sid_b"], "passport_a.png")
            expect_status(status, 409, body)

        @step("Different face on another passport: face mismatch")
        def _():
            status, doc = upload(state["b"], state["sid_b"], "passport_b.png")
            expect_status(status, 200, doc)
            status, doc = selfie(state["b"], state["sid_b"], "other.jpg")
            expect_status(status, 200, doc)
            expect(doc["faceMatched"] is False, f"faceMatched={doc['faceMatched']}")
            expect("FACE_MISMATCH" in doc["warnings"], f"warnings={doc['warnings']}")
            return f"warnings {doc['warnings']}"

        @step("Admin sees the case with the selfie and the face score")
        def _():
            status, body = api.call("POST", "/auth/login", body={"email": admin_email, "password": admin_password})
            expect_status(status, 200, body)
            state["admin"] = body["token"]
            status, queue = api.call("GET", "/admin/reviews", state["admin"])
            expect_status(status, 200, queue)
            expect(any(item["documentId"] == state["doc_a"] for item in queue), "document missing from the queue")
            status, detail = api.call("GET", f"/admin/reviews/{state['doc_a']}", state["admin"])
            expect_status(status, 200, detail)
            expect(detail["faceSimilarity"] is not None and detail["faceSimilarity"] >= detail["faceThreshold"],
                   f"similarity {detail['faceSimilarity']} vs threshold {detail['faceThreshold']}")
            expect({"FRONT", "SELFIE"} <= set(detail["imageSides"]), f"imageSides={detail['imageSides']}")
            status, image = api.call("GET", f"/admin/reviews/{state['doc_a']}/images/SELFIE", state["admin"])
            expect_status(status, 200, "")
            expect(isinstance(image, bytes) and len(image) > 1000, "selfie image is empty")
            return f"similarity {detail['faceSimilarity']:.2f} (threshold {detail['faceThreshold']})"

        @step("Admin approves: applicant verified, photos deleted")
        def _():
            status, detail = api.call("POST", f"/admin/reviews/{state['doc_a']}/decision", state["admin"],
                                      body={"decision": "APPROVE"})
            expect_status(status, 200, detail)
            expect(detail["imageSides"] == [], f"photos still stored: {detail['imageSides']}")
            status, session = api.call("GET", f"/onboarding/sessions/{state['sid_a']}", state["a"])
            expect_status(status, 200, session)
            expect(session["status"] == "APPROVED", f"session status {session['status']}")
            status, _ = api.call("GET", f"/admin/reviews/{state['doc_a']}/images/FRONT", state["admin"])
            expect_status(status, 404, "")
    except StepFailed:
        pass
    finally:
        if args.summary:
            write_summary(Path(args.summary), results)

    return 0 if results and all(ok for _, ok, _ in results) else 1


def write_summary(path: Path, results) -> None:
    passed = sum(ok for _, ok, _ in results)
    lines = [f"### End-to-end: {passed}/{len(results)} steps passed", "",
             "| | Step | Details |", "|---|---|---|"]
    for name, ok, note in results:
        lines.append(f"| {'✅' if ok else '❌'} | {name} | {note.replace('|', '/')} |")
    lines += ["", "_Synthetic passports and public-domain sample faces only (see e2e/make_fixtures.py)._", ""]
    with path.open("a", encoding="utf-8") as f:
        f.write("\n".join(lines))


if __name__ == "__main__":
    sys.exit(main())
