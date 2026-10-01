# Choreo, vendored

[Choreo](https://github.com/informalsystems/choreo) is Apache-2.0; its licence
is [LICENSE](LICENSE), beside these files. `choreo.qnt` and
`spells/basicSpells.qnt` are Choreo's, byte for byte, from commit
`000cf4eed315187dc6f216a148781cff7dde6521` (2026-06-23).

They are copied rather than fetched because Quint resolves imports from the
file system and has no package manager to fetch them with. In your own project,
copy them the same way, or point the import at wherever your copy of Choreo
lives.
