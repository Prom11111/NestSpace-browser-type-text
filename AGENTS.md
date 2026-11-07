# Repository instructions

## Release notes

Do not write release notes for ordinary feature or fix requests.

When the user explicitly asks to publish a release, run the release workflow or
execute this release document:

1. Read `docs/releases/README.md` and `docs/releases/TEMPLATE.md`.
2. Find the previous release tag with `git describe --tags --abbrev=0`.
3. Use the target version from the user. If omitted, propose the next SemVer
   patch version and state it before publishing.
4. Inspect `git log` and `git diff --stat` from the previous tag to the current
   release commit.
5. Generate exactly one file at `docs/releases/vX.Y.Z.md` for that version.
6. Group changes into user-visible sections such as 新增, 修复, 优化 and
   升级注意. Merge related commits and translate technical messages into
   concise Chinese. Do not paste the raw commit list.
7. Verify version names, APK asset names, image paths and the GitHub compare
   link.
8. Run the relevant tests/build before creating the tag.
9. Commit all release-related changes and push `main` to GitHub.
10. Create an annotated tag `vX.Y.Z` on the pushed release commit and push that
    exact tag to GitHub.
11. Wait for the `Build APK` GitHub Actions tag run to finish successfully.
12. Verify the GitHub Release exists, its Markdown body matches
    `docs/releases/<tag>.md`, and it contains arm64, x86_64 and universal APKs.

Use `gh run list --workflow "Build APK" --limit 10` to find the tag run,
`gh run watch <run-id> --exit-status` to wait for it, and
`gh release view vX.Y.Z --json tagName,body,assets,url` to verify the published
Release and assets.

When the user says to publish a release or execute this document, the task is
not complete after creating files locally. A local Markdown file, local commit
or local tag is not a release. Completion means the tag and Release exist on
GitHub and the Release is verified there.

If the user only asks to generate or update the note, create/edit the Markdown
file but do not commit, tag or push until explicitly asked.

The release workflow requires `docs/releases/<tag>.md`; a tag without this file
must fail instead of publishing an empty or auto-generated placeholder.
