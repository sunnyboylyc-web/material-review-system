"""Generate private plans for one authorized date; never imports or rewrites sources."""
import argparse
import copy
import json
from pathlib import Path
import sys
from uuid import uuid4
import collect


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--receipt', required=True)
    parser.add_argument('--date', required=True)
    parser.add_argument('--baseline', required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    receipt_path = collect.private_path(root, args.receipt)
    receipt = collect.decode(receipt_path.read_bytes())
    collect.require(receipt['status'] == 'COLLECTED_NOT_IMPORTED' and receipt['transportEvidence'] == 'HTTP_BYTES')
    for item in receipt['publication'] + receipt['rawEvidence'] + receipt['sources']:
        path = Path(item['absolutePath'])
        collect.require(path.resolve(strict=True).is_relative_to(root))
        collect.require(collect.evidence(path) == item)
    directory = receipt_path.parent/'publication'
    material = collect.decode((directory/'material_day.json').read_bytes())
    baseline_path = collect.private_path(root, args.baseline)
    baseline_evidence = collect.evidence(baseline_path)
    baseline = collect.decode(baseline_path.read_bytes())
    from datetime import date
    stat_date = date.fromisoformat(args.date).isoformat()
    collect.require(stat_date == args.date)
    collect.require(material['statDate'] == stat_date and material['projectRef'] == 'sampleproject'
                    and material['advertiserId'] == baseline['expectedAccountId'])
    days = baseline['autoDays'] + baseline['manualDays']
    collect.require(len({d['date'] for d in days}) == len(days)
                    and all(d['date'] != stat_date for d in days))
    run = root/'.local/authorized-collection'/uuid4().hex
    run.mkdir()
    entry = dict(date=stat_date, inputDirectory=str(directory))
    single = copy.deepcopy(baseline)
    single.update(runId='authorized-new-day-' + stat_date.replace('-', ''), autoDays=[entry], manualDays=[])
    combined = copy.deepcopy(baseline)
    combined['runId'] = 'facts-through-' + stat_date.replace('-', '')
    combined['autoDays'].append(entry)
    for name, config in [('single-day.json', single), ('combined.json', combined)]:
        collect.write_once(run/name, config)
    collect.require(collect.evidence(baseline_path) == baseline_evidence)
    collect.write_once(run/'preparation-evidence.json', dict(baseline=baseline_evidence,
                       receipt=collect.evidence(receipt_path), statDate=stat_date,
                       dayCount=len(days)+1, status='PLANS_PREPARED_NOT_IMPORTED',
                       outputs=[collect.evidence(run/name) for name in ('single-day.json', 'combined.json')]))
    print(json.dumps(dict(status='PLANS_PREPARED_NOT_IMPORTED', singleDay=str(run/'single-day.json'), combined=str(run/'combined.json'), rowCount=len(material['rows']), zeroSpendRows=material['counts']['zeroSpendRows'])))


if __name__ == '__main__':
    try:
        main()
    except Exception:
        print('IMPORT_PLAN_PREPARATION_REJECTED')
        sys.exit(2)
