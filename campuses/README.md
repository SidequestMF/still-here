# Campus mode

Campus mode is set up by the student. They enter their school's numbers once, or pick their school from `campuses.json` to fill them in, and can edit anything.

| Setting | What it does | Required |
| --- | --- | --- |
| Campus police | Added to every alert text, so friends know who can get into the dorm fastest | Yes, for campus mode |
| Safe ride or escort | Offered on the watch when a check-in is passed but shaky (slower than usual or with mistakes) | No |
| Confidential support | Shown the morning after any alert, with advice to ask for a drug screen quickly | No |
| Where you live on campus | Added to alert texts, to help friends find you | No |

Still Here never calls or texts campus police itself. Friends do, with the number in front of them. Automatic police alerts would need the department's agreement and would risk false alarms.

## Add your school

1. Copy one entry in `campuses.json` and change every field.
2. Use numbers from the school's own website only, in `+1...` format, and list each page in `sources`.
3. Set `verified` to the date you checked.
4. Run `node tests/check-campuses.mjs`, then open a pull request.

Wrong numbers are dangerous here. If you find one, open an issue right away.
