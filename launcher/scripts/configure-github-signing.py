#!/usr/bin/env python3
"""Connect the existing River Azure identity after repository access is granted."""
import argparse
import json
import pathlib
import subprocess

REPO = "WyZEU/RiverClient"
APP = "river-client-github-signing"
SUBJECT = f"repo:{REPO}:ref:refs/heads/master"


def read(*command):
    return json.loads(subprocess.check_output(command, text=True))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true", help="Set the three GitHub Actions secrets")
    args = parser.parse_args()
    account = read("az", "account", "show", "-o", "json")
    apps = read("az", "ad", "app", "list", "--display-name", APP, "-o", "json")
    if len(apps) != 1:
        raise RuntimeError(f"Expected exactly one Azure app named {APP}.")
    app = apps[0]
    signing = json.loads((pathlib.Path(__file__).resolve().parents[1] / "windows-signing.json").read_text())
    roles = read("az", "role", "assignment", "list", "--assignee", app["appId"], "--all", "-o", "json")
    profile_suffix = f"/codeSigningAccounts/{signing['account']}/certificateProfiles/{signing['profile']}"
    if not any(r["scope"].endswith(profile_suffix)
               and r["roleDefinitionId"].endswith("/2837e146-70d7-4cfd-ad55-7efa6464f958")
               and r["scope"].startswith(f"/subscriptions/{account['id']}/") for r in roles):
        raise RuntimeError("Select the Azure subscription containing River's certificate-profile signer role.")
    credentials = read("az", "ad", "app", "federated-credential", "list", "--id", app["id"], "-o", "json")
    if not any(c["subject"] == SUBJECT and c["issuer"] == "https://token.actions.githubusercontent.com"
               and c["audiences"] == ["api://AzureADTokenExchange"] for c in credentials):
        raise RuntimeError("The expected master-only GitHub federation is missing.")
    repo = read("gh", "api", f"repos/{REPO}")
    print(f"Azure identity: {APP}; GitHub write access: {repo['permissions']['push']}")
    if not args.apply:
        print("Check only. After access is granted, rerun with --apply.")
        return
    if not repo["permissions"]["push"]:
        raise RuntimeError("GitHub write access has not been granted yet.")
    values = {
        "AZURE_CLIENT_ID": app["appId"],
        "AZURE_TENANT_ID": account["tenantId"],
        "AZURE_SUBSCRIPTION_ID": account["id"],
    }
    for name, value in values.items():
        subprocess.run(["gh", "secret", "set", name, "--repo", REPO], input=value, text=True, check=True)
    configured = read("gh", "api", f"repos/{REPO}/actions/secrets")["secrets"]
    if not set(values).issubset({item["name"] for item in configured}):
        raise RuntimeError("GitHub secret metadata readback failed.")
    print("Verified GitHub secret names: " + ", ".join(values))


if __name__ == "__main__":
    main()
