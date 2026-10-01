# Secret Rotation and Git History Purge

> **REQUIRES EXPLICIT APPROVAL — NOT EXECUTED**
>
> Nothing in this document has been run. Every command rewrites shared history, changes credentials, or both. The repository owner executes it manually, in order, after approving it.

Scope: repository `https://github.com/ayberkaarda/EduCore.git`. Prepared in Phase P0 (2026-10-01). Secret values are never written here; they are referred to by the environment variable that now carries them.

## 0. Why both steps are needed

Every value that was committed must be treated as compromised (rule R7). Purging history reduces further exposure, but it cannot recall copies that already exist in clones, forks, CI caches or GitHub's cached views. **Rotation is the real remedy; the purge is hygiene.** Rotate first, purge second.

## 1. Rotation list

| # | Credential | Where it was committed | New home | Rotation action |
|---|---|---|---|---|
| R-1 | PostgreSQL password of the application role (`postgres`) | `src/main/resources/application.properties`, `config-repo/educore.yml`, `docker-compose.yml` | `EDUCORE_DB_PASSWORD` (`.env`, never committed) | Change the role password in every database that ever used it (section 1.1). |
| R-2 | JWT HMAC signing key | `src/main/java/com/example/project3/security/JwtService.java` (`SECRET_KEY`) | `EDUCORE_JWT_SECRET` (base64, at least 32 decoded bytes) | Generate a new key (section 1.2). Every token signed with the old key becomes invalid; users log in again. |
| R-3 | Seeded account passwords (`admin` and seeded users) and default student passwords | `src/main/java/com/example/project3/config/DataSeeder.java`, `BatchConfig.java`, `ApiController.java`, `StudentMultiThreadService.java`, `StudentSoapEndpoint.java` | Reworked in P1/P2 | On every deployed instance, change the password of each seeded account and of every account created through CSV, API or SOAP import (section 1.3). |
| R-4 | Neon credentials | Not found | — | Conditional, see section 1.4. |

### 1.1 Database password (R-1)

1. Generate a new value locally (Git Bash): `openssl rand -base64 32`
2. Put it into the local, untracked `.env` as `EDUCORE_DB_PASSWORD`.
3. Change the role password inside each running PostgreSQL that used the old value. `POSTGRES_PASSWORD` is applied only when a Docker volume is initialised for the first time, so an existing volume keeps the old password until it is changed explicitly:

   ```sh
   docker compose exec postgres-db psql -U postgres -c '\password postgres'
   ```

   `\password` prompts twice and sends the value hashed, so it does not appear in shell history or server logs. For a non-Docker local PostgreSQL run `psql -U postgres -c '\password postgres'` against that server.
4. Restart the backend with the new `.env` and confirm it starts and connects.
5. If the old password was reused anywhere else (other databases, accounts, services), change it there too.

### 1.2 JWT signing key (R-2)

1. Generate: `openssl rand -base64 48`
2. Store it as `EDUCORE_JWT_SECRET` in `.env` (and in any deployment secret store).
3. Restart the backend. All existing tokens fail validation; this is intended.

### 1.3 Seeded and default account passwords (R-3)

For every environment that ran `DataSeeder` or imported students: log in as each seeded account and change its password, or set a new BCrypt hash directly in the `account` table. Accounts created via CSV/API/SOAP share a known default; until P2 introduces forced reset, change them or disable them on any instance reachable by others.

### 1.4 Neon (R-4)

Evidence read in the working tree on 2026-10-01:

- `frontend/.neon` (69 bytes) contains only an initialisation marker declaring the `database` feature. It has no project id, branch id, host name, role or connection string.
- `frontend/skills-lock.json` pins two agent skills from `neondatabase/agent-skills`; it holds hashes only.
- No `neon.tech` host, `neondb` database name or Neon connection string exists anywhere else in the working tree.

Conclusion: the working tree does not imply a live Neon project or contain Neon credentials. Git history was not inspected (no git commands in P0). Before closing R-4, the owner checks the Neon console for a project created around the date `frontend/.neon` was added; if one exists and is unused, delete it, otherwise reset its role passwords and API keys. The history check is `git log --all -p -S neon.tech` on any clone.

## 2. History purge with git filter-repo

Prerequisites: rotation (section 1) is complete; `git filter-repo` is installed (`pip install git-filter-repo`); all collaborators have pushed or discarded pending work; branch protection on GitHub allows force-pushes for the duration of the operation.

Work happens in a separate mirror clone next to the working copy, never inside it. The sensitive-value files live in a directory outside every repository and are deleted at the end.

### 2.1 Build the replacement and pattern files (values typed interactively)

Run in Git Bash from the parent directory of the working copy (for example `C:/dev`). `read -rs` does not echo and does not write to shell history. Look up the old values in commit `270a97b` (the last commit before P0) inside the working copy, without saving them anywhere: `git show 270a97b:src/main/resources/application.properties`, `git show 270a97b:src/main/java/com/example/project3/security/JwtService.java`, `git show 270a97b:src/main/java/com/example/project3/config/DataSeeder.java`. Paste each value when prompted, then clear the terminal scrollback.

```sh
mkdir -p educore-purge
chmod 700 educore-purge
cd educore-purge

printf 'Old DB password: ';          read -rs OLD_DB_PASSWORD;    echo
printf 'Old JWT SECRET_KEY value: '; read -rs OLD_JWT_SECRET;     echo
printf 'Old seeder student number: '; read -rs OLD_STUDENT_NUMBER; echo

# git filter-repo --replace-text format: one rule per line, "literal==>replacement".
{
  printf '%s==>REMOVED-DB-PASSWORD\n'  "$OLD_DB_PASSWORD"
  printf '%s==>REMOVED-JWT-SECRET\n'   "$OLD_JWT_SECRET"
  printf '%s==>9000000\n'              "$OLD_STUDENT_NUMBER"
} > replacements.txt

# Same values, one per line, for the post-purge verification grep.
printf '%s\n' "$OLD_DB_PASSWORD" "$OLD_JWT_SECRET" "$OLD_STUDENT_NUMBER" > patterns.txt

unset OLD_DB_PASSWORD OLD_JWT_SECRET OLD_STUDENT_NUMBER
chmod 600 replacements.txt patterns.txt
wc -l replacements.txt patterns.txt
```

Expected: `3 replacements.txt`, `3 patterns.txt`.

If `application.properties`, `config-repo/educore.yml` and `docker-compose.yml` ever held different database passwords (check each with `git log -p` on that file), repeat the `read -rs` and both `printf` lines once per distinct value so every value has its own rule and pattern line.

`--replace-text` rewrites every blob in every commit, so these three rules cover all committed copies in `application.properties`, `config-repo/educore.yml`, `docker-compose.yml`, `JwtService.java` and `DataSeeder.java`, including renamed or older paths.

### 2.2 Rewrite a fresh mirror

```sh
cd ..                     # back to the directory that contains educore-purge/
git clone --mirror https://github.com/ayberkaarda/EduCore.git educore-mirror.git
cd educore-mirror.git

git filter-repo \
  --replace-text ../educore-purge/replacements.txt \
  --path-regex '^csv_uploads/[^/]+\.(csv|done|fail)$' \
  --path node_modules/ \
  --path package.json \
  --path package-lock.json \
  --invert-paths
```

What the path rules remove from every commit:

- `csv_uploads/<file>.csv|.done|.fail` directly under `csv_uploads/` (the real-looking student and course lists). The regex does not match `csv_uploads/sample/**` or `csv_uploads/*/.gitkeep`, so the synthetic samples added in P0 survive.
- Root `node_modules/`, root `package.json`, root `package-lock.json` (about 13 MB of vendored packages, removed in P0). `--path` is anchored at the repository root, so `frontend/package.json` and `frontend/package-lock.json` are kept.

### 2.3 Verify the rewritten history before pushing

```sh
# 1. No old value in any revision of any file. Expected output: 0
git log --all -p | grep -c -F -f ../educore-purge/patterns.txt

# 2. No purged path left in any commit. Expected output: nothing
git log --all --name-only --format= | sort -u | grep -E '^(csv_uploads/[^/]+\.(csv|done|fail)|node_modules/|package(-lock)?\.json)$'

# 3. Secret scan of the full history with the repository rules. Expected: "no leaks found"
git clone ../educore-mirror.git ../educore-verify
cd ../educore-verify
gitleaks detect --source . --config .gitleaks.toml --redact
cd ../educore-mirror.git
```

Command 3 needs the P0 commit that adds `.gitleaks.toml` to be part of the mirrored history; if the purge runs before that commit is pushed, pass `--config` with the path to the working copy's `.gitleaks.toml` instead.

Stop here if any check fails.

### 2.4 Force-push

`git filter-repo` deletes the `origin` remote on purpose. Re-add it and push branches and tags explicitly (a `--mirror` push would also try to update GitHub's read-only `refs/pull/*` refs and fail):

```sh
git remote add origin https://github.com/ayberkaarda/EduCore.git
git push --force --all origin
git push --force --tags origin
```

Then restore branch protection on GitHub.

### 2.5 Re-clone everywhere

Old clones still contain the secrets and will reintroduce them on the next push. On every machine (including `C:\dev\EduCore`):

1. Copy out any uncommitted work (diffs or files), plus the local `.env`.
2. Delete the old clone directory.
3. `git clone https://github.com/ayberkaarda/EduCore.git`
4. Restore `.env` and re-apply uncommitted work by hand. Do not `git pull`, merge or cherry-pick from an old clone.

CI runners, Docker build caches and any image built from an old commit contain the old files too; clear the caches and rebuild images from the rewritten history.

### 2.6 Clean up the sensitive working files

```sh
cd ..
rm -f educore-purge/replacements.txt educore-purge/patterns.txt
rmdir educore-purge
rm -rf educore-mirror.git educore-verify
```

## 3. What a history rewrite cannot reach

- **GitHub cached views and pull-request refs.** Commit pages, diffs and `refs/pull/*` of closed or merged pull requests can stay reachable by SHA after a force-push. Removing them requires a request to GitHub Support (sensitive data removal) listing the affected repository and commit SHAs.
- **Forks and existing clones.** A fork or clone made before the purge keeps the full old history; GitHub does not rewrite forks.
- **Third-party mirrors, search engines, archives and package caches** may have copied the files.

Because of these, the credentials in section 1 must be rotated whether or not the purge is executed. A purged history with unrotated credentials is still a compromised system.

## 4. Approval checklist

- [ ] R-1 database password rotated in every environment
- [ ] R-2 JWT signing key rotated
- [ ] R-3 seeded/default account passwords changed on every reachable instance
- [ ] R-4 Neon console checked
- [ ] Collaborators notified; pending work pushed or saved
- [ ] Sections 2.1 to 2.6 executed and the 2.3 checks returned the expected output
- [ ] GitHub Support request filed for cached views (section 3), if the repository was ever public
