-- 本地联调数据：只在开发环境按需手工执行，不由生产启动脚本自动加载。
INSERT INTO tenant(id, tenant_code, tenant_name, status)
VALUES ('10000000-0000-0000-0000-000000000001', 'DEV', 'VoltBridge 开发租户', 'ACTIVE')
ON CONFLICT (id) DO NOTHING;

INSERT INTO charging_station(id, tenant_id, station_code, station_name, status)
VALUES ('20000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001',
        'DEV-STATION', 'VoltBridge 协议联调站', 'ACTIVE')
ON CONFLICT (id) DO NOTHING;

INSERT INTO charging_device(id, tenant_id, station_id, device_code, device_name,
                            protocol, protocol_version, status, additional_info)
VALUES ('30000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001',
        '20000000-0000-0000-0000-000000000001', '20231212000010', '云快充 1.5 联调桩',
        'YUN_KUAI_CHONG_150', '1.5', 'REGISTERED', '{}'::jsonb),
       ('30000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000001',
        '20000000-0000-0000-0000-000000000001', 'OCPP-TEST-001', 'OCPP 1.6J 联调桩',
        'OCPP_16', '1.6J', 'REGISTERED', '{}'::jsonb)
ON CONFLICT (id) DO NOTHING;

INSERT INTO charging_connector(id, tenant_id, station_id, device_id, connector_no,
                               connector_code, connector_type, rated_power, status)
VALUES ('40000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001',
        '20000000-0000-0000-0000-000000000001', '30000000-0000-0000-0000-000000000001',
        1, 'YKC-DEV-01', 'DC', 120, 'AVAILABLE'),
       ('40000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000001',
        '20000000-0000-0000-0000-000000000001', '30000000-0000-0000-0000-000000000002',
        1, 'OCPP-DEV-01', 'DC', 120, 'AVAILABLE')
ON CONFLICT (id) DO NOTHING;
