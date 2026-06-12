# Device Support

## Current validated target

- Primary MAC: `41:42:D3:16:6F:68`
- Match signals:
  - Manufacturer ID `0x0A0B`
  - MAC prefix `41:42`

## What is supported

- Total battery via `180F / 2A19`
- Split battery via validated notify frame:
  - `DD ?? 04 0C XX YY ZZ AA`
