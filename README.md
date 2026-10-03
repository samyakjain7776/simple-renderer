# Simple Renderer

A Java/Spring Boot service that converts metadata-driven JSON into CSV or XLSX reports. Jackson parses input one row at a time; Apache POI SXSSF writes XLSX with a 100-row memory window.

This is an independent implementation using synthetic data. It contains no Goldman Sachs source code or internal assets. Production metrics from the original project do not describe this version.

## Run

Requires JDK 21 or newer. The Maven wrapper downloads Maven on first use.

```sh
./mvnw verify
./mvnw spring-boot:run
```

The service listens on `127.0.0.1:8080`.

```sh
curl --fail-with-body http://127.0.0.1:8080/reports \
  -H 'Content-Type: application/json' \
  --data-binary @examples/positions.json \
  -o /tmp/positions.xlsx
```

Set `metadata.fileType` to `csv` and save the response as `.csv` for CSV output. To run the packaged service:

```sh
java -jar target/simple-renderer-0.1.0-SNAPSHOT.jar
```

## Input

The root object must contain `metadata` followed by `values`, in that order. Each value is an array whose width matches the column list. See [examples/positions.json](examples/positions.json).

| Column type | Input | Output |
| --- | --- | --- |
| `STRING` | JSON string | Text; never inferred as an XLSX formula |
| `DECIMAL` | JSON number or numeric string | Numeric cell; values exceeding 15 significant digits become XLSX text to preserve precision |
| `DATE` | ISO `yyyy-MM-dd` string, years 1900–9999 | Excel date or ISO text in CSV |
| `BOOLEAN` | JSON `true` or `false` | Boolean |

All types accept `null`. Decimal strings are preferable when the producer might round JSON numbers. Decimal precision and absolute scale are limited to 100. Numeric XLSX cells still use Excel's floating-point representation.

Optional formats: decimals support `0`, `0.00`, `#,##0.00`; dates support `yyyy-mm-dd`, `mm/dd/yyyy`. Formats affect XLSX display only. Columns have fixed widths; there is no full-report auto-sizing.

CSV uses UTF-8, quoted fields, doubled quotes and CRLF record separators. Null and empty strings both produce an empty field. Formula-like text and headings are prefixed with an apostrophe to reduce spreadsheet formula interpretation; this intentionally changes exported text. Negative numeric values are preserved.

## Rendering flow

```text
HTTP input → Jackson parser → typed row → CSV / SXSSF writer
                                             ↓
                                      temporary artifact
                                             ↓
                             validate EOF and finalize file
                                             ↓
                                        HTTP download
                                             ↓
                                     delete artifact
```

The first version is sequential: one request thread owns parsing and writing, preserving row order directly. The JSON rows are never collected into a whole-report list. Metadata is bounded to 64 Ki characters, rows to 128 Ki characters, cells to 32,767 characters, and schemas to 128 columns. XLSX styles are reused per format. Temporary files are used for output staging and SXSSF sheet data; bounded row memory does not mean zero disk usage.

Responses start only after the entire input has been validated and the file has been finalized. Failed renders delete staged artifacts. An admission permit stays held through download and cleanup. Invalid reports return 400, size limits return 413, and a busy renderer returns 429. Transport failures after a download starts cannot be replaced with a JSON error response.

## Configuration

Override these Spring properties with `--property=value` when running the jar.

| Property | Default |
| --- | --- |
| `renderer.max-input-bytes` | 104857600 (100 MiB) |
| `renderer.max-output-bytes` | 104857600 (100 MiB) |
| `renderer.max-rows` | 1000000 |
| `renderer.max-concurrent-reports` | 2 |
| `renderer.staging-directory` | `${java.io.tmpdir}/simple-renderer` |

The output byte limit covers the final CSV/XLSX artifact, not POI's intermediate sheet files. POI manages its own temporary sheet files under the JVM temporary directory and removes them on close. The service does not yet impose a total temporary-disk quota or report deadline. Keep it local while those deployment limits are unfinished.

## Tests and next step

Tests reopen generated XLSX files, verify CSV escaping and formula handling, exercise malformed input and resource limits, check permit/file cleanup, and send real HTTP requests to the Spring Boot server. A reader test verifies that the first row is available without consuming the whole input.

Next: add bounded chunk transformation with ordered output, then benchmark it against this sequential baseline. No large-input throughput or peak-memory benchmarks have been run yet.
