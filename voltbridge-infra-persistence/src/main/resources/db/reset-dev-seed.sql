-- Delete only rows belonging to the fixed VoltBridge local demo fixture.
-- Rows referenced by non-demo data are deliberately left in place.
BEGIN;

DELETE FROM billing_line_item li
USING billing_record br
WHERE li.billing_record_id = br.id
  AND br.tenant_id = '11111111-1111-4111-8111-111111111111'
  AND (br.id BETWEEN '80000000-0000-4000-8000-000000000001'::uuid AND '80000000-0000-4000-8000-000000000014'::uuid
       OR br.transaction_id IN (
           SELECT id FROM charging_transaction
           WHERE tenant_id = '11111111-1111-4111-8111-111111111111'
             AND business_ref LIKE 'DEMO-ORDER-%'));

DELETE FROM billing_record
WHERE tenant_id = '11111111-1111-4111-8111-111111111111'
  AND id BETWEEN '80000000-0000-4000-8000-000000000001'::uuid AND '80000000-0000-4000-8000-000000000014'::uuid;

DELETE FROM billing_record
WHERE tenant_id = '11111111-1111-4111-8111-111111111111'
  AND transaction_id IN (
      SELECT id FROM charging_transaction
      WHERE tenant_id = '11111111-1111-4111-8111-111111111111'
        AND business_ref LIKE 'DEMO-ORDER-%');

DELETE FROM charging_meter_sample
WHERE transaction_id IN (
    SELECT id FROM charging_transaction
    WHERE tenant_id = '11111111-1111-4111-8111-111111111111'
      AND business_ref LIKE 'DEMO-ORDER-%');

DELETE FROM charging_transaction
WHERE tenant_id = '11111111-1111-4111-8111-111111111111'
  AND business_ref LIKE 'DEMO-ORDER-%';

-- Remove only the original demo fallback when the family has not been extended or billed.
DELETE FROM tariff_period period
USING tariff_plan fallback
WHERE period.tariff_plan_id = fallback.id
  AND fallback.id = '50000000-0000-4000-8000-000000000004'
  AND fallback.family_id = '51000000-0000-4000-8000-000000000004'
  AND fallback.tenant_id = '11111111-1111-4111-8111-111111111111'
  AND fallback.role = 'FALLBACK'
  AND NOT EXISTS (SELECT 1 FROM billing_record br WHERE br.tariff_plan_id = fallback.id)
  AND NOT EXISTS (SELECT 1 FROM tariff_plan sibling
                  WHERE sibling.family_id = fallback.family_id AND sibling.id <> fallback.id);

DELETE FROM tariff_plan fallback
WHERE fallback.id = '50000000-0000-4000-8000-000000000004'
  AND fallback.family_id = '51000000-0000-4000-8000-000000000004'
  AND fallback.tenant_id = '11111111-1111-4111-8111-111111111111'
  AND fallback.role = 'FALLBACK'
  AND NOT EXISTS (SELECT 1 FROM billing_record br WHERE br.tariff_plan_id = fallback.id)
  AND NOT EXISTS (SELECT 1 FROM tariff_plan sibling
                  WHERE sibling.family_id = fallback.family_id AND sibling.id <> fallback.id);

DELETE FROM device_command dc
WHERE dc.tenant_id = '11111111-1111-4111-8111-111111111111'
  AND dc.business_ref LIKE 'DEMO-CMD-%'
  AND NOT EXISTS (SELECT 1 FROM charging_transaction tx WHERE tx.command_id = dc.id);

DELETE FROM tariff_period period
USING tariff_plan tp
WHERE period.tariff_plan_id = tp.id
  AND tp.tenant_id = '11111111-1111-4111-8111-111111111111'
  AND tp.name LIKE 'VoltBridge Demo - %'
  AND NOT EXISTS (SELECT 1 FROM billing_record br WHERE br.tariff_plan_id = tp.id);

DELETE FROM tariff_plan tp
WHERE tp.tenant_id = '11111111-1111-4111-8111-111111111111'
  AND tp.name LIKE 'VoltBridge Demo - %'
  AND NOT EXISTS (SELECT 1 FROM billing_record br WHERE br.tariff_plan_id = tp.id);

DELETE FROM charging_connector c
WHERE c.tenant_id = '11111111-1111-4111-8111-111111111111'
  AND (c.connector_code LIKE 'VB-DEMO-%'
       OR c.connector_code IN ('OCPP-SIM-001-01', 'OCPP-SIM-002-01', 'OCPP-SIM-003-01',
                               'YKC-20231212000010-01', 'YKC-20231212000011-01', 'YKC-20231212000012-01'))
  AND NOT EXISTS (SELECT 1 FROM charging_transaction tx WHERE tx.connector_id = c.id)
  AND NOT EXISTS (SELECT 1 FROM device_command dc WHERE dc.connector_id = c.id);

DELETE FROM charging_device d
WHERE d.tenant_id = '11111111-1111-4111-8111-111111111111'
  AND (d.device_code LIKE 'VB-DEMO-%'
       OR d.device_code IN ('OCPP-SIM-001', 'OCPP-SIM-002', 'OCPP-SIM-003',
                            '20231212000010', '20231212000011', '20231212000012'))
  AND NOT EXISTS (SELECT 1 FROM charging_transaction tx WHERE tx.device_id = d.id)
  AND NOT EXISTS (SELECT 1 FROM device_command dc WHERE dc.device_id = d.id)
  AND NOT EXISTS (SELECT 1 FROM charging_connector c WHERE c.device_id = d.id);

DELETE FROM charging_station s
WHERE s.id = '22222222-2222-4222-8222-222222222222'
  AND s.tenant_id = '11111111-1111-4111-8111-111111111111'
  AND s.station_code = 'VB-DEMO-STATION-001'
  AND NOT EXISTS (SELECT 1 FROM charging_device d WHERE d.station_id = s.id);

DELETE FROM tenant t
WHERE t.id = '11111111-1111-4111-8111-111111111111'
  AND t.tenant_code = 'VB-DEMO'
  AND NOT EXISTS (SELECT 1 FROM app_user u WHERE u.tenant_id = t.id)
  AND NOT EXISTS (SELECT 1 FROM charging_station s WHERE s.tenant_id = t.id)
  AND NOT EXISTS (SELECT 1 FROM charging_device d WHERE d.tenant_id = t.id)
  AND NOT EXISTS (SELECT 1 FROM charging_connector c WHERE c.tenant_id = t.id)
  AND NOT EXISTS (SELECT 1 FROM device_command dc WHERE dc.tenant_id = t.id)
  AND NOT EXISTS (SELECT 1 FROM charging_transaction tx WHERE tx.tenant_id = t.id)
  AND NOT EXISTS (SELECT 1 FROM tariff_plan tp WHERE tp.tenant_id = t.id)
  AND NOT EXISTS (SELECT 1 FROM billing_record br WHERE br.tenant_id = t.id);

COMMIT;

SELECT 'demo tenant rows remaining' AS fixture, count(*) AS rows FROM tenant
 WHERE id = '11111111-1111-4111-8111-111111111111' AND tenant_code = 'VB-DEMO'
UNION ALL SELECT 'demo devices', count(*) FROM charging_device
 WHERE tenant_id = '11111111-1111-4111-8111-111111111111' AND device_code LIKE 'VB-DEMO-%'
UNION ALL SELECT 'simulator devices', count(*) FROM charging_device
 WHERE tenant_id = '11111111-1111-4111-8111-111111111111'
   AND device_code IN ('OCPP-SIM-001', 'OCPP-SIM-002', 'OCPP-SIM-003',
                       '20231212000010', '20231212000011', '20231212000012')
UNION ALL SELECT 'demo commands', count(*) FROM device_command
 WHERE tenant_id = '11111111-1111-4111-8111-111111111111' AND business_ref LIKE 'DEMO-CMD-%'
UNION ALL SELECT 'demo transactions', count(*) FROM charging_transaction
 WHERE tenant_id = '11111111-1111-4111-8111-111111111111' AND business_ref LIKE 'DEMO-ORDER-%'
UNION ALL SELECT 'demo tariffs', count(*) FROM tariff_plan
 WHERE tenant_id = '11111111-1111-4111-8111-111111111111' AND name LIKE 'VoltBridge Demo - %'
UNION ALL SELECT 'demo fallback revisions', count(*) FROM tariff_plan
 WHERE tenant_id = '11111111-1111-4111-8111-111111111111' AND role = 'FALLBACK'
UNION ALL SELECT 'demo billing records', count(*) FROM billing_record
 WHERE tenant_id = '11111111-1111-4111-8111-111111111111'
  AND id BETWEEN '80000000-0000-4000-8000-000000000001'::uuid AND '80000000-0000-4000-8000-000000000014'::uuid
ORDER BY fixture;
