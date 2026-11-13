#!/usr/bin/env python3
"""Envoie un AAB (et son mapping R8) dans un canal de Google Play, via l'API Google Play Developer (v3).

Utilisé par la CI (.github/workflows/android.yml, job play-upload) à chaque push sur main. Bibliothèque standard seulement,
plus `openssl` pour signer le jeton : la clé du compte de service n'est confiée à aucune action ni dépendance tierce.

La clé JSON du compte de service est lue dans la variable d'environnement PLAY_SERVICE_ACCOUNT_JSON (secret GitHub de
l'environnement `play`). Elle n'est jamais affichée ; la clé privée n'est écrite que le temps de la signature, dans un fichier
lisible par le seul utilisateur, supprimé aussitôt.

Étapes de l'API : créer une modification (edit), y envoyer le bundle, puis le mapping, placer la version dans le canal,
valider (commit). En cas d'erreur, la modification n'est pas validée : rien ne change sur Play.

Usage : play_upload.py --package fr.junade.taipo --track alpha --aab app-release.aab [--mapping mapping.txt] [--status completed]
"""

import argparse
import base64
import json
import os
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request

API = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications"
UPLOAD_API = "https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications"
SCOPE = "https://www.googleapis.com/auth/androidpublisher"


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def access_token(account: dict) -> str:
    """Jeton OAuth du compte de service (JWT signé RS256 par openssl, échangé contre un jeton d'accès)."""
    token_uri = account.get("token_uri", "https://oauth2.googleapis.com/token")
    now = int(time.time())
    header = b64url(json.dumps({"alg": "RS256", "typ": "JWT"}).encode())
    claims = b64url(json.dumps({
        "iss": account["client_email"],
        "scope": SCOPE,
        "aud": token_uri,
        "iat": now,
        "exp": now + 3600,
    }).encode())
    signing_input = f"{header}.{claims}".encode()
    fd, key_path = tempfile.mkstemp(suffix=".pem")
    try:
        with os.fdopen(fd, "w") as key_file:  # créé en 0600 par mkstemp
            key_file.write(account["private_key"])
        signature = subprocess.run(
            ["openssl", "dgst", "-sha256", "-sign", key_path],
            input=signing_input, capture_output=True, check=True,
        ).stdout
    finally:
        os.remove(key_path)
    assertion = f"{header}.{claims}.{b64url(signature)}"
    body = urllib.parse.urlencode({
        "grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer",
        "assertion": assertion,
    }).encode()
    return request("POST", token_uri, body, {"Content-Type": "application/x-www-form-urlencoded"})["access_token"]


def request(method: str, url: str, body: bytes | None = None, headers: dict | None = None, timeout: int = 600) -> dict:
    req = urllib.request.Request(url, data=body, method=method, headers=headers or {})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as response:
            payload = response.read()
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", "replace")
        # Le message de Google explique la cause (droits manquants, versionCode déjà utilisé, canal inconnu…).
        sys.exit(f"::error::{method} {url.split('?')[0]} : HTTP {error.code}\n{detail}")
    return json.loads(payload) if payload else {}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--package", required=True)
    parser.add_argument("--track", required=True)
    parser.add_argument("--aab", required=True)
    parser.add_argument("--mapping")
    parser.add_argument("--status", default="completed", choices=["completed", "draft"])
    args = parser.parse_args()

    raw = os.environ.get("PLAY_SERVICE_ACCOUNT_JSON", "")
    if not raw.strip():
        sys.exit("::error::PLAY_SERVICE_ACCOUNT_JSON absent")
    account = json.loads(raw)
    auth = {"Authorization": f"Bearer {access_token(account)}"}
    app = f"{API}/{args.package}"
    upload = f"{UPLOAD_API}/{args.package}"

    edit_id = request("POST", f"{app}/edits", b"{}", {**auth, "Content-Type": "application/json"})["id"]
    print(f"Modification Play ouverte : {edit_id}")

    with open(args.aab, "rb") as aab:
        bundle = request(
            "POST", f"{upload}/edits/{edit_id}/bundles?uploadType=media", aab.read(),
            {**auth, "Content-Type": "application/octet-stream"},
        )
    version_code = bundle["versionCode"]
    print(f"Bundle envoyé : versionCode {version_code}")

    if args.mapping and os.path.isfile(args.mapping):
        with open(args.mapping, "rb") as mapping:
            request(
                "POST",
                f"{upload}/edits/{edit_id}/apks/{version_code}/deobfuscationFiles/proguard?uploadType=media",
                mapping.read(), {**auth, "Content-Type": "application/octet-stream"},
            )
        print("Mapping R8 envoyé")

    track = {"track": args.track, "releases": [{"versionCodes": [str(version_code)], "status": args.status}]}
    request("PUT", f"{app}/edits/{edit_id}/tracks/{args.track}", json.dumps(track).encode(),
            {**auth, "Content-Type": "application/json"})
    request("POST", f"{app}/edits/{edit_id}:commit", b"", auth)
    print(f"✅ versionCode {version_code} publié dans le canal « {args.track} » ({args.status})")


if __name__ == "__main__":
    main()
