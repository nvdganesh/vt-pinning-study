# Optional secondary experiment: real JDBC driver pinning

This is **not required** for the core study (RQ1/RQ2/H1/H2). It exists to
give the paper one external-validity data point: "does the synthetic
finding generalize to a real, widely-used driver?"

## Setup

```
docker compose up -d
```

Then build the app twice with different driver versions and re-test
`/db-query` only:

```
# Pinning-prone driver version (contains synchronized blocks pre-fix)
mvn -f app/pom.xml -Dpgjdbc.version=42.6.0 clean package

# Fixed driver version (migrated to ReentrantLock per pgjdbc#1951)
mvn -f app/pom.xml -Dpgjdbc.version=42.7.4 clean package
```

**Before citing specific version numbers in the paper**, verify against the
pgjdbc changelog / GitHub issue (pgjdbc#1951) which exact release the fix
landed in -- driver release numbering changes over time and you should not
take the version numbers in this file as ground truth without checking.

## Why this is secondary, not primary

You don't control the driver's internals, so you can't independently vary
"pinning vs. not" while holding everything else constant the way the
synthetic `/io-synchronized-pinned-*` endpoints let you. Treat this as a
confirmatory check on top of the controlled experiment, not a replacement
for it.
