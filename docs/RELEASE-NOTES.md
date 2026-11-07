# Release notes

Write each GitHub release for someone who has not read the development conversation. Use plain headings and concrete changes, grouped by feature. Include the behavior a user will notice and where to find new options. Omit empty categories.

## Format

```markdown
## Added

- New features and their useful settings.

## Changed

- Changes to defaults, navigation and existing behavior.

## Fixed

- Specific bugs and the resulting behavior.

## Assets

- **Sunlith-v<version>.apk**: the Android app.
- **Sunlith-companion-v<version>.zip**: the computer connection tools and setup guide.
- **SHA256SUMS.txt**: checksums for verifying the downloaded files; optional for ordinary installation.
```

The title is `Sunlith v<version>`. Keep the description neutral and specific. Do not include a Validation section or test counts; keep verification records in TESTING.md. Do not add routine installation instructions, promises that existing data or pairing is preserved, marketing filler, generated change lists, contributor credits, screenshots or videos. Explain an upgrade step only when that release actually requires one. Put the brief Assets section last, immediately above GitHub's downloads, and explain every uploaded file.

GitHub provides the download assets below the description. Keep setup instructions in the computer connection documentation.
