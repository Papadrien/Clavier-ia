#!/usr/bin/env python3
"""Envoie un AAB (et son mapping R8) dans un canal de Google Play, via l'API Google Play Developer (v3).

Utilisé par la CI (.github/workflows/android.yml, job play-upload) à chaque push sur main. Bibliothèque standard seulement,
plus `openssl` pour signer le jeton : la clé du compte de service n'est confiée à aucune action ni dépendance tierce.

La clé JSON du compte de service est lue dans la variable d'environnement PLAY_SERVICE_ACCOUNT_JSON (secret GitHub de
l'environnement `play`). Elle n'est jamais affichée ; la clé privée n'est écrite que le temps de la signature, dans un fichier
lisible par le seul utilisateur, supprimé aussitôt.

Étapes de l'API : créer une modification (edit), y envoyer le bundle, puis le mapping, placer la version dans le canal,
valider (commit). En cas d'erreur, la modification n'est pas validée : rien ne change sur Play.

Notes de version (--release-notes DOSSIER) : un fichier <langue>.txt par langue de l'application (en.txt, fr.txt, pt.txt, nb.txt…).
Chaque langue de la fiche Play Store reçoit le fichier de sa langue (fr-FR → fr.txt, pt-BR → pt.txt, no-NO → nb.txt), sinon en.txt.
Play limite une note à 500 caractères : vérifié avant d'ouvrir la modification.

Usage : play_upload.py --package fr.junade.taipo --track alpha --aab app-release.aab [--mapping mapping.txt]
        [--release-notes distribution/release-notes] [--status completed]
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
MAX_NOTES_LENGTH = 500  # limite de Play pour les notes de version, par langue
FALLBACK_LANGUAGE = "en"


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


def load_release_notes(directory: str) -> dict[str, str]:
    """Notes par code de langue (nom du fichier sans .txt). Échec franc si une note est vide ou trop longue pour Play."""
    notes = {}
    for name in sorted(os.listdir(directory)):
        if not name.endswith(".txt"):
            continue
        with open(os.path.join(directory, name), encoding="utf-8") as notes_file:
            text = notes_file.read().strip()
        if not text or len(text) > MAX_NOTES_LENGTH:
            sys.exit(f"::error::{name} : {len(text)} caractères, Play accepte de 1 à {MAX_NOTES_LENGTH}")
        notes[name[:-4]] = text
    if FALLBACK_LANGUAGE not in notes:
        sys.exit(f"::error::{FALLBACK_LANGUAGE}.txt absent de {directory} (note de repli)")
    return notes


def notes_for(language: str, notes: dict[str, str]) -> str:
    """Note d'une langue de la fiche Play (fr-FR, pt-BR, no-NO…) : langue exacte, puis langue sans région, puis anglais."""
    primary = language.split("-")[0]
    if primary == "no":  # Play écrit le norvégien « no-NO », l'application « nb »
        primary = "nb"
    for code in (language, primary):
        if code in notes:
            return notes[code]
    print(f"::warning::pas de note de version pour {language} : texte anglais utilisé")
    return notes[FALLBACK_LANGUAGE]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--package", required=True)
    parser.add_argument("--track", required=True)
    parser.add_argument("--aab", required=True)
    parser.add_argument("--mapping")
    parser.add_argument("--release-notes", help="dossier des notes de version, un <langue>.txt par langue")
    parser.add_argument("--status", default="completed", choices=["completed", "draft"])
    args = parser.parse_args()

    notes = load_release_notes(args.release_notes) if args.release_notes else None

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

    release = {"versionCodes": [str(version_code)], "status": args.status}
    if notes:
        # Langues de la fiche Play Store : Play refuse une note dans une langue que la fiche n'a pas.
        listings = request("GET", f"{app}/edits/{edit_id}/listings", None, auth).get("listings", [])
        languages = [listing["language"] for listing in listings] or ["en-US"]
        release["releaseNotes"] = [{"language": language, "text": notes_for(language, notes)} for language in languages]
        print(f"Notes de version : {', '.join(languages)}")
    track = {"track": args.track, "releases": [release]}
    request("PUT", f"{app}/edits/{edit_id}/tracks/{args.track}", json.dumps(track).encode(),
            {**auth, "Content-Type": "application/json"})
    request("POST", f"{app}/edits/{edit_id}:commit", b"", auth)
    print(f"✅ versionCode {version_code} publié dans le canal « {args.track} » ({args.status})")


if __name__ == "__main__":
    main()
