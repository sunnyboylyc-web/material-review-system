import unittest
from decimal import Decimal
import convert_upload_template as c


class PackedFactsTests(unittest.TestCase):
    def test_lossless_canonical_hash_sort_and_last_row(self):
        rows = [dict(material_id=i, value=Decimal(v), name='合成素材', missing='UNKNOWN')
                for i, v in [('2', '-0'), ('10', '4.3100'), ('-2', '0.123456789012345678901')]]
        packed = c.PackedFacts()
        for row in rows:
            packed.append(row)
        self.assertEqual(c.canonical_json(rows), c.canonical_json(packed))
        self.assertEqual(c.facts_hash(rows), c.facts_hash(packed))
        rows[0] = dict(material_id='2', value=Decimal('0'), name='末行', missing='UNKNOWN')
        packed[0] = rows[0]
        packed.sort_by_material()
        rows.sort(key=lambda row: row['material_id'])
        self.assertEqual(rows, list(packed))
        self.assertEqual(c.canonical_json(rows), c.canonical_json(packed))
        self.assertEqual(c.facts_hash(rows), c.facts_hash(packed))
        self.assertEqual([], c.PackedFacts())


if __name__ == '__main__':
    unittest.main()
