-- Local development/UI demo fixtures only. Run manually after Flyway has created the schema.
-- This file is intentionally outside db/migration and is never run automatically by the application.
BEGIN;

INSERT INTO tenant (id, tenant_code, tenant_name, status)
VALUES ('11111111-1111-4111-8111-111111111111', 'VB-DEMO', 'VoltBridge 演示租户', 'ACTIVE')
ON CONFLICT (id) DO UPDATE
SET tenant_code = EXCLUDED.tenant_code,
    tenant_name = EXCLUDED.tenant_name,
    status = EXCLUDED.status,
    updated_at = CURRENT_TIMESTAMP
WHERE tenant.tenant_code = EXCLUDED.tenant_code;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM tenant
        WHERE id = '11111111-1111-4111-8111-111111111111' AND tenant_code = 'VB-DEMO'
    ) THEN
        RAISE EXCEPTION 'The fixed VoltBridge demo tenant ID is already used by a non-demo tenant';
    END IF;
END
$$;

INSERT INTO charging_station (id, tenant_id, station_code, station_name, longitude, latitude,
                              province, city, district, address, status)
VALUES ('22222222-2222-4222-8222-222222222222', '11111111-1111-4111-8111-111111111111',
        'VB-DEMO-STATION-001', 'VoltBridge 演示充电站', 116.3970000, 39.9080000,
        '北京市', '北京市', '东城区', '演示地址，仅用于本地开发', 'ACTIVE')
ON CONFLICT (id) DO UPDATE
SET tenant_id = EXCLUDED.tenant_id,
    station_code = EXCLUDED.station_code,
    station_name = EXCLUDED.station_name,
    longitude = EXCLUDED.longitude,
    latitude = EXCLUDED.latitude,
    province = EXCLUDED.province,
    city = EXCLUDED.city,
    district = EXCLUDED.district,
    address = EXCLUDED.address,
    status = EXCLUDED.status,
    updated_at = CURRENT_TIMESTAMP
WHERE charging_station.tenant_id = EXCLUDED.tenant_id
  AND charging_station.station_code = EXCLUDED.station_code;

WITH device_seed (id, device_code, device_name, protocol, protocol_version, business_status, model) AS (
    VALUES
        ('30000000-0000-4000-8000-000000000001'::uuid, 'VB-DEMO-001', '演示直流桩 001', 'YUN_KUAI_CHONG_150', '1.5', 'ENABLED', 'YKC-DC-120'),
        ('30000000-0000-4000-8000-000000000002'::uuid, 'VB-DEMO-002', '演示直流桩 002', 'YUN_KUAI_CHONG_150', '1.5', 'ENABLED', 'YKC-DC-120'),
        ('30000000-0000-4000-8000-000000000003'::uuid, 'VB-DEMO-003', '演示直流桩 003', 'YUN_KUAI_CHONG_150', '1.5', 'ENABLED', 'YKC-DC-180'),
        ('30000000-0000-4000-8000-000000000004'::uuid, 'VB-DEMO-004', '演示直流桩 004', 'YUN_KUAI_CHONG_150', '1.5', 'DISABLED', 'YKC-DC-120'),
        ('30000000-0000-4000-8000-000000000005'::uuid, 'VB-DEMO-005', '演示 OCPP 充电桩 005', 'OCPP_16', '1.6J', 'ENABLED', 'OCPP-DC-120'),
        ('30000000-0000-4000-8000-000000000006'::uuid, 'VB-DEMO-006', '演示 OCPP 充电桩 006', 'OCPP_16', '1.6J', 'ENABLED', 'OCPP-DC-180'),
        ('30000000-0000-4000-8000-000000000007'::uuid, 'VB-DEMO-007', '停用演示充电桩 007', 'OCPP_16', '1.6J', 'DISABLED', 'OCPP-DC-120'),
        ('30000000-0000-4000-8000-000000000008'::uuid, 'VB-DEMO-008', '故障演示充电桩 008', 'OCPP_16', '1.6J', 'ENABLED', 'OCPP-DC-120')
)
INSERT INTO charging_device (id, tenant_id, station_id, device_code, device_name, protocol,
                             protocol_version, manufacturer, brand, model, device_type, business_status, additional_info)
SELECT id, '11111111-1111-4111-8111-111111111111', '22222222-2222-4222-8222-222222222222',
       device_code, device_name, protocol, protocol_version, 'VoltBridge Demo', 'Demo', model,
       'DC_CHARGER', business_status, jsonb_build_object('seed', 'voltbridge-dev-demo')
FROM device_seed
ON CONFLICT (id) DO UPDATE
SET tenant_id = EXCLUDED.tenant_id,
    station_id = EXCLUDED.station_id,
    device_code = EXCLUDED.device_code,
    device_name = EXCLUDED.device_name,
    protocol = EXCLUDED.protocol,
    protocol_version = EXCLUDED.protocol_version,
    manufacturer = EXCLUDED.manufacturer,
    brand = EXCLUDED.brand,
    model = EXCLUDED.model,
    device_type = EXCLUDED.device_type,
    business_status = EXCLUDED.business_status,
    additional_info = EXCLUDED.additional_info,
    updated_at = CURRENT_TIMESTAMP
WHERE charging_device.tenant_id = EXCLUDED.tenant_id
  AND charging_device.device_code = EXCLUDED.device_code;

-- Protocol simulators are intentionally separated from UI demo fixtures.
WITH simulator_device_seed (id, device_code, device_name, protocol, protocol_version, model) AS (
    VALUES
        ('31000000-0000-4000-8000-000000000001'::uuid, 'OCPP-SIM-001', 'OCPP Simulator 001', 'OCPP_16', '1.6J', 'OCPP-DC-120'),
        ('31000000-0000-4000-8000-000000000002'::uuid, 'OCPP-SIM-002', 'OCPP Simulator 002', 'OCPP_16', '1.6J', 'OCPP-DC-120'),
        ('31000000-0000-4000-8000-000000000003'::uuid, 'OCPP-SIM-003', 'OCPP Simulator 003', 'OCPP_16', '1.6J', 'OCPP-DC-120'),
        ('31000000-0000-4000-8000-000000000004'::uuid, '20231212000010', 'YKC Simulator 001', 'YUN_KUAI_CHONG_150', '1.5', 'YKC-DC-120'),
        ('31000000-0000-4000-8000-000000000005'::uuid, '20231212000011', 'YKC Simulator 002', 'YUN_KUAI_CHONG_150', '1.5', 'YKC-DC-120'),
        ('31000000-0000-4000-8000-000000000006'::uuid, '20231212000012', 'YKC Simulator 003', 'YUN_KUAI_CHONG_150', '1.5', 'YKC-DC-120')
)
INSERT INTO charging_device (id, tenant_id, station_id, device_code, device_name, protocol,
                             protocol_version, manufacturer, brand, model, device_type, business_status, additional_info)
SELECT id, '11111111-1111-4111-8111-111111111111', '22222222-2222-4222-8222-222222222222',
       device_code, device_name, protocol, protocol_version, 'VoltBridge Simulator', 'Simulator', model,
       'DC_CHARGER', 'ENABLED', jsonb_build_object('seed', 'voltbridge-dev-simulator')
FROM simulator_device_seed
ON CONFLICT (id) DO UPDATE
SET tenant_id = EXCLUDED.tenant_id,
    station_id = EXCLUDED.station_id,
    device_code = EXCLUDED.device_code,
    device_name = EXCLUDED.device_name,
    protocol = EXCLUDED.protocol,
    protocol_version = EXCLUDED.protocol_version,
    manufacturer = EXCLUDED.manufacturer,
    brand = EXCLUDED.brand,
    model = EXCLUDED.model,
    device_type = EXCLUDED.device_type,
    business_status = EXCLUDED.business_status,
    additional_info = EXCLUDED.additional_info,
    updated_at = CURRENT_TIMESTAMP
WHERE charging_device.tenant_id = EXCLUDED.tenant_id
  AND charging_device.device_code = EXCLUDED.device_code;

WITH connector_seed (id, device_no, connector_code, rated_power, status) AS (
    VALUES
        ('40000000-0000-4000-8000-000000000001'::uuid, 1, 'VB-DEMO-001-C1', 120.000, 'AVAILABLE'),
        ('40000000-0000-4000-8000-000000000002'::uuid, 2, 'VB-DEMO-002-C1', 120.000, 'CHARGING'),
        ('40000000-0000-4000-8000-000000000003'::uuid, 3, 'VB-DEMO-003-C1', 180.000, 'OCCUPIED'),
        ('40000000-0000-4000-8000-000000000004'::uuid, 4, 'VB-DEMO-004-C1', 120.000, 'AVAILABLE'),
        ('40000000-0000-4000-8000-000000000005'::uuid, 5, 'VB-DEMO-005-C1', 120.000, 'AVAILABLE'),
        ('40000000-0000-4000-8000-000000000006'::uuid, 6, 'VB-DEMO-006-C1', 180.000, 'AVAILABLE'),
        ('40000000-0000-4000-8000-000000000007'::uuid, 7, 'VB-DEMO-007-C1', 120.000, 'DISABLED'),
        ('40000000-0000-4000-8000-000000000008'::uuid, 8, 'VB-DEMO-008-C1', 120.000, 'FAULTED')
)
INSERT INTO charging_connector (id, tenant_id, station_id, device_id, connector_no, connector_code,
                                connector_type, rated_power, status)
SELECT s.id, d.tenant_id, d.station_id, d.id, 1, s.connector_code,
       'DC', s.rated_power, s.status
FROM connector_seed s
JOIN charging_device d ON d.device_code = 'VB-DEMO-' || lpad(s.device_no::text, 3, '0')
ON CONFLICT (id) DO UPDATE
SET tenant_id = EXCLUDED.tenant_id,
    station_id = EXCLUDED.station_id,
    device_id = EXCLUDED.device_id,
    connector_no = EXCLUDED.connector_no,
    connector_code = EXCLUDED.connector_code,
    connector_type = EXCLUDED.connector_type,
    rated_power = EXCLUDED.rated_power,
    status = EXCLUDED.status,
    updated_at = CURRENT_TIMESTAMP
WHERE charging_connector.device_id = EXCLUDED.device_id
  AND charging_connector.connector_code = EXCLUDED.connector_code;

WITH simulator_connector_seed (id, device_code, connector_code) AS (
    VALUES
        ('41000000-0000-4000-8000-000000000001'::uuid, 'OCPP-SIM-001', 'OCPP-SIM-001-01'),
        ('41000000-0000-4000-8000-000000000002'::uuid, 'OCPP-SIM-002', 'OCPP-SIM-002-01'),
        ('41000000-0000-4000-8000-000000000003'::uuid, 'OCPP-SIM-003', 'OCPP-SIM-003-01'),
        ('41000000-0000-4000-8000-000000000004'::uuid, '20231212000010', 'YKC-20231212000010-01'),
        ('41000000-0000-4000-8000-000000000005'::uuid, '20231212000011', 'YKC-20231212000011-01'),
        ('41000000-0000-4000-8000-000000000006'::uuid, '20231212000012', 'YKC-20231212000012-01')
)
INSERT INTO charging_connector (id, tenant_id, station_id, device_id, connector_no, connector_code,
                                connector_type, rated_power, status)
SELECT seed.id, device.tenant_id, device.station_id, device.id, 1, seed.connector_code, 'DC', 120.000, 'AVAILABLE'
FROM simulator_connector_seed seed
JOIN charging_device device ON device.device_code = seed.device_code
ON CONFLICT (id) DO UPDATE
SET tenant_id = EXCLUDED.tenant_id,
    station_id = EXCLUDED.station_id,
    device_id = EXCLUDED.device_id,
    connector_no = EXCLUDED.connector_no,
    connector_code = EXCLUDED.connector_code,
    connector_type = EXCLUDED.connector_type,
    rated_power = EXCLUDED.rated_power,
    status = EXCLUDED.status,
    updated_at = CURRENT_TIMESTAMP
WHERE charging_connector.device_id = EXCLUDED.device_id
  AND charging_connector.connector_code = EXCLUDED.connector_code;

-- The demo tenant receives one fixed all-day FALLBACK family. Re-seeding never overwrites operator revisions.
INSERT INTO tariff_plan (id, family_id, revision, tenant_id, role, name, currency, pricing_type, timezone,
                         effective_from, effective_to, status, activated_at, retired_at, created_at)
VALUES ('50000000-0000-4000-8000-000000000004', '51000000-0000-4000-8000-000000000004', 1,
        '11111111-1111-4111-8111-111111111111', 'FALLBACK', '默认固定资费', 'CNY', 'FIXED', 'Asia/Shanghai',
        CURRENT_TIMESTAMP - INTERVAL '30 days', NULL, 'ACTIVE', CURRENT_TIMESTAMP - INTERVAL '30 days', NULL,
        CURRENT_TIMESTAMP - INTERVAL '30 days')
ON CONFLICT (id) DO NOTHING;

INSERT INTO tariff_plan (id, family_id, revision, tenant_id, role, name, currency, pricing_type, timezone,
                         effective_from, effective_to, status, activated_at, retired_at, created_at)
VALUES
    ('50000000-0000-4000-8000-000000000001', '51000000-0000-4000-8000-000000000001', 1,
     '11111111-1111-4111-8111-111111111111', 'SCHEDULED', 'VoltBridge Demo - 标准固定资费 V1', 'CNY', 'FIXED', 'Asia/Shanghai',
     CURRENT_TIMESTAMP - INTERVAL '30 days', NULL, 'ACTIVE', CURRENT_TIMESTAMP - INTERVAL '30 days', NULL, CURRENT_TIMESTAMP - INTERVAL '30 days'),
    ('50000000-0000-4000-8000-000000000002', '51000000-0000-4000-8000-000000000002', 1,
     '11111111-1111-4111-8111-111111111111', 'SCHEDULED', 'VoltBridge Demo - 低价历史固定资费 V1', 'CNY', 'FIXED', 'Asia/Shanghai',
     CURRENT_TIMESTAMP - INTERVAL '60 days', CURRENT_TIMESTAMP - INTERVAL '30 days', 'RETIRED',
     CURRENT_TIMESTAMP - INTERVAL '60 days', CURRENT_TIMESTAMP - INTERVAL '30 days', CURRENT_TIMESTAMP - INTERVAL '60 days'),
    ('50000000-0000-4000-8000-000000000003', '51000000-0000-4000-8000-000000000003', 1,
     '11111111-1111-4111-8111-111111111111', 'SCHEDULED', 'VoltBridge Demo - 标准峰谷资费 V1', 'CNY', 'TIME_OF_USE', 'Asia/Shanghai',
     CURRENT_TIMESTAMP + INTERVAL '7 days', NULL, 'DRAFT', NULL, NULL, CURRENT_TIMESTAMP)
ON CONFLICT (id) DO UPDATE
SET tenant_id = EXCLUDED.tenant_id,
    family_id = EXCLUDED.family_id,
    revision = EXCLUDED.revision,
    role = EXCLUDED.role,
    name = EXCLUDED.name,
    currency = EXCLUDED.currency,
    pricing_type = EXCLUDED.pricing_type,
    timezone = EXCLUDED.timezone,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    status = EXCLUDED.status,
    activated_at = EXCLUDED.activated_at,
    retired_at = EXCLUDED.retired_at,
    created_at = EXCLUDED.created_at,
    version = 0
WHERE tariff_plan.tenant_id = EXCLUDED.tenant_id;

INSERT INTO tariff_revision_counter (family_id, last_revision)
SELECT family_id, MAX(revision)
FROM tariff_plan
WHERE tenant_id = '11111111-1111-4111-8111-111111111111'
GROUP BY family_id
ON CONFLICT (family_id) DO UPDATE
SET last_revision = GREATEST(tariff_revision_counter.last_revision, EXCLUDED.last_revision);

INSERT INTO tariff_period (tariff_plan_id, sequence_no, period_type, start_minute, end_minute,
                           energy_price_per_kwh, service_fee_per_kwh)
VALUES
    ('50000000-0000-4000-8000-000000000001', 1, 'FLAT', 0, 1440, 1.000000, 0.200000),
    ('50000000-0000-4000-8000-000000000002', 1, 'FLAT', 0, 1440, 0.750000, 0.150000),
    ('50000000-0000-4000-8000-000000000004', 1, 'FLAT', 0, 1440, 1.000000, 0.200000),
    ('50000000-0000-4000-8000-000000000003', 1, 'VALLEY', 0, 420, 0.600000, 0.200000),
    ('50000000-0000-4000-8000-000000000003', 2, 'PEAK', 420, 600, 1.200000, 0.300000),
    ('50000000-0000-4000-8000-000000000003', 3, 'FLAT', 600, 1020, 0.800000, 0.200000),
    ('50000000-0000-4000-8000-000000000003', 4, 'PEAK', 1020, 1260, 1.200000, 0.300000),
    ('50000000-0000-4000-8000-000000000003', 5, 'VALLEY', 1260, 1440, 0.600000, 0.200000)
ON CONFLICT (tariff_plan_id, sequence_no) DO UPDATE
SET period_type = EXCLUDED.period_type,
    start_minute = EXCLUDED.start_minute,
    end_minute = EXCLUDED.end_minute,
    energy_price_per_kwh = EXCLUDED.energy_price_per_kwh,
    service_fee_per_kwh = EXCLUDED.service_fee_per_kwh
WHERE tariff_period.tariff_plan_id <> '50000000-0000-4000-8000-000000000004';

WITH command_seed (id, device_no, command_type, command_status, business_ref,
                   request_payload, response_payload, age_minutes, sent_offset_seconds,
                   ack_offset_seconds, completed_offset_seconds, expiry_offset_seconds,
                   failure_code, failure_reason) AS (
    VALUES
        ('60000000-0000-4000-8000-000000000001'::uuid, 1, 'START_CHARGING', 'CREATED', 'DEMO-CMD-001',
         '{"connectorNo":"1","limitAmount":50.00}'::jsonb, NULL::jsonb, 2, NULL::integer, NULL::integer, NULL::integer, 600, NULL::varchar, NULL::varchar),
        ('60000000-0000-4000-8000-000000000002'::uuid, 4, 'SYNCHRONIZE_TIME', 'SENT', 'DEMO-CMD-002',
         '{"targetTime":"server-now"}'::jsonb, NULL::jsonb, 30, 1, NULL::integer, NULL::integer, 3600, NULL::varchar, NULL::varchar),
        ('60000000-0000-4000-8000-000000000003'::uuid, 2, 'START_CHARGING', 'ACKED', 'DEMO-CMD-003',
         '{"connectorNo":"1","limitAmount":50.00}'::jsonb, '{"accepted":true}'::jsonb, 60, 1, 2, NULL::integer, 7200, NULL::varchar, NULL::varchar),
        ('60000000-0000-4000-8000-000000000004'::uuid, 5, 'START_CHARGING', 'EXECUTING', 'DEMO-CMD-004',
         '{"connectorNo":"1"}'::jsonb, '{"accepted":true}'::jsonb, 40, 1, 2, NULL::integer, 3600, NULL::varchar, NULL::varchar),
        ('60000000-0000-4000-8000-000000000005'::uuid, 3, 'START_CHARGING', 'SUCCEEDED', 'DEMO-CMD-005',
         '{"connectorNo":"1"}'::jsonb, '{"effect":"charging-start-observed"}'::jsonb, 145, 1, 2, 25, NULL::integer, NULL::varchar, NULL::varchar),
        ('60000000-0000-4000-8000-000000000006'::uuid, 4, 'STOP_CHARGING', 'SUCCEEDED', 'DEMO-CMD-006',
         '{"connectorNo":"1"}'::jsonb, '{"effect":"charging-stop-observed"}'::jsonb, 90, 1, 2, 5, NULL::integer, NULL::varchar, NULL::varchar),
        ('60000000-0000-4000-8000-000000000007'::uuid, 5, 'RESET_DEVICE', 'SUCCEEDED', 'DEMO-CMD-007',
         '{"whenIdle":true}'::jsonb, '{"effect":"reset-completed"}'::jsonb, 2880, 1, 2, 8, NULL::integer, NULL::varchar, NULL::varchar),
        ('60000000-0000-4000-8000-000000000008'::uuid, 7, 'START_CHARGING', 'FAILED', 'DEMO-CMD-008',
         '{"connectorNo":"1"}'::jsonb, '{"accepted":false}'::jsonb, 1080, 1, 2, 2, NULL::integer, 'DEVICE_REJECTED', '设备拒绝了启动请求'),
        ('60000000-0000-4000-8000-000000000009'::uuid, 6, 'SYNCHRONIZE_TIME', 'TIMEOUT', 'DEMO-CMD-009',
         '{"targetTime":"server-now"}'::jsonb, NULL::jsonb, 600, 1, NULL::integer, 2, 2, NULL::varchar, NULL::varchar),
        ('60000000-0000-4000-8000-000000000010'::uuid, 1, 'START_CHARGING', 'SUCCEEDED', 'DEMO-CMD-010',
         '{"connectorNo":"1"}'::jsonb, '{"effect":"charging-start-observed"}'::jsonb, 220, 1, 2, 20, NULL::integer, NULL::varchar, NULL::varchar),
        ('60000000-0000-4000-8000-000000000011'::uuid, 2, 'START_CHARGING', 'FAILED', 'DEMO-CMD-011',
         '{"connectorNo":"1"}'::jsonb, '{"accepted":false}'::jsonb, 360, 1, 2, 3, NULL::integer, 'DEVICE_REJECTED', '模拟设备拒绝了启动请求'),
        ('60000000-0000-4000-8000-000000000012'::uuid, 8, 'RESET_DEVICE', 'TIMEOUT', 'DEMO-CMD-012',
         '{"whenIdle":true}'::jsonb, NULL::jsonb, 720, 1, NULL::integer, 2, 2, NULL::varchar, NULL::varchar)
)
INSERT INTO device_command (id, tenant_id, device_id, device_code, connector_id, connector_no,
                            command_type, command_status, business_ref, request_payload, response_payload,
                            created_at, updated_at, sent_at, acked_at, completed_at, expired_at,
                            failure_code, failure_reason)
SELECT s.id, d.tenant_id, d.id, d.device_code, c.id, '1', s.command_type, s.command_status,
       s.business_ref, s.request_payload, s.response_payload,
       CURRENT_TIMESTAMP - make_interval(mins => s.age_minutes),
       CASE WHEN s.completed_offset_seconds IS NOT NULL
            THEN CURRENT_TIMESTAMP - make_interval(mins => s.age_minutes) + make_interval(secs => s.completed_offset_seconds)
            WHEN s.ack_offset_seconds IS NOT NULL
            THEN CURRENT_TIMESTAMP - make_interval(mins => s.age_minutes) + make_interval(secs => s.ack_offset_seconds)
            WHEN s.sent_offset_seconds IS NOT NULL
            THEN CURRENT_TIMESTAMP - make_interval(mins => s.age_minutes) + make_interval(secs => s.sent_offset_seconds)
            ELSE CURRENT_TIMESTAMP - make_interval(mins => s.age_minutes) END,
       CASE WHEN s.sent_offset_seconds IS NOT NULL
            THEN CURRENT_TIMESTAMP - make_interval(mins => s.age_minutes) + make_interval(secs => s.sent_offset_seconds) END,
       CASE WHEN s.ack_offset_seconds IS NOT NULL
            THEN CURRENT_TIMESTAMP - make_interval(mins => s.age_minutes) + make_interval(secs => s.ack_offset_seconds) END,
       CASE WHEN s.completed_offset_seconds IS NOT NULL
            THEN CURRENT_TIMESTAMP - make_interval(mins => s.age_minutes) + make_interval(secs => s.completed_offset_seconds) END,
       CASE WHEN s.expiry_offset_seconds IS NOT NULL
            THEN CURRENT_TIMESTAMP - make_interval(mins => s.age_minutes) + make_interval(secs => s.expiry_offset_seconds) END,
       s.failure_code, s.failure_reason
FROM command_seed s
JOIN charging_device d ON d.device_code = 'VB-DEMO-' || lpad(s.device_no::text, 3, '0')
JOIN charging_connector c ON c.device_id = d.id AND c.connector_no = 1
ON CONFLICT (id) DO UPDATE
SET tenant_id = EXCLUDED.tenant_id,
    device_id = EXCLUDED.device_id,
    device_code = EXCLUDED.device_code,
    connector_id = EXCLUDED.connector_id,
    connector_no = EXCLUDED.connector_no,
    command_type = EXCLUDED.command_type,
    command_status = EXCLUDED.command_status,
    business_ref = EXCLUDED.business_ref,
    request_payload = EXCLUDED.request_payload,
    response_payload = EXCLUDED.response_payload,
    created_at = EXCLUDED.created_at,
    updated_at = EXCLUDED.updated_at,
    sent_at = EXCLUDED.sent_at,
    acked_at = EXCLUDED.acked_at,
    completed_at = EXCLUDED.completed_at,
    expired_at = EXCLUDED.expired_at,
    failure_code = EXCLUDED.failure_code,
    failure_reason = EXCLUDED.failure_reason,
    version = 0
WHERE device_command.tenant_id = EXCLUDED.tenant_id
  AND device_command.business_ref = EXCLUDED.business_ref;

-- Keep VB-DEMO-006 connector 01 free for manual remote-start testing in Console.
WITH transaction_seed (id, row_no, device_no, protocol_transaction_id, status, age_minutes,
                       duration_minutes, energy_kwh, command_id, stop_category) AS (
    VALUES
        ('70000000-0000-4000-8000-000000000001'::uuid, 1, 1, '700000001', 'COMPLETED', 120, 38, 8.500000, NULL::uuid, 'REMOTE_STOP'),
        ('70000000-0000-4000-8000-000000000002'::uuid, 2, 2, '700000002', 'COMPLETED', 240, 52, 15.200000, NULL::uuid, 'TARGET_SOC_REACHED'),
        ('70000000-0000-4000-8000-000000000003'::uuid, 3, 3, '700000003', 'COMPLETED', 360, 71, 23.700000, NULL::uuid, 'ENERGY_LIMIT_REACHED'),
        ('70000000-0000-4000-8000-000000000004'::uuid, 4, 4, '700000004', 'COMPLETED', 480, 64, 31.400000, NULL::uuid, 'USER_STOP'),
        ('70000000-0000-4000-8000-000000000005'::uuid, 5, 5, '700000005', 'COMPLETED', 600, 83, 45.800000, NULL::uuid, 'REMOTE_STOP'),
        ('70000000-0000-4000-8000-000000000006'::uuid, 6, 6, '700000006', 'COMPLETED', 1620, 44, 12.600000, NULL::uuid, 'TARGET_SOC_REACHED'),
        ('70000000-0000-4000-8000-000000000007'::uuid, 7, 1, '700000007', 'COMPLETED', 1800, 67, 18.300000, NULL::uuid, 'REMOTE_STOP'),
        ('70000000-0000-4000-8000-000000000008'::uuid, 8, 2, '700000008', 'COMPLETED', 2100, 59, 27.500000, NULL::uuid, 'VEHICLE_STOP'),
        ('70000000-0000-4000-8000-000000000009'::uuid, 9, 3, '700000009', 'COMPLETED', 2400, 34, 9.700000, NULL::uuid, 'AMOUNT_LIMIT_REACHED'),
        ('70000000-0000-4000-8000-000000000010'::uuid, 10, 4, '700000010', 'COMPLETED', 3000, 78, 36.200000, NULL::uuid, 'REMOTE_STOP'),
        ('70000000-0000-4000-8000-000000000011'::uuid, 11, 5, '700000011', 'COMPLETED', 3600, 61, 21.100000, NULL::uuid, 'TARGET_SOC_REACHED'),
        ('70000000-0000-4000-8000-000000000012'::uuid, 12, 6, '700000012', 'COMPLETED', 4200, 48, 14.800000, NULL::uuid, 'USER_STOP'),
        ('70000000-0000-4000-8000-000000000013'::uuid, 13, 1, '700000013', 'COMPLETED', 5400, 41, 11.000000, NULL::uuid, 'REMOTE_STOP'),
        ('70000000-0000-4000-8000-000000000014'::uuid, 14, 1, NULL, 'STARTING', 2, NULL, NULL, '60000000-0000-4000-8000-000000000001', NULL),
        ('70000000-0000-4000-8000-000000000015'::uuid, 15, 2, '700000015', 'CHARGING', 50, NULL, 4.200000, '60000000-0000-4000-8000-000000000003', NULL),
        ('70000000-0000-4000-8000-000000000016'::uuid, 16, 3, '700000016', 'ENDING', 120, 116, 7.200000, '60000000-0000-4000-8000-000000000005', NULL),
        ('70000000-0000-4000-8000-000000000017'::uuid, 17, 7, NULL, 'START_FAILED', 1080, NULL, NULL, '60000000-0000-4000-8000-000000000008', 'DEVICE_FAULT'),
        ('70000000-0000-4000-8000-000000000018'::uuid, 18, 8, '700000018', 'ABORTED', 5760, 20, 3.250000, NULL::uuid, 'COMMUNICATION_TIMEOUT'),
        ('70000000-0000-4000-8000-000000000019'::uuid, 19, 1, '700000019', 'COMPLETED', 6600, 46, 16.400000, '60000000-0000-4000-8000-000000000010', 'REMOTE_STOP'),
        ('70000000-0000-4000-8000-000000000020'::uuid, 20, 5, '700000020', 'COMPLETED', 7200, 57, 24.600000, NULL::uuid, 'USER_STOP')
), transaction_values AS (
    SELECT s.*, d.id AS device_id, d.device_code, d.protocol, c.id AS connector_id,
           CURRENT_TIMESTAMP - make_interval(mins => s.age_minutes) AS created_at
    FROM transaction_seed s
    JOIN charging_device d ON d.device_code = 'VB-DEMO-' || lpad(s.device_no::text, 3, '0')
    JOIN charging_connector c ON c.device_id = d.id AND c.connector_no = 1
)
INSERT INTO charging_transaction (id, tenant_id, device_id, device_code, connector_id, connector_no,
                                  command_id, protocol, protocol_transaction_id, business_ref, status,
                                  started_at, ended_at, start_meter_kwh, end_meter_kwh, energy_kwh,
                                  start_soc, latest_soc, end_soc, charging_duration_seconds, total_amount,
                                  stop_category, raw_stop_code, raw_stop_detail, start_method,
                                  last_progress_at, final_record_received_at, raw_final_record,
                                  additional_info, created_at, updated_at)
SELECT s.id, '11111111-1111-4111-8111-111111111111', s.device_id, s.device_code, s.connector_id, 1,
       s.command_id, s.protocol, s.protocol_transaction_id, 'DEMO-ORDER-' || lpad(s.row_no::text, 3, '0'), s.status,
       CASE WHEN s.status IN ('STARTING', 'START_FAILED') THEN NULL ELSE s.created_at + INTERVAL '1 minute' END,
       CASE
           WHEN s.status IN ('COMPLETED', 'ABORTED') THEN s.created_at + INTERVAL '1 minute' + make_interval(mins => s.duration_minutes)
           WHEN s.status = 'ENDING' THEN CURRENT_TIMESTAMP - INTERVAL '3 minutes'
           WHEN s.status = 'START_FAILED' THEN s.created_at + INTERVAL '15 seconds'
           ELSE NULL
       END,
       CASE WHEN s.status IN ('CHARGING', 'ENDING', 'COMPLETED', 'ABORTED')
            THEN (1000 + s.row_no * 100)::numeric(20, 6) END,
       CASE WHEN s.status IN ('COMPLETED', 'ABORTED')
            THEN (1000 + s.row_no * 100 + s.energy_kwh)::numeric(20, 6) END,
       s.energy_kwh,
       CASE WHEN s.status IN ('CHARGING', 'ENDING', 'COMPLETED', 'ABORTED') THEN 25 END,
       CASE WHEN s.status IN ('CHARGING', 'ENDING') THEN 62
            WHEN s.status IN ('COMPLETED', 'ABORTED') THEN 82 END,
       CASE WHEN s.status IN ('ENDING', 'COMPLETED', 'ABORTED') THEN 82 END,
       CASE WHEN s.status IN ('ENDING', 'COMPLETED', 'ABORTED') THEN s.duration_minutes * 60 END,
       CASE WHEN s.energy_kwh IS NOT NULL THEN s.energy_kwh * 1.200000 END,
       s.stop_category,
       CASE WHEN s.status = 'COMPLETED' THEN 0 WHEN s.status = 'START_FAILED' THEN 17
            WHEN s.status = 'ABORTED' THEN 91 END,
       CASE WHEN s.status = 'START_FAILED' THEN '设备未开始充电'
            WHEN s.status = 'ABORTED' THEN '演示交易因通信中断提前终止' END,
       'REMOTE',
       CASE WHEN s.status IN ('CHARGING', 'ENDING') THEN CURRENT_TIMESTAMP - INTERVAL '1 minute' END,
       CASE WHEN s.status = 'COMPLETED'
            THEN s.created_at + INTERVAL '1 minute' + make_interval(mins => s.duration_minutes) + INTERVAL '2 seconds' END,
       CASE WHEN s.status = 'COMPLETED'
            THEN jsonb_build_object('source', 'dev-seed', 'businessRef', 'DEMO-ORDER-' || lpad(s.row_no::text, 3, '0')) END,
       jsonb_build_object('source', 'dev-seed', 'businessRef', 'DEMO-ORDER-' || lpad(s.row_no::text, 3, '0')),
       s.created_at,
       CASE WHEN s.status = 'COMPLETED'
            THEN s.created_at + INTERVAL '1 minute' + make_interval(mins => s.duration_minutes) + INTERVAL '2 seconds'
            WHEN s.status IN ('START_FAILED', 'ABORTED')
            THEN s.created_at + INTERVAL '1 minute' + make_interval(mins => COALESCE(s.duration_minutes, 0))
            ELSE s.created_at END
FROM transaction_values s
ON CONFLICT (id) DO UPDATE
SET tenant_id = EXCLUDED.tenant_id,
    device_id = EXCLUDED.device_id,
    device_code = EXCLUDED.device_code,
    connector_id = EXCLUDED.connector_id,
    connector_no = EXCLUDED.connector_no,
    command_id = EXCLUDED.command_id,
    protocol = EXCLUDED.protocol,
    protocol_transaction_id = EXCLUDED.protocol_transaction_id,
    business_ref = EXCLUDED.business_ref,
    status = EXCLUDED.status,
    started_at = EXCLUDED.started_at,
    ended_at = EXCLUDED.ended_at,
    start_meter_kwh = EXCLUDED.start_meter_kwh,
    end_meter_kwh = EXCLUDED.end_meter_kwh,
    energy_kwh = EXCLUDED.energy_kwh,
    start_soc = EXCLUDED.start_soc,
    latest_soc = EXCLUDED.latest_soc,
    end_soc = EXCLUDED.end_soc,
    charging_duration_seconds = EXCLUDED.charging_duration_seconds,
    total_amount = EXCLUDED.total_amount,
    stop_category = EXCLUDED.stop_category,
    raw_stop_code = EXCLUDED.raw_stop_code,
    raw_stop_detail = EXCLUDED.raw_stop_detail,
    start_method = EXCLUDED.start_method,
    last_progress_at = EXCLUDED.last_progress_at,
    final_record_received_at = EXCLUDED.final_record_received_at,
    raw_final_record = EXCLUDED.raw_final_record,
    additional_info = EXCLUDED.additional_info,
    created_at = EXCLUDED.created_at,
    updated_at = EXCLUDED.updated_at,
    version = 0
WHERE charging_transaction.tenant_id = EXCLUDED.tenant_id
  AND charging_transaction.business_ref = EXCLUDED.business_ref;

WITH billing_seed (id, transaction_id) AS (
    VALUES
        ('80000000-0000-4000-8000-000000000001'::uuid, '70000000-0000-4000-8000-000000000001'::uuid),
        ('80000000-0000-4000-8000-000000000002'::uuid, '70000000-0000-4000-8000-000000000002'::uuid),
        ('80000000-0000-4000-8000-000000000003'::uuid, '70000000-0000-4000-8000-000000000003'::uuid),
        ('80000000-0000-4000-8000-000000000004'::uuid, '70000000-0000-4000-8000-000000000004'::uuid),
        ('80000000-0000-4000-8000-000000000005'::uuid, '70000000-0000-4000-8000-000000000005'::uuid),
        ('80000000-0000-4000-8000-000000000006'::uuid, '70000000-0000-4000-8000-000000000006'::uuid),
        ('80000000-0000-4000-8000-000000000007'::uuid, '70000000-0000-4000-8000-000000000007'::uuid),
        ('80000000-0000-4000-8000-000000000008'::uuid, '70000000-0000-4000-8000-000000000008'::uuid),
        ('80000000-0000-4000-8000-000000000009'::uuid, '70000000-0000-4000-8000-000000000009'::uuid),
        ('80000000-0000-4000-8000-000000000010'::uuid, '70000000-0000-4000-8000-000000000010'::uuid),
        ('80000000-0000-4000-8000-000000000011'::uuid, '70000000-0000-4000-8000-000000000011'::uuid),
        ('80000000-0000-4000-8000-000000000012'::uuid, '70000000-0000-4000-8000-000000000012'::uuid),
        ('80000000-0000-4000-8000-000000000013'::uuid, '70000000-0000-4000-8000-000000000019'::uuid),
        ('80000000-0000-4000-8000-000000000014'::uuid, '70000000-0000-4000-8000-000000000020'::uuid)
)
INSERT INTO billing_record (id, tenant_id, transaction_id, tariff_plan_id, currency, energy_kwh,
                            energy_fee, service_fee, subtotal_amount, discount_amount, total_amount,
                            status, charging_started_at, charging_ended_at, pricing_snapshot,
                            created_at, finalized_at)
SELECT b.id, tx.tenant_id, tx.id, tariff.id, tariff.currency, tx.energy_kwh,
       round(tx.energy_kwh * period.energy_price_per_kwh, 2),
       round(tx.energy_kwh * period.service_fee_per_kwh, 2),
       round(tx.energy_kwh * period.energy_price_per_kwh, 2) + round(tx.energy_kwh * period.service_fee_per_kwh, 2),
       0.00,
       round(tx.energy_kwh * period.energy_price_per_kwh, 2) + round(tx.energy_kwh * period.service_fee_per_kwh, 2),
       'FINALIZED', tx.started_at, tx.ended_at,
       jsonb_build_object('tariffPlanId', tariff.id, 'familyId', tariff.family_id,
                          'revision', tariff.revision, 'name', tariff.name,
                          'currency', tariff.currency, 'pricingType', tariff.pricing_type,
                          'timezone', tariff.timezone, 'effectiveFrom', tariff.effective_from,
                          'effectiveTo', tariff.effective_to,
                          'periods', jsonb_build_array(jsonb_build_object(
                              'sequenceNo', period.sequence_no, 'periodType', period.period_type,
                              'startMinute', period.start_minute, 'endMinute', period.end_minute,
                              'energyPricePerKwh', period.energy_price_per_kwh,
                              'serviceFeePerKwh', period.service_fee_per_kwh)),
                          'appliedSegments', jsonb_build_array(jsonb_build_object(
                              'sequenceNo', period.sequence_no, 'periodType', period.period_type,
                              'startMinute', period.start_minute, 'endMinute', period.end_minute,
                              'energyKwh', tx.energy_kwh,
                              'energyPricePerKwh', period.energy_price_per_kwh,
                              'serviceFeePerKwh', period.service_fee_per_kwh)),
                          'energyKwh', tx.energy_kwh,
                          'energyFee', round(tx.energy_kwh * period.energy_price_per_kwh, 2),
                          'serviceFee', round(tx.energy_kwh * period.service_fee_per_kwh, 2),
                          'subtotalAmount', round(tx.energy_kwh * period.energy_price_per_kwh, 2)
                              + round(tx.energy_kwh * period.service_fee_per_kwh, 2),
                          'discountAmount', 0.00,
                          'totalAmount', round(tx.energy_kwh * (period.energy_price_per_kwh + period.service_fee_per_kwh), 2)),
       tx.ended_at + INTERVAL '1 second', tx.ended_at + INTERVAL '1 second'
FROM billing_seed b
JOIN charging_transaction tx ON tx.id = b.transaction_id AND tx.status = 'COMPLETED'
JOIN tariff_plan tariff ON tariff.id = '50000000-0000-4000-8000-000000000001'
JOIN tariff_period period ON period.tariff_plan_id = tariff.id AND period.sequence_no = 1
ON CONFLICT (id) DO UPDATE
SET tenant_id = EXCLUDED.tenant_id,
    transaction_id = EXCLUDED.transaction_id,
    tariff_plan_id = EXCLUDED.tariff_plan_id,
    currency = EXCLUDED.currency,
    energy_kwh = EXCLUDED.energy_kwh,
    energy_fee = EXCLUDED.energy_fee,
    service_fee = EXCLUDED.service_fee,
    subtotal_amount = EXCLUDED.subtotal_amount,
    discount_amount = EXCLUDED.discount_amount,
    total_amount = EXCLUDED.total_amount,
    status = EXCLUDED.status,
    charging_started_at = EXCLUDED.charging_started_at,
    charging_ended_at = EXCLUDED.charging_ended_at,
    pricing_snapshot = EXCLUDED.pricing_snapshot,
    created_at = EXCLUDED.created_at,
    finalized_at = EXCLUDED.finalized_at
WHERE billing_record.tenant_id = EXCLUDED.tenant_id
  AND billing_record.transaction_id = EXCLUDED.transaction_id;

WITH billing_seed (id) AS (
    VALUES
        ('80000000-0000-4000-8000-000000000001'::uuid), ('80000000-0000-4000-8000-000000000002'::uuid),
        ('80000000-0000-4000-8000-000000000003'::uuid), ('80000000-0000-4000-8000-000000000004'::uuid),
        ('80000000-0000-4000-8000-000000000005'::uuid), ('80000000-0000-4000-8000-000000000006'::uuid),
        ('80000000-0000-4000-8000-000000000007'::uuid), ('80000000-0000-4000-8000-000000000008'::uuid),
        ('80000000-0000-4000-8000-000000000009'::uuid), ('80000000-0000-4000-8000-000000000010'::uuid),
        ('80000000-0000-4000-8000-000000000011'::uuid), ('80000000-0000-4000-8000-000000000012'::uuid),
        ('80000000-0000-4000-8000-000000000013'::uuid), ('80000000-0000-4000-8000-000000000014'::uuid)
)
INSERT INTO billing_line_item (billing_record_id, line_no, line_type, description,
                               quantity, unit, unit_price, amount)
SELECT br.id, line.line_no, line.line_type, line.description,
       br.energy_kwh, 'kWh', line.unit_price, line.amount
FROM billing_seed b
JOIN billing_record br ON br.id = b.id
JOIN tariff_plan tariff ON tariff.id = br.tariff_plan_id
JOIN tariff_period period ON period.tariff_plan_id = tariff.id AND period.sequence_no = 1
CROSS JOIN LATERAL (VALUES
    (1, 'ENERGY_FEE'::varchar, '电能费'::varchar, period.energy_price_per_kwh, br.energy_fee),
    (2, 'SERVICE_FEE'::varchar, '充电服务费'::varchar, period.service_fee_per_kwh, br.service_fee)
) AS line(line_no, line_type, description, unit_price, amount)
ON CONFLICT (billing_record_id, line_no) DO UPDATE
SET line_type = EXCLUDED.line_type,
    description = EXCLUDED.description,
    quantity = EXCLUDED.quantity,
    unit = EXCLUDED.unit,
    unit_price = EXCLUDED.unit_price,
    amount = EXCLUDED.amount;

DO $$
BEGIN
    IF (SELECT count(*) FROM charging_device WHERE tenant_id = '11111111-1111-4111-8111-111111111111' AND device_code LIKE 'VB-DEMO-%') <> 8
       OR (SELECT count(*) FROM charging_device WHERE tenant_id = '11111111-1111-4111-8111-111111111111'
           AND device_code IN ('OCPP-SIM-001', 'OCPP-SIM-002', 'OCPP-SIM-003', '20231212000010', '20231212000011', '20231212000012')) <> 6
       OR (SELECT count(*) FROM charging_connector c JOIN charging_device d ON d.id = c.device_id
           WHERE d.tenant_id = '11111111-1111-4111-8111-111111111111'
             AND d.device_code IN ('OCPP-SIM-001', 'OCPP-SIM-002', 'OCPP-SIM-003', '20231212000010', '20231212000011', '20231212000012')
             AND c.connector_no = 1 AND c.status = 'AVAILABLE') <> 6
       OR EXISTS (SELECT 1 FROM charging_device d JOIN charging_transaction tx ON tx.device_id = d.id
                  WHERE d.device_code IN ('OCPP-SIM-001', 'OCPP-SIM-002', 'OCPP-SIM-003', '20231212000010', '20231212000011', '20231212000012')
                    AND tx.status IN ('STARTING', 'CHARGING', 'ENDING'))
       OR EXISTS (SELECT 1 FROM charging_device d JOIN device_command dc ON dc.device_id = d.id
                  WHERE d.device_code IN ('OCPP-SIM-001', 'OCPP-SIM-002', 'OCPP-SIM-003', '20231212000010', '20231212000011', '20231212000012'))
       OR (SELECT count(*) FROM device_command WHERE tenant_id = '11111111-1111-4111-8111-111111111111' AND business_ref LIKE 'DEMO-CMD-%') <> 12
       OR (SELECT count(*) FROM charging_transaction WHERE tenant_id = '11111111-1111-4111-8111-111111111111' AND business_ref LIKE 'DEMO-ORDER-%') <> 20
       OR (SELECT count(*) FROM tariff_plan WHERE tenant_id = '11111111-1111-4111-8111-111111111111' AND name LIKE 'VoltBridge Demo - %') <> 3
       OR (SELECT count(*) FROM tariff_plan WHERE id = '50000000-0000-4000-8000-000000000004'
           AND tenant_id = '11111111-1111-4111-8111-111111111111' AND role = 'FALLBACK'
           AND pricing_type = 'FIXED') <> 1
       OR (SELECT count(*) FROM tariff_period WHERE tariff_plan_id = '50000000-0000-4000-8000-000000000004'
           AND sequence_no = 1 AND period_type = 'FLAT' AND start_minute = 0 AND end_minute = 1440
           AND energy_price_per_kwh = 1.00 AND service_fee_per_kwh = 0.20) <> 1
       OR (SELECT count(DISTINCT family_id) FROM tariff_plan
           WHERE tenant_id = '11111111-1111-4111-8111-111111111111' AND role = 'FALLBACK') <> 1
       OR (SELECT count(*) FROM tariff_plan
           WHERE tenant_id = '11111111-1111-4111-8111-111111111111' AND role = 'FALLBACK'
             AND status = 'ACTIVE' AND effective_to IS NULL) <> 1
       OR (SELECT count(*) FROM tariff_plan
           WHERE tenant_id = '11111111-1111-4111-8111-111111111111' AND role = 'FALLBACK'
             AND effective_to IS NULL) <> 1
       OR (SELECT count(*) FROM tariff_plan
           WHERE tenant_id = '11111111-1111-4111-8111-111111111111' AND role = 'FALLBACK')
          <> (SELECT max(revision) FROM tariff_plan
              WHERE tenant_id = '11111111-1111-4111-8111-111111111111' AND role = 'FALLBACK')
       OR EXISTS (SELECT 1 FROM tariff_plan a JOIN tariff_plan b
                  ON a.family_id = b.family_id AND a.revision + 1 = b.revision
                 WHERE a.tenant_id = '11111111-1111-4111-8111-111111111111'
                   AND a.role = 'FALLBACK' AND b.role = 'FALLBACK'
                   AND (a.effective_to IS DISTINCT FROM b.effective_from
                        OR a.status <> 'RETIRED' OR a.retired_at IS DISTINCT FROM a.effective_to))
       OR EXISTS (SELECT 1 FROM tariff_plan a JOIN tariff_plan b
                  ON a.tenant_id = b.tenant_id AND a.id < b.id
                 WHERE a.tenant_id = '11111111-1111-4111-8111-111111111111'
                   AND a.role = 'SCHEDULED' AND b.role = 'SCHEDULED'
                   AND a.status = 'ACTIVE' AND b.status = 'ACTIVE'
                   AND (a.effective_to IS NULL OR b.effective_from < a.effective_to)
                   AND (b.effective_to IS NULL OR a.effective_from < b.effective_to))
       OR (SELECT count(*) FROM billing_record WHERE tenant_id = '11111111-1111-4111-8111-111111111111' AND id BETWEEN '80000000-0000-4000-8000-000000000001'::uuid AND '80000000-0000-4000-8000-000000000014'::uuid) <> 14
       OR (SELECT count(*) FROM billing_line_item li JOIN billing_record br ON br.id = li.billing_record_id
           WHERE br.tenant_id = '11111111-1111-4111-8111-111111111111' AND br.id BETWEEN '80000000-0000-4000-8000-000000000001'::uuid AND '80000000-0000-4000-8000-000000000014'::uuid) <> 28
       OR EXISTS (SELECT 1 FROM charging_transaction tx
                  JOIN charging_device d ON d.id = tx.device_id
                  JOIN charging_connector c ON c.id = tx.connector_id
                  WHERE d.device_code = 'VB-DEMO-006' AND c.connector_no = 1
                    AND tx.status IN ('STARTING', 'CHARGING', 'ENDING')) THEN
        RAISE EXCEPTION 'VoltBridge demo seed did not produce the complete expected fixture set';
    END IF;
END
$$;

COMMIT;

SELECT 'devices' AS fixture, count(*) AS rows FROM charging_device
 WHERE tenant_id = '11111111-1111-4111-8111-111111111111' AND device_code LIKE 'VB-DEMO-%'
UNION ALL SELECT 'simulator_devices', count(*) FROM charging_device
 WHERE tenant_id = '11111111-1111-4111-8111-111111111111'
   AND device_code IN ('OCPP-SIM-001', 'OCPP-SIM-002', 'OCPP-SIM-003', '20231212000010', '20231212000011', '20231212000012')
UNION ALL SELECT 'commands', count(*) FROM device_command
 WHERE tenant_id = '11111111-1111-4111-8111-111111111111' AND business_ref LIKE 'DEMO-CMD-%'
UNION ALL SELECT 'transactions', count(*) FROM charging_transaction
 WHERE tenant_id = '11111111-1111-4111-8111-111111111111' AND business_ref LIKE 'DEMO-ORDER-%'
UNION ALL SELECT 'tariffs', count(*) FROM tariff_plan
 WHERE tenant_id = '11111111-1111-4111-8111-111111111111' AND name LIKE 'VoltBridge Demo - %'
UNION ALL SELECT 'fallback_tariffs', count(*) FROM tariff_plan
 WHERE tenant_id = '11111111-1111-4111-8111-111111111111' AND role = 'FALLBACK'
UNION ALL SELECT 'billing_records', count(*) FROM billing_record
 WHERE tenant_id = '11111111-1111-4111-8111-111111111111' AND id BETWEEN '80000000-0000-4000-8000-000000000001'::uuid AND '80000000-0000-4000-8000-000000000014'::uuid
UNION ALL SELECT 'billing_line_items', count(*) FROM billing_line_item li JOIN billing_record br ON br.id = li.billing_record_id
 WHERE br.tenant_id = '11111111-1111-4111-8111-111111111111' AND br.id BETWEEN '80000000-0000-4000-8000-000000000001'::uuid AND '80000000-0000-4000-8000-000000000014'::uuid
ORDER BY fixture;
