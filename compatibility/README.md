# Operaton compatibility pins

One folder per Operaton minor version. Each `pom.xml` pins the **latest patch** of that minor in
the `operaton.version` property. Dependabot raises it with patch updates only; a weekly workflow
opens a pull request adding the folder for a new minor.

CI reads these pins to build every extension against each minor its `extension.yml` declares.
See [docs/conventions/compatibility.md](../docs/conventions/compatibility.md).
