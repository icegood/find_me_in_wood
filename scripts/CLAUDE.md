# scripts/

- `l10n/gen.py`: builds `res/values*/strings.xml` for `app` and `feature/map` from
  `<module>.<lang>.txt`. Lines are `key=value`; `key!=value` = not translatable (English
  only); `key[]=a|b` = string array. Every language must have exactly the English keys with
  the same placeholders, or the script fails. Add a string to `*.en.txt` and to every
  translation.
- `e2e/discovery_join.py`: discovery + join rounds on two real phones over adb
  (`--master <serial> --joiner <serial>`, or `FMIW_MASTER` / `FMIW_JOINER`). Needs
  `source env.sh`. See the `phone-testing` skill for device setup.
- `coverage-summary.py`: summary used by CI after `jacocoAggregateReport`.
