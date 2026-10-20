-- VoltBridge 数据库结构版本记录
CREATE SEQUENCE IF NOT EXISTS protocol_transaction_id_seq
    AS BIGINT START WITH 1 INCREMENT BY 1 MINVALUE 1 MAXVALUE 2147483647 NO CYCLE;

CREATE TABLE IF NOT EXISTS schema_version (
    version VARCHAR(64) PRIMARY KEY,                              -- 结构版本号
    description VARCHAR(255) NOT NULL,                           -- 本次结构变更说明
    installed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP  -- 结构版本安装时间
);

-- 租户基础信息
CREATE TABLE IF NOT EXISTS tenant (
    id UUID PRIMARY KEY,                                                    -- 租户主键 UUID
    tenant_code VARCHAR(64) NOT NULL UNIQUE,                                -- 租户唯一编码
    tenant_name VARCHAR(128) NOT NULL,                                      -- 租户名称
    status VARCHAR(32) NOT NULL CHECK (status IN ('ACTIVE', 'DISABLED')),   -- 租户状态
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,              -- 创建时间
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,              -- 最后更新时间
    version BIGINT NOT NULL DEFAULT 0                                       -- 乐观锁版本号
);

-- 平台用户账号
CREATE TABLE IF NOT EXISTS app_user (
    id UUID PRIMARY KEY,                                                             -- 用户主键 UUID
    tenant_id UUID NOT NULL REFERENCES tenant(id),                                  -- 所属租户 ID
    username VARCHAR(64) NOT NULL,                                                   -- 租户内唯一登录名
    password_hash VARCHAR(255) NOT NULL,                                             -- 密码哈希，不存储明文密码
    display_name VARCHAR(128),                                                       -- 用户显示名称
    status VARCHAR(32) NOT NULL CHECK (status IN ('ACTIVE', 'LOCKED', 'DISABLED')), -- 用户状态
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,                       -- 创建时间
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,                       -- 最后更新时间
    version BIGINT NOT NULL DEFAULT 0,                                               -- 乐观锁版本号
    CONSTRAINT uk_app_user_tenant_username UNIQUE (tenant_id, username)              -- 租户内登录名唯一
);

-- 充电站点
CREATE TABLE IF NOT EXISTS charging_station (
    id UUID PRIMARY KEY,                                                       -- 充电站主键 UUID
    tenant_id UUID NOT NULL REFERENCES tenant(id),                            -- 所属租户 ID
    station_code VARCHAR(64) NOT NULL,                                        -- 租户内唯一站点编码
    station_name VARCHAR(128) NOT NULL,                                       -- 站点名称
    longitude NUMERIC(10, 7) CHECK (longitude BETWEEN -180 AND 180),           -- 经度，WGS84 坐标
    latitude NUMERIC(10, 7) CHECK (latitude BETWEEN -90 AND 90),               -- 纬度，WGS84 坐标
    province VARCHAR(64),                                                     -- 省级行政区
    city VARCHAR(64),                                                         -- 城市
    district VARCHAR(64),                                                     -- 区县
    address VARCHAR(255),                                                     -- 详细地址
    status VARCHAR(32) NOT NULL CHECK (status IN ('ACTIVE', 'INACTIVE')),     -- 站点运营状态
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,                -- 创建时间
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,                -- 最后更新时间
    version BIGINT NOT NULL DEFAULT 0,                                        -- 乐观锁版本号
    CONSTRAINT uk_station_tenant_code UNIQUE (tenant_id, station_code)        -- 租户内站点编码唯一
);

-- 接入平台的充电设备或充电桩
CREATE TABLE IF NOT EXISTS charging_device (
    id UUID PRIMARY KEY,                                                                           -- 设备主键 UUID
    tenant_id UUID NOT NULL REFERENCES tenant(id),                                                  -- 所属租户 ID
    station_id UUID NOT NULL REFERENCES charging_station(id),                                      -- 所属充电站 ID
    device_code VARCHAR(64) NOT NULL UNIQUE,                                                        -- 平台全局唯一设备编码，用于协议登录和路由
    device_name VARCHAR(128) NOT NULL,                                                              -- 设备名称
    protocol VARCHAR(64) NOT NULL,                                                                 -- 设备接入协议标识
    protocol_version VARCHAR(32) NOT NULL,                                                         -- 设备协议版本
    manufacturer VARCHAR(128),                                                                    -- 设备制造商
    brand VARCHAR(128),                                                                           -- 设备品牌
    model VARCHAR(128),                                                                           -- 设备型号
    device_type VARCHAR(64),                                                                      -- 设备类型
    status VARCHAR(32) NOT NULL CHECK (status IN ('REGISTERED', 'ONLINE', 'OFFLINE', 'DISABLED')), -- 设备业务状态，不代表瞬时 TCP 在线状态
    additional_info JSONB NOT NULL DEFAULT '{}'::jsonb,                                            -- 非核心扩展信息 JSON
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,                                     -- 创建时间
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,                                     -- 最后更新时间
    version BIGINT NOT NULL DEFAULT 0                                                              -- 乐观锁版本号
);

-- 充电设备下的物理充电枪或连接器
CREATE TABLE IF NOT EXISTS charging_connector (
    id UUID PRIMARY KEY,                                                                                                  -- 连接器主键 UUID
    tenant_id UUID NOT NULL REFERENCES tenant(id),                                                                        -- 所属租户 ID
    station_id UUID NOT NULL REFERENCES charging_station(id),                                                            -- 所属充电站 ID
    device_id UUID NOT NULL REFERENCES charging_device(id),                                                              -- 所属充电设备 ID
    connector_no INTEGER NOT NULL,                                                                                        -- 设备协议内的连接器序号
    connector_code VARCHAR(64) NOT NULL,                                                                                  -- 平台连接器唯一编码
    connector_type VARCHAR(64),                                                                                           -- 连接器类型，例如直流或交流
    rated_power NUMERIC(12, 3),                                                                                           -- 额定功率，单位 kW
    status VARCHAR(32) NOT NULL CHECK (status IN ('UNKNOWN', 'AVAILABLE', 'OCCUPIED', 'CHARGING', 'FAULTED', 'DISABLED')), -- 连接器业务状态
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,                                                            -- 创建时间
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,                                                            -- 最后更新时间
    version BIGINT NOT NULL DEFAULT 0,                                                                                    -- 乐观锁版本号
    CONSTRAINT uk_connector_device_no UNIQUE (device_id, connector_no),                                                   -- 同一设备内连接器序号唯一
    CONSTRAINT uk_connector_code UNIQUE (connector_code)                                                                  -- 平台连接器编码唯一
);

-- 设备动态扩展属性，按类型列存储
CREATE TABLE IF NOT EXISTS device_attribute (
    device_id UUID NOT NULL REFERENCES charging_device(id),                                          -- 所属设备 ID
    attr_key VARCHAR(128) NOT NULL,                                                                   -- 属性键，与 device_id 组成主键
    value_type VARCHAR(32) NOT NULL CHECK (value_type IN ('BOOLEAN', 'STRING', 'LONG', 'DOUBLE', 'JSON')), -- 属性值类型
    bool_value BOOLEAN,                                                                               -- 布尔类型属性值
    string_value TEXT,                                                                                -- 字符串类型属性值
    long_value BIGINT,                                                                                -- 长整型属性值
    double_value DOUBLE PRECISION,                                                                    -- 浮点类型属性值
    json_value JSONB,                                                                                 -- 结构化 JSON 属性值
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,                                        -- 最后更新时间
    version BIGINT NOT NULL DEFAULT 0,                                                                -- 乐观锁版本号
    PRIMARY KEY (device_id, attr_key),                                                                -- 设备与属性键联合主键
    CONSTRAINT ck_device_attribute_value CHECK (
        num_nonnulls(bool_value, string_value, long_value, double_value, json_value) = 1
        AND ((value_type = 'BOOLEAN' AND bool_value IS NOT NULL)
          OR (value_type = 'STRING' AND string_value IS NOT NULL)
          OR (value_type = 'LONG' AND long_value IS NOT NULL)
          OR (value_type = 'DOUBLE' AND double_value IS NOT NULL)
          OR (value_type = 'JSON' AND json_value IS NOT NULL))
    )
);

-- 平台下发给设备的命令及其完整生命周期
CREATE TABLE IF NOT EXISTS device_command (
    id UUID PRIMARY KEY,                                                                                                                   -- 命令主键 UUID，同时作为统一 command_id
    tenant_id UUID NOT NULL REFERENCES tenant(id),                                                                                        -- 所属租户 ID
    device_id UUID NOT NULL REFERENCES charging_device(id),                                                                              -- 目标设备 ID
    device_code VARCHAR(64),                                                                                                              -- 目标设备编码快照
    connector_id UUID REFERENCES charging_connector(id),                                                                                  -- 目标连接器 ID，设备级命令可为空
    connector_no VARCHAR(16),                                                                                                             -- 目标连接器协议序号快照
    command_type VARCHAR(64) NOT NULL,                                                                                                    -- 命令类型
    command_status VARCHAR(32) NOT NULL CHECK (command_status IN ('CREATED', 'SENT', 'ACKED', 'EXECUTING', 'SUCCEEDED', 'FAILED', 'TIMEOUT')), -- 命令生命周期状态
    business_ref VARCHAR(128),                                                                                                            -- 调用方业务引用，例如订单号或幂等键
    request_payload JSONB NOT NULL DEFAULT '{}'::jsonb,                                                                                   -- 下发请求快照 JSON
    response_payload JSONB,                                                                                                               -- 设备响应快照 JSON
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,                                                                            -- 命令创建时间
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,                                                                            -- 命令状态最后更新时间
    sent_at TIMESTAMPTZ,                                                                                                                  -- 字节成功写入传输层的时间
    acked_at TIMESTAMPTZ,                                                                                                                 -- 设备确认命令的时间
    completed_at TIMESTAMPTZ,                                                                                                             -- 命令进入最终状态的时间
    expired_at TIMESTAMPTZ,                                                                                                               -- 命令超时时间
    failure_code VARCHAR(64),                                                                                                             -- 失败代码
    failure_reason VARCHAR(512),                                                                                                          -- 失败原因
    version BIGINT NOT NULL DEFAULT 0,                                                                                                    -- 乐观锁版本号，防止并发结果覆盖
    CONSTRAINT ck_device_command_expiration CHECK (expired_at IS NULL OR expired_at >= created_at)                                         -- 超时时间不得早于创建时间
);

-- 一次完整充电交易或充电会话
CREATE TABLE IF NOT EXISTS charging_transaction (
    id UUID PRIMARY KEY,                                                                                                                   -- 充电交易主键 UUID
    tenant_id UUID NOT NULL REFERENCES tenant(id),                                                                                        -- 所属租户 ID
    device_id UUID NOT NULL REFERENCES charging_device(id),                                                                              -- 执行充电的设备 ID
    device_code VARCHAR(64) NOT NULL,                                                                                                     -- 设备编码快照
    connector_id UUID NOT NULL REFERENCES charging_connector(id),                                                                        -- 执行充电的连接器 ID
    connector_no INTEGER NOT NULL,                                                                                                        -- 设备协议内连接器号快照
    command_id UUID REFERENCES device_command(id),                                                                                        -- 发起远程启动的设备命令，本地启动可为空
    protocol VARCHAR(64) NOT NULL,                                                                                                        -- 协议标识
    protocol_transaction_id VARCHAR(128),                                                                                                 -- 协议交易流水号，远程启动确认后绑定
    business_ref VARCHAR(128),                                                                                                            -- 订单号等外部业务引用
    status VARCHAR(32) NOT NULL CHECK (status IN ('STARTING', 'CHARGING', 'ENDING', 'COMPLETED', 'START_FAILED', 'ABORTED')),              -- 与设备命令独立的交易状态
    started_at TIMESTAMPTZ,                                                                                                               -- 可信充电证据确认的开始时间
    ended_at TIMESTAMPTZ,                                                                                                                 -- 设备报告的结束时间
    start_meter_kwh NUMERIC(20, 6),                                                                                                       -- 起始电表读数 kWh
    end_meter_kwh NUMERIC(20, 6),                                                                                                         -- 结束电表读数 kWh
    energy_kwh NUMERIC(20, 6) CHECK (energy_kwh IS NULL OR energy_kwh >= 0),                                                              -- 最终或最新汇总电量 kWh
    start_soc INTEGER CHECK (start_soc BETWEEN 0 AND 100),                                                                                -- 开始 SOC
    latest_soc INTEGER CHECK (latest_soc BETWEEN 0 AND 100),                                                                              -- 最近一次进度 SOC
    end_soc INTEGER CHECK (end_soc BETWEEN 0 AND 100),                                                                                    -- 结束 SOC
    charging_duration_seconds BIGINT CHECK (charging_duration_seconds IS NULL OR charging_duration_seconds >= 0),                          -- 累计充电时长秒
    total_amount NUMERIC(20, 6) CHECK (total_amount IS NULL OR total_amount >= 0),                                                        -- 最终或最新汇总金额
    stop_category VARCHAR(64),                                                                                                            -- 平台标准停止分类
    raw_stop_code INTEGER,                                                                                                                -- 协议原始停止码
    raw_stop_detail VARCHAR(512),                                                                                                         -- 协议停止诊断文本
    vehicle_vin VARCHAR(64),                                                                                                              -- 车辆 VIN
    start_method VARCHAR(32),                                                                                                             -- 平台标准启动方式
    last_progress_at TIMESTAMPTZ,                                                                                                         -- 最近一次汇总进度时间
    final_record_received_at TIMESTAMPTZ,                                                                                                 -- 最终交易记录接收时间
    raw_final_record JSONB,                                                                                                               -- 最终记录诊断快照，不包含敏感卡号
    additional_info JSONB NOT NULL DEFAULT '{}'::jsonb,                                                                                  -- 可扩展结构化明细，如分时电量
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,                                                                            -- 创建时间
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,                                                                            -- 最后更新时间
    version BIGINT NOT NULL DEFAULT 0,                                                                                                    -- 乐观锁版本号
    CONSTRAINT ck_charging_transaction_time CHECK (ended_at IS NULL OR started_at IS NULL OR ended_at >= started_at)                      -- 结束时间不得早于开始时间
);

-- 可版本化的固定资费方案；费率变更通过新建方案完成，避免篡改历史账单语义
CREATE TABLE IF NOT EXISTS tariff_plan (
    id UUID PRIMARY KEY,                                                                                                                   -- 资费方案主键 UUID
    tenant_id UUID NOT NULL REFERENCES tenant(id),                                                                                        -- 所属租户 ID
    name VARCHAR(128) NOT NULL,                                                                                                           -- 运营人员可读的方案名称
    currency VARCHAR(8) NOT NULL,                                                                                                        -- ISO 货币代码，例如 CNY
    pricing_type VARCHAR(32) NOT NULL CHECK (pricing_type IN ('FIXED')),                                                                  -- 当前仅固定费率，后续可扩展计价类型
    energy_price_per_kwh NUMERIC(20, 6) NOT NULL CHECK (energy_price_per_kwh >= 0),                                                       -- 每 kWh 电能单价
    service_fee_per_kwh NUMERIC(20, 6) NOT NULL CHECK (service_fee_per_kwh >= 0),                                                         -- 每 kWh 服务费单价
    effective_from TIMESTAMPTZ NOT NULL,                                                                                                  -- 按交易开始时间解析的生效起点
    effective_to TIMESTAMPTZ,                                                                                                             -- 生效终点，空值表示持续有效
    status VARCHAR(32) NOT NULL CHECK (status IN ('DRAFT', 'ACTIVE', 'RETIRED')),                                                        -- 资费方案生命周期
    activated_at TIMESTAMPTZ,                                                                                                             -- 显式启用时间
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,                                                                            -- 方案创建时间
    version BIGINT NOT NULL DEFAULT 0,                                                                                                   -- 乐观锁版本号
    CONSTRAINT ck_tariff_plan_effective_time CHECK (effective_to IS NULL OR effective_to > effective_from)                               -- 生效终点必须晚于起点
);

-- 已完成充电交易的不可变计费结果；它不是支付、退款或结算记录
CREATE TABLE IF NOT EXISTS billing_record (
    id UUID PRIMARY KEY,                                                                                                                   -- 账单主键 UUID
    tenant_id UUID NOT NULL REFERENCES tenant(id),                                                                                        -- 所属租户 ID
    transaction_id UUID NOT NULL REFERENCES charging_transaction(id),                                                                     -- 对应已完成充电交易，一笔交易只能生成一张账单
    tariff_plan_id UUID NOT NULL REFERENCES tariff_plan(id),                                                                              -- 实际采用的资费方案版本
    currency VARCHAR(8) NOT NULL,                                                                                                        -- 结算货币快照
    energy_kwh NUMERIC(20, 6) NOT NULL CHECK (energy_kwh >= 0),                                                                           -- 最终可计费电量 kWh
    energy_fee NUMERIC(20, 2) NOT NULL CHECK (energy_fee >= 0),                                                                           -- 电能费用
    service_fee NUMERIC(20, 2) NOT NULL CHECK (service_fee >= 0),                                                                         -- 服务费用
    subtotal_amount NUMERIC(20, 2) NOT NULL CHECK (subtotal_amount >= 0),                                                                 -- 折扣前小计
    discount_amount NUMERIC(20, 2) NOT NULL DEFAULT 0 CHECK (discount_amount >= 0),                                                      -- 当前固定为零，为后续调整保留列
    total_amount NUMERIC(20, 2) NOT NULL CHECK (total_amount >= 0),                                                                       -- 最终应收金额，不代表已支付
    status VARCHAR(32) NOT NULL CHECK (status IN ('PENDING', 'FINALIZED', 'FAILED')),                                                    -- 计费生命周期状态
    charging_started_at TIMESTAMPTZ,                                                                                                      -- 充电实际开始时间快照
    charging_ended_at TIMESTAMPTZ,                                                                                                        -- 充电实际结束时间快照
    pricing_snapshot JSONB NOT NULL,                                                                                                      -- 不可变历史定价证据 JSON
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,                                                                            -- 账单创建时间
    finalized_at TIMESTAMPTZ,                                                                                                             -- 账单最终计算完成时间
    CONSTRAINT uk_billing_record_transaction UNIQUE (transaction_id)                                                                      -- Kafka/设备重传下防止重复计费
);

-- 账单可解释明细；MVP 写入电能费和服务费，后续可增加折扣或停车费等新类型
CREATE TABLE IF NOT EXISTS billing_line_item (
    id BIGSERIAL PRIMARY KEY,                                                                                                             -- 明细自增主键
    billing_record_id UUID NOT NULL REFERENCES billing_record(id) ON DELETE RESTRICT,                                                     -- 所属账单 ID
    line_no INTEGER NOT NULL CHECK (line_no > 0),                                                                                        -- 账单内稳定行序号
    line_type VARCHAR(32) NOT NULL CHECK (line_type IN ('ENERGY_FEE', 'SERVICE_FEE')),                                                    -- 明细类型
    description VARCHAR(256) NOT NULL,                                                                                                   -- 人类可读说明
    quantity NUMERIC(20, 6) NOT NULL CHECK (quantity >= 0),                                                                               -- 计费数量，例如 kWh
    unit VARCHAR(16) NOT NULL,                                                                                                           -- 数量单位
    unit_price NUMERIC(20, 6) NOT NULL CHECK (unit_price >= 0),                                                                           -- 单价快照
    amount NUMERIC(20, 2) NOT NULL CHECK (amount >= 0),                                                                                  -- 本行舍入后金额
    CONSTRAINT uk_billing_line_item_no UNIQUE (billing_record_id, line_no)                                                               -- 同一账单内行号唯一
);

-- 兼容已由早期 bootstrap 创建的本地数据库；正式版本将迁移到 Flyway 增量脚本。
ALTER TABLE tenant ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE app_user ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE charging_station ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE charging_device ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE charging_connector ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE device_command ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE device_command ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE device_command ADD COLUMN IF NOT EXISTS device_code VARCHAR(64);
ALTER TABLE device_command ADD COLUMN IF NOT EXISTS connector_no VARCHAR(16);
ALTER TABLE charging_transaction ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE charging_transaction ADD COLUMN IF NOT EXISTS device_code VARCHAR(64);
ALTER TABLE charging_transaction ADD COLUMN IF NOT EXISTS connector_no INTEGER;
ALTER TABLE charging_transaction ADD COLUMN IF NOT EXISTS command_id UUID REFERENCES device_command(id);
ALTER TABLE charging_transaction ADD COLUMN IF NOT EXISTS protocol VARCHAR(64);
ALTER TABLE charging_transaction ADD COLUMN IF NOT EXISTS business_ref VARCHAR(128);
ALTER TABLE charging_transaction ADD COLUMN IF NOT EXISTS started_at TIMESTAMPTZ;
ALTER TABLE charging_transaction ADD COLUMN IF NOT EXISTS ended_at TIMESTAMPTZ;
ALTER TABLE charging_transaction ADD COLUMN IF NOT EXISTS start_meter_kwh NUMERIC(20, 6);
ALTER TABLE charging_transaction ADD COLUMN IF NOT EXISTS end_meter_kwh NUMERIC(20, 6);
ALTER TABLE charging_transaction ADD COLUMN IF NOT EXISTS latest_soc INTEGER;
ALTER TABLE charging_transaction ADD COLUMN IF NOT EXISTS charging_duration_seconds BIGINT;
ALTER TABLE charging_transaction ADD COLUMN IF NOT EXISTS total_amount NUMERIC(20, 6);
ALTER TABLE charging_transaction ADD COLUMN IF NOT EXISTS stop_category VARCHAR(64);
ALTER TABLE charging_transaction ADD COLUMN IF NOT EXISTS raw_stop_code INTEGER;
ALTER TABLE charging_transaction ADD COLUMN IF NOT EXISTS raw_stop_detail VARCHAR(512);
ALTER TABLE charging_transaction ADD COLUMN IF NOT EXISTS vehicle_vin VARCHAR(64);
ALTER TABLE charging_transaction ADD COLUMN IF NOT EXISTS start_method VARCHAR(32);
ALTER TABLE charging_transaction ADD COLUMN IF NOT EXISTS last_progress_at TIMESTAMPTZ;
ALTER TABLE charging_transaction ADD COLUMN IF NOT EXISTS final_record_received_at TIMESTAMPTZ;
ALTER TABLE charging_transaction ADD COLUMN IF NOT EXISTS raw_final_record JSONB;
ALTER TABLE charging_transaction ADD COLUMN IF NOT EXISTS additional_info JSONB NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE charging_transaction ADD COLUMN IF NOT EXISTS start_time TIMESTAMPTZ;
ALTER TABLE charging_transaction ADD COLUMN IF NOT EXISTS end_time TIMESTAMPTZ;
UPDATE charging_transaction
SET started_at = COALESCE(started_at, start_time),
    ended_at = COALESCE(ended_at, end_time)
WHERE start_time IS NOT NULL OR end_time IS NOT NULL;
ALTER TABLE charging_transaction DROP COLUMN IF EXISTS start_time;
ALTER TABLE charging_transaction DROP COLUMN IF EXISTS end_time;
ALTER TABLE charging_transaction DROP CONSTRAINT IF EXISTS charging_transaction_status_check;
ALTER TABLE charging_transaction ADD CONSTRAINT charging_transaction_status_check
    CHECK (status IN ('STARTING', 'CHARGING', 'ENDING', 'COMPLETED', 'START_FAILED', 'ABORTED')) NOT VALID;

CREATE INDEX IF NOT EXISTS idx_app_user_tenant_id ON app_user(tenant_id);
CREATE INDEX IF NOT EXISTS idx_station_tenant_id ON charging_station(tenant_id);
CREATE INDEX IF NOT EXISTS idx_device_tenant_station ON charging_device(tenant_id, station_id);
CREATE INDEX IF NOT EXISTS idx_connector_device_id ON charging_connector(device_id);
CREATE INDEX IF NOT EXISTS idx_command_device_status ON device_command(device_id, command_status);
CREATE INDEX IF NOT EXISTS idx_command_expired_at ON device_command(expired_at);
CREATE UNIQUE INDEX IF NOT EXISTS uk_command_active_stop_transaction
    ON device_command(command_type, business_ref)
    WHERE command_type = 'STOP_CHARGING'
      AND command_status IN ('CREATED', 'SENT', 'ACKED', 'EXECUTING');
CREATE INDEX IF NOT EXISTS idx_transaction_connector_started_at ON charging_transaction(connector_id, started_at);
CREATE INDEX IF NOT EXISTS idx_transaction_tenant ON charging_transaction(tenant_id);
CREATE INDEX IF NOT EXISTS idx_transaction_device_status ON charging_transaction(device_id, status);
CREATE INDEX IF NOT EXISTS idx_transaction_started_at ON charging_transaction(started_at);
CREATE INDEX IF NOT EXISTS idx_tariff_plan_effective ON tariff_plan(tenant_id, status, effective_from, effective_to);
CREATE INDEX IF NOT EXISTS idx_billing_record_tenant_created ON billing_record(tenant_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_billing_line_item_record ON billing_line_item(billing_record_id, line_no);
CREATE UNIQUE INDEX IF NOT EXISTS uk_transaction_protocol_device_trade
    ON charging_transaction(protocol, device_id, protocol_transaction_id)
    WHERE protocol_transaction_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_transaction_active_connector
    ON charging_transaction(connector_id)
    WHERE status IN ('STARTING', 'CHARGING', 'ENDING');

INSERT INTO schema_version(version, description)
VALUES ('001', 'Initial VoltBridge schema')
ON CONFLICT (version) DO NOTHING;

INSERT INTO schema_version(version, description)
VALUES ('002', 'Architecture constraints and optimistic-lock columns')
ON CONFLICT (version) DO NOTHING;

INSERT INTO schema_version(version, description)
VALUES ('006', 'Charging transaction lifecycle, protocol correlation and final record')
ON CONFLICT (version) DO NOTHING;

INSERT INTO schema_version(version, description)
VALUES ('007', 'Remote stop lifecycle and active stop command idempotency')
ON CONFLICT (version) DO NOTHING;

INSERT INTO schema_version(version, description)
VALUES ('008', 'Protocol-neutral device initiated transaction and durable numeric id allocation')
ON CONFLICT (version) DO NOTHING;

INSERT INTO schema_version(version, description)
VALUES ('009', 'Billing MVP tariff plans, immutable billing records and line items')
ON CONFLICT (version) DO NOTHING;
