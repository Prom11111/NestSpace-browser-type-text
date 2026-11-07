# Repository instructions

## Release notes

Do not write release notes for ordinary feature or fix requests.

When the user explicitly asks to prepare or publish a release:

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
9. Commit the release notes, create `vX.Y.Z`, and push the branch and tag only
   when the user asked to publish. Do not create a branch unless requested.

If the user only asks to generate or update the note, create/edit the Markdown
file but do not commit, tag or push until explicitly asked.

The release workflow requires `docs/releases/<tag>.md`; a tag without this file
must fail instead of publishing an empty or auto-generated placeholder.
