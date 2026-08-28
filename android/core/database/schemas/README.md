# Room schemas

Room writes a JSON schema here on every build (`room.schemaLocation`), one file
per database version.

**These files are committed on purpose.** They are what makes a schema change
reviewable in a pull request, and what Room's migration tests run against. A
migration written without them can only be verified by hand.

The directory is empty until the first Android build runs.
