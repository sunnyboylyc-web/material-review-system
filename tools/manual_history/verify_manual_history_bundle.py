from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
import stat
from collections import Counter
from decimal import Decimal
from pathlib import Path
from typing import Any

import openpyxl
from openpyxl.utils import column_index_from_string
from openpyxl.utils.datetime import from_excel


class VerificationError(RuntimeError):
    pass


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def canonical_bytes(value: Any) -> bytes:
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
        allow_nan=False,
    ).encode("utf-8")


def load_json(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exception:
        raise VerificationError(f"Invalid JSON evidence: {path.name}") from exception


def tree_hash(root: Path, excluded_names: set[str]) -> dict[str, Any]:
    records = []
    total = 0
    for path in sorted(item for item in root.rglob("*") if item.is_file() and item.name not in excluded_names):
        size = path.stat().st_size
        digest = sha256_file(path)
        relative = path.relative_to(root).as_posix()
        records.append((relative, size, digest))
        total += size
    payload = "".join(f"{relative}\t{size}\t{digest}\n" for relative, size, digest in records).encode("utf-8")
    return {"fileCount": len(records), "sizeBytes": total, "sha256": sha256_bytes(payload)}


def numeric_equal(left: Any, right: Any) -> bool:
    if isinstance(left, bool) or isinstance(right, bool):
        return left is right
    if isinstance(left, (int, float)) and isinstance(right, (int, float)):
        return abs(Decimal(str(left)) - Decimal(str(right))) <= Decimal("0.000000001")
    return left == right


def workbook_date(value: Any, epoch: dt.datetime) -> dt.date | None:
    if isinstance(value, dt.datetime):
        return value.date()
    if isinstance(value, dt.date):
        return value
    if isinstance(value, (int, float)) and not isinstance(value, bool):
        try:
            converted = from_excel(value, epoch)
            if isinstance(converted, dt.datetime):
                return converted.date()
        except (ValueError, TypeError, OverflowError):
            return None
    if isinstance(value, str):
        for pattern in ("%Y-%m-%d", "%Y/%m/%d", "%Y.%m.%d"):
            try:
                return dt.datetime.strptime(value.strip(), pattern).date()
            except ValueError:
                pass
    return None


def workbook_value(value: Any, unknown: str) -> Any:
    if value is None or value == "":
        return unknown
    if isinstance(value, dt.datetime):
        return value.strftime("%Y-%m-%d %H:%M:%S")
    if isinstance(value, dt.date):
        return value.isoformat()
    if isinstance(value, (str, int, float, bool)):
        return value
    return str(value)


def compare_source_value(expected: Any, actual: Any, row_number: int, field: str) -> int:
    """Exact value comparison, independently of converter output hashes/counters."""
    if isinstance(expected, dict):
        if not isinstance(actual, dict) or expected.keys() != actual.keys():
            raise VerificationError(f"Source field set mismatch at workbook row {row_number}, field {field}")
        return sum(compare_source_value(value, actual[key], row_number, f"{field}.{key}") for key, value in expected.items())
    if isinstance(expected, bool) or isinstance(actual, bool):
        matched = type(expected) is type(actual) and expected == actual
    elif isinstance(expected, (int, float)) and isinstance(actual, (int, float)):
        matched = Decimal(str(expected)) == Decimal(str(actual))
    else:
        matched = type(expected) is type(actual) and expected == actual
    if not matched:
        raise VerificationError(f"Source value mismatch at workbook row {row_number}, field {field}")
    return 1


def verify_source_rows(
    source: Path, contract: dict[str, Any], rows_by_date: dict[str, list[dict[str, Any]]]
) -> dict[str, Any]:
    """Re-read Excel cells; never call the converter or trust its selected-row totals."""
    outputs: dict[int, dict[str, Any]] = {}
    for rows in rows_by_date.values():
        for row in rows:
            metadata = row.get("_manual_history")
            index = metadata.get("source_workbook_row") if isinstance(metadata, dict) else None
            if isinstance(index, bool) or not isinstance(index, int) or index < 2 or index in outputs:
                raise VerificationError("Output source workbook row references are invalid or duplicated")
            outputs[index] = row
    unknown = contract["unknownValue"]
    rate_fields = set(contract["rateFields"])
    source_indices: set[int] = set()
    fields_compared = 0
    repeated_headers = 0
    workbook = openpyxl.load_workbook(source, read_only=True, data_only=True)
    try:
        worksheet = workbook[contract["worksheet"]]
        source_iterator = worksheet.iter_rows(values_only=True)
        header = next(source_iterator)
        for index, cells in enumerate(source_iterator, start=2):
            def cell(column: str | None) -> Any:
                offset = column_index_from_string(column) - 1 if column else len(cells)
                return cells[offset] if offset < len(cells) else None

            date = workbook_date(cell("A"), workbook.epoch)
            rank = cell("B")
            if isinstance(rank, bool) or not isinstance(rank, int):
                if date is not None and cell("D") not in (None, ""):
                    periods = [period for period in contract["periods"] if period["validFrom"] <= date.isoformat() <= period["validThrough"]]
                    if rank in (None, "") and len(periods) == 1:
                        metric_columns = list(periods[0]["availableMetrics"].values())
                        anchor_columns = ("C", "D", "G", periods[0]["availableMetrics"]["stat_cost_for_roi2"])
                        anchors_match = all(
                            isinstance(cell(column), str) and bool(cell(column))
                            and column_index_from_string(column) <= len(header)
                            and cell(column) == header[column_index_from_string(column) - 1]
                            for column in anchor_columns
                        )
                        # Dated repeated headings are not facts. Require exact identity/spend
                        # headings and text in every metric column; numeric material rows still fail.
                        if anchors_match and all(isinstance(cell(column), str) and bool(cell(column)) for column in metric_columns):
                            repeated_headers += 1
                            continue
                    raise VerificationError(f"Potential fact omitted because source rank is invalid at workbook row {index}")
                continue
            if date is None or cell("D") in (None, ""):
                raise VerificationError(f"Source date or identity missing at workbook row {index}")
            periods = [period for period in contract["periods"] if period["validFrom"] <= date.isoformat() <= period["validThrough"]]
            if len(periods) != 1:
                raise VerificationError(f"Source date has no unique mapping at workbook row {index}")
            period = periods[0]
            if period.get("duplicateDateColumn") and workbook_date(cell(period["duplicateDateColumn"]), workbook.epoch) != date:
                raise VerificationError(f"Duplicate source date mismatch at workbook row {index}")
            material_name = unknown if cell("C") in (None, "") else str(cell("C"))
            display_id = str(cell("D"))
            material_id = display_id
            for rule in contract.get("exactSentinelRules", []):
                if display_id == rule["workbookMaterialId"] and material_name == rule["exactName"]:
                    material_id = rule["canonicalMaterialId"]
                    break
            else:
                if any(display_id == rule["workbookMaterialId"] or material_name == rule["exactName"] for rule in contract.get("exactSentinelRules", [])):
                    raise VerificationError(f"Source sentinel is not an exact match at workbook row {index}")
            expected = {
                "material_create_time_v2": workbook_value(cell("G"), unknown),
                "material_id": material_id,
                "roi2_material_video_name": material_name,
                "roi2_material_video_type": unknown,
                "stat_time_day": date.isoformat(),
            }
            for field in contract["metricFields"]:
                column = period["availableMetrics"].get(field)
                value = cell(column)
                if column is None or value is None or value == "":
                    expected[field] = unknown
                    continue
                if isinstance(value, bool) or not isinstance(value, (int, float, Decimal)):
                    raise VerificationError(f"Source metric is not numeric at workbook row {index}, field {field}")
                number = Decimal(str(value)) * (100 if field in rate_fields else 1)
                if not number.is_finite():
                    raise VerificationError(f"Source metric is not finite at workbook row {index}, field {field}")
                expected[field] = int(number) if number == number.to_integral_value() else float(number)
            expected["_manual_history"] = {
                "display_material_id": display_id,
                "mapping_period": period["id"],
                "source_optional_columns": {item["field"]: workbook_value(cell(item["column"]), unknown) for item in contract.get("sourceOptionalColumns", [])},
                "source_rank": rank,
                "source_workbook_row": index,
            }
            if index not in outputs:
                raise VerificationError(f"Source fact missing from output at workbook row {index}")
            fields_compared += compare_source_value(expected, outputs[index], index, "fact")
            source_indices.add(index)
    finally:
        workbook.close()
    if source_indices != outputs.keys():
        raise VerificationError("Output contains facts with no selected source workbook row")
    return {"status": "PASSED", "sourceRowsCompared": len(source_indices), "fieldValuesCompared": fields_compared,
            "datedRepeatedHeaderRowsSkipped": repeated_headers}


def verify_ready_attestations(day: dict[str, Any], project_root: Path, source_hash: str, row_count: int) -> None:
    for key, schema in (
        ("accountIdentityEvidence", "manual-history-account-attestation-1.0.0"),
        ("completenessEvidence", "manual-history-completeness-attestation-1.0.0"),
    ):
        item = day.get(key)
        if not isinstance(item, dict):
            raise VerificationError("Ready day lacks a referenced attestation")
        path = Path(item["absolutePath"]).resolve(strict=True)
        if not path.is_relative_to(project_root / ".local"):
            raise VerificationError("Ready attestation is outside the project data root")
        if path.stat().st_size != item["sizeBytes"] or sha256_file(path) != item["sha256"]:
            raise VerificationError("Ready attestation file evidence mismatch")
        payload = load_json(path)
        if (
            payload.get("schemaVersion") != schema
            or payload.get("status") != "VERIFIED"
            or payload.get("verificationMethod") != "USER_CONFIRMED"
            or not isinstance(payload.get("evidenceRef"), str)
            or not payload["evidenceRef"].strip()
            or payload.get("projectRef") != day["projectRef"]
            or payload.get("accountId") != day["accountId"]
            or payload.get("sourceWorkbookSha256") != source_hash
        ):
            raise VerificationError("Ready attestation does not bind the account and exact source")
        if key == "accountIdentityEvidence":
            valid_from = dt.date.fromisoformat(payload["validFrom"])
            valid_through = dt.date.fromisoformat(payload["validThrough"])
            if not valid_from <= dt.date.fromisoformat(day["statDate"]) <= valid_through:
                raise VerificationError("Ready day is outside the attested identity date range")
        else:
            status = payload.get("days", {}).get(day["statDate"])
            expected = "COMPLETE" if row_count else "COMPLETE_NO_ROWS"
            if status != expected or status != day["completenessStatus"]:
                raise VerificationError("Ready completeness attestation does not match the date or rows")


def verify_overlap(
    rows_by_date: dict[str, list[dict[str, Any]]],
    contract: dict[str, Any],
    auto_root: Path,
    project_ref: str,
) -> dict[str, Any]:
    matched_days = 0
    manual_rows = 0
    auto_positive_rows = 0
    auto_zero_rows = 0
    field_comparisons = 0
    mismatches = 0
    key_differences = 0
    observed_accounts = set()
    fields = contract["overlap"]["compareFields"]
    for date in contract["overlap"]["dates"]:
        day = auto_root / date / project_ref
        manifest = load_json(day / "manifest.json")
        material = load_json(day / "material_day.json")
        if (
            manifest.get("jobStatus") != "COMPLETE"
            or manifest.get("paginationComplete") is not True
            or material.get("paginationComplete") is not True
            or manifest.get("advertiserId") != material.get("advertiserId")
            or manifest.get("statDate") != date
            or material.get("statDate") != date
        ):
            raise VerificationError(f"Automatic overlap evidence is not complete for {date}")
        observed_accounts.add(str(material["advertiserId"]))
        automatic = material.get("rows")
        if not isinstance(automatic, list):
            raise VerificationError(f"Automatic overlap rows are invalid for {date}")
        positive = [
            row for row in automatic
            if isinstance(row, dict)
            and isinstance(row.get("stat_cost_for_roi2"), (int, float))
            and not isinstance(row.get("stat_cost_for_roi2"), bool)
            and row["stat_cost_for_roi2"] > 0
        ]
        zero_count = sum(
            1 for row in automatic
            if isinstance(row, dict)
            and isinstance(row.get("stat_cost_for_roi2"), (int, float))
            and not isinstance(row.get("stat_cost_for_roi2"), bool)
            and row["stat_cost_for_roi2"] == 0
        )
        manual = rows_by_date.get(date, [])
        auto_by_id = {str(row["material_id"]): row for row in positive}
        manual_by_id = {str(row["material_id"]): row for row in manual}
        key_difference = len(set(auto_by_id) ^ set(manual_by_id))
        day_mismatch = 0
        for material_id in set(auto_by_id) & set(manual_by_id):
            for field in fields:
                field_comparisons += 1
                if not numeric_equal(manual_by_id[material_id].get(field), auto_by_id[material_id].get(field)):
                    day_mismatch += 1
        if len(manual) != len(positive) or key_difference or day_mismatch:
            raise VerificationError(f"Independent overlap mismatch for {date}")
        matched_days += 1
        manual_rows += len(manual)
        auto_positive_rows += len(positive)
        auto_zero_rows += zero_count
        mismatches += day_mismatch
        key_differences += key_difference
    if len(observed_accounts) != 1:
        raise VerificationError("Automatic overlap evidence does not use one account")
    return {
        "requiredDays": len(contract["overlap"]["dates"]),
        "matchedDays": matched_days,
        "manualRows": manual_rows,
        "autoPositiveRows": auto_positive_rows,
        "autoZeroSpendRows": auto_zero_rows,
        "fieldComparisons": field_comparisons,
        "fieldMismatchCount": mismatches,
        "keyDifferenceCount": key_differences,
        "accountConsistency": "ONE_REDACTED_ACCOUNT",
        "manualPublicationAction": "DO_NOT_IMPORT_OR_OVERWRITE",
    }


def verify(args: argparse.Namespace) -> dict[str, Any]:
    project_root = Path(args.project_root).resolve(strict=True)
    bundle = Path(args.bundle).resolve(strict=True)
    audit_output = Path(args.audit_output).resolve()
    contract_path = Path(args.contract).resolve(strict=True)
    source = Path(args.source).resolve(strict=True)
    auto_root = Path(args.auto_overlap_root).resolve(strict=True)
    for path, label in ((bundle, "bundle"), (audit_output, "audit output"), (contract_path, "contract")):
        try:
            path.relative_to(project_root)
        except ValueError as exception:
            raise VerificationError(f"{label} must be inside the project") from exception
    for path, label in ((bundle, "bundle"), (audit_output, "audit output")):
        relative = path.relative_to(project_root)
        if not relative.parts or relative.parts[0] != ".local":
            raise VerificationError(f"{label} must be under the project's .local directory")

    contract = load_json(contract_path)
    receipt = load_json(bundle / "conversion-receipt.json")
    source_hash = sha256_file(source)
    source_size = source.stat().st_size
    if source_hash != receipt["sourceWorkbook"]["sha256"] or source_size != receipt["sourceWorkbook"]["sizeBytes"]:
        raise VerificationError("Desktop source no longer matches the conversion receipt")
    source_copy = Path(receipt["sourceEvidenceCopy"]["absolutePath"])
    if (
        not source_copy.is_file()
        or source_copy.stat().st_size != source_size
        or sha256_file(source_copy) != source_hash
    ):
        raise VerificationError("Project source evidence copy does not match the desktop source")

    day_dirs = sorted(path for path in (bundle / "days").iterdir() if path.is_dir())
    daily_summary = {
        item["statDate"]: item
        for item in (
            json.loads(line)
            for line in (bundle / "daily-summary.jsonl").read_text(encoding="utf-8").splitlines()
            if line
        )
    }
    manifest = load_json(bundle / "bundle-manifest.json")
    if len(day_dirs) != len(daily_summary) or len(day_dirs) != len(manifest["days"]):
        raise VerificationError("Daily directory, summary, and bundle manifest counts differ")

    total_rows = 0
    zero_rows = 0
    unknown_metric_values: Counter[str] = Counter()
    mapping_rows: Counter[str] = Counter()
    missing_material_names = 0
    sentinel_rows = 0
    staged_days = 0
    ready_days = 0
    no_row_days = 0
    rank_statuses: Counter[str] = Counter()
    rows_by_date: dict[str, list[dict[str, Any]]] = {}
    metric_fields = contract["metricFields"]
    exact_sentinel_names = {item["exactName"] for item in contract["exactSentinelRules"]}
    for day_dir in day_dirs:
        date = day_dir.name
        dt.date.fromisoformat(date)
        day_evidence = load_json(day_dir / "day-evidence.json")
        facts_path = day_dir / "facts.jsonl"
        if Path(day_evidence["factsFileEvidence"]["absolutePath"]).resolve() != facts_path.resolve():
            raise VerificationError(f"Facts evidence path mismatch for {date}")
        facts_bytes = facts_path.read_bytes()
        if sha256_bytes(facts_bytes) != day_evidence["factsFileEvidence"]["sha256"]:
            raise VerificationError(f"Facts file SHA-256 mismatch for {date}")
        rows = [json.loads(line) for line in facts_bytes.splitlines()]
        if len(rows) != day_evidence["rowCount"]:
            raise VerificationError(f"Row count mismatch for {date}")
        if sha256_bytes(canonical_bytes(rows)) != day_evidence["contentHash"]:
            raise VerificationError(f"Content hash mismatch for {date}")
        ids = [str(row["material_id"]) for row in rows]
        if len(ids) != len(set(ids)):
            raise VerificationError(f"Duplicate material ID for {date}")
        if any(row["stat_time_day"] != date for row in rows):
            raise VerificationError(f"Statistic date mismatch for {date}")
        day_zero = sum(1 for row in rows if row["stat_cost_for_roi2"] == 0)
        if day_zero != day_evidence["zeroSpendRowCount"]:
            raise VerificationError(f"Zero-spend count mismatch for {date}")
        summary = daily_summary.get(date)
        if not summary or any(
            summary[key] != day_evidence[key]
            for key in ("status", "rowCount", "zeroSpendRowCount", "contentHash", "sourceRankStatus")
        ):
            raise VerificationError(f"Daily summary mismatch for {date}")
        if day_evidence["status"] == "STAGED_UNVERIFIED":
            staged_days += 1
            if day_evidence["databaseImportEligible"] is not False:
                raise VerificationError(f"Staged day incorrectly claims database eligibility for {date}")
        elif day_evidence["status"] == "READY_FOR_IMPORT":
            if date in contract["overlap"]["dates"]:
                raise VerificationError(f"Known automatic overlap day incorrectly claims READY for {date}")
            if day_evidence.get("databaseImportEligible") is not True or day_evidence.get("publicationBlockers") != []:
                raise VerificationError(f"Ready day has inconsistent eligibility for {date}")
            verify_ready_attestations(day_evidence, project_root, source_hash, len(rows))
            ready_days += 1
        else:
            raise VerificationError(f"Unsupported day status for {date}")
        if not rows:
            no_row_days += 1
        total_rows += len(rows)
        zero_rows += day_zero
        rank_statuses[day_evidence["sourceRankStatus"]] += 1
        for row in rows:
            if row["roi2_material_video_name"] == "UNKNOWN":
                missing_material_names += 1
            if row["material_id"] == "-2" and row["roi2_material_video_name"] in exact_sentinel_names:
                sentinel_rows += 1
            mapping_rows[row["_manual_history"]["mapping_period"]] += 1
            for field in metric_fields:
                if row[field] == "UNKNOWN":
                    unknown_metric_values[field] += 1
        rows_by_date[date] = rows

    if total_rows != receipt["workbook"]["selectedFactRows"] or zero_rows != receipt["workbook"]["zeroSpendRows"]:
        raise VerificationError("Independent totals do not match the conversion receipt")
    if dict(sorted(mapping_rows.items())) != receipt["workbook"]["mappingPeriodRowCounts"]:
        raise VerificationError("Independent mapping-period totals do not match")
    if dict(sorted(unknown_metric_values.items())) != receipt["workbook"]["unknownValueCountsByField"]:
        raise VerificationError("Independent UNKNOWN metric totals do not match")

    source_comparison = verify_source_rows(source, contract, rows_by_date)
    if sha256_file(source) != source_hash:
        raise VerificationError("Source workbook changed during independent comparison")
    overlap = verify_overlap(rows_by_date, contract, auto_root, receipt["projectRef"])
    receipt_scope_tree = tree_hash(bundle, {"conversion-receipt.json"})
    if receipt_scope_tree != {
        "fileCount": receipt["output"]["fileCount"],
        "sizeBytes": receipt["output"]["sizeBytes"],
        "sha256": receipt["output"]["treeSha256"],
    }:
        raise VerificationError("Bundle tree does not match the conversion receipt")
    full_tree = tree_hash(bundle, set())
    result = {
        "schemaVersion": "manual-history-independent-readback-1.0.0",
        "auditId": args.audit_id,
        "status": "PASSED_STAGED_UNVERIFIED" if staged_days else "PASSED_READY_FOR_IMPORT",
        "bundleId": receipt["bundleId"],
        "mappingVersion": receipt["mappingVersion"],
        "source": {
            "originalPath": str(source),
            "evidenceCopyPath": str(source_copy),
            "sizeBytes": source_size,
            "sha256": source_hash,
            "byteForByteMatch": True,
        },
        "facts": {
            "calendarDays": len(day_dirs),
            "daysWithRows": len(day_dirs) - no_row_days,
            "daysWithoutRows": no_row_days,
            "rows": total_rows,
            "zeroSpendRows": zero_rows,
            "positiveSpendRows": total_rows - zero_rows,
            "missingMaterialNameRows": missing_material_names,
            "exactAigcSentinelRows": sentinel_rows,
            "mappingPeriodRowCounts": dict(sorted(mapping_rows.items())),
            "unknownMetricValueCounts": dict(sorted(unknown_metric_values.items())),
            "sourceRankStatuses": dict(sorted(rank_statuses.items())),
        },
        "publication": {
            "readyForImportDays": ready_days,
            "stagedUnverifiedDays": staged_days,
            "mysqlImportAttempted": False,
        },
        "overlap": overlap,
        "sourceWorkbookComparison": source_comparison,
        "bundle": {
            "absolutePath": str(bundle),
            "receiptScopeTree": receipt_scope_tree,
            "fullTreeIncludingConversionReceipt": full_tree,
        },
        "verification": {
            "allRowsReparsed": True,
            "allDailyFileHashesRecomputed": True,
            "allDailyContentHashesRecomputed": True,
            "allDailyKeysCheckedUnique": True,
            "desktopSourceRehashed": True,
            "sourceEvidenceCopyRehashed": True,
            "automaticOverlapRecompared": True,
            "sourceWorkbookEverySelectedRowCompared": True,
            "sourceWorkbookEveryMappedFieldCompared": True,
        },
    }
    audit_output.parent.mkdir(parents=True, exist_ok=False)
    audit_output.write_text(
        json.dumps(result, ensure_ascii=False, sort_keys=True, indent=2) + "\n",
        encoding="utf-8",
    )
    audit_output.chmod(stat.S_IREAD)
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description="Independently re-read a manual history evidence bundle")
    parser.add_argument("--project-root", required=True)
    parser.add_argument("--bundle", required=True)
    parser.add_argument("--contract", required=True)
    parser.add_argument("--source", required=True)
    parser.add_argument("--auto-overlap-root", required=True)
    parser.add_argument("--audit-id", required=True)
    parser.add_argument("--audit-output", required=True)
    args = parser.parse_args()
    try:
        result = verify(args)
    except Exception as exception:
        print(json.dumps({"status": "FAILED", "message": str(exception)}, ensure_ascii=False))
        return 2
    print(json.dumps({
        "status": result["status"],
        "rows": result["facts"]["rows"],
        "calendarDays": result["facts"]["calendarDays"],
        "stagedUnverifiedDays": result["publication"]["stagedUnverifiedDays"],
        "overlapMatchedDays": result["overlap"]["matchedDays"],
        "fullTreeSha256": result["bundle"]["fullTreeIncludingConversionReceipt"]["sha256"],
    }, ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
