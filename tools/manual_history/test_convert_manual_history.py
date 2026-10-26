import argparse
import copy
import datetime as dt
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import openpyxl

from convert_manual_history import (
    compare_overlap_day,
    ConversionError,
    convert,
    convert_workbook_rows,
    load_json,
    load_completeness_binding,
    load_identity_binding,
    validate_contract,
    write_failure_receipt,
)


PROJECT_ROOT = Path(__file__).resolve().parents[2]
CONTRACT_PATH = PROJECT_ROOT / "contracts" / "manual-history-workbook-v1.json"


class ManualHistoryConverterTest(unittest.TestCase):
    def setUp(self):
        test_root = PROJECT_ROOT / "qa" / "runs"
        test_root.mkdir(parents=True, exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(prefix="manual-history-synthetic-", dir=test_root)
        self.root = Path(self.temporary.name)
        self.contract = load_json(CONTRACT_PATH)

    def attestation(self, kind, source_hash):
        payload = {
            "schemaVersion": f"manual-history-{kind}-attestation-1.0.0",
            "status": "VERIFIED",
            "verificationMethod": "USER_CONFIRMED",
            "evidenceRef": "synthetic-user-confirmation",
            "projectRef": "sampleproject",
            "accountId": "synthetic-account",
            "sourceWorkbookSha256": source_hash,
        }
        if kind == "account":
            payload.update(validFrom="2025-11-01", validThrough="2026-10-11")
        else:
            payload["days"] = {"2026-10-01": "COMPLETE"}
        path = self.root / f"{kind}.json"
        path.write_text(json.dumps(payload), encoding="utf-8")
        return path, payload

    def args_for(self, workbook_path, run_id=None):
        return argparse.Namespace(
            source=str(workbook_path),
            expected_source_sha256=__import__("hashlib").sha256(workbook_path.read_bytes()).hexdigest(),
            contract=str(CONTRACT_PATH),
            project_root=str(PROJECT_ROOT),
            output_root=str(PROJECT_ROOT / ".local" / "manual-history-test" / self.root.name),
            run_id=run_id or f"synthetic-{self.root.name}",
            project_ref="sampleproject",
            account_id=None,
            account_evidence=None,
            completeness_evidence=None,
            auto_overlap_root=None,
        )

    def cleanup_output(self, args):
        output = Path(args.output_root)
        output.resolve().relative_to((PROJECT_ROOT / ".local" / "manual-history-test").resolve())
        if output.exists():
            for item in output.rglob("*"):
                if item.is_file():
                    item.chmod(0o666)
            __import__("shutil").rmtree(output)

    def write_single_day_workbook(self, path, date):
        workbook = openpyxl.Workbook()
        sheet = workbook.active
        sheet.title = self.contract["worksheet"]
        sheet.append(["header"])
        sheet.append([date, 1, "synthetic", "synthetic-1", None, None,
                      dt.datetime(2026, 9, 1), None, None] + [1] * 28)
        workbook.save(path)

    def tearDown(self):
        self.temporary.cleanup()

    def write_workbook(self, path: Path) -> None:
        workbook = openpyxl.Workbook()
        worksheet = workbook.active
        worksheet.title = self.contract["worksheet"]
        worksheet.append([f"H{index}" for index in range(1, 38)])

        first = [None] * 37
        first[0] = dt.date(2025, 11, 1)
        first[1] = 1
        first[2] = "synthetic-material-one"
        first[3] = "1001"
        first[6] = dt.datetime(2025, 10, 1, 8, 30)
        first[9] = "2025-11-01"
        for index in range(10, 29):
            first[index] = 0 if index % 2 else index / 10
        worksheet.append(first)

        second = [None] * 37
        second[0] = dt.date(2026, 1, 1)
        second[1] = 1
        second[2] = None
        second[3] = "1002"
        second[6] = dt.datetime(2025, 12, 1, 9, 45)
        for index in range(9, 29):
            second[index] = 0 if index % 2 else index / 10
        second[9] = 1
        worksheet.append(second)

        third = [None] * 37
        third[0] = dt.date(2026, 4, 27)
        third[1] = 1
        third[2] = "synthetic-material-three"
        third[3] = "1003"
        third[6] = dt.datetime(2026, 4, 1, 10, 15)
        for index in range(9, 37):
            third[index] = 0 if index % 2 else index / 10
        third[9] = 0
        worksheet.append(third)

        sentinel = [None] * 37
        sentinel[0] = dt.date(2026, 4, 27)
        sentinel[1] = 3
        sentinel[2] = "AIGC动态创意视频素材集合"
        sentinel[3] = "-"
        sentinel[6] = "-"
        for index in range(9, 37):
            sentinel[index] = 0
        sentinel[9] = 1
        worksheet.append(sentinel)

        worksheet.append([None, "排序"] + [None] * 35)
        workbook.save(path)

    def test_maps_three_periods_preserves_zero_and_marks_absent_fields_unknown(self):
        workbook_path = self.root / "input.xlsx"
        self.write_workbook(workbook_path)

        rows_by_date, stats = convert_workbook_rows(workbook_path, self.contract)

        self.assertEqual(stats["selectedFactRows"], 4)
        self.assertEqual(stats["ignoredRows"], 1)
        self.assertEqual(stats["zeroSpendRows"], 1)
        first = rows_by_date[dt.date(2025, 11, 1)][0]
        self.assertEqual(first["video_play_duration_10s_rate_for_roi2"], "UNKNOWN")
        self.assertEqual(first["additional_delivery_stat_cost_for_roi2_assist"], "UNKNOWN")
        self.assertEqual(first["live_cvr_rate_for_roi2_v2"], 120)
        self.assertEqual(
            rows_by_date[dt.date(2026, 1, 1)][0]["roi2_material_video_name"],
            "UNKNOWN",
        )
        later = rows_by_date[dt.date(2026, 4, 27)]
        self.assertEqual(later[0]["stat_cost_for_roi2"], 0)
        self.assertEqual(later[1]["material_id"], "-2")
        self.assertEqual(later[1]["_manual_history"]["display_material_id"], "-")
        self.assertIn("2026-04-27", stats["sourceRankAnomalyDays"])

    def test_full_bundle_is_staged_and_read_back_without_database(self):
        workbook_path = self.root / "input.xlsx"
        self.write_workbook(workbook_path)
        source_hash = __import__("hashlib").sha256(workbook_path.read_bytes()).hexdigest()
        args = argparse.Namespace(
            source=str(workbook_path),
            expected_source_sha256=source_hash,
            contract=str(CONTRACT_PATH),
            project_root=str(PROJECT_ROOT),
            output_root=str(PROJECT_ROOT / ".local" / "manual-history-test" / self.root.name),
            run_id=f"synthetic-{self.root.name}",
            project_ref="sampleproject",
            account_id=None,
            account_evidence=None,
            completeness_evidence=None,
            auto_overlap_root=None,
        )
        try:
            receipt = convert(args)
            self.assertEqual(receipt["result"], "STAGED_UNVERIFIED")
            self.assertEqual(receipt["workbook"]["selectedFactRows"], 4)
            self.assertEqual(receipt["readback"]["rowsReadBack"], 4)
            self.assertEqual(receipt["readback"]["status"], "PASSED")
            self.assertFalse(receipt["database"]["importAttempted"])
            self.assertEqual(receipt["daily"]["readyForImportDays"], 0)
        finally:
            output = PROJECT_ROOT / ".local" / "manual-history-test" / self.root.name
            if output.exists():
                for item in output.rglob("*"):
                    if item.is_file():
                        item.chmod(0o666)
                import shutil
                shutil.rmtree(output)

    def test_overlap_compares_every_field_and_blocks_manual_publication(self):
        stat_date = dt.date(2026, 10, 1)
        fields = self.contract["overlap"]["compareFields"]
        row = {field: 0 for field in fields}
        row.update({
            "material_create_time_v2": "2026-09-01 08:00:00",
            "material_id": "synthetic-1",
            "roi2_material_video_name": "synthetic",
            "stat_time_day": stat_date.isoformat(),
            "stat_cost_for_roi2": 1,
            "_manual_history": {"source_rank": 1},
        })
        auto_root = self.root / "auto"
        day = auto_root / stat_date.isoformat() / "sampleproject"
        day.mkdir(parents=True)
        (day / "manifest.json").write_text(json.dumps({
            "jobStatus": "COMPLETE",
            "paginationComplete": True,
            "statDate": stat_date.isoformat(),
            "projectRef": "sampleproject",
            "advertiserId": "synthetic-account",
        }), encoding="utf-8")
        auto_row = {key: value for key, value in row.items() if key != "_manual_history"}
        (day / "material_day.json").write_text(json.dumps({
            "paginationComplete": True,
            "statDate": stat_date.isoformat(),
            "projectRef": "sampleproject",
            "advertiserId": "synthetic-account",
            "rows": [auto_row, {**auto_row, "material_id": "zero-only", "stat_cost_for_roi2": 0}],
        }), encoding="utf-8")

        result, account = compare_overlap_day(
            auto_root, "sampleproject", stat_date, [row], fields
        )

        self.assertEqual(result["status"], "MATCHED_AUTO_POSITIVE_ROWS")
        self.assertEqual(result["fieldComparisons"], len(fields))
        self.assertEqual(result["autoZeroSpendRows"], 1)
        self.assertEqual(result["manualPublicationAction"], "DO_NOT_IMPORT_OR_OVERWRITE")
        self.assertEqual(account, "synthetic-account")

    def test_known_overlap_is_blocked_without_automatic_archive_argument(self):
        workbook_path = self.root / "overlap.xlsx"
        self.write_single_day_workbook(workbook_path, dt.date(2026, 10, 1))
        args = self.args_for(workbook_path)
        identity, _ = self.attestation("account", args.expected_source_sha256)
        completeness, _ = self.attestation("completeness", args.expected_source_sha256)
        args.account_id = "synthetic-account"
        args.account_evidence = str(identity)
        args.completeness_evidence = str(completeness)
        try:
            receipt = convert(args)
            day = load_json(Path(receipt["output"]["absolutePath"]) / "days/2026-10-01/day-evidence.json")
            self.assertEqual(day["status"], "STAGED_UNVERIFIED")
            self.assertFalse(day["databaseImportEligible"])
            self.assertEqual(day["overlap"]["status"], "AUTO_EVIDENCE_NOT_PROVIDED")
            self.assertIn("AUTO_AUTHORITATIVE_OVERLAP_DO_NOT_IMPORT", day["publicationBlockers"])
        finally:
            self.cleanup_output(args)

    def test_ready_requires_both_date_bound_attestations_and_matching_row_semantics(self):
        workbook_path = self.root / "verified.xlsx"
        self.write_single_day_workbook(workbook_path, dt.date(2026, 9, 30))
        for scenario in ("verified", "outside-identity-date", "missing-completeness-date", "wrong-empty-status"):
            with self.subTest(scenario=scenario):
                args = self.args_for(workbook_path, scenario)
                identity, identity_payload = self.attestation("account", args.expected_source_sha256)
                completeness, completeness_payload = self.attestation("completeness", args.expected_source_sha256)
                completeness_payload["days"] = {"2026-09-30": "COMPLETE"}
                if scenario == "outside-identity-date":
                    identity_payload["validFrom"] = "2026-10-01"
                elif scenario == "missing-completeness-date":
                    completeness_payload["days"] = {}
                elif scenario == "wrong-empty-status":
                    completeness_payload["days"] = {"2026-09-30": "COMPLETE_NO_ROWS"}
                identity.write_text(json.dumps(identity_payload), encoding="utf-8")
                completeness.write_text(json.dumps(completeness_payload), encoding="utf-8")
                args.account_id = "synthetic-account"
                args.account_evidence = str(identity)
                args.completeness_evidence = str(completeness)
                try:
                    if scenario == "wrong-empty-status":
                        with self.assertRaisesRegex(ConversionError, "contradicts the source row count"):
                            convert(args)
                    else:
                        receipt = convert(args)
                        self.assertEqual(receipt["daily"]["readyForImportDays"], 1 if scenario == "verified" else 0)
                finally:
                    self.cleanup_output(args)

    def test_attestations_reject_other_source_account_or_missing_confirmation(self):
        source_hash = "a" * 64
        for kind in ("account", "completeness"):
            for field, value in (("sourceWorkbookSha256", "b" * 64), ("accountId", "another-account"),
                                 ("projectRef", "another-project"), ("verificationMethod", None), ("evidenceRef", "")):
                with self.subTest(kind=kind, field=field):
                    path, payload = self.attestation(kind, source_hash)
                    payload[field] = value
                    path.write_text(json.dumps(payload), encoding="utf-8")
                    with self.assertRaises(ConversionError):
                        if kind == "account":
                            load_identity_binding("synthetic-account", path, "sampleproject", source_hash)
                        else:
                            load_completeness_binding(path, "synthetic-account", "sampleproject", source_hash)

    def test_unsafe_run_and_mapping_paths_rejected_before_writes(self):
        workbook_path = self.root / "input.xlsx"
        self.write_workbook(workbook_path)
        for segment in ("../escape", "..\\escape", "C:\\outside", "/outside", ".."):
            with self.subTest(segment=segment):
                args = self.args_for(workbook_path, segment)
                with patch("convert_manual_history.copy_source_evidence") as copying:
                    with self.assertRaises(ConversionError):
                        convert(args)
                    copying.assert_not_called()
                with patch("pathlib.Path.mkdir") as mkdir:
                    write_failure_receipt(args, ConversionError("TEST", "synthetic"))
                    mkdir.assert_not_called()
                contract = copy.deepcopy(self.contract)
                contract["mappingVersion"] = segment
                with self.assertRaises(ConversionError):
                    validate_contract(contract)


if __name__ == "__main__":
    unittest.main()
