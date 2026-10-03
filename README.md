# Simple Renderer

A Spring Boot service that turns JSON into CSV and Excel reports. Jackson reads the input row by row, workers convert the values, and a single writer saves them in order. Excel output uses Apache POI SXSSF to avoid keeping the whole workbook in memory.

## Run

Requires Java 21 or newer.

```sh
./mvnw verify
./mvnw spring-boot:run
```

Send a report to `localhost:8080`:

```sh
curl --fail-with-body http://127.0.0.1:8080/reports \
  -H 'Content-Type: application/json' \
  --data-binary @examples/positions.json \
  -o /tmp/positions.xlsx
```

For CSV, set `metadata.fileType` to `csv` and save the response as `.csv`.

## Input

See [positions.json](examples/positions.json) for a complete request.

Send `metadata` before `values`. The renderer needs the column definitions before it can process rows. Each row must have one value per column. If `rowCount` is included, it must match the number of data rows, excluding the header.

| Type | Accepted value | Optional format |
| --- | --- | --- |
| `STRING` | JSON string | `General` |
| `DECIMAL` | Number or numeric string | `0`, `0.00`, `#,##0.00` |
| `DATE` | `yyyy-MM-dd` string, years 1900–9999 | `yyyy-MM-dd`, `yyyy-mm-dd`, `mm/dd/yyyy` |
| `BOOLEAN` | `true` or `false` | `General` |

All types accept `null`. Formats control Excel display; CSV dates remain ISO text. Decimals with more than 15 significant digits are written as Excel text to preserve precision. Other numeric cells use Excel's floating-point representation. Decimal precision and absolute scale are capped at 100.

CSV output uses UTF-8, quoted fields and CRLF line endings. Null and empty strings both become empty fields. Text that could be interpreted as a spreadsheet formula gets an apostrophe prefix; numeric values are unchanged. Excel string cells are always written as text.

## How it works

1. Read the metadata and prepare column styles.
2. Read rows into chunks, stopping at the row limit or character target.
3. Convert chunks on worker threads. Wait for results in input order.
4. Write rows to a temporary CSV or XLSX file. SXSSF keeps 100 rows in memory and flushes older rows to disk.
5. Validate the end of the input, finish the file, send it, and delete it.

Only the request thread touches the writer. A chunk keeps its slot until it has been written, even if its worker has already finished. If the first chunk is slow, the in-flight limit stops further reading.

The character target can be exceeded by the final row, which is capped at 128 Ki characters. Cells are limited to 32,767 characters, metadata to 64 Ki characters, and reports to 128 columns. These limits bound retained data, not exact heap usage.

Invalid input returns 400, configured size limits return 413, and a busy service returns 429. The concurrency slot stays occupied until the download and cleanup finish.

## Configuration

Defaults are in [application.properties](src/main/resources/application.properties).

| Property | Default |
| --- | --- |
| `renderer.max-input-bytes` | 104857600 (100 MiB) |
| `renderer.max-output-bytes` | 104857600 (100 MiB) |
| `renderer.max-rows` | 1000000 |
| `renderer.max-concurrent-reports` | 2 |
| `renderer.worker-threads` | 4 |
| `renderer.chunk-size` | 500 rows |
| `renderer.max-chunk-characters` | 262144 characters |
| `renderer.max-in-flight-chunks` | 4 |
| `renderer.staging-directory` | `${java.io.tmpdir}/simple-renderer` |

The service binds to `127.0.0.1`. The output limit covers the finished file, not POI's temporary sheet files. There is no total disk quota or report timeout.

## Tests

`./mvnw verify` checks the HTTP endpoint, input validation, CSV escaping, Excel types and formats, cleanup, chunk limits, and output order when the first worker is delayed. Throughput on multi-GB inputs has not been benchmarked.
